package io.github.bovmelo.cph.cmake

import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CMakeTargetsTest {
    @Test
    fun `reads declared targets and ignores comments and variable names`() {
        val text = """
            cmake_minimum_required(VERSION 3.20)
            project(contest)
            add_executable(A A.cpp)
            ADD_EXECUTABLE( "B" src/B.cpp )
            # add_executable(C C.cpp)
            add_executable(${'$'}{NAME} x.cpp) # add_executable(D D.cpp)
            add_library(E E.cpp)
            add_custom_target(F)
            target_link_libraries(A G)
            add_executable(${'$'}{PROJECT_NAME} main.cpp)
            add_executable(${'$'}{CMAKE_PROJECT_NAME}_tests t.cpp)
        """.trimIndent()

        assertEquals(setOf("A", "B", "E", "F", "contest", "contest_tests"), CMakeTargets.declaredTargets(text))
    }

    @Test
    fun `knows the target names every CMake generator reserves`() {
        val reserved = listOf(
            "all", "ALL_BUILD", "help", "install", "INSTALL", "preinstall", "clean", "edit_cache", "rebuild_cache",
            "ZERO_CHECK", "codegen",
        )
        assertTrue(reserved.all(CMakeTargets::isReservedTargetName))
        assertFalse(listOf("test", "package", "A", "allx", "Clean", "ALL").any(CMakeTargets::isReservedTargetName))
    }

    @Test
    fun `skips bracket comments and counts the targets CMake defines for testing and packaging`() {
        val commented = """
            add_executable(A A.cpp)
            #[[ add_executable(B B.cpp)
            add_executable(C C.cpp) ]]
            #[==[
            add_executable(D D.cpp)
            ]==]
            add_executable(E E.cpp) # enable_testing()
        """.trimIndent()
        assertEquals(setOf("A", "E"), CMakeTargets.declaredTargets(commented))

        assertEquals(
            setOf("A", "test", "RUN_TESTS"),
            CMakeTargets.declaredTargets("enable_testing()\nadd_executable(A A.cpp)"),
        )
        assertEquals(setOf("test", "RUN_TESTS"), CMakeTargets.declaredTargets("include(CTest)"))
        assertEquals(setOf("package", "package_source", "PACKAGE"), CMakeTargets.declaredTargets("include(CPack)"))
        assertEquals(emptySet(), CMakeTargets.declaredTargets("include(CTestUseLaunchers)"))
    }

    @Test
    fun `quoted source paths keep dollar signs, semicolons and quotes literal`() {
        assertEquals(
            "add_executable(B \"my\\${'$'}{missing}/B.cpp\")\n",
            CMakeTargets.appendExecutable("", "B", "my${'$'}{missing}/B.cpp"),
        )
        assertEquals(
            "add_executable(B \"a\\;b/q\\\"t/B.cpp\")\n",
            CMakeTargets.appendExecutable("", "B", "a;b/q\"t/B.cpp"),
        )
    }

    @Test
    fun `appends a target on its own line`() {
        assertEquals(
            "project(x)\nadd_executable(B B.cpp)\n",
            CMakeTargets.appendExecutable("project(x)", "B", "B.cpp"),
        )
        assertEquals(
            "project(x)\nadd_executable(B \"my dir/B.cpp\")\n",
            CMakeTargets.appendExecutable("project(x)\n", "B", "my dir/B.cpp"),
        )
        assertEquals("add_executable(A A.cpp)\n", CMakeTargets.appendExecutable("", "A", "A.cpp"))
    }

    @Test
    fun `references sources relative to the CMake directory when possible`() {
        val root = Path.of("/work/contest")

        assertEquals("problems/B.cpp", CMakeTargets.sourceReference(root, root.resolve("problems/B.cpp")))
        assertEquals("B.cpp", CMakeTargets.sourceReference(root, root.resolve("B.cpp")))
        assertEquals("/elsewhere/B.cpp", CMakeTargets.sourceReference(root, Path.of("/elsewhere/B.cpp")))
    }
}
