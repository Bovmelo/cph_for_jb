package io.github.bovmelo.cph.project

import io.github.bovmelo.cph.model.LanguageId
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class NewProblemRulesTest {
    @Test
    fun `each IDE creates problems in its own language`() {
        fun languages(productCode: String) = NewProblemRules.optionsFor(productCode, LanguageId.CPP).languages

        assertEquals(NewProblemOptions(listOf(LanguageId.CPP), cmakeTarget = true), NewProblemRules.optionsFor("CL", LanguageId.KOTLIN))
        assertEquals(
            NewProblemOptions(listOf(LanguageId.KOTLIN, LanguageId.JAVA), cmakeTarget = false),
            NewProblemRules.optionsFor("IU", LanguageId.CPP),
        )
        assertEquals(listOf(LanguageId.KOTLIN, LanguageId.JAVA), languages("IC"))
        assertEquals(listOf(LanguageId.PYTHON), languages("PY"))
        assertEquals(listOf(LanguageId.PYTHON), languages("pc"))
        assertEquals(listOf(LanguageId.GO), languages("GO"))
        assertEquals(listOf(LanguageId.RUST), languages("RR"))
        assertEquals(listOf(LanguageId.RUBY), languages("RM"))
        assertEquals(listOf(LanguageId.JAVASCRIPT), languages("WS"))
        assertEquals(listOf(LanguageId.CSHARP), languages("RD"))
    }

    @Test
    fun `an IDE without a runnable language of its own uses the configured default`() {
        assertEquals(
            NewProblemOptions(listOf(LanguageId.RUST), cmakeTarget = false),
            NewProblemRules.optionsFor("PS", LanguageId.RUST),
        )
    }

    @Test
    fun `accepts file-safe names and explains why others are rejected`() {
        val taken = listOf("A", "main")
        fun error(name: String) = NewProblemRules.nameError(NewProblemRequest(name, LanguageId.PYTHON, false), taken)

        assertEquals(null, error("B1"))
        assertEquals(null, error("two_sum-2"))
        assertEquals(null, error("1A"))
        assertEquals("Enter a problem name.", error(""))
        assertEquals("a already exists in this project.", error("a"))
        assertEquals("Use letters, digits, '_' and '-' only.", error("B.py"))
        assertEquals("Use letters, digits, '_' and '-' only.", error("../B"))
        assertEquals("Use letters, digits, '_' and '-' only.", error("a b"))
        assertEquals("nul is a reserved file name on Windows.", error("nul"))
        assertEquals("Use at most 64 characters.", error("x".repeat(65)))
    }

    @Test
    fun `CMake reserved target names only matter for CMake problems`() {
        fun error(name: String, cmakeTarget: Boolean) =
            NewProblemRules.nameError(NewProblemRequest(name, LanguageId.CPP, cmakeTarget), emptyList())

        assertEquals("all is a reserved CMake target name.", error("all", cmakeTarget = true))
        assertEquals("edit_cache is a reserved CMake target name.", error("edit_cache", cmakeTarget = true))
        assertEquals("ZERO_CHECK is a reserved CMake target name.", error("ZERO_CHECK", cmakeTarget = true))
        assertEquals("codegen is a reserved CMake target name.", error("codegen", cmakeTarget = true))
        // CMake compares these case-sensitively and only reserves `test` when testing is enabled.
        assertEquals(null, error("Clean", cmakeTarget = true))
        assertEquals(null, error("test", cmakeTarget = true))
        assertEquals(null, error("all", cmakeTarget = false))
    }

    @Test
    fun `language rules reject names the toolchain cannot run`() {
        fun error(name: String, language: LanguageId) =
            NewProblemRules.nameError(NewProblemRequest(name, language, false), emptyList())

        assertEquals(null, error("Solution_2", LanguageId.JAVA))
        assertEquals("1A is not a valid Java class name.", error("1A", LanguageId.JAVA))
        assertEquals("A-1 is not a valid Java class name.", error("A-1", LanguageId.JAVA))
        assertEquals("class is not a valid Java class name.", error("class", LanguageId.JAVA))
        assertEquals("record is not a valid Java class name.", error("record", LanguageId.JAVA))
        assertEquals("A class named String would hide java.lang.String.", error("String", LanguageId.JAVA))
        assertEquals("A class named Math would hide java.lang.Math.", error("Math", LanguageId.JAVA))
        assertEquals(null, error("String", LanguageId.KOTLIN))
        assertEquals(null, error("1A", LanguageId.KOTLIN))
        assertEquals("Go treats a_test.go as a test file and will not build it.", error("a_test", LanguageId.GO))
        assertEquals("Go ignores files whose names begin with '_'.", error("_A", LanguageId.GO))
        assertEquals(null, error("A_", LanguageId.GO))
        assertEquals(null, error("a_test", LanguageId.PYTHON))
        assertEquals("random.py would shadow Python's standard random module.", error("random", LanguageId.PYTHON))
        assertEquals("heapq.py would shadow Python's standard heapq module.", error("heapq", LanguageId.PYTHON))
        // Python matches module names case-sensitively, and other languages have no such import path.
        assertEquals(null, error("Random", LanguageId.PYTHON))
        assertEquals(null, error("random", LanguageId.CPP))
    }
}
