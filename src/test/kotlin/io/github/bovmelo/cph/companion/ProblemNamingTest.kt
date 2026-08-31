package io.github.bovmelo.cph.companion

import io.github.bovmelo.cph.model.CompanionProblemPayload
import io.github.bovmelo.cph.model.LanguageId
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class ProblemNamingTest {
    @Test
    fun `uses compact Codeforces identifier`() {
        val payload = CompanionProblemPayload(
            name = "A. Watermelon",
            url = "https://codeforces.com/problemset/problem/4/A",
        )

        assertEquals("4A.kt", ProblemNaming.fileName(payload, LanguageId.KOTLIN))
    }

    @Test
    fun `sanitizes generic problem names`() {
        val payload = CompanionProblemPayload(
            name = "G. Castle Defense!",
            url = "https://example.test/problems/42",
        )

        assertEquals("G_Castle_Defense.py", ProblemNaming.fileName(payload, LanguageId.PYTHON))
    }

    @Test
    fun `creates a valid Java class filename for numeric platform ids`() {
        val payload = CompanionProblemPayload(
            name = "A. Watermelon",
            url = "https://codeforces.com/problemset/problem/4/A",
        )

        assertEquals("Problem4A.java", ProblemNaming.fileName(payload, LanguageId.JAVA))
    }

    @Test
    fun `uses Kattis problem slug`() {
        val payload = CompanionProblemPayload(
            name = "Hello World!",
            url = "https://open.kattis.com/problems/hello",
        )

        assertEquals("hello.kt", ProblemNaming.fileName(payload, LanguageId.KOTLIN))
    }

    @Test
    fun `preserves Unicode letters and avoids reserved filesystem names`() {
        assertEquals(
            "洛谷_入门题.kt",
            ProblemNaming.fileName(CompanionProblemPayload(name = "洛谷：入门题"), LanguageId.KOTLIN),
        )
        assertEquals(
            "Problem_CON.kt",
            ProblemNaming.fileName(CompanionProblemPayload(name = "CON"), LanguageId.KOTLIN),
        )
    }
}
