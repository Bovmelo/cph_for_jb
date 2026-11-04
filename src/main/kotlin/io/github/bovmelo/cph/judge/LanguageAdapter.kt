package io.github.bovmelo.cph.judge

import io.github.bovmelo.cph.model.LanguageId
import io.github.bovmelo.cph.settings.CphSettingsSnapshot
import io.github.bovmelo.cph.settings.LanguageToolchain
import java.nio.file.Path

data class CommandSpec(
    val executable: String,
    val arguments: List<String>,
    val workingDirectory: Path,
    val environment: Map<String, String> = mapOf("CPH" to "true", "DEBUG" to "true"),
)

sealed interface FilePreparation {
    val target: Path

    data class Copy(
        val source: Path,
        override val target: Path,
    ) : FilePreparation

    data class Write(
        val content: String,
        override val target: Path,
    ) : FilePreparation
}

data class ExecutionPlan(
    val compile: CommandSpec?,
    val run: CommandSpec,
    val preparations: List<FilePreparation> = emptyList(),
)

interface LanguageAdapter {
    val languageId: LanguageId

    fun plan(sourcePath: Path, buildDirectory: Path, settings: CphSettingsSnapshot): ExecutionPlan
}

object LanguageAdapters {
    private val adapters: Map<LanguageId, LanguageAdapter> = listOf(
        KotlinLanguageAdapter,
        CppLanguageAdapter,
        CLanguageAdapter,
        CSharpLanguageAdapter,
        RustLanguageAdapter,
        GoLanguageAdapter,
        HaskellLanguageAdapter,
        PythonLanguageAdapter,
        RubyLanguageAdapter,
        JavaLanguageAdapter,
        JavaScriptLanguageAdapter,
        CangjieLanguageAdapter,
    ).associateBy(LanguageAdapter::languageId)

    fun resolve(sourcePath: Path): LanguageAdapter? =
        LanguageId.fromPath(sourcePath)?.let(adapters::get)
}

object KotlinLanguageAdapter : LanguageAdapter {
    override val languageId = LanguageId.KOTLIN

    override fun plan(sourcePath: Path, buildDirectory: Path, settings: CphSettingsSnapshot): ExecutionPlan {
        val toolchain = settings.requiredToolchain(languageId)
        val jarPath = buildDirectory.resolve("solution.jar")
        return ExecutionPlan(
            compile = command(
                executable = toolchain.compileCommand,
                arguments = listOf(sourcePath.toString(), "-include-runtime", "-d", jarPath.toString()) +
                    CommandArguments.parse(toolchain.compileArguments),
                sourcePath = sourcePath,
                settings = settings,
            ),
            run = command(
                executable = toolchain.runCommand,
                arguments = CommandArguments.parse(toolchain.runArguments) + listOf("-jar", jarPath.toString()),
                sourcePath = sourcePath,
                settings = settings,
            ),
        )
    }
}

object CppLanguageAdapter : LanguageAdapter {
    override val languageId = LanguageId.CPP

    override fun plan(sourcePath: Path, buildDirectory: Path, settings: CphSettingsSnapshot): ExecutionPlan =
        nativePlan(
            languageId = languageId,
            sourcePath = sourcePath,
            buildDirectory = buildDirectory,
            settings = settings,
            defaultArguments = listOf("-std=c++20", "-pipe", "-DDEBUG", "-DCPH") +
                nativeOptimizationFlags(settings) + onlineJudgeDefine(settings),
        )
}

object CLanguageAdapter : LanguageAdapter {
    override val languageId = LanguageId.C

    override fun plan(sourcePath: Path, buildDirectory: Path, settings: CphSettingsSnapshot): ExecutionPlan =
        nativePlan(
            languageId = languageId,
            sourcePath = sourcePath,
            buildDirectory = buildDirectory,
            settings = settings,
            defaultArguments = listOf("-pipe", "-DDEBUG", "-DCPH") +
                nativeOptimizationFlags(settings) + onlineJudgeDefine(settings),
        )
}

