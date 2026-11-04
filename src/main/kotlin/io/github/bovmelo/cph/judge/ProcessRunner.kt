package io.github.bovmelo.cph.judge

import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessOutputTypes
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

data class ProcessResult(
    val stdout: String,
    val stderr: String,
    val exitCode: Int?,
    val durationMs: Long,
    val timedOut: Boolean = false,
    val cancelled: Boolean = false,
    val launchError: Boolean = false,
    val outputLimitExceeded: Boolean = false,
    val peakMemoryKb: Long? = null,
)

interface ProcessExecutor {
    fun run(
        command: CommandSpec,
        stdin: String,
        timeoutMs: Long,
        cancellationRequested: () -> Boolean,
    ): ProcessResult

    fun cancel()
}

class ProcessRunner(
    private val memoryReader: ProcessMemoryReader? = ProcessMemoryReaders.forCurrentOs(),
) : ProcessExecutor {
    private val activeHandler = AtomicReference<OSProcessHandler?>()

    override fun run(
        command: CommandSpec,
        stdin: String,
        timeoutMs: Long,
        cancellationRequested: () -> Boolean,
    ): ProcessResult {
        val stdout = StringBuffer()
        val stderr = StringBuffer()
        val capturedCharacters = AtomicInteger()
        val outputLimitExceeded = AtomicBoolean(false)
        val startedAt = System.nanoTime()
        if (cancellationRequested()) {
            return ProcessResult("", "", null, elapsedMs(startedAt), cancelled = true)
        }

        val handler = try {
            val commandLine = GeneralCommandLine(command.executable)
                .withParameters(command.arguments)
                .withWorkDirectory(command.workingDirectory.toFile())
                .withCharset(StandardCharsets.UTF_8)
            commandLine.environment.putAll(command.environment)
            OSProcessHandler(commandLine).apply {
                setShouldDestroyProcessRecursively(true)
            }
        } catch (error: ExecutionException) {
            return ProcessResult(
                stdout = "",
                stderr = error.message.orEmpty(),
                exitCode = null,
                durationMs = elapsedMs(startedAt),
                launchError = true,
            )
        }

        handler.addProcessListener(object : ProcessListener {
            override fun onTextAvailable(event: ProcessEvent, outputType: com.intellij.openapi.util.Key<*>) {
                val target = when (outputType) {
                    ProcessOutputTypes.STDOUT -> stdout
                    ProcessOutputTypes.STDERR -> stderr
                    else -> return
                }
                val used = capturedCharacters.getAndAdd(event.text.length)
                val remaining = (MAX_CAPTURED_CHARACTERS - used).coerceAtLeast(0)
                val captured = event.text.take(remaining)
                target.append(captured)
                if (event.text.length > remaining && outputLimitExceeded.compareAndSet(false, true)) {
                    handler.destroyProcess()
                }
            }
        })

        activeHandler.set(handler)
        var memoryMonitor: PeakMemoryMonitor? = null
        return try {
            handler.startNotify()
            memoryMonitor = memoryReader?.let { PeakMemoryMonitor(handler.process.pid(), it) }
            if (cancellationRequested()) {
                handler.destroyProcess()
            } else {
                runCatching {
                    handler.processInput?.bufferedWriter(StandardCharsets.UTF_8)?.use { writer ->
                        writer.write(stdin)
                        writer.flush()
                    }
                }
            }

            val completed = handler.waitFor(timeoutMs)
            val cancelled = cancellationRequested()
            val timedOut = !completed && !cancelled
            if (!completed) {
                handler.destroyProcess()
                handler.waitFor(PROCESS_SHUTDOWN_GRACE_MS)
            }

            val peakMemoryKb = memoryMonitor?.stop()
            ProcessResult(
                stdout = stdout.toString(),
                stderr = stderr.toString(),
                exitCode = handler.exitCode,
                durationMs = elapsedMs(startedAt),
                timedOut = timedOut,
                cancelled = cancelled || cancellationRequested(),
                outputLimitExceeded = outputLimitExceeded.get(),
                peakMemoryKb = peakMemoryKb,
            )
        } finally {
            memoryMonitor?.stop()
            activeHandler.compareAndSet(handler, null)
        }
    }

    override fun cancel() {
        activeHandler.get()?.destroyProcess()
    }

    private fun elapsedMs(startedAt: Long): Long = (System.nanoTime() - startedAt) / 1_000_000

    companion object {
        private const val PROCESS_SHUTDOWN_GRACE_MS = 5_000L
        private const val MAX_CAPTURED_CHARACTERS = 4 * 1024 * 1024
    }
}
