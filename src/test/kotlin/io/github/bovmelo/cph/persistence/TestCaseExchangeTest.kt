package io.github.bovmelo.cph.persistence

import io.github.bovmelo.cph.model.TestCase
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class TestCaseExchangeTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `round trips portable testcase JSON without persistent ids`() {
        val path = temporaryDirectory.resolve("cases.json")
        val original = listOf(
            TestCase(input = "1 2\n", output = "3\n", id = 10),
            TestCase(input = "hello\n", output = "world\n", id = 20),
        )

        TestCaseExchange.write(path, original)
        val imported = TestCaseExchange.read(path)

        assertEquals(original.map(TestCase::input), imported.map(TestCase::input))
        assertEquals(original.map(TestCase::output), imported.map(TestCase::output))
        assertNotEquals(original.map(TestCase::id), imported.map(TestCase::id))
        assertTrue(TestCaseExchange.encode(original).contains("\"input\""))
    }

    @Test
    fun `rejects malformed and empty imports with a useful boundary`() {
        assertFailsWith<IllegalArgumentException> { TestCaseExchange.decode("{}") }
        assertFailsWith<IllegalArgumentException> { TestCaseExchange.decode("[]") }
        assertFailsWith<IllegalArgumentException> { TestCaseExchange.decode("[{\"input\":1,\"output\":\"x\"}]") }
        assertFailsWith<IllegalArgumentException> { TestCaseExchange.decode("[{\"input\":\"x\"}]") }
    }
}