object RustLanguageAdapter : LanguageAdapter {
    override val languageId = LanguageId.RUST

    override fun plan(sourcePath: Path, buildDirectory: Path, settings: CphSettingsSnapshot): ExecutionPlan =
        nativePlan(
            languageId = languageId,
            sourcePath = sourcePath,
            buildDirectory = buildDirectory,
            settings = settings,
            defaultArguments = listOf("-O"),
        )
}

object GoLanguageAdapter : LanguageAdapter {
    override val languageId = LanguageId.GO

    override fun plan(sourcePath: Path, buildDirectory: Path, settings: CphSettingsSnapshot): ExecutionPlan {
        val toolchain = settings.requiredToolchain(languageId)
        val executablePath = buildDirectory.resolve(platformExecutableName())
        return ExecutionPlan(
            compile = command(
                executable = toolchain.compileCommand,
                arguments = listOf("build", "-o", executablePath.toString(), sourcePath.toString()) +
                    CommandArguments.parse(toolchain.compileArguments),
                sourcePath = sourcePath,
                settings = settings,
            ),
            run = generatedExecutableCommand(executablePath, toolchain, sourcePath, settings),
        )
    }
}

object HaskellLanguageAdapter : LanguageAdapter {
    override val languageId = LanguageId.HASKELL

    override fun plan(sourcePath: Path, buildDirectory: Path, settings: CphSettingsSnapshot): ExecutionPlan {
        val toolchain = settings.requiredToolchain(languageId)
        val executablePath = buildDirectory.resolve(platformExecutableName())
        return ExecutionPlan(
            compile = command(
                executable = toolchain.compileCommand,
                arguments = listOf(
                    sourcePath.toString(),
                    "-O2",
                    "-o",
                    executablePath.toString(),
                    "-outputdir",
                    buildDirectory.toString(),
                ) + CommandArguments.parse(toolchain.compileArguments),
                sourcePath = sourcePath,
                settings = settings,
            ),
            run = generatedExecutableCommand(executablePath, toolchain, sourcePath, settings),
        )
    }
}

object PythonLanguageAdapter : LanguageAdapter {
    override val languageId = LanguageId.PYTHON

    override fun plan(sourcePath: Path, buildDirectory: Path, settings: CphSettingsSnapshot): ExecutionPlan =
        interpretedPlan(languageId, sourcePath, settings)
}

object RubyLanguageAdapter : LanguageAdapter {
    override val languageId = LanguageId.RUBY

    override fun plan(sourcePath: Path, buildDirectory: Path, settings: CphSettingsSnapshot): ExecutionPlan =
        interpretedPlan(languageId, sourcePath, settings)
}

object JavaScriptLanguageAdapter : LanguageAdapter {
    override val languageId = LanguageId.JAVASCRIPT

    override fun plan(sourcePath: Path, buildDirectory: Path, settings: CphSettingsSnapshot): ExecutionPlan =
        interpretedPlan(languageId, sourcePath, settings)
}

object JavaLanguageAdapter : LanguageAdapter {
    override val languageId = LanguageId.JAVA

    override fun plan(sourcePath: Path, buildDirectory: Path, settings: CphSettingsSnapshot): ExecutionPlan {
        val toolchain = settings.requiredToolchain(languageId)
        val mainClass = sourcePath.fileName.toString().substringBeforeLast('.')
        val onlineJudgeArgument = if (settings.onlineJudge) listOf("-DONLINE_JUDGE=true") else emptyList()
        return ExecutionPlan(
            compile = command(
                executable = toolchain.compileCommand,
                arguments = listOf(sourcePath.toString(), "-d", buildDirectory.toString()) +
                    CommandArguments.parse(toolchain.compileArguments),
                sourcePath = sourcePath,
                settings = settings,
            ),
            run = command(
                executable = toolchain.runCommand,
                arguments = onlineJudgeArgument + CommandArguments.parse(toolchain.runArguments) +
                    listOf("-cp", buildDirectory.toString(), mainClass),
                sourcePath = sourcePath,
                settings = settings,
            ),
        )
    }
}

