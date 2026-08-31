package io.github.bovmelo.cph.judge

import io.github.bovmelo.cph.model.LanguageId
import io.github.bovmelo.cph.settings.CphSettingsSnapshot
import io.github.bovmelo.cph.settings.LanguageToolchain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LanguageAdapterTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    private val settings = CphSettingsSnapshot(
        toolchains = CphSettingsSnapshot.defaultToolchains().toMutableMap().apply {
            this[LanguageId.KOTLIN] = LanguageToolchain("/tools/kotlinc", "/tools/java")
            this[LanguageId.CPP] = LanguageToolchain("/tools/g++")
            this[LanguageId.PYTHON] = LanguageToolchain(runCommand = "/tools/python3")
        },
    )
    private val buildDirectory = Path.of("/tmp/cph-build")

    @Test
    fun `builds Kotlin compile and run commands without a shell`() {
        val plan = KotlinLanguageAdapter.plan(Path.of("/work/A.kt"), buildDirectory, settings)

        assertEquals("/tools/kotlinc", plan.compile?.executable)
        assertTrue(plan.compile!!.arguments.contains("/work/A.kt"))
        assertEquals("/tools/java", plan.run.executable)
        assertEquals(listOf("-jar", "/tmp/cph-build/solution.jar"), plan.run.arguments)
    }

    @Test
    fun `Python skips compilation`() {
        val plan = PythonLanguageAdapter.plan(Path.of("/work/a.py"), buildDirectory, settings)

        assertNull(plan.compile)
        assertEquals("/tools/python3", plan.run.executable)
        assertEquals(listOf("/work/a.py"), plan.run.arguments)
    }

    @Test
    fun `every advertised extension resolves to an adapter`() {
        LanguageId.entries.forEach { language ->
            language.extensions.forEach { extension ->
                assertEquals(language, LanguageAdapters.resolve(Path.of("solution.$extension"))?.languageId)
            }
        }
    }

    @Test
    fun `Java uses the source filename as its main class`() {
        val plan = JavaLanguageAdapter.plan(Path.of("/work/Watermelon.java"), buildDirectory, settings)

        assertEquals(listOf("-cp", "/tmp/cph-build", "Watermelon"), plan.run.arguments)
    }

    @Test
    fun `dotnet C sharp plan prepares an isolated project`() {
        val source = temporaryDirectory.resolve("Program.cs")
        Files.writeString(source, "Console.WriteLine(1);")
        val plan = CSharpLanguageAdapter.plan(source, temporaryDirectory.resolve("build"), settings)

        assertEquals("dotnet", plan.compile?.executable)
        assertEquals(2, plan.preparations.size)
        assertIs<FilePreparation.Copy>(plan.preparations[0])
        assertIs<FilePreparation.Write>(plan.preparations[1])
        assertTrue(plan.run.arguments.single().endsWith("solution.dll"))
    }

    @Test
    fun `ONLINE_JUDGE is passed without a shell`() {
        val onlineSettings = settings.copy(onlineJudge = true)
        val plan = CppLanguageAdapter.plan(Path.of("/work/a.cpp"), buildDirectory, onlineSettings)

        assertTrue(plan.compile!!.arguments.contains("-DONLINE_JUDGE"))
        assertEquals("true", plan.run.environment["ONLINE_JUDGE"])
    }
}
