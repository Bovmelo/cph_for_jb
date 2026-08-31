package io.github.bovmelo.cph.submission

import io.github.bovmelo.cph.model.LanguageId
import io.github.bovmelo.cph.model.Problem
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SubmissionModelsTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `Codeforces response is compatible and consumed once`() {
        val queue = CodeforcesSubmitQueue()
        val response = queue.prepare(
            problem = problem("https://codeforces.com/contest/2048/problem/C"),
            sourceCode = "fun main() = Unit",
            languageId = LanguageId.KOTLIN,
        )

        assertEquals("2048C", response.problemName)
        assertEquals(88, response.languageId)
        assertFalse(queue.offer(response))
        assertEquals(response, queue.poll())
        assertNull(queue.poll())
    }

    @Test
    fun `Codeforces validation rejects unrelated hosts and unsupported languages`() {
        val queue = CodeforcesSubmitQueue()

        assertFailsWith<IllegalArgumentException> {
            queue.prepare(problem("https://evilcodeforces.com/contest/1/problem/A"), "code", LanguageId.KOTLIN)
        }
        assertFailsWith<IllegalArgumentException> {
            queue.prepare(problem("https://codeforces.com/contest/1/problem/A"), "code", LanguageId.CANGJIE)
        }
    }

    @Test
    fun `Kattis command uses the local authenticated client as an argument array`() {
        val source = temporaryDirectory.resolve("Main.kt")
        Files.writeString(source, "fun main() = Unit")
        val kattisDirectory = Files.createDirectories(temporaryDirectory.resolve("home/.kattis"))
        Files.writeString(kattisDirectory.resolve(".kattisrc"), "config")
        val client = kattisDirectory.resolve("submit.py")
        Files.writeString(client, "# client")

        val plan = KattisSubmission.plan(
            problem = problem("https://open.kattis.com/problems/hello"),
            sourcePath = source,
            userHome = temporaryDirectory.resolve("home"),
            pythonCommand = "/tools/python3",
        )

        assertEquals("/tools/python3", plan.command.executable)
        assertEquals(listOf(client.toString(), "-f", source.toString()), plan.command.arguments)
        assertEquals("Y\n", plan.stdin)
        assertTrue(isKattisUrl("https://open.kattis.com/problems/hello"))
    }

    private fun problem(url: String) = Problem(
        name = "Sample",
        url = url,
        sourcePath = temporaryDirectory.resolve("solution.kt").toString(),
    )
}
