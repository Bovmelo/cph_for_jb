package io.github.bovmelo.cph.judge

import io.github.bovmelo.cph.model.CphJson
import io.github.bovmelo.cph.model.LanguageId
import io.github.bovmelo.cph.model.Problem
import io.github.bovmelo.cph.model.RunPhase
import io.github.bovmelo.cph.model.TestCase
import io.github.bovmelo.cph.model.TestRunResult
import io.github.bovmelo.cph.model.Verdict
import io.github.bovmelo.cph.settings.CphSettingsSnapshot
import io.github.bovmelo.cph.settings.LanguageToolchain
import kotlinx.serialization.decodeFromString
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class StressRunnerTest {
    @TempDir
    lateinit var directory: Path

    private val settings = CphSettingsSnapshot(failOnStderr = false)

    @Test
    fun `reports the first iteration where the solution differs from the brute force`() {
        val outcome = stress(iterations = 10) { program, seed, stdin ->
            when (program) {
                "gen.py" -> ProcessResult("case $seed\n", "", 0, 1)
                "brute.py" -> ProcessResult("ok\n", "", 0, 1)
                else -> ProcessResult(if (stdin == "case 3\n") "bad\n" else "ok\n", "", 0, 1)
            }
        }

        val failed = assertIs<StressOutcome.Failed>(outcome)
        assertEquals(3, failed.iteration)
        assertEquals("case 3\n", failed.input)
        assertEquals("ok\n", failed.expectedOutput)
        assertEquals(Verdict.WRONG_ANSWER, failed.verdict)
        assertTrue(failed.differences.isNotEmpty())
    }

    @Test
    fun `passes when every iteration agrees`() {
        val outcome = stress(iterations = 5) { program, seed, _ ->
            ProcessResult(if (program == "gen.py") "case $seed\n" else "ok\n", "", 0, 1)
        }

        assertEquals(StressOutcome.Passed(5), outcome)
    }

    @Test
    fun `solution crash is a runtime error with the generated input`() {
        val outcome = stress(iterations = 5) { program, seed, _ ->
            when (program) {
                "gen.py" -> ProcessResult("case $seed\n", "", 0, 1)
                "solution.py" -> ProcessResult("", "boom", 1, 1)
                else -> ProcessResult("ok\n", "", 0, 1)
            }
        }

        val failed = assertIs<StressOutcome.Failed>(outcome)
        assertEquals(Verdict.RUNTIME_ERROR, failed.verdict)
        assertEquals(1, failed.iteration)
    }

    @Test
    fun `a failing generator is reported as an error instead of a solution failure`() {
        val outcome = stress(iterations = 5) { program, _, _ ->
            if (program == "gen.py") ProcessResult("", "bad generator", 2, 1) else ProcessResult("ok\n", "", 0, 1)
        }

        val error = assertIs<StressOutcome.Error>(outcome)
        assertTrue("Generator" in error.message)
    }

    @Test
    fun `the brute force receives the generated input`() {
        val inputs = mutableListOf<String>()
        stress(iterations = 2) { program, seed, stdin ->
            if (program == "brute.py") inputs += stdin
            ProcessResult(if (program == "gen.py") "case $seed\n" else "ok\n", "", 0, 1)
        }

        assertEquals(listOf("case 1\n", "case 2\n"), inputs)
    }

    @Test
    fun `cancellation stops before the next iteration`() {
        val engine = JudgeEngine(
            FakeExecutor { program, seed, _ ->
                ProcessResult(if (program == "gen.py") "case $seed\n" else "ok\n", "", 0, 1)
            },
        )
        val run = engine.prepareRun()
        engine.cancel()

        val outcome = engine.stress(run, config(10), settings) {}

        assertIs<StressOutcome.Cancelled>(outcome)
    }

    @Test
    fun `disabled testcases are skipped by run all but run when selected`() {
        val source = directory.resolve("solution.py").also { Files.writeString(it, "print('ok')") }
        val problem = Problem(
            name = "Disabled",
            url = "file:///disabled",
            tests = listOf(
                TestCase(output = "ok\n", id = 1),
                TestCase(output = "ok\n", id = 2, enabled = false),
            ),
            sourcePath = source.toString(),
        )
        val ran = { selected: Set<Long>? ->
            val results = mutableListOf<TestRunResult>()
            val engine = JudgeEngine(FakeExecutor { _, _, _ -> ProcessResult("ok\n", "", 0, 1) })
            engine.run(
                engine.prepareRun(),
                problem,
                selected,
                settings,
                object : JudgeCallbacks {
                    override fun onPhase(phase: RunPhase, message: String) = Unit

                    override fun onCompileOutput(output: String) = Unit

                    override fun onResult(result: TestRunResult) {
                        if (result.verdict == Verdict.ACCEPTED) results += result
                    }
                },
            )
            results.map(TestRunResult::testCaseId)
        }

        assertEquals(listOf(1L), ran(null))
        assertEquals(listOf(2L), ran(setOf(2L)))
    }

    @Test
    fun `problems saved before the enabled flag existed load as enabled`() {
        val problem = CphJson.format.decodeFromString<Problem>(
            """{"name":"Old","url":"","srcPath":"a.kt","tests":[{"input":"1","output":"2","id":7}]}""",
        )

        assertTrue(problem.tests.single().enabled)
    }

    @Test
    fun `finds a real counterexample with compiled Java programs`() {
        fun program(name: String, body: String): Path {
            val source = directory.resolve(name).resolve("Main.java")
            Files.createDirectories(source.parent)
            Files.writeString(source, "public class Main { public static void main(String[] a) throws Exception { $body } }")
            return source
        }
        // The solution is wrong exactly when the generated number is 4.
        val solution = program(
            "solution",
            "int n = new java.util.Scanner(System.in).nextInt(); System.out.println(n == 4 ? -1 : n * 2);",
        )
        val generator = program("generator", "System.out.println(a[0]);")
        val brute = program("brute", "int n = new java.util.Scanner(System.in).nextInt(); System.out.println(n * 2);")
        val javaBin = Path.of(System.getProperty("java.home"), "bin")
        val suffix = if (System.getProperty("os.name").startsWith("Windows", true)) ".exe" else ""
        val realSettings = CphSettingsSnapshot(
            timeoutMs = 10_000,
            compileTimeoutMs = 30_000,
            failOnStderr = false,
            toolchains = CphSettingsSnapshot.defaultToolchains().toMutableMap().apply {
                this[LanguageId.JAVA] = LanguageToolchain(
                    compileCommand = javaBin.resolve("javac$suffix").toString(),
                    runCommand = javaBin.resolve("java$suffix").toString(),
                )
            },
        )
        val engine = JudgeEngine()

        val outcome = engine.stress(engine.prepareRun(), StressConfig(solution, generator, brute, 10), realSettings) {}

        val failed = assertIs<StressOutcome.Failed>(outcome)
        assertEquals(4, failed.iteration)
        assertEquals("4", failed.input.trim())
        assertEquals("8", failed.expectedOutput.trim())
        assertEquals("-1", failed.actualOutput.trim())
    }

    private fun config(iterations: Int): StressConfig {
        val files = listOf("solution.py", "gen.py", "brute.py").map { name ->
            directory.resolve(name).also { if (!Files.exists(it)) Files.writeString(it, "pass") }
        }
        return StressConfig(files[0], files[1], files[2], iterations)
    }

    private fun stress(iterations: Int, behaviour: (String, String, String) -> ProcessResult): StressOutcome {
        val engine = JudgeEngine(FakeExecutor(behaviour))
        return engine.stress(engine.prepareRun(), config(iterations), settings) {}
    }

    /** Dispatches on the script name (first argument) and passes the trailing argument as the seed. */
    private class FakeExecutor(private val behaviour: (String, String, String) -> ProcessResult) : ProcessExecutor {
        override fun run(
            command: CommandSpec,
            stdin: String,
            timeoutMs: Long,
            cancellationRequested: () -> Boolean,
        ): ProcessResult = behaviour(
            Path.of(command.arguments.first()).fileName.toString(),
            command.arguments.last(),
            stdin,
        )

        override fun cancel() = Unit
    }
}