object CSharpLanguageAdapter : LanguageAdapter {
    override val languageId = LanguageId.CSHARP

    override fun plan(sourcePath: Path, buildDirectory: Path, settings: CphSettingsSnapshot): ExecutionPlan {
        val toolchain = settings.requiredToolchain(languageId)
        return if (toolchain.compileCommand.executableName().contains("dotnet", ignoreCase = true)) {
            dotnetPlan(sourcePath, buildDirectory, settings, toolchain)
        } else {
            cscPlan(sourcePath, buildDirectory, settings, toolchain)
        }
    }

    private fun dotnetPlan(
        sourcePath: Path,
        buildDirectory: Path,
        settings: CphSettingsSnapshot,
        toolchain: LanguageToolchain,
    ): ExecutionPlan {
        val projectFile = buildDirectory.resolve("solution.csproj")
        val copiedSource = buildDirectory.resolve("Program.cs")
        val outputDirectory = buildDirectory.resolve("out")
        val assembly = outputDirectory.resolve("solution.dll")
        val defineConstants = if (settings.onlineJudge) {
            listOf("-p:DefineConstants=TRACE%3BONLINE_JUDGE")
        } else {
            emptyList()
        }
        return ExecutionPlan(
            preparations = listOf(
                FilePreparation.Copy(sourcePath, copiedSource),
                FilePreparation.Write(DOTNET_PROJECT, projectFile),
            ),
            compile = command(
                executable = toolchain.compileCommand,
                arguments = listOf(
                    "build",
                    projectFile.toString(),
                    "-c",
                    "Release",
                    "-o",
                    outputDirectory.toString(),
                    "--nologo",
                ) + defineConstants + CommandArguments.parse(toolchain.compileArguments),
                sourcePath = sourcePath,
                settings = settings,
            ),
            run = command(
                executable = toolchain.runCommand,
                arguments = CommandArguments.parse(toolchain.runArguments) + assembly.toString(),
                sourcePath = sourcePath,
                settings = settings,
            ),
        )
    }

    private fun cscPlan(
        sourcePath: Path,
        buildDirectory: Path,
        settings: CphSettingsSnapshot,
        toolchain: LanguageToolchain,
    ): ExecutionPlan {
        val executablePath = buildDirectory.resolve(if (isWindows()) "solution.exe" else "solution.exe")
        val definitions = if (settings.onlineJudge) listOf("-define:ONLINE_JUDGE") else emptyList()
        val runner = toolchain.runCommand.ifBlank { if (isWindows()) executablePath.toString() else "mono" }
        val runArguments = if (runner == executablePath.toString()) {
            CommandArguments.parse(toolchain.runArguments)
        } else {
            listOf(executablePath.toString()) + CommandArguments.parse(toolchain.runArguments)
        }
        return ExecutionPlan(
            compile = command(
                executable = toolchain.compileCommand,
                arguments = listOf(sourcePath.toString(), "-out:${executablePath}") + definitions +
                    CommandArguments.parse(toolchain.compileArguments),
                sourcePath = sourcePath,
                settings = settings,
            ),
            run = command(runner, runArguments, sourcePath, settings),
        )
    }

    private val DOTNET_PROJECT = """
        <Project Sdk="Microsoft.NET.Sdk">
          <PropertyGroup>
            <OutputType>Exe</OutputType>
            <TargetFramework>net8.0</TargetFramework>
            <AssemblyName>solution</AssemblyName>
            <ImplicitUsings>enable</ImplicitUsings>
            <Nullable>disable</Nullable>
          </PropertyGroup>
        </Project>
    """.trimIndent() + "\n"
}

