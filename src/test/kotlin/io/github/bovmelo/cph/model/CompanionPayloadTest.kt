package io.github.bovmelo.cph.model

import kotlinx.serialization.decodeFromString
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class CompanionPayloadTest {
    @Test
    fun `decodes Competitive Companion payload and ignores unknown fields`() {
        val payload = CphJson.format.decodeFromString<CompanionProblemPayload>(
            """
                {
                  "name": "A. Watermelon",
                  "group": "Codeforces - Codeforces Beta Round 4",
                  "url": "https://codeforces.com/problemset/problem/4/A",
                  "interactive": false,
                  "memoryLimit": 64,
                  "timeLimit": 1000,
                  "tests": [{"input": "8\n", "output": "YES\n"}],
                  "testType": "single",
                  "input": {"type": "stdin"},
                  "output": {"type": "stdout"},
                  "unknownFutureField": true
                }
            """.trimIndent(),
        )

        val problem = payload.toProblem(Path.of("/tmp/4A.kt"), LanguageId.KOTLIN)

        assertEquals("A. Watermelon", problem.name)
        assertEquals(1_000, problem.timeLimit)
        assertEquals("8\n", problem.tests.single().input)
        assertEquals("YES\n", problem.tests.single().output)
        assertFalse(problem.interactive)
    }
}
