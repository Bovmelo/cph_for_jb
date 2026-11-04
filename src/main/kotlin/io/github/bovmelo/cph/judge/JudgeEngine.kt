package io.github.bovmelo.cph.judge

import io.github.bovmelo.cph.model.LanguageId
import io.github.bovmelo.cph.model.LineDifference
import io.github.bovmelo.cph.model.Problem
import io.github.bovmelo.cph.model.RunPhase
import io.github.bovmelo.cph.model.TestCase
import io.github.bovmelo.cph.model.TestRunResult
import io.github.bovmelo.cph.model.Verdict
import io.github.bovmelo.cph.settings.CphSettingsSnapshot
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

interface JudgeCallbacks {
    fun onPhase(phase: RunPhase, message: String)

    fun onCompileOutput(output: String)

    fun onResult(result: TestRunResult)
}

class JudgeRun internal constructor() {
    private val cancellationRequested = AtomicBoolean(false)

    internal fun cancel() = cancellationRequested.set(true)

    internal fun isCancellationRequested(): Boolean = cancellationRequested.get()
}

class JudgeEngine(private val processRunner: ProcessExecutor = ProcessRunner()) {
    private val activeRun = AtomicReference<JudgeRun?>()

    fun prepareRun(): JudgeRun {
        val run = JudgeRun()
        check(activeRun.compareAndSet(null, run)) { "Another judge run is already active." }
        return run
    }

    fun run(
        run: JudgeRun,
        problem: Problem,
        selectedCaseIds: Set<Long>?,
        settings: CphSettingsSnapshot,
        callbacks: JudgeCallbacks,
    ) {
        check(activeRun.get() === run) { "The judge run was not prepared by this engine." }
        try {
            execute(run, problem, selectedCaseIds, settings, callbacks)
        } finally {
            activeRun.compareAndSet(run, null)
        }
    }

    fun stress(
        run: JudgeRun,
        config: StressConfig,
        settings: CphSettingsSnapshot,
        onProgress: (String) -> Unit,
    ): StressOutcome {
        check(activeRun.get() === run) { "The judge run was not prepared by this engine." }
        try {
            return StressRunner(processRunner).run(config, settings, run, onProgress)
        } finally {
            activeRun.compareAndSet(run, null)
        }
    }

    fun cancel() {
        activeRun.get()?.cancel()
        processRunner.cancel()
    }

