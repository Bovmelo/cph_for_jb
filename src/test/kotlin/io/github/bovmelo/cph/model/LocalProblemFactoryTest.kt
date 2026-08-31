package io.github.bovmelo.cph.model

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LocalProblemFactoryTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `creates the same blank local problem shape as the original CPH workflow`() {
        val sourcePath = temporaryDirectory.resolve("I.cpp")

        val problem = LocalProblemFactory.create(sourcePath)

        assertEquals("Local: I", problem.name)
        assertEquals(sourcePath.toAbsolutePath().normalize().toString(), problem.url)
        assertEquals("local", problem.group)
        assertEquals(3_000L, problem.timeLimit)
        assertEquals(1_024, problem.memoryLimit)
        assertEquals("cpp", problem.languageId)
        assertTrue(problem.local)
        assertEquals(listOf(TestCase(input = "", output = "", id = problem.tests.single().id)), problem.tests)
    }
}
