package io.github.bovmelo.cph.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import java.nio.file.Path

@Serializable
data class CompanionProblemPayload(
    val name: String,
    val group: String = "",
    val url: String = "",
    val interactive: Boolean = false,
    val memoryLimit: Int? = null,
    val timeLimit: Long? = null,
    val tests: List<CompanionTestCasePayload> = emptyList(),
    val testType: String = "single",
    val input: CompanionIo? = null,
    val output: CompanionIo? = null,
    val languages: JsonObject? = null,
    val batch: CompanionBatch? = null,
) {
    fun toProblem(sourcePath: Path, languageId: LanguageId, local: Boolean = false): Problem = Problem(
        name = name,
        url = url,
        interactive = interactive,
        memoryLimit = memoryLimit ?: 1024,
        timeLimit = timeLimit ?: 3_000,
        group = group,
        tests = tests.map { TestCase(input = it.input, output = it.output.orEmpty()) },
        sourcePath = sourcePath.toAbsolutePath().normalize().toString(),
        local = local,
        languageId = languageId.settingValue,
    )
}

@Serializable
data class CompanionTestCasePayload(
    val input: String = "",
    val output: String? = null,
)

@Serializable
data class CompanionIo(
    val type: String,
    val fileName: String? = null,
    val pattern: String? = null,
)

@Serializable
data class CompanionBatch(
    val id: String,
    val size: Int,
)