    private fun execute(
        run: JudgeRun,
        problem: Problem,
        selectedCaseIds: Set<Long>?,
        settings: CphSettingsSnapshot,
        callbacks: JudgeCallbacks,
    ) {
        val sourcePath = Path.of(problem.sourcePath).toAbsolutePath().normalize()
        val adapter = LanguageAdapters.resolve(sourcePath)
        val cases = problem.tests.filter { if (selectedCaseIds == null) it.enabled else it.id in selectedCaseIds }

        if (run.isCancellationRequested()) {
            cancelCases(cases, callbacks)
            return
        }
        if (problem.interactive) {
            cases.forEach {
                callbacks.onResult(
                    it.result(
                        Verdict.UNSUPPORTED,
                        stderr = "Interactive problems require an interactor and cannot use the batch testcase runner.",
                    ),
                )
            }
            callbacks.onPhase(RunPhase.IDLE, "Interactive problems are not supported by the testcase runner.")
            return
        }
        if (adapter == null) {
            callbacks.onCompileOutput("Unsupported source file: ${sourcePath.fileName}")
            cases.forEach { callbacks.onResult(it.result(Verdict.UNSUPPORTED, stderr = "Unsupported language")) }
            callbacks.onPhase(RunPhase.IDLE, "Unsupported source language.")
            return
        }
        if (!Files.isRegularFile(sourcePath)) {
            callbacks.onCompileOutput("Source file does not exist: $sourcePath")
            cases.forEach { callbacks.onResult(it.result(Verdict.COMPILATION_ERROR, stderr = "Source file not found")) }
            callbacks.onPhase(RunPhase.IDLE, "Source file was not found.")
            return
        }

        val buildDirectory = Files.createTempDirectory("cph-judge-")
        try {
            val plan = runCatching {
                adapter.plan(sourcePath, buildDirectory, settings).also { applyPreparations(it, buildDirectory) }
            }.getOrElse { error ->
                val message = "Could not prepare ${adapter.languageId.displayName}: ${error.message}"
                callbacks.onCompileOutput(message)
                cases.forEach { callbacks.onResult(it.result(Verdict.COMPILATION_ERROR, stderr = message)) }
                callbacks.onPhase(RunPhase.IDLE, "Compilation could not be prepared.")
                return
            }

            val compileResult = plan.compile?.let { compileCommand ->
                callbacks.onPhase(RunPhase.COMPILING, "Compiling ${sourcePath.fileName}...")
                processRunner.run(
                    compileCommand,
                    stdin = "",
                    timeoutMs = settings.compileTimeoutMs,
                    cancellationRequested = run::isCancellationRequested,
                )
            }

            if (compileResult != null) {
                val compileOutput = listOf(compileResult.stdout, compileResult.stderr)
                    .filter(String::isNotBlank)
                    .joinToString(separator = "\n")
                callbacks.onCompileOutput(compileOutput)

                if (compileResult.cancelled || run.isCancellationRequested()) {
                    cancelCases(cases, callbacks)
                    return
                }
                if (
                    compileResult.timedOut ||
                    compileResult.launchError ||
                    compileResult.outputLimitExceeded ||
                    compileResult.exitCode != 0
                ) {
                    val message = when {
                        compileResult.timedOut -> "Compiler timed out."
                        compileResult.launchError -> "Could not start compiler."
                        compileResult.outputLimitExceeded -> "Compiler output exceeded the 4 MiB capture limit."
                        else -> "Compilation failed."
                    }
                    cases.forEach {
                        callbacks.onResult(
                            it.result(
                                verdict = Verdict.COMPILATION_ERROR,
                                stderr = compileOutput.ifBlank { message },
                                exitCode = compileResult.exitCode,
                                durationMs = compileResult.durationMs,
                            ),
                        )
                    }
                    callbacks.onPhase(RunPhase.IDLE, message)
                    return
                }
            }

            for ((index, testCase) in cases.withIndex()) {
                if (run.isCancellationRequested()) {
                    cancelCases(cases.drop(index), callbacks)
                    return
                }
                callbacks.onPhase(RunPhase.RUNNING, "Running testcase ${index + 1} of ${cases.size}...")
                callbacks.onResult(testCase.result(Verdict.RUNNING))
                val processResult = processRunner.run(
                    plan.run,
                    testCase.input,
                    settings.timeoutMs,
                    run::isCancellationRequested,
                )
                val preliminaryVerdict = processVerdict(processResult, settings)
                val judged = if (preliminaryVerdict == null && problem.customCheckerPath.isNullOrBlank().not()) {
                    callbacks.onPhase(RunPhase.CHECKING, "Checking testcase ${index + 1} of ${cases.size}...")
                    judgeWithCustomChecker(testCase, processResult, problem.customCheckerPath.orEmpty(), settings, run)
                } else {
                    judgeNormally(testCase, processResult, preliminaryVerdict)
                }
                callbacks.onResult(judged)
                if (judged.verdict == Verdict.CANCELLED || run.isCancellationRequested()) {
                    cancelCases(cases.drop(index + 1), callbacks)
                    return
                }
            }
            callbacks.onPhase(RunPhase.IDLE, "Finished ${cases.size} testcase(s).")
        } finally {
            deleteTree(buildDirectory)
        }
    }

    private fun judgeNormally(
        testCase: TestCase,
        processResult: ProcessResult,
        preliminaryVerdict: Verdict?,
    ): TestRunResult {
        if (preliminaryVerdict != null) {
            return testCase.processResult(preliminaryVerdict, processResult)
        }
        val comparison = OutputComparator.compare(testCase.output, processResult.stdout)
        return testCase.processResult(
            verdict = if (comparison.matches) Verdict.ACCEPTED else Verdict.WRONG_ANSWER,
            processResult = processResult,
            differences = comparison.differences,
        )
    }

