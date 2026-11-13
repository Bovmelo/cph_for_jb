package io.github.bovmelo.cph.project

import io.github.bovmelo.cph.cmake.CMakeTargets
import io.github.bovmelo.cph.model.LanguageId
import javax.lang.model.SourceVersion

/** What "Add problem" creates: `<name>.<extension>`, also registered as a CMake target when [cmakeTarget]. */
data class NewProblemRequest(val name: String, val language: LanguageId, val cmakeTarget: Boolean)

/** The languages "Add problem" offers in one IDE, and whether a new problem is a CMake target there. */
data class NewProblemOptions(val languages: List<LanguageId>, val cmakeTarget: Boolean)

object NewProblemRules {
    private const val MAX_NAME_LENGTH = 64
    private val namePattern = Regex("[A-Za-z0-9_][A-Za-z0-9_-]*")
    private val windowsReservedPattern = Regex("(?i)CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9]")

    // Identifiers that javac accepts elsewhere but rejects as a type name.
    private val javaRestrictedTypeNames = setOf("var", "yield", "record", "sealed", "permits")

    /**
     * Each IDE creates problems in its own language. [productCode] is the IDE's build prefix, such as `CL`
     * for CLion or `IU` for IntelliJ IDEA.
     */
    fun optionsFor(productCode: String, defaultLanguage: LanguageId): NewProblemOptions =
        when (productCode.uppercase()) {
            // CLion's contest setup is one CMake executable per problem.
            "CL" -> NewProblemOptions(listOf(LanguageId.CPP), cmakeTarget = true)
            "IU", "IC", "IE", "AI" -> sources(LanguageId.KOTLIN, LanguageId.JAVA)
            "PY", "PC", "PE", "DS" -> sources(LanguageId.PYTHON)
            "GO" -> sources(LanguageId.GO)
            "RR" -> sources(LanguageId.RUST)
            "RM" -> sources(LanguageId.RUBY)
            "WS" -> sources(LanguageId.JAVASCRIPT)
            "RD" -> sources(LanguageId.CSHARP)
            // IDEs for a language CPH cannot run (PhpStorm, DataGrip, ...) fall back to the configured default.
            else -> sources(defaultLanguage)
        }

    /**
     * Why [request] cannot be created, or null when it can. Names compare case-insensitively because `B.cpp`
     * and `b.cpp` collide on the default macOS and Windows file systems.
     */
    fun nameError(request: NewProblemRequest, takenNames: Collection<String>): String? {
        val name = request.name
        return when {
            name.isEmpty() -> "Enter a problem name."
            name.length > MAX_NAME_LENGTH -> "Use at most $MAX_NAME_LENGTH characters."
            !namePattern.matches(name) -> "Use letters, digits, '_' and '-' only."
            windowsReservedPattern.matches(name) -> "$name is a reserved file name on Windows."
            else -> languageNameError(name, request.language)
                ?: "$name is a reserved CMake target name."
                    .takeIf { request.cmakeTarget && CMakeTargets.isReservedTargetName(name) }
                ?: "$name already exists in this project."
                    .takeIf { takenNames.any { it.equals(name, ignoreCase = true) } }
        }
    }

    /** Names the language's own toolchain cannot build or run; each rule was checked against the real tool. */
    private fun languageNameError(name: String, language: LanguageId): String? = when (language) {
        // The file name is the class the Java launcher is asked to run.
        LanguageId.JAVA -> when {
            !SourceVersion.isName(name) || name in javaRestrictedTypeNames -> "$name is not a valid Java class name."
            isJavaLangClass(name) -> "A class named $name would hide java.lang.$name."
            else -> null
        }

        LanguageId.GO -> when {
            name.startsWith("_") -> "Go ignores files whose names begin with '_'."
            name.endsWith("_test") -> "Go treats $name.go as a test file and will not build it."
            else -> null
        }

        // The script's directory comes first on the import path of every script in it.
        LanguageId.PYTHON -> "$name.py would shadow Python's standard $name module."
            .takeIf { name in PYTHON_STANDARD_MODULES }

        else -> null
    }

    private fun isJavaLangClass(name: String): Boolean =
        runCatching { Class.forName("java.lang.$name", false, null) }.isSuccess

    private fun sources(vararg languages: LanguageId) = NewProblemOptions(languages.toList(), cmakeTarget = false)
}
