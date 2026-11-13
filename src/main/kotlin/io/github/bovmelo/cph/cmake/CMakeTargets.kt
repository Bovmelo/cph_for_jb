package io.github.bovmelo.cph.cmake

import java.nio.file.Path

/** Text-level helpers for contest-style CMake projects where every problem is its own executable. */
object CMakeTargets {
    // CMake rejects exactly these target names (case-sensitively), which breaks loading the whole project.
    // `codegen` is only reserved from policy version 3.31 on, which new projects use.
    private val cmakeReservedNames = setOf(
        "all", "ALL_BUILD", "help", "install", "INSTALL", "preinstall", "clean", "edit_cache", "rebuild_cache",
        "ZERO_CHECK", "codegen",
    )
    private val bracketCommentPattern = Regex("""#\[(=*)\[[\s\S]*?\]\1\]""")
    private val testingPattern = Regex("""(?i)\benable_testing\s*\(|\binclude\s*\(\s*CTest\b""")
    private val packagingPattern = Regex("""(?i)\binclude\s*\(\s*CPack\b""")
    private val projectPattern = Regex("""(?i)\bproject\s*\(\s*("[^"]*"|[^\s()]+)""")
    private val projectNameReference = Regex("""\$\{(?:CMAKE_)?PROJECT_NAME}""")
    private val targetPattern = Regex(
        """(?i)\badd_(?:executable|library|custom_target)\s*\(\s*("[^"]*"|[^\s()]+)""",
    )

    /**
     * Names declared by `add_executable`, `add_library` and `add_custom_target`, which share one namespace,
     * plus the targets CMake defines itself once testing or packaging is enabled. Comments are ignored, and
     * so are targets built from variables other than the project name, the one variable target name that is
     * both common and knowable from the text.
     */
    fun declaredTargets(cmakeText: String): Set<String> {
        val uncommented = cmakeText.replace(bracketCommentPattern, "")
            .lineSequence()
            .joinToString("\n") { it.substringBefore('#') }
        val projectName = projectPattern.find(uncommented)?.groupValues?.get(1)?.removeSurrounding("\"")
        val builtIn = buildSet {
            if (testingPattern.containsMatchIn(uncommented)) addAll(listOf("test", "RUN_TESTS"))
            if (packagingPattern.containsMatchIn(uncommented)) addAll(listOf("package", "package_source", "PACKAGE"))
        }
        return builtIn + targetPattern.findAll(uncommented)
            .map { it.groupValues[1].removeSurrounding("\"") }
            .map { target ->
                if (projectName == null) target else target.replace(projectNameReference, Regex.escapeReplacement(projectName))
            }
            .filterNot { it.contains('$') }
            .toSet()
    }

    fun isReservedTargetName(name: String): Boolean = name in cmakeReservedNames

    fun appendExecutable(cmakeText: String, target: String, sourceReference: String): String {
        val separator = if (cmakeText.isEmpty() || cmakeText.endsWith("\n")) "" else "\n"
        return "$cmakeText${separator}add_executable($target ${quoteIfNeeded(sourceReference)})\n"
    }

    /** A path as CMake wants it: forward slashes, relative to [cmakeDirectory] when the source lives below it. */
    fun sourceReference(cmakeDirectory: Path, sourcePath: Path): String {
        val base = cmakeDirectory.toAbsolutePath().normalize()
        val source = sourcePath.toAbsolutePath().normalize()
        val reference = if (source.startsWith(base)) base.relativize(source) else source
        return reference.toString().replace('\\', '/')
    }

    private fun quoteIfNeeded(value: String): String {
        if (value.none { it.isWhitespace() || it in "()#\"\\$;" }) {
            return value
        }
        // Inside quotes CMake still expands `$` references and splits lists at `;`.
        val escaped = value.replace(Regex("""[\\"$;]""")) { "\\" + it.value }
        return "\"$escaped\""
    }
}
