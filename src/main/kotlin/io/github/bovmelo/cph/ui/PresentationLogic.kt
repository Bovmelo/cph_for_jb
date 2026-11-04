package io.github.bovmelo.cph.ui

import io.github.bovmelo.cph.model.LineDifference
import io.github.bovmelo.cph.model.TestCase
import io.github.bovmelo.cph.model.TestRunResult
import io.github.bovmelo.cph.model.Verdict

/** Totals shown under the verdict strip. Disabled cases are not counted: they do not run with "Run". */
internal data class RunSummary(
    val enabled: Int,
    val passed: Int,
    val failed: Int,
    val running: Int,
    val totalMs: Long,
    val peakMemoryKb: Long?,
) {
    val finished: Int get() = passed + failed

    fun headline(): String = when {
        enabled == 0 -> "No enabled testcases"
        running > 0 -> "Running, $finished of $enabled done"
        finished == 0 -> "$enabled testcase${if (enabled == 1) "" else "s"} ready"
        failed == 0 && finished == enabled -> if (enabled == 1) "Accepted" else "All $enabled passed"
        else -> "$passed of $enabled passed"
    }

    companion object {
        fun of(tests: List<TestCase>, results: Map<Long, TestRunResult>): RunSummary {
            val relevant = tests.filter { it.enabled }
            val verdicts = relevant.mapNotNull { results[it.id] }
            return RunSummary(
                enabled = relevant.size,
                passed = verdicts.count { it.verdict == Verdict.ACCEPTED },
                failed = verdicts.count { CphTheme.isFailure(it.verdict) || it.verdict == Verdict.UNSUPPORTED },
                running = verdicts.count { it.verdict == Verdict.RUNNING },
                totalMs = verdicts.sumOf { it.durationMs },
                peakMemoryKb = verdicts.mapNotNull { it.peakMemoryKb }.maxOrNull(),
            )
        }
    }
}

internal fun formatMemory(kb: Long): String =
    if (kb >= 1024) "%.1f MB".format(kb / 1024.0) else "$kb KB"

internal fun formatDuration(ms: Long): String =
    if (ms >= 10_000) "%.1f s".format(ms / 1000.0) else "$ms ms"

/** One short sentence explaining why a run did not simply pass, or null when the verdict explains itself. */
internal fun failureDetail(result: TestRunResult): String? = when (result.verdict) {
    Verdict.TIME_LIMIT_EXCEEDED -> "Stopped after ${formatDuration(result.durationMs)}."
    Verdict.RUNTIME_ERROR -> result.exitCode?.let { "Exited with code $it." } ?: "The process could not run."
    Verdict.OUTPUT_LIMIT_EXCEEDED -> "Output passed the 4 MiB capture limit."
    Verdict.COMPILATION_ERROR -> "Fix the compiler errors, then run again."
    Verdict.CHECKER_ERROR -> "The custom checker failed to run."
    Verdict.UNSUPPORTED -> result.stderr.takeIf { it.isNotBlank() }
    else -> null
}

/**
 * Zero-based line indexes to highlight in the expected or the actual text.
 * The comparator ignores leading blank lines, so its line numbers are shifted by that many lines in the raw text.
 */
internal fun highlightedLines(text: String, differences: List<LineDifference>, expectedSide: Boolean): List<Int> {
    val leadingBlank = text.replace("\r\n", "\n").replace('\r', '\n')
        .split('\n')
        .takeWhile { it.isBlank() }
        .size
        .let { if (it == text.split('\n').size) 0 else it }
    return differences
        .filter { (if (expectedSide) it.expected else it.actual) != null }
        .map { leadingBlank + it.lineNumber - 1 }
}
