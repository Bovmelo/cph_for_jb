package io.github.bovmelo.cph.companion

import io.github.bovmelo.cph.model.LanguageId
import io.github.bovmelo.cph.model.Problem
import io.github.bovmelo.cph.submission.CodeforcesSubmitQueue
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CompanionHttpServerTest {
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()

    @Test
    fun `accepts a real Competitive Companion POST on loopback`() {
        val received = AtomicReference<String>()
        val queue = CodeforcesSubmitQueue()
        CompanionHttpServer(0, queue, onProblem = { received.set(it.name) }).use { server ->
            val port = server.start()
            val response = request(
                port = port,
                method = "POST",
                body = """
                    {
                      "name": "A. Watermelon",
                      "url": "https://codeforces.com/problemset/problem/4/A",
                      "tests": [{"input":"8\n","output":"YES\n"}]
                    }
                """.trimIndent(),
            )

            assertEquals(200, response.statusCode())
            assertTrue(response.body().contains("\"empty\""))
            assertEquals("A. Watermelon", received.get())
            assertEquals("*", response.headers().firstValue("Access-Control-Allow-Origin").orElse(null))
        }
    }

    @Test
    fun `serves and clears cph-submit payloads without parsing them as problems`() {
        val received = AtomicReference<String>()
        val queue = CodeforcesSubmitQueue()
        val prepared = queue.prepare(
            Problem(
                name = "A",
                url = "https://codeforces.com/contest/1/problem/A",
                sourcePath = "/work/A.kt",
            ),
            "fun main() = Unit",
            LanguageId.KOTLIN,
        )
        queue.offer(prepared)

        CompanionHttpServer(0, queue, onProblem = { received.set(it.name) }).use { server ->
            val port = server.start()
            val first = request(
                port,
                "GET",
                headers = mapOf("cph-submit" to "true"),
                path = "/getSubmit",
                host = "localhost",
            )
            val second = request(
                port,
                "GET",
                headers = mapOf("cph-submit" to "true"),
                path = "/getSubmit",
                host = "localhost",
            )

            assertEquals(200, first.statusCode())
            assertTrue(first.body().contains("\"problemName\": \"1A\""))
            assertTrue(first.body().contains("\"languageId\": 88"))
            assertTrue(second.body().contains("\"empty\": true"))
            assertEquals(null, received.get())
        }
    }

    @Test
    fun `handles preflight invalid methods and bounded payloads`() {
        val errors = mutableListOf<Throwable>()
        val queue = CodeforcesSubmitQueue()
        CompanionHttpServer(0, queue, onProblem = {}, onError = errors::add).use { server ->
            val port = server.start()
            val preflight = request(port, "OPTIONS")
            val invalidMethod = request(port, "GET")
            val invalidJson = request(port, "POST", "not-json")
            val oversized = request(port, "POST", "x".repeat(2 * 1024 * 1024 + 1))

            assertEquals(204, preflight.statusCode())
            assertEquals(405, invalidMethod.statusCode())
            assertEquals(400, invalidJson.statusCode())
            assertEquals(413, oversized.statusCode())
            assertNotNull(errors.firstOrNull())
        }
    }

    private fun request(
        port: Int,
        method: String,
        body: String = "",
        headers: Map<String, String> = emptyMap(),
        path: String = "/",
        host: String = "127.0.0.1",
    ): HttpResponse<String> {
        val publisher = if (body.isEmpty()) {
            HttpRequest.BodyPublishers.noBody()
        } else {
            HttpRequest.BodyPublishers.ofString(body)
        }
        val builder = HttpRequest.newBuilder()
            .uri(URI("http://$host:$port$path"))
            .timeout(Duration.ofSeconds(5))
            .method(method, publisher)
            .header("Content-Type", "application/json")
        headers.forEach(builder::header)
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }
}
