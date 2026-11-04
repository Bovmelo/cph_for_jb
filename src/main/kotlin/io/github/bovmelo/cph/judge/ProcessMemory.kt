package io.github.bovmelo.cph.judge

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.WinNT
import java.nio.file.Files
import java.nio.file.Path

/** Reads the peak memory in KiB that a live process has used so far, or null when it cannot be read. */
fun interface ProcessMemoryReader {
    fun peakKb(pid: Long): Long?
}

object ProcessMemoryReaders {
    fun forCurrentOs(): ProcessMemoryReader? {
        val os = System.getProperty("os.name").orEmpty().lowercase()
        return when {
            os.contains("linux") -> ProcFsMemoryReader
            os.contains("mac") -> MacOsMemoryReader
            os.contains("windows") -> WindowsMemoryReader
            else -> null
        }
    }
}

/** Linux: `VmHWM` is the kernel-tracked peak resident set size of the process. */
internal object ProcFsMemoryReader : ProcessMemoryReader {
    override fun peakKb(pid: Long): Long? = runCatching {
        parseVmHwmKb(Files.readString(Path.of("/proc/$pid/status")))
    }.getOrNull()

    fun parseVmHwmKb(status: String): Long? =
        VM_HWM.find(status)?.groupValues?.get(1)?.toLongOrNull()

    private val VM_HWM = Regex("""^VmHWM:\s+(\d+)\s+kB""", RegexOption.MULTILINE)
}

/**
 * macOS: `proc_pid_rusage` reports the lifetime peak physical footprint, the figure Activity Monitor
 * calls "Memory". It is read through libSystem so no extra native code is shipped.
 */
internal object MacOsMemoryReader : ProcessMemoryReader {
    private interface LibSystem : Library {
        fun proc_pid_rusage(pid: Int, flavor: Int, buffer: Pointer): Int
    }

    private val library: LibSystem? by lazy {
        runCatching { Native.load("System", LibSystem::class.java) }.getOrNull()
    }

    override fun peakKb(pid: Long): Long? = runCatching {
        val libSystem = library ?: return null
        val buffer = Memory(RUSAGE_BUFFER_BYTES)
        buffer.clear()
        if (libSystem.proc_pid_rusage(pid.toInt(), RUSAGE_INFO_V4, buffer) != 0) {
            return null
        }
        (buffer.getLong(LIFETIME_MAX_PHYS_FOOTPRINT_OFFSET) / 1024).takeIf { it > 0 }
    }.getOrNull()

    private const val RUSAGE_INFO_V4 = 4
    private const val RUSAGE_BUFFER_BYTES = 512L

    // Byte offset of ri_lifetime_max_phys_footprint inside struct rusage_info_v4.
    private const val LIFETIME_MAX_PHYS_FOOTPRINT_OFFSET = 240L
}

/**
 * Windows: `PeakWorkingSetSize` from psapi.
 * The platform's bundled `Psapi` binding lacks GetProcessMemoryInfo, so it is declared here.
 */
internal object WindowsMemoryReader : ProcessMemoryReader {
    private interface PsapiLibrary : Library {
        fun GetProcessMemoryInfo(process: WinNT.HANDLE, counters: ProcessMemoryCounters, size: Int): Boolean
    }

    // Field order and widths follow PROCESS_MEMORY_COUNTERS on 64-bit Windows, where SIZE_T is 8 bytes.
    @Structure.FieldOrder(
        "cb",
        "pageFaultCount",
        "peakWorkingSetSize",
        "workingSetSize",
        "quotaPeakPagedPoolUsage",
        "quotaPagedPoolUsage",
        "quotaPeakNonPagedPoolUsage",
        "quotaNonPagedPoolUsage",
        "pagefileUsage",
        "peakPagefileUsage",
    )
    class ProcessMemoryCounters : Structure() {
        @JvmField var cb: Int = 0

        @JvmField var pageFaultCount: Int = 0

        @JvmField var peakWorkingSetSize: Long = 0

        @JvmField var workingSetSize: Long = 0

        @JvmField var quotaPeakPagedPoolUsage: Long = 0

        @JvmField var quotaPagedPoolUsage: Long = 0

        @JvmField var quotaPeakNonPagedPoolUsage: Long = 0

        @JvmField var quotaNonPagedPoolUsage: Long = 0

        @JvmField var pagefileUsage: Long = 0

        @JvmField var peakPagefileUsage: Long = 0
    }

    private val psapi: PsapiLibrary? by lazy {
        runCatching { Native.load("psapi", PsapiLibrary::class.java) }.getOrNull()
    }

    override fun peakKb(pid: Long): Long? = runCatching {
        val library = psapi ?: return null
        val kernel32 = Kernel32.INSTANCE
        val handle = kernel32.OpenProcess(WinNT.PROCESS_QUERY_INFORMATION or WinNT.PROCESS_VM_READ, false, pid.toInt())
            ?: return null
        try {
            val counters = ProcessMemoryCounters()
            counters.cb = counters.size()
            if (!library.GetProcessMemoryInfo(handle, counters, counters.cb)) {
                return null
            }
            (counters.peakWorkingSetSize / 1024).takeIf { it > 0 }
        } finally {
            kernel32.CloseHandle(handle)
        }
    }.getOrNull()
}

/**
 * Polls a process (and any descendants) while it runs and remembers the largest total seen.
 * Peaks are read from the OS, so a short sampling gap only loses growth that happens right before exit.
 */
class PeakMemoryMonitor(
    private val pid: Long,
    private val reader: ProcessMemoryReader,
    private val intervalMs: Long = SAMPLE_INTERVAL_MS,
) {
    @Volatile
    private var peakKb: Long? = null

    @Volatile
    private var running = true

    private val thread = Thread(::pollUntilStopped, "cph-memory-monitor").apply {
        isDaemon = true
        start()
    }

    /** Stops sampling and returns the peak in KiB, or null when no sample could be read. */
    fun stop(): Long? {
        sample()
        running = false
        thread.interrupt()
        runCatching { thread.join(JOIN_TIMEOUT_MS) }
        return peakKb
    }

    private fun pollUntilStopped() {
        while (running) {
            sample()
            try {
                Thread.sleep(intervalMs)
            } catch (_: InterruptedException) {
                return
            }
        }
    }

    private fun sample() {
        val root = ProcessHandle.of(pid).orElse(null) ?: return
        val handles = listOf(root) + root.descendants().toList()
        val readings = handles.mapNotNull { reader.peakKb(it.pid()) }
        if (readings.isEmpty()) {
            return
        }
        val total = readings.sum()
        synchronized(this) {
            if (total > (peakKb ?: 0)) {
                peakKb = total
            }
        }
    }

    companion object {
        private const val SAMPLE_INTERVAL_MS = 2L
        private const val JOIN_TIMEOUT_MS = 200L
    }
}
