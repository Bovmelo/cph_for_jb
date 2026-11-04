package io.github.bovmelo.cph.settings

import io.github.bovmelo.cph.model.LanguageId

data class LanguageToolchain(
    val compileCommand: String = "",
    val runCommand: String = "",
    val compileArguments: String = "",
    val runArguments: String = "",
)

data class CphSettingsSnapshot(
    val port: Int = 27_121,
    val defaultLanguage: LanguageId = LanguageId.KOTLIN,
    val timeoutMs: Long = 3_000,
    val compileTimeoutMs: Long = 60_000,
    val failOnStderr: Boolean = true,
    val onlineJudge: Boolean = false,
    val optimizeO2: Boolean = true,
    val sanitizers: Boolean = false,
    val autoLoadActiveFile: Boolean = true,
    val problemDirectory: String = "",
    val templatePath: String = "",
    val toolchains: Map<LanguageId, LanguageToolchain> = defaultToolchains(),
) {
    fun toolchain(languageId: LanguageId): LanguageToolchain =
        toolchains[languageId] ?: defaultToolchains().getValue(languageId)

    companion object {
        fun defaultToolchains(): Map<LanguageId, LanguageToolchain> = linkedMapOf(
            LanguageId.KOTLIN to LanguageToolchain(compileCommand = "kotlinc", runCommand = "java"),
            LanguageId.CPP to LanguageToolchain(compileCommand = "g++"),
            LanguageId.C to LanguageToolchain(compileCommand = "gcc"),
            LanguageId.CSHARP to LanguageToolchain(compileCommand = "dotnet", runCommand = "dotnet"),
            LanguageId.RUST to LanguageToolchain(compileCommand = "rustc"),
            LanguageId.GO to LanguageToolchain(compileCommand = "go"),
            LanguageId.HASKELL to LanguageToolchain(compileCommand = "ghc"),
            LanguageId.PYTHON to LanguageToolchain(runCommand = "python3"),
            LanguageId.RUBY to LanguageToolchain(runCommand = "ruby"),
            LanguageId.JAVA to LanguageToolchain(compileCommand = "javac", runCommand = "java"),
            LanguageId.JAVASCRIPT to LanguageToolchain(runCommand = "node"),
            LanguageId.CANGJIE to LanguageToolchain(compileCommand = "cjc"),
        )
    }
}
