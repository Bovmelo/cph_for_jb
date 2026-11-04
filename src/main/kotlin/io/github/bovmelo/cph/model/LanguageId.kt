package io.github.bovmelo.cph.model

import java.nio.file.Path

enum class LanguageId(
    val settingValue: String,
    val displayName: String,
    val extension: String,
    vararg extensionAliases: String,
) {
    KOTLIN("kotlin", "Kotlin", "kt"),
    CPP("cpp", "C++", "cpp", "cc", "cxx"),
    C("c", "C", "c"),
    CSHARP("csharp", "C#", "cs"),
    RUST("rust", "Rust", "rs"),
    GO("go", "Go", "go"),
    HASKELL("haskell", "Haskell", "hs"),
    PYTHON("python", "Python", "py"),
    RUBY("ruby", "Ruby", "rb"),
    JAVA("java", "Java", "java"),
    JAVASCRIPT("javascript", "JavaScript (Node.js)", "js"),
    CANGJIE("cangjie", "Cangjie", "cj"),
    ;

    val extensions: Set<String> = setOf(extension, *extensionAliases)

    companion object {
        fun fromSetting(value: String?): LanguageId =
            entries.firstOrNull { it.settingValue.equals(value, ignoreCase = true) } ?: KOTLIN

        fun fromPath(path: Path): LanguageId? {
            val extension = path.fileName.toString().substringAfterLast('.', missingDelimiterValue = "")
            return entries.firstOrNull { language ->
                language.extensions.any { it.equals(extension, ignoreCase = true) }
            }
        }
    }
}
