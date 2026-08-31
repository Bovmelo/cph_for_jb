package io.github.bovmelo.cph.persistence

import io.github.bovmelo.cph.model.Problem
import io.github.bovmelo.cph.model.TestCase
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProblemStoreTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `round trips CPH compatible problem metadata`() {
        val source = temporaryDirectory.resolve("Main.kt")
        Files.writeString(source, "fun main() = Unit")
        val problem = Problem(
            name = "Local Main",
            url = source.toUri().toString(),
            group = "local",
            tests = listOf(TestCase(input = "1\n", output = "1\n", id = 7)),
            sourcePath = source.toString(),
            local = true,
            languageId = "kotlin",
        )
        val store = ProblemStore()

        store.save(problem)

        assertEquals(problem, store.load(source))
        assertTrue(store.metadataPath(source).fileName.toString().startsWith(".Main.kt_"))
        assertTrue(store.metadataPath(source).fileName.toString().endsWith(".prob"))
    }

    @Test
    fun `reports corrupt metadata as present without rewriting it`() {
        val source = temporaryDirectory.resolve("Broken.kt")
        Files.writeString(source, "fun main() = Unit")
        val store = ProblemStore()
        val metadata = store.metadataPath(source)
        Files.createDirectories(metadata.parent)
        Files.writeString(metadata, "not-json")

        assertTrue(store.exists(source))
        assertNull(store.load(source))
        assertEquals("not-json", Files.readString(metadata))
    }
}