    private fun judgeWithCustomChecker(
        testCase: TestCase,
        processResult: ProcessResult,
        rawCheckerPath: String,
        settings: CphSettingsSnapshot,
        run: JudgeRun,
    ): TestRunResult {
        val checkerPath = runCatching { Path.of(rawCheckerPath.trim()).toAbsolutePath().normalize() }.getOrNull()
        if (checkerPath == null || !Files.isRegularFile(checkerPath)) {
            return testCase.processResult(
                Verdict.CHECKER_ERROR,
                processResult,
                checkerStderr = "Custom checker was not found: ${rawCheckerPath.trim()}",
            )
        }

        var inputPath: Path? = null
        var outputPath: Path? = null
        return try {
            val createdInputPath = Files.createTempFile("cph-input-", ".txt")
            val createdOutputPath = Files.createTempFile("cph-output-", ".txt")
            inputPath = createdInputPath
            outputPath = createdOutputPath
            Files.writeString(createdInputPath, testCase.input, StandardCharsets.UTF_8)
            Files.writeString(createdOutputPath, processResult.stdout, StandardCharsets.UTF_8)
            val python = settings.toolchain(LanguageId.PYTHON).runCommand
            val checkerResult = if (python.isBlank()) {
                ProcessResult(
                    stdout = "",
                    stderr = "No Python interpreter is configured for the custom checker.",
                    exitCode = null,
                    durationMs = 0,
                    launchError = true,
                )
            } else {
                processRunner.run(
                    command = CommandSpec(
                        executable = python,
                        arguments = listOf(
                            checkerPath.toString(),
                            createdInputPath.toString(),
                            createdOutputPath.toString(),
                        ),
                        workingDirectory = checkerPath.parent,
                    ),
                    stdin = "",
                    timeoutMs = settings.timeoutMs,
                    cancellationRequested = run::isCancellationRequested,
                )
            }
            val verdict = when {
                checkerResult.cancelled || run.isCancellationRequested() -> Verdict.CANCELLED
                checkerResult.timedOut -> Verdict.CHECKER_ERROR
                checkerResult.launchError -> Verdict.CHECKER_ERROR
                checkerResult.outputLimitExceeded -> Verdict.CHECKER_ERROR
                checkerResult.exitCode == 0 -> Verdict.ACCEPTED
                else -> Verdict.WRONG_ANSWER
            }
            testCase.processResult(
                verdict = verdict,
                processResult = processResult,
                checkerStdout = checkerResult.stdout,
                checkerStderr = checkerResult.stderr,
            )
        } catch (error: Exception) {
            testCase.processResult(
                Verdict.CHECKER_ERROR,
                processResult,
                checkerStderr = "Custom checker failed: ${error.message}",
            )
        } finally {
            inputPath?.let { runCatching { Files.deleteIfExists(it) } }
            outputPath?.let { runCatching { Files.deleteIfExists(it) } }
        }
    }

    private fun processVerdict(processResult: ProcessResult, settings: CphSettingsSnapshot): Verdict? = when {
        processResult.cancelled -> Verdict.CANCELLED
        processResult.timedOut -> Verdict.TIME_LIMIT_EXCEEDED
        processResult.outputLimitExceeded -> Verdict.OUTPUT_LIMIT_EXCEEDED
        processResult.launchError -> Verdict.RUNTIME_ERROR
        processResult.exitCode != 0 -> Verdict.RUNTIME_ERROR
        settings.failOnStderr && processResult.stderr.isNotBlank() -> Verdict.RUNTIME_ERROR
        else -> null
    }

    private fun TestCase.processResult(
        verdict: Verdict,
        processResult: ProcessResult,
        differences: List<LineDifference> = emptyList(),
        checkerStdout: String = "",
        checkerStderr: String = "",
    ): TestRunResult = result(
        verdict = verdict,
        stdout = processResult.stdout,
        stderr = processResult.stderr,
        exitCode = processResult.exitCode,
        durationMs = processResult.durationMs,
        peakMemoryKb = processResult.peakMemoryKb,
        differences = differences,
        checkerStdout = checkerStdout,
        checkerStderr = checkerStderr,
    )

    private fun TestCase.result(
        verdict: Verdict,
        stdout: String = "",
        stderr: String = "",
        exitCode: Int? = null,
        durationMs: Long = 0,
        peakMemoryKb: Long? = null,
        differences: List<LineDifference> = emptyList(),
        checkerStdout: String = "",
        checkerStderr: String = "",
    ) = TestRunResult(
        testCaseId = id,
        verdict = verdict,
        stdout = stdout,
        stderr = stderr,
        exitCode = exitCode,
        durationMs = durationMs,
        peakMemoryKb = peakMemoryKb,
        differences = differences,
        checkerStdout = checkerStdout,
        checkerStderr = checkerStderr,
    )

    private fun cancelCases(cases: List<TestCase>, callbacks: JudgeCallbacks) {
        cases.forEach { callbacks.onResult(it.result(Verdict.CANCELLED)) }
        callbacks.onPhase(RunPhase.IDLE, "Run cancelled.")
    }
}
