package io.github.bovmelo.cph.judge

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.StringArray
import com.sun.jna.ptr.IntByReference
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Gets a newly built executable past the work an operating system does the first time it launches a file, so
 * that this delay is not charged to the first timed run. None of the program's own code may run.
 */
fun interface FirstLaunchPrimer {
    /** False when the launch could not be seen through; the first timed run may then still be slow. */
    fun prime(executable: Path, cancellationRequested: () -> Boolean): Boolean
}

object FirstLaunchPrimers {
    fun forCurrentOs(): FirstLaunchPrimer? =
        MacOsFirstLaunchPrimer.takeIf { System.getProperty("os.name").orEmpty().lowercase().contains("mac") }
}

/**
 * macOS checks an executable it has not seen before when the file is first launched, and holds the new process
 * for hundreds of milliseconds before `main` while it does. Here the file is launched suspended, so the check
 * runs but the program never does, and the process is killed once the check is over. It goes through
 * libSystem so no extra native code is shipped.
 */
internal object MacOsFirstLaunchPrimer : FirstLaunchPrimer {
    private interface LibSystem : Library {
        fun posix_spawnattr_init(attributes: Pointer): Int

        fun posix_spawnattr_setflags(attributes: Pointer, flags: Short): Int

        fun posix_spawnattr_destroy(attributes: Pointer): Int

        fun posix_spawn(
            pid: IntByReference,
            path: Pointer,
            fileActions: Pointer?,
            attributes: Pointer,
            argv: StringArray,
            envp: StringArray,
        ): Int

        fun proc_pidinfo(pid: Int, flavor: Int, argument: Long, buffer: Pointer, size: Int): Int

        fun kill(pid: Int, signal: Int): Int

        fun waitpid(pid: Int, status: IntByReference?, options: Int): Int
    }

    private val library: LibSystem? by lazy {
        runCatching { Native.load("System", LibSystem::class.java) }.getOrNull()
    }

    // Set after a launch that never settled: this system behaves differently, so stop making runs wait for it.
    private val unsupported = AtomicBoolean(false)

    override fun prime(executable: Path, cancellationRequested: () -> Boolean): Boolean {
        if (unsupported.get()) {
            return false
        }
        return runCatching {
            val libSystem = library ?: return false
            val pid = spawnSuspended(libSystem, executable) ?: return false
            try {
                val deadline = System.nanoTime() + SETTLE_TIMEOUT_NANOS
                while (!settled(libSystem, pid)) {
                    if (cancellationRequested()) {
                        return false
                    }
                    if (System.nanoTime() > deadline) {
                        unsupported.set(true)
                        return false
                    }
                    Thread.sleep(POLL_INTERVAL_MS)
                }
                true
            } finally {
                // SIGKILL is not a crash, so unlike an aborted launch it leaves no diagnostic report behind.
                libSystem.kill(pid, SIGKILL)
                libSystem.waitpid(pid, null, 0)
            }
        }.getOrDefault(false)
    }

    private fun spawnSuspended(libSystem: LibSystem, executable: Path): Int? {
        val argv = StringArray(arrayOf(executable.toString()), "UTF-8")
        val attributes = Memory(Native.POINTER_SIZE.toLong())
        if (libSystem.posix_spawnattr_init(attributes) != 0) {
            return null
        }
        try {
            if (libSystem.posix_spawnattr_setflags(attributes, SPAWN_FLAGS) != 0) {
                return null
            }
            val pid = IntByReference()
            val environment = StringArray(emptyArray<String>())
            val spawned = libSystem.posix_spawn(pid, argv.getPointer(0), null, attributes, argv, environment) == 0
            return if (spawned) pid.value else null
        } finally {
            libSystem.posix_spawnattr_destroy(attributes)
        }
    }

    /**
     * While the check runs, the process's only thread waits inside the kernel and keeps its kernel stack. Once
     * the check is over the thread parks at the suspension without one, which the kernel reports as "swapped".
     */
    private fun settled(libSystem: LibSystem, pid: Int): Boolean {
        val threads = Memory(Long.SIZE_BYTES.toLong())
        if (libSystem.proc_pidinfo(pid, PROC_PIDLISTTHREADS, 0, threads, Long.SIZE_BYTES) < Long.SIZE_BYTES) {
            return false
        }
        val info = Memory(THREAD_INFO_BYTES.toLong())
        val read = libSystem.proc_pidinfo(pid, PROC_PIDTHREADINFO, threads.getLong(0), info, THREAD_INFO_BYTES)
        return read >= THREAD_INFO_BYTES && info.getInt(THREAD_FLAGS_OFFSET) and TH_FLAGS_SWAPPED != 0
    }

    private const val POSIX_SPAWN_START_SUSPENDED = 0x0080
    private const val POSIX_SPAWN_CLOEXEC_DEFAULT = 0x4000
    private const val SPAWN_FLAGS = (POSIX_SPAWN_START_SUSPENDED or POSIX_SPAWN_CLOEXEC_DEFAULT).toShort()
    private const val PROC_PIDTHREADINFO = 5
    private const val PROC_PIDLISTTHREADS = 6

    // struct proc_threadinfo: two 64-bit times, then 32-bit cpu_usage, policy, run_state and flags.
    private const val THREAD_INFO_BYTES = 112
    private const val THREAD_FLAGS_OFFSET = 28L
    private const val TH_FLAGS_SWAPPED = 0x1
    private const val SIGKILL = 9
    private const val POLL_INTERVAL_MS = 5L

    // The check itself takes well under a second; killing it early would only make the first run pay again.
    private const val SETTLE_TIMEOUT_NANOS = 5_000_000_000L
}
