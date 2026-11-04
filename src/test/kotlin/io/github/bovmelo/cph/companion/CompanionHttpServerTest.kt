package io.github.bovmelo.cph.companion

import org.junit.jupiter.api.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class CompanionHttpServerTest {
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()

    @Test
    fun `accepts a real Competitive Companion POST on loopback`() {
        val received = AtomicReference<String>()
        CompanionHttpServer(0, onProblem = { received.set(it.name) }).use { server ->
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
                        assertEquals("A. Watermelon", received.get())
            assertEquals("*", response.headers().firstValue("Access-Control-Allow-Origin").orElse(null))
        }
    }

    @Test
    fun `handles preflight invalid methods and bounded payloads`() {
        val errors = mutableListOf<Throwable>()
        CompanionHttpServer(0, onProblem = {}, onError = errors::add).use { server ->
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
