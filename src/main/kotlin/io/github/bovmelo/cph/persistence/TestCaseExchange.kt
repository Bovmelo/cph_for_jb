package io.github.bovmelo.cph.persistence

import io.github.bovmelo.cph.model.CphJson
import io.github.bovmelo.cph.model.TestCase
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

object TestCaseExchange {
    fun decode(content: String): List<TestCase> {
        require(content.toByteArray(StandardCharsets.UTF_8).size <= MAX_FILE_BYTES) {
            "The testcase file is larger than 2 MiB."
        }
        val root = CphJson.format.parseToJsonElement(content)
        require(root is JsonArray) { "The JSON root must be an array." }
        require(root.isNotEmpty()) { "The JSON array must contain at least one testcase." }
        require(root.size <= MAX_TEST_CASES) { "The JSON file contains more than $MAX_TEST_CASES testcases." }

        return root.mapIndexed { index, element ->
            require(element is JsonObject) { "Testcase $index must be a JSON object." }
            TestCase(
                input = element.requiredString("input", index),
                output = element.requiredString("output", index),
            )
        }
    }

    fun encode(testCases: List<TestCase>): String {
        require(testCases.isNotEmpty()) { "At least one testcase is required." }
        require(testCases.size <= MAX_TEST_CASES) { "At most $MAX_TEST_CASES testcases can be exported." }
        require(testCases.none { it.inputFile.isNotBlank() }) {
            "Testcases that use an input file cannot be exported as JSON."
        }
        val portable = testCases.map { PortableTestCase(it.input, it.output) }
        return CphJson.format.encodeToString(portable) + "\n"
    }

    fun read(path: Path): List<TestCase> {
        require(Files.isRegularFile(path)) { "Testcase file does not exist: $path" }
        require(Files.size(path) <= MAX_FILE_BYTES) { "The testcase file is larger than 2 MiB." }
        return decode(Files.readString(path, StandardCharsets.UTF_8))
    }

    fun write(path: Path, testCases: List<TestCase>) {
        val normalizedPath = path.toAbsolutePath().normalize()
        val parent = requireNotNull(normalizedPath.parent) { "The export path must have a parent directory." }
        Files.createDirectories(parent)
        val temporaryPath = Files.createTempFile(parent, ".cph-cases-", ".tmp")
        try {
            Files.writeString(temporaryPath, encode(testCases), StandardCharsets.UTF_8)
            try {
                Files.move(
                    temporaryPath,
                    normalizedPath,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporaryPath, normalizedPath, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporaryPath)
        }
    }

    private fun JsonObject.requiredString(key: String, index: Int): String {
        val value = get(key)
        require(value is JsonPrimitive && value.isString) {
            "Testcase $index must contain a string '$key' field."
        }
        return value.content
    }

    @Serializable
    private data class PortableTestCase(
        val input: String,
        val output: String,
    )

    private const val MAX_FILE_BYTES = 2 * 1024 * 1024
    private const val MAX_TEST_CASES = 1_000
}
