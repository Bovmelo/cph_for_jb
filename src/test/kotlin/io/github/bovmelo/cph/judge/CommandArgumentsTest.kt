package io.github.bovmelo.cph.judge

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CommandArgumentsTest {
    @Test
    fun `parses quotes escapes and empty arguments`() {
        assertEquals(
            listOf("-DNAME=hello world", "", "single value", "plain value"),
            CommandArguments.parse("\"-DNAME=hello world\" \"\" 'single value' plain\\ value"),
        )
    }

    @Test
    fun `rejects incomplete quoting instead of invoking a shell`() {
        assertFailsWith<IllegalArgumentException> { CommandArguments.parse("--flag 'unfinished") }
        assertFailsWith<IllegalArgumentException> { CommandArguments.parse("unfinished\\") }
    }

    @Test
    fun `preserves Windows path separators inside double quotes`() {
        assertEquals(
            listOf("C:\\Program Files\\LLVM\\include", "quoted\"value"),
            CommandArguments.parse("\"C:\\Program Files\\LLVM\\include\" \"quoted\\\"value\""),
        )
    }
}
