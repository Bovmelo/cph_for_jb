package io.github.bovmelo.cph.judge

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OutputComparatorTest {
    @Test
    fun `matches CPH line trimming behavior`() {
        val result = OutputComparator.compare("  hello  \r\nworld\n", "hello\n  world  \n\n")

        assertTrue(result.matches)
        assertTrue(result.differences.isEmpty())
    }

    @Test
    fun `reports changed missing and extra lines`() {
        val result = OutputComparator.compare("one\ntwo\nthree", "one\nTWO\nthree\nfour")

        assertFalse(result.matches)
        assertEquals(2, result.differences.size)
        assertEquals(2, result.differences[0].lineNumber)
        assertEquals("two", result.differences[0].expected)
        assertEquals("TWO", result.differences[0].actual)
        assertEquals(null, result.differences[1].expected)
        assertEquals("four", result.differences[1].actual)
    }
}
