package io.github.bovmelo.cph.project

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class RunTestCasesPolicyTest {
    @Test
    fun `first invocation creates a local problem without running it`() {
        assertEquals(
            RunTestCasesIntent.CREATE_LOCAL_PROBLEM,
            RunTestCasesPolicy.resolve(hasSavedProblem = false),
        )
    }

    @Test
    fun `later invocations run all saved testcases`() {
        assertEquals(
            RunTestCasesIntent.RUN_ALL,
            RunTestCasesPolicy.resolve(hasSavedProblem = true),
        )
    }
}
