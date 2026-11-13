package io.github.bovmelo.cph.project

import com.intellij.openapi.components.service
import com.intellij.testFramework.LoggedErrorProcessor
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import io.github.bovmelo.cph.model.LanguageId
import io.github.bovmelo.cph.settings.CphSettings
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@TestApplication
class AddContestProblemPlatformTest {
    private val project = projectFixture()

    // The sandbox IDE lacks the bundled TextMate grammars that CMake files trigger; that is not under test.
    private fun ignoringTextMateBundleErrors(body: () -> Unit) {
        LoggedErrorProcessor.executeWith<RuntimeException>(object : LoggedErrorProcessor() {
            override fun processError(
                category: String,
                message: String,
                details: Array<String>,
                t: Throwable?,
            ): Set<Action> =
                if ("textmate" in message.lowercase()) Action.NONE else Action.ALL
        }, body)
    }

    @Test
    fun `adds lettered sources and registers each one in CMakeLists`() = ignoringTextMateBundleErrors {
        val root = Path.of(project.get().basePath!!)
        val cmake = root.resolve("CMakeLists.txt")
        Files.writeString(cmake, "cmake_minimum_required(VERSION 3.20)\nproject(contest)\nadd_executable(A A.cpp)\n")
        Files.writeString(root.resolve("A.cpp"), "int main() {}\n")
        val service = project.get().service<CphProjectService>()

        listOf("B", "C").forEach { name ->
            service.addProblem(NewProblemRequest(name, LanguageId.CPP, cmakeTarget = true))
            awaitProblem(service, root.resolve("$name.cpp"))
            assertEquals("", Files.readString(root.resolve("$name.cpp")))
        }

        assertEquals(
            "cmake_minimum_required(VERSION 3.20)\nproject(contest)\nadd_executable(A A.cpp)\n" +
                "add_executable(B B.cpp)\nadd_executable(C C.cpp)\n",
            Files.readString(cmake),
        )
    }

    @Test
    fun `without CMakeLists a CMake problem is not created`() = ignoringTextMateBundleErrors {
        val root = Path.of(project.get().basePath!!)
        val cmake = root.resolve("CMakeLists.txt")
        Files.deleteIfExists(cmake)
        val service = project.get().service<CphProjectService>()

        service.addProblem(NewProblemRequest("Z", LanguageId.CPP, cmakeTarget = true))
        val deadline = System.nanoTime() + 10_000_000_000L
        while (!service.currentState().statusMessage.startsWith("Could not add a problem") && System.nanoTime() < deadline) {
            Thread.sleep(50)
        }

        assertTrue(service.currentState().statusMessage.contains("CMakeLists.txt"))
        assertFalse(Files.exists(root.resolve("Z.cpp")))
    }

    @Test
    fun `source-only problems need no CMakeLists and start empty`() = ignoringTextMateBundleErrors {
        val root = Path.of(project.get().basePath!!)
        Files.deleteIfExists(root.resolve("CMakeLists.txt"))
        val service = project.get().service<CphProjectService>()

        mapOf(
            LanguageId.PYTHON to "P1.py",
            LanguageId.KOTLIN to "K1.kt",
            LanguageId.JAVA to "J1.java",
            LanguageId.GO to "G1.go",
        ).forEach { (language, fileName) ->
            assertEquals(null, service.newProblemError(cmakeTarget = false))
            service.addProblem(NewProblemRequest(fileName.substringBefore('.'), language, cmakeTarget = false))
            awaitProblem(service, root.resolve(fileName))
            assertEquals("Created $fileName.", service.currentState().statusMessage)
        }

        listOf("P1.py", "K1.kt", "J1.java", "G1.go").forEach { fileName ->
            assertEquals("", Files.readString(root.resolve(fileName)), fileName)
        }
        assertFalse(Files.exists(root.resolve("CMakeLists.txt")))
    }

    @Test
    fun `only a template configured for that language is written`() = ignoringTextMateBundleErrors {
        val root = Path.of(project.get().basePath!!)
        val template = Files.writeString(root.resolve("template.txt"), "# \$name\$\n")
        val service = project.get().service<CphProjectService>()
        val settings = service<CphSettings>()
        val original = settings.snapshot()
        settings.update(original.copy(defaultLanguage = LanguageId.PYTHON, templatePath = template.toString()))
        try {
            service.addProblem(NewProblemRequest("P2", LanguageId.PYTHON, cmakeTarget = false))
            awaitProblem(service, root.resolve("P2.py"))
            service.addProblem(NewProblemRequest("K2", LanguageId.KOTLIN, cmakeTarget = false))
            awaitProblem(service, root.resolve("K2.kt"))
        } finally {
            settings.update(original)
        }

        assertEquals("# P2\n", Files.readString(root.resolve("P2.py")))
        assertEquals("", Files.readString(root.resolve("K2.kt")))
    }

