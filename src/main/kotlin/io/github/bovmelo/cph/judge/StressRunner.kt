package io.github.bovmelo.cph.judge

import io.github.bovmelo.cph.model.LineDifference
import io.github.bovmelo.cph.model.Verdict
import io.github.bovmelo.cph.settings.CphSettingsSnapshot
import java.nio.file.Files
import java.nio.file.Path

data class StressConfig(
    val solutionPath: Path,
    val generatorPath: Path,
    val brutePath: Path,
    val iterations: Int,
)

sealed interface StressOutcome {
    data class Passed(val iterations: Int) : StressOutcome

    data class Failed(
        val iteration: Int,
        val input: String,
        val expectedOutput: String,
        val verdict: Verdict,
        val actualOutput: String,
        val stderr: String,
        val differences: List<LineDifference>,
    ) : StressOutcome

    data class Cancelled(val iterations: Int) : StressOutcome

    data class Error(val message: String) : StressOutcome
}

/**
 * Runs generator, brute force and solution in lockstep until the solution disagrees with the brute force.
 * The iteration number is passed to the generator as its only argument so a failing case can be reproduced.
 */
class StressRunner(private val processRunner: ProcessExecutor) {
    fun run(
        config: StressConfig,
        settings: CphSettingsSnapshot,
        run: JudgeRun,
        onProgress: (String) -> Unit,
    ): StressOutcome {
        val buildRoot = Files.createTempDirectory("cph-stress-")
        try {
            val solution = prepare("solution", config.solutionPath, buildRoot, settings, run, onProgress)
                .getOrElse { return abort(it, run) }
            val generator = prepare("generator", config.generatorPath, buildRoot, settings, run, onProgress)
                .getOrElse { return abort(it, run) }
            val brute = prepare("brute force", config.brutePath, buildRoot, settings, run, onProgress)
                .getOrElse { return abort(it, run) }

            for (iteration in 1..config.iterations) {
                if (run.isCancellationRequested()) {
                    return StressOutcome.Cancelled(iteration - 1)
                }
                onProgress("Stress test $iteration of ${config.iterations}...")

                val generated = execute(generator.withArgument(iteration.toString()), "", settings, run)
                failure("Generator", generated)?.let { return it }
                val input = generated.stdout

                val expected = execute(brute, input, settings, run)
                failure("Brute force", expected)?.let { return it }

                val actual = execute(solution, input, settings, run)
                if (actual.cancelled || run.isCancellationRequested()) {
                    return StressOutcome.Cancelled(iteration - 1)
                }
                val verdict = solutionVerdict(actual, settings)
                val comparison = if (verdict == null) OutputComparator.compare(expected.stdout, actual.stdout) else null
                if (verdict != null || comparison?.matches == false) {
                    return StressOutcome.Failed(
                        iteration = iteration,
                        input = input,
                        expectedOutput = expected.stdout,
                        verdict = verdict ?: Verdict.WRONG_ANSWER,
                        actualOutput = actual.stdout,
                        stderr = actual.stderr,
                        differences = comparison?.differences.orEmpty(),
                    )
                }
            }
            return StressOutcome.Passed(config.iterations)
        } finally {
            deleteTree(buildRoot)
        }
    }

    private fun prepare(
        label: String,
        sourcePath: Path,
        buildRoot: Path,
        settings: CphSettingsSnapshot,
        run: JudgeRun,
        onProgress: (String) -> Unit,
    ): Result<CommandSpec> = runCatching {
        val source = sourcePath.toAbsolutePath().normalize()
        require(Files.isRegularFile(source)) { "The $label source file was not found: $source" }
        val adapter = requireNotNull(LanguageAdapters.resolve(source)) {
            "The $label has an unsupported language: ${source.fileName}"
        }
        // Each program gets its own directory so build outputs and class names cannot collide.
        val buildDirectory = Files.createTempDirectory(buildRoot, "$label-".replace(' ', '-'))
        val plan = adapter.plan(source, buildDirectory, settings).also { applyPreparations(it, buildDirectory) }
        plan.compile?.let { compile ->
            onProgress("Compiling $label ${source.fileName}...")
            val result = processRunner.run(compile, "", settings.compileTimeoutMs, run::isCancellationRequested)
            check(!result.cancelled && !run.isCancellationRequested()) { "Stress test cancelled." }
            check(!result.timedOut && !result.launchError && result.exitCode == 0) {
                val output = listOf(result.stdout, result.stderr).filter(String::isNotBlank).joinToString("\n")
                "Could not compile the $label: ${output.ifBlank { "compiler failed" }}"
            }
        }
        plan.run
    }

    private fun abort(error: Throwable, run: JudgeRun): StressOutcome =
        if (run.isCancellationRequested()) StressOutcome.Cancelled(0) else StressOutcome.Error(error.message.orEmpty())

    private fun execute(
        command: CommandSpec,
        stdin: String,
        settings: CphSettingsSnapshot,
        run: JudgeRun,
    ): ProcessResult = processRunner.run(command, stdin, settings.timeoutMs, run::isCancellationRequested)

    private fun failure(label: String, result: ProcessResult): StressOutcome? = when {
        result.cancelled -> StressOutcome.Cancelled(0)
        result.timedOut -> StressOutcome.Error("$label timed out.")
        result.launchError -> StressOutcome.Error("$label could not be started.")
        result.outputLimitExceeded -> StressOutcome.Error("$label output exceeded the capture limit.")
        result.exitCode != 0 -> StressOutcome.Error("$label exited with code ${result.exitCode}: ${result.stderr.trim()}")
        else -> null
    }

    private fun solutionVerdict(result: ProcessResult, settings: CphSettingsSnapshot): Verdict? = when {
        result.timedOut -> Verdict.TIME_LIMIT_EXCEEDED
        result.outputLimitExceeded -> Verdict.OUTPUT_LIMIT_EXCEEDED
        result.launchError || result.exitCode != 0 -> Verdict.RUNTIME_ERROR
        settings.failOnStderr && result.stderr.isNotBlank() -> Verdict.RUNTIME_ERROR
        else -> null
    }

    private fun CommandSpec.withArgument(argument: String) = copy(arguments = arguments + argument)
}
