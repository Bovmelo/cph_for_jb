package io.github.bovmelo.cph.judge

import io.github.bovmelo.cph.companion.TemplateEngine
import io.github.bovmelo.cph.model.CompanionProblemPayload
import io.github.bovmelo.cph.model.LanguageId
import io.github.bovmelo.cph.model.Problem
import io.github.bovmelo.cph.model.RunPhase
import io.github.bovmelo.cph.model.TestCase
import io.github.bovmelo.cph.model.TestRunResult
import io.github.bovmelo.cph.model.Verdict
import io.github.bovmelo.cph.settings.CphSettingsSnapshot
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals

class LanguageExecutionIntegrationTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @ParameterizedTest(name = "{0} toolchain compiles and runs a solution when installed")
    @EnumSource(LanguageId::class)
    fun `installed language toolchains compile and run`(language: LanguageId) {
        val settings = installedSettings(language)

        val sourcePath = temporaryDirectory.resolve(sourceFileName(language))
        Files.writeString(sourcePath, sourceCode(language))
        val problem = Problem(
            name = "Installed ${language.displayName} toolchain",
            url = sourcePath.toUri().toString(),
            tests = listOf(TestCase(output = "ok\n", id = 1)),
            sourcePath = sourcePath.toString(),
            languageId = language.settingValue,
        )
        val callbacks = IntegrationCallbacks()
        val engine = JudgeEngine()

        engine.run(engine.prepareRun(), problem, null, settings, callbacks)

        assertEquals(Verdict.ACCEPTED, callbacks.result?.verdict, callbacks.status)
    }

    @ParameterizedTest(name = "{0} built-in template compiles when its toolchain is installed")
    @EnumSource(LanguageId::class)
    fun `installed toolchains compile built in templates`(language: LanguageId) {
        val settings = installedSettings(language)
        val sourcePath = temporaryDirectory.resolve(sourceFileName(language))
        val className = sourcePath.fileName.toString().substringBeforeLast('.')
        val template = TemplateEngine().expand(
            TemplateEngine().builtIn(language),
            CompanionProblemPayload(name = "Template verification"),
            className,
        )
        Files.writeString(sourcePath, template.content)
        val problem = Problem(
            name = "${language.displayName} template",
            url = sourcePath.toUri().toString(),
            tests = listOf(TestCase(output = "", id = 2)),
            sourcePath = sourcePath.toString(),
            languageId = language.settingValue,
        )
        val callbacks = IntegrationCallbacks()
        val engine = JudgeEngine()

        engine.run(engine.prepareRun(), problem, null, settings, callbacks)

        assertEquals(Verdict.ACCEPTED, callbacks.result?.verdict, callbacks.status)
    }

    private fun installedSettings(language: LanguageId): CphSettingsSnapshot {
        val baseSettings = CphSettingsSnapshot(
            timeoutMs = 10_000,
            compileTimeoutMs = 120_000,
            failOnStderr = false,
        )
        val defaults = baseSettings.toolchain(language)
        val environmentPrefix = "CPH_TEST_${language.settingValue.uppercase()}"
        val effectiveToolchain = defaults.copy(
            compileCommand = System.getenv("${environmentPrefix}_COMPILER")?.takeIf(String::isNotBlank)
                ?: defaults.compileCommand,
            runCommand = System.getenv("${environmentPrefix}_RUNNER")?.takeIf(String::isNotBlank)
                ?: defaults.runCommand,
        )
        val settings = baseSettings.copy(
            toolchains = baseSettings.toolchains + (language to effectiveToolchain),
        )
        val requiredCommand = effectiveToolchain.compileCommand.ifBlank { effectiveToolchain.runCommand }
        assumeTrue(commandExists(requiredCommand), "$requiredCommand is not installed")
        if (effectiveToolchain.runCommand.isNotBlank()) {
            assumeTrue(commandExists(effectiveToolchain.runCommand), "${effectiveToolchain.runCommand} is not installed")
        }
        return settings
    }

    private fun sourceFileName(language: LanguageId): String = when (language) {
        LanguageId.JAVA -> "Solution.java"
        else -> "solution.${language.extension}"
    }

    private fun sourceCode(language: LanguageId): String = when (language) {
        LanguageId.KOTLIN -> "fun main() { println(\"ok\") }\n"
        LanguageId.CPP -> "#include <iostream>\nint main() { std::cout << \"ok\\n\"; }\n"
        LanguageId.C -> "#include <stdio.h>\nint main(void) { puts(\"ok\"); return 0; }\n"
        LanguageId.CSHARP -> "using System; internal static class Program { static void Main() { Console.WriteLine(\"ok\"); } }\n"
        LanguageId.RUST -> "fn main() { println!(\"ok\"); }\n"
        LanguageId.GO -> "package main\nimport \"fmt\"\nfunc main() { fmt.Println(\"ok\") }\n"
        LanguageId.HASKELL -> "main :: IO ()\nmain = putStrLn \"ok\"\n"
        LanguageId.PYTHON -> "print(\"ok\")\n"
        LanguageId.RUBY -> "puts 'ok'\n"
        LanguageId.JAVA -> "public class Solution { public static void main(String[] args) { System.out.println(\"ok\"); } }\n"
        LanguageId.JAVASCRIPT -> "console.log('ok');\n"
        LanguageId.CANGJIE -> "main() { println(\"ok\") }\n"
    }

    private fun commandExists(command: String): Boolean {
        if (command.isBlank()) {
            return false
        }
        if (command.contains('/') || command.contains('\\')) {
            return Files.isExecutable(Path.of(command))
        }
        val extensions = if (isWindows()) listOf("", ".exe", ".cmd", ".bat") else listOf("")
        return System.getenv("PATH").orEmpty()
            .split(File.pathSeparatorChar)
            .filter(String::isNotBlank)
            .any { directory -> extensions.any { extension -> Files.isExecutable(Path.of(directory, command + extension)) } }
    }

    private fun isWindows(): Boolean = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)

    private class IntegrationCallbacks : JudgeCallbacks {
        var result: TestRunResult? = null
        var status: String = ""

        override fun onPhase(phase: RunPhase, message: String) {
            status = message
        }

        override fun onCompileOutput(output: String) {
            if (output.isNotBlank()) {
                status = output
            }
        }

        override fun onResult(result: TestRunResult) {
            if (result.verdict != Verdict.RUNNING) {
                this.result = result
            }
        }
    }
}
