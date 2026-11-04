package io.github.bovmelo.cph.judge

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ProcessMemoryTest {
    @Test
    fun `parses the Linux peak resident set size`() {
        val status = "Name:\tjava\nVmPeak:\t 9000 kB\nVmHWM:\t  123456 kB\nVmRSS:\t   100000 kB\n"

        assertEquals(123_456L, ProcFsMemoryReader.parseVmHwmKb(status))
        assertNull(ProcFsMemoryReader.parseVmHwmKb("Name:\tkernel\n"))
    }

    @Test
    fun `monitor keeps the largest reading and tolerates unreadable samples`() {
        val readings = ArrayDeque(listOf(100L, null, 400L, 250L))
        val reader = ProcessMemoryReader { readings.removeFirstOrNull() }
        val monitor = PeakMemoryMonitor(ProcessHandle.current().pid(), reader, intervalMs = 1)

        Thread.sleep(100)

        assertEquals(400L, monitor.stop())
    }

    @Test
    fun `monitor reports nothing when the process cannot be read`() {
        val monitor = PeakMemoryMonitor(ProcessHandle.current().pid(), { null }, intervalMs = 1)

        assertNull(monitor.stop())
    }

    @Test
    fun `judge results carry the peak memory of the run`() {
        val source = java.nio.file.Files.createTempFile("memory-", ".py")
        try {
            java.nio.file.Files.writeString(source, "print('ok')")
            val engine = JudgeEngine(
                object : ProcessExecutor {
                    override fun run(
                        command: CommandSpec,
                        stdin: String,
                        timeoutMs: Long,
                        cancellationRequested: () -> Boolean,
                    ) = ProcessResult("ok\n", "", 0, 5, peakMemoryKb = 4_321)

                    override fun cancel() = Unit
                },
            )
            val problem = io.github.bovmelo.cph.model.Problem(
                name = "Memory",
                url = "file:///memory",
                tests = listOf(io.github.bovmelo.cph.model.TestCase(output = "ok\n", id = 1)),
                sourcePath = source.toString(),
            )
            val results = mutableListOf<io.github.bovmelo.cph.model.TestRunResult>()

            engine.run(
                engine.prepareRun(),
                problem,
                null,
                io.github.bovmelo.cph.settings.CphSettingsSnapshot(),
                object : JudgeCallbacks {
                    override fun onPhase(phase: io.github.bovmelo.cph.model.RunPhase, message: String) = Unit

                    override fun onCompileOutput(output: String) = Unit

                    override fun onResult(result: io.github.bovmelo.cph.model.TestRunResult) {
                        results += result
                    }
                },
            )

            assertEquals(4_321L, results.last().peakMemoryKb)
        } finally {
            java.nio.file.Files.deleteIfExists(source)
        }
    }
}
