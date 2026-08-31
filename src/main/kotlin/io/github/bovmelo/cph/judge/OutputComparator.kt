package io.github.bovmelo.cph.judge

import io.github.bovmelo.cph.model.ComparisonResult
import io.github.bovmelo.cph.model.LineDifference

object OutputComparator {
    fun compare(expected: String, actual: String): ComparisonResult {
        val expectedLines = normalize(expected)
        val actualLines = normalize(actual)
        val differences = buildList {
            val lineCount = maxOf(expectedLines.size, actualLines.size)
            for (index in 0 until lineCount) {
                val expectedLine = expectedLines.getOrNull(index)
                val actualLine = actualLines.getOrNull(index)
                if (expectedLine != actualLine) {
                    add(LineDifference(index + 1, expectedLine, actualLine))
                }
            }
        }
        return ComparisonResult(matches = differences.isEmpty(), differences = differences)
    }

    private fun normalize(value: String): List<String> = value
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .trim()
        .split('\n')
        .map(String::trim)
}
