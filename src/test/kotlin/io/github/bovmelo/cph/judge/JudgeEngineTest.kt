package io.github.bovmelo.cph.judge

import io.github.bovmelo.cph.model.Problem
import io.github.bovmelo.cph.model.RunPhase
import io.github.bovmelo.cph.model.TestCase
import io.github.bovmelo.cph.model.TestRunResult
import io.github.bovmelo.cph.model.Verdict
import io.github.bovmelo.cph.settings.CphSettingsSnapshot
import io.github.bovmelo.cph.settings.LanguageToolchain
import io.github.bovmelo.cph.model.LanguageId
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JudgeEngineTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `cancellation requested before the worker starts is retained`() {
        val executor = RecordingExecutor()
        val engine = JudgeEngine(executor)
        val callbacks = RecordingCallbacks()
        val run = engine.prepareRun()

        engine.cancel()
        engine.run(run, problem(), null, CphSettingsSnapshot(), callbacks)

        assertEquals(0, executor.runCount)
        assertEquals(listOf(Verdict.CANCELLED, Verdict.CANCELLED), callbacks.finalVerdicts())
        assertEquals("Run cancelled.", callbacks.phases.last().second)
    }

    @Test
    fun `cancellation between testcases prevents the next process from starting`() {
        val executor = RecordingExecutor()
        val engine = JudgeEngine(executor)
        val callbacks = RecordingCallbacks { result ->
            if (result.testCaseId == 1L && result.verdict == Verdict.ACCEPTED) {
                engine.cancel()
            }
        }
        val run = engine.prepareRun()

        engine.run(run, problem(), null, CphSettingsSnapshot(), callbacks)

        assertEquals(1, executor.runCount)
        assertEquals(listOf(Verdict.ACCEPTED, Verdict.CANCELLED), callbacks.finalVerdicts())
        assertEquals("Run cancelled.", callbacks.phases.last().second)
    }

    @Test
    fun `compiles and judges a real Java solution end to end`() {
        val source = temporaryDirectory.resolve("Main.java")
        Files.writeString(
            source,
            """
                import java.io.BufferedReader;
                import java.io.InputStreamReader;

                public class Main {
                    public static void main(String[] args) throws Exception {
                        System.out.println(new BufferedReader(new InputStreamReader(System.in)).readLine());
                    }
                }
            """.trimIndent(),
        )
        val javaHome = Path.of(System.getProperty("java.home"), "bin")
        val executableSuffix = if (System.getProperty("os.name").startsWith("Windows", true)) ".exe" else ""
        val settings = CphSettingsSnapshot(
            timeoutMs = 5_000,
            compileTimeoutMs = 10_000,
            failOnStderr = false,
            toolchains = CphSettingsSnapshot.defaultToolchains().toMutableMap().apply {
                this[LanguageId.JAVA] = LanguageToolchain(
                    compileCommand = javaHome.resolve("javac$executableSuffix").toString(),
                    runCommand = javaHome.resolve("java$executableSuffix").toString(),
                )
            },
        )
        val callbacks = RecordingCallbacks()
        val engine = JudgeEngine()
        val run = engine.prepareRun()
        val problem = Problem(
            name = "Echo",
            url = "file:///echo",
            tests = listOf(
                TestCase(input = "alpha\n", output = "alpha\n", id = 11),
                TestCase(input = "beta\n", output = "beta\n", id = 12),
            ),
            sourcePath = source.toString(),
        )

        engine.run(run, problem, null, settings, callbacks)

        assertEquals(listOf(Verdict.ACCEPTED, Verdict.ACCEPTED), callbacks.finalVerdicts())
        assertEquals("Finished 2 testcase(s).", callbacks.phases.last().second)
    }

    @Test
    fun `custom checker receives temporary input and actual output files`() {
        val source = temporaryDirectory.resolve("solution.py")
        val checker = temporaryDirectory.resolve("checker.py")
        Files.writeString(source, "print('actual')")
        Files.writeString(checker, "# checked by fake executor")
        val executor = CheckerExecutor()
        val callbacks = RecordingCallbacks()
        val engine = JudgeEngine(executor)
        val run = engine.prepareRun()
        val problem = Problem(
            name = "Special judge",
            url = "file:///checker",
            tests = listOf(TestCase(input = "input data", output = "deliberately different", id = 21)),
            sourcePath = source.toString(),
            customCheckerPath = checker.toString(),
        )

        engine.run(run, problem, null, CphSettingsSnapshot(failOnStderr = false), callbacks)

        assertEquals(listOf(Verdict.ACCEPTED), callbacks.finalVerdicts())
        assertEquals("input data", executor.checkerInput)
        assertEquals("actual output", executor.checkerOutput)
        assertTrue(executor.checkerPathsWereTemporary)
    }

    @Test
    fun `classifies process outcomes into distinct verdicts`() {
        val source = temporaryDirectory.resolve("verdicts.py")
        Files.writeString(source, "print('expected')")
        val outcomes = listOf(
            ProcessResult("expected\n", "", 0, 1) to Verdict.ACCEPTED,
            ProcessResult("different\n", "", 0, 1) to Verdict.WRONG_ANSWER,
            ProcessResult("", "", null, 101, timedOut = true) to Verdict.TIME_LIMIT_EXCEEDED,
            ProcessResult("", "boom", 7, 1) to Verdict.RUNTIME_ERROR,
            ProcessResult("expected\n", "debug", 0, 1) to Verdict.RUNTIME_ERROR,
            ProcessResult("", "", null, 1, outputLimitExceeded = true) to Verdict.OUTPUT_LIMIT_EXCEEDED,
        )

        outcomes.forEachIndexed { index, (processResult, expectedVerdict) ->
            val callbacks = RecordingCallbacks()
            val engine = JudgeEngine(SingleResultExecutor(processResult))
            val problem = Problem(
                name = "Verdict $index",
                url = "file:///verdict",
                tests = listOf(TestCase(output = "expected\n", id = index.toLong())),
                sourcePath = source.toString(),
            )

            engine.run(engine.prepareRun(), problem, null, CphSettingsSnapshot(failOnStderr = true), callbacks)

            assertEquals(listOf(expectedVerdict), callbacks.finalVerdicts())
        }
    }

    @Test
    fun `stderr output alone does not fail a testcase by default`() {
        val source = temporaryDirectory.resolve("stderr-default.py")
        Files.writeString(source, "print('expected')")
        val callbacks = RecordingCallbacks()
        val engine = JudgeEngine(SingleResultExecutor(ProcessResult("expected\n", "debug", 0, 1)))
        val problem = Problem(
            name = "Stderr",
            url = "file:///stderr",
            tests = listOf(TestCase(output = "expected\n", id = 1)),
            sourcePath = source.toString(),
        )

        engine.run(engine.prepareRun(), problem, null, CphSettingsSnapshot(), callbacks)

        assertEquals(listOf(Verdict.ACCEPTED), callbacks.finalVerdicts())
    }

    @Test
    fun `nonzero custom checker exit means wrong answer and preserves diagnostics`() {
        val source = temporaryDirectory.resolve("checker-verdict.py")
        val checker = temporaryDirectory.resolve("checker-verdict-checker.py")
        Files.writeString(source, "print('candidate')")
        Files.writeString(checker, "# fake")
        val executor = QueueExecutor(
            ProcessResult("candidate\n", "", 0, 1),
            ProcessResult("", "not a valid candidate", 3, 1),
        )
        val callbacks = RecordingCallbacks()
        val engine = JudgeEngine(executor)
        val problem = Problem(
            name = "Checker verdict",
            url = "file:///checker-verdict",
            tests = listOf(TestCase(output = "ignored", id = 31)),
            sourcePath = source.toString(),
            customCheckerPath = checker.toString(),
        )

        engine.run(engine.prepareRun(), problem, null, CphSettingsSnapshot(), callbacks)

        assertEquals(Verdict.WRONG_ANSWER, callbacks.result(31)?.verdict)
        assertEquals("not a valid candidate", callbacks.result(31)?.checkerStderr)
    }

    @Test
    fun `a new executable has its first launch before any testcase is timed`() {
        val executor = BuildExecutor(compile = ProcessResult("", "", 0, 900))
        val callbacks = RecordingCallbacks()
        val engine = JudgeEngine(executor)

        engine.run(engine.prepareRun(), compiledProblem(), null, CphSettingsSnapshot(), callbacks)

        assertEquals(listOf("compile", "first launch", "run", "run"), executor.events)
        assertEquals(setOf(executor.firstLaunch), executor.runs.toSet())
        assertEquals(listOf(Verdict.ACCEPTED, Verdict.ACCEPTED), callbacks.finalVerdicts())
        assertEquals(listOf(1L, 1L), listOf(callbacks.result(1)?.durationMs, callbacks.result(2)?.durationMs))
    }

    @Test
    fun `an interpreted solution has no first launch to absorb`() {
        val executor = BuildExecutor(compile = ProcessResult("", "", 0, 900))
        val engine = JudgeEngine(executor)

        engine.run(engine.prepareRun(), problem(), null, CphSettingsSnapshot(), RecordingCallbacks())

        assertEquals(listOf("run", "run"), executor.events)
    }

    @Test
    fun `a failed compilation charges no time to the testcases`() {
        val executor = BuildExecutor(compile = ProcessResult("", "solution.cpp:1:1: error", 1, 900))
        val callbacks = RecordingCallbacks()
        val engine = JudgeEngine(executor)

        engine.run(engine.prepareRun(), compiledProblem(), null, CphSettingsSnapshot(), callbacks)

        assertEquals(listOf("compile"), executor.events)
        assertEquals(listOf(Verdict.COMPILATION_ERROR, Verdict.COMPILATION_ERROR), callbacks.finalVerdicts())
        assertEquals(listOf(0L, 0L), listOf(callbacks.result(1)?.durationMs, callbacks.result(2)?.durationMs))
    }

    private fun compiledProblem(): Problem {
        val source = temporaryDirectory.resolve("solution.cpp")
        Files.writeString(source, "int main() {}")
        return problem().copy(sourcePath = source.toString())
    }

    private fun problem(): Problem {
        val source = temporaryDirectory.resolve("solution.py")
        Files.writeString(source, "print('ok')")
        return Problem(
            name = "Cancellation",
            url = "https://example.test/problem",
            tests = listOf(
                TestCase(input = "", output = "ok\n", id = 1),
                TestCase(input = "", output = "ok\n", id = 2),
            ),
            sourcePath = source.toString(),
        )
    }

    private class RecordingExecutor : ProcessExecutor {
        var runCount = 0

        override fun run(
            command: CommandSpec,
            stdin: String,
            timeoutMs: Long,
            cancellationRequested: () -> Boolean,
        ): ProcessResult {
            runCount += 1
            return ProcessResult(stdout = "ok\n", stderr = "", exitCode = 0, durationMs = 1)
        }

        override fun cancel() = Unit
    }

    /** Stands in for a compiler and the program it builds, recording the order in which the engine uses them. */
    private class BuildExecutor(private val compile: ProcessResult) : ProcessExecutor {
        private val compiler = CphSettingsSnapshot().toolchain(LanguageId.CPP).compileCommand
        val events = mutableListOf<String>()
        val runs = mutableListOf<CommandSpec>()
        var firstLaunch: CommandSpec? = null

        override fun run(
            command: CommandSpec,
            stdin: String,
            timeoutMs: Long,
            cancellationRequested: () -> Boolean,
        ): ProcessResult {
            if (command.executable == compiler) {
                events += "compile"
                return compile
            }
            events += "run"
            runs += command
            return ProcessResult(stdout = "ok\n", stderr = "", exitCode = 0, durationMs = 1)
        }

        override fun primeFirstLaunch(command: CommandSpec, cancellationRequested: () -> Boolean) {
            events += "first launch"
            firstLaunch = command
        }

        override fun cancel() = Unit
    }

    private class CheckerExecutor : ProcessExecutor {
        private var invocation = 0
        var checkerInput = ""
        var checkerOutput = ""
        var checkerPathsWereTemporary = false

        override fun run(
            command: CommandSpec,
            stdin: String,
            timeoutMs: Long,
            cancellationRequested: () -> Boolean,
        ): ProcessResult {
            invocation += 1
            if (invocation == 1) {
                return ProcessResult("actual output", "", 0, 1)
            }
            val inputPath = Path.of(command.arguments[1])
            val outputPath = Path.of(command.arguments[2])
            checkerInput = Files.readString(inputPath)
            checkerOutput = Files.readString(outputPath)
            checkerPathsWereTemporary = inputPath.fileName.toString().startsWith("cph-input-") &&
                outputPath.fileName.toString().startsWith("cph-output-")
            return ProcessResult("checker accepted", "", 0, 1)
        }

        override fun cancel() = Unit
    }

    private class SingleResultExecutor(private val result: ProcessResult) : ProcessExecutor {
        override fun run(
            command: CommandSpec,
            stdin: String,
            timeoutMs: Long,
            cancellationRequested: () -> Boolean,
        ): ProcessResult = result

        override fun cancel() = Unit
    }

    private class QueueExecutor(vararg results: ProcessResult) : ProcessExecutor {
        private val results = ArrayDeque(results.toList())

        override fun run(
            command: CommandSpec,
            stdin: String,
            timeoutMs: Long,
            cancellationRequested: () -> Boolean,
        ): ProcessResult = results.removeFirst()

        override fun cancel() = Unit
    }

    private class RecordingCallbacks(
        private val afterResult: (TestRunResult) -> Unit = {},
    ) : JudgeCallbacks {
        val phases = mutableListOf<Pair<RunPhase, String>>()
        private val results = linkedMapOf<Long, TestRunResult>()

        override fun onPhase(phase: RunPhase, message: String) {
            phases += phase to message
        }

        override fun onCompileOutput(output: String) = Unit

        override fun onResult(result: TestRunResult) {
            results[result.testCaseId] = result
            afterResult(result)
        }

        fun finalVerdicts(): List<Verdict> = results.values.map(TestRunResult::verdict)

        fun result(id: Long): TestRunResult? = results[id]
    }
}
