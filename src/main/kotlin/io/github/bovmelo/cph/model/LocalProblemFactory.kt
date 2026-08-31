package io.github.bovmelo.cph.model

import java.nio.file.Path

object LocalProblemFactory {
    fun create(sourcePath: Path): Problem {
        val normalizedSourcePath = sourcePath.toAbsolutePath().normalize()
        val language = requireNotNull(LanguageId.fromPath(normalizedSourcePath)) {
            "Unsupported source file: ${normalizedSourcePath.fileName}"
        }
        val fileName = normalizedSourcePath.fileName.toString()
        val problemName = fileName.substringBeforeLast('.', fileName).ifBlank { fileName }

        return Problem(
            name = "Local: $problemName",
            url = normalizedSourcePath.toString(),
            group = "local",
            tests = listOf(TestCase()),
            sourcePath = normalizedSourcePath.toString(),
            local = true,
            languageId = language.settingValue,
        )
    }
}