object CangjieLanguageAdapter : LanguageAdapter {
    override val languageId = LanguageId.CANGJIE

    override fun plan(sourcePath: Path, buildDirectory: Path, settings: CphSettingsSnapshot): ExecutionPlan =
        nativePlan(
            languageId = languageId,
            sourcePath = sourcePath,
            buildDirectory = buildDirectory,
            settings = settings,
            defaultArguments = emptyList(),
        )
}

private fun nativePlan(
    languageId: LanguageId,
    sourcePath: Path,
    buildDirectory: Path,
    settings: CphSettingsSnapshot,
    defaultArguments: List<String>,
): ExecutionPlan {
    val toolchain = settings.requiredToolchain(languageId)
    val executablePath = buildDirectory.resolve(platformExecutableName())
    return ExecutionPlan(
        compile = command(
            executable = toolchain.compileCommand,
            arguments = listOf(sourcePath.toString()) + defaultArguments +
                CommandArguments.parse(toolchain.compileArguments) + listOf("-o", executablePath.toString()),
            sourcePath = sourcePath,
            settings = settings,
        ),
        run = generatedExecutableCommand(executablePath, toolchain, sourcePath, settings),
    )
}

private fun interpretedPlan(
    languageId: LanguageId,
    sourcePath: Path,
    settings: CphSettingsSnapshot,
): ExecutionPlan {
    val toolchain = settings.requiredToolchain(languageId)
    return ExecutionPlan(
        compile = null,
        run = command(
            executable = toolchain.runCommand,
            arguments = listOf(sourcePath.toString()) + CommandArguments.parse(toolchain.runArguments),
            sourcePath = sourcePath,
            settings = settings,
        ),
    )
}

private fun generatedExecutableCommand(
    executablePath: Path,
    toolchain: LanguageToolchain,
    sourcePath: Path,
    settings: CphSettingsSnapshot,
): CommandSpec {
    val configuredRunner = toolchain.runCommand
    return if (configuredRunner.isBlank()) {
        command(
            executable = executablePath.toString(),
            arguments = CommandArguments.parse(toolchain.runArguments),
            sourcePath = sourcePath,
            settings = settings,
        )
    } else {
        command(
            executable = configuredRunner,
            arguments = listOf(executablePath.toString()) + CommandArguments.parse(toolchain.runArguments),
            sourcePath = sourcePath,
            settings = settings,
        )
    }
}

private fun command(
    executable: String,
    arguments: List<String>,
    sourcePath: Path,
    settings: CphSettingsSnapshot,
): CommandSpec {
    require(executable.isNotBlank()) { "No command is configured for ${sourcePath.fileName}." }
    return CommandSpec(
        executable = executable,
        arguments = arguments,
        workingDirectory = sourcePath.parent,
        environment = buildMap {
            put("CPH", "true")
            put("DEBUG", "true")
            if (settings.onlineJudge) {
                put("ONLINE_JUDGE", "true")
            }
        },
    )
}

private fun CphSettingsSnapshot.requiredToolchain(languageId: LanguageId): LanguageToolchain = toolchain(languageId)

private fun onlineJudgeDefine(settings: CphSettingsSnapshot): List<String> =
    if (settings.onlineJudge) listOf("-DONLINE_JUDGE") else emptyList()

// Sanitizer flags apply to compile and link in one gcc/g++ invocation; -g gives readable reports.
private fun nativeOptimizationFlags(settings: CphSettingsSnapshot): List<String> =
    (if (settings.optimizeO2) listOf("-O2") else emptyList()) +
        (if (settings.sanitizers) listOf("-g", "-fsanitize=address,undefined") else emptyList())

private fun platformExecutableName(): String = if (isWindows()) "solution.exe" else "solution"

private fun isWindows(): Boolean = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)

private fun String.executableName(): String = substringAfterLast('/').substringAfterLast('\\')
