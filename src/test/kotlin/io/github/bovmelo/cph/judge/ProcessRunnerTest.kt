package io.github.bovmelo.cph.judge

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ProcessRunnerTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    private lateinit var javaCommand: String

    @BeforeEach
    fun compileFixture() {
        val source = temporaryDirectory.resolve("ProcessFixture.java")
        Files.writeString(
            source,
            """
                import java.nio.charset.StandardCharsets;

                public class ProcessFixture {
                    public static void main(String[] args) throws Exception {
                        switch (args[0]) {
                            case "echo" -> System.out.print(new String(System.in.readAllBytes(), StandardCharsets.UTF_8));
                            case "sleep" -> Thread.sleep(5_000L);
                            case "fail" -> {
                                System.err.print("fixture failure");
                                System.exit(7);
                            }
                            case "alloc" -> {
                                byte[] bytes = new byte[200 * 1024 * 1024];
                                java.util.Arrays.fill(bytes, (byte) 1);
                                Thread.sleep(300L);
                                System.out.print(bytes[12345]);
                            }
                            case "spam" -> {
                                byte[] bytes = new byte[5 * 1024 * 1024];
                                java.util.Arrays.fill(bytes, (byte) 'x');
                                System.out.write(bytes);
                            }
                            default -> throw new IllegalArgumentException(args[0]);
                        }
                    }
                }
            """.trimIndent(),
        )
        val compilation = ProcessBuilder("javac", "--release", "21", source.toString())
            .redirectErrorStream(true)
            .start()
        val compilationOutput = compilation.inputStream.bufferedReader().readText()
        assertEquals(0, compilation.waitFor(), compilationOutput)
        javaCommand = Path.of(System.getProperty("java.home"), "bin", if (isWindows()) "java.exe" else "java").toString()
    }

    @Test
    fun `passes stdin and captures stdout from a real process`() {
        val result = ProcessRunner().run(command("echo"), "hello\n", 5_000) { false }

        assertEquals("hello\n", result.stdout)
        assertEquals(0, result.exitCode)
        assertFalse(result.timedOut)
    }

    @Test
    fun `streams an input file larger than 2 MiB to stdin`() {
        val file = temporaryDirectory.resolve("big-input.txt")
        val content = "0123456789abcdef\n".repeat(200_000)
        Files.writeString(file, content)

        val result = ProcessRunner().runWithInputFile(command("echo"), file, 10_000) { false }

        assertTrue(content.length > 2 * 1024 * 1024)
        assertEquals(content.length, result.stdout.length)
        assertEquals(0, result.exitCode)
    }

    @Test
    fun `reports timeout launch error exit status and output limit`() {
        val timeout = ProcessRunner().run(command("sleep"), "", 100) { false }
        val failure = ProcessRunner().run(command("fail"), "", 5_000) { false }
        val missing = ProcessRunner().run(
            CommandSpec("definitely-missing-cph-command", emptyList(), temporaryDirectory),
            "",
            5_000,
        ) { false }
        val spam = ProcessRunner().run(command("spam"), "", 5_000) { false }

        assertTrue(timeout.timedOut)
        assertEquals(7, failure.exitCode)
        assertEquals("fixture failure", failure.stderr)
        assertTrue(missing.launchError)
        assertTrue(spam.outputLimitExceeded)
        assertTrue(spam.stdout.length <= 4 * 1024 * 1024)
    }

    @Test
    fun `reports the peak memory of a real process`() {
        val result = ProcessRunner().run(command("alloc"), "", 10_000) { false }

        assertEquals("1", result.stdout)
        val supported = System.getProperty("os.name").let { it.startsWith("Mac") || it.startsWith("Linux") }
        if (supported) {
            val peakKb = assertNotNull(result.peakMemoryKb, "peak memory should be measured on this OS")
            // The fixture touches 200 MiB; allow for footprint accounting differences between kernels.
            assertTrue(peakKb in 150_000..1_500_000, "unexpected peak memory: $peakKb KiB")
        }
    }

    @Test
    fun `memory measurement can be disabled`() {
        val result = ProcessRunner(memoryReader = null).run(command("echo"), "x", 5_000) { false }

        assertEquals(null, result.peakMemoryKb)
    }

    @Test
    fun `cancels an active process tree`() {
        val runner = ProcessRunner()
        val cancellation = AtomicBoolean(false)
        val worker = Executors.newSingleThreadExecutor()
        try {
            val future = worker.submit<ProcessResult> {
                runner.run(command("sleep"), "", 10_000, cancellation::get)
            }
            Thread.sleep(200)
            cancellation.set(true)
            runner.cancel()
            val result = future.get(5, TimeUnit.SECONDS)

            assertTrue(result.cancelled)
            assertFalse(result.timedOut)
        } finally {
            worker.shutdownNow()
        }
    }

    private fun command(mode: String) = CommandSpec(
        executable = javaCommand,
        arguments = listOf("-cp", temporaryDirectory.toString(), "ProcessFixture", mode),
        workingDirectory = temporaryDirectory,
    )

    private fun isWindows(): Boolean = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
}