    @Test
    fun `a failed metadata save leaves no source behind and the name can be retried`() = ignoringTextMateBundleErrors {
        val root = Path.of(project.get().basePath!!)
        // A file where the metadata directory belongs makes the save fail after the source was written.
        val blocker = Files.writeString(root.resolve(".cph"), "")
        val service = project.get().service<CphProjectService>()

        service.addProblem(NewProblemRequest("M1", LanguageId.PYTHON, cmakeTarget = false))
        awaitFailure(service)
        assertFalse(Files.exists(root.resolve("M1.py")))

        Files.delete(blocker)
        service.addProblem(NewProblemRequest("M1", LanguageId.PYTHON, cmakeTarget = false))
        awaitProblem(service, root.resolve("M1.py"))
    }

    @Test
    fun `a failed metadata save leaves CMakeLists untouched`() = ignoringTextMateBundleErrors {
        val root = Path.of(project.get().basePath!!)
        val cmake = root.resolve("CMakeLists.txt")
        Files.writeString(cmake, "add_executable(A A.cpp)\n")
        Files.writeString(root.resolve(".cph"), "")
        val service = project.get().service<CphProjectService>()

        service.addProblem(NewProblemRequest("M2", LanguageId.CPP, cmakeTarget = true))
        awaitFailure(service)

        assertFalse(Files.exists(root.resolve("M2.cpp")))
        assertEquals("add_executable(A A.cpp)\n", Files.readString(cmake))
    }

    @Test
    fun `an invalid source-only name creates nothing`() = ignoringTextMateBundleErrors {
        val root = Path.of(project.get().basePath!!)
        val service = project.get().service<CphProjectService>()

        service.addProblem(NewProblemRequest("class", LanguageId.JAVA, cmakeTarget = false))
        val deadline = System.nanoTime() + 10_000_000_000L
        while (!service.currentState().statusMessage.startsWith("Could not add a problem") && System.nanoTime() < deadline) {
            Thread.sleep(50)
        }

        assertTrue(service.currentState().statusMessage.contains("Java class name"), service.currentState().statusMessage)
        assertFalse(Files.exists(root.resolve("class.java")))
    }

    @Test
    fun `taken names are the CMake targets and sources of the same language`() = ignoringTextMateBundleErrors {
        val root = Path.of(project.get().basePath!!)
        Files.writeString(root.resolve("CMakeLists.txt"), "add_executable(A A.cpp)\nadd_library(util util.cpp)\n")
        Files.writeString(root.resolve("D.cpp"), "")
        Files.writeString(root.resolve("E.in"), "")
        Files.writeString(root.resolve("F.py"), "")
        val service = project.get().service<CphProjectService>()

        assertEquals(null, service.newProblemError(cmakeTarget = true))
        val cmakeTaken = service.takenProblemNames(LanguageId.CPP, cmakeTarget = true)
        assertTrue(cmakeTaken.containsAll(setOf("A", "util", "D")), cmakeTaken.toString())
        assertTrue("E" !in cmakeTaken && "F" !in cmakeTaken && "CMakeLists" !in cmakeTaken, cmakeTaken.toString())
        // Without a CMake target only a source of the same language is in the way.
        assertEquals(setOf("F"), service.takenProblemNames(LanguageId.PYTHON, cmakeTarget = false))
        assertEquals(setOf("D"), service.takenProblemNames(LanguageId.CPP, cmakeTarget = false))
    }

    @Test
    fun `a project without CMakeLists reports why CMake problems cannot be added`() = ignoringTextMateBundleErrors {
        val root = Path.of(project.get().basePath!!)
        Files.deleteIfExists(root.resolve("CMakeLists.txt"))
        val service = project.get().service<CphProjectService>()

        assertTrue(service.newProblemError(cmakeTarget = true)!!.contains("CMakeLists.txt"))
        assertEquals(null, service.newProblemError(cmakeTarget = false))
    }

    private fun awaitFailure(service: CphProjectService) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (!service.currentState().statusMessage.startsWith("Could not add a problem") && System.nanoTime() < deadline) {
            Thread.sleep(50)
        }
        assertTrue(service.currentState().statusMessage.startsWith("Could not add a problem"), service.currentState().statusMessage)
    }

    private fun awaitProblem(service: CphProjectService, source: Path) {
        val expected = source.toAbsolutePath().normalize().toString()
        val deadline = System.nanoTime() + 20_000_000_000L
        while (service.currentState().problem?.sourcePath != expected && System.nanoTime() < deadline) {
            Thread.sleep(50)
        }
        assertEquals(expected, service.currentState().problem?.sourcePath, service.currentState().statusMessage)
    }
}
