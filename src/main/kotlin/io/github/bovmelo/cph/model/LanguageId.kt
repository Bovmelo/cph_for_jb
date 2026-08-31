package io.github.bovmelo.cph.model

import java.nio.file.Path

enum class LanguageId(
    val settingValue: String,
    val displayName: String,
    val extension: String,
    val codeforcesLanguageId: Int?,
    vararg extensionAliases: String,
) {
    KOTLIN("kotlin", "Kotlin", "kt", 88),
    CPP("cpp", "C++", "cpp", 91, "cc", "cxx"),
    C("c", "C", "c", 43),
    CSHARP("csharp", "C#", "cs", 79),
    RUST("rust", "Rust", "rs", 98),
    GO("go", "Go", "go", 32),
    HASKELL("haskell", "Haskell", "hs", 12),
    PYTHON("python", "Python", "py", 70),
    RUBY("ruby", "Ruby", "rb", 67),
    JAVA("java", "Java", "java", 87),
    JAVASCRIPT("javascript", "JavaScript (Node.js)", "js", 55),
    CANGJIE("cangjie", "Cangjie", "cj", null),
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
