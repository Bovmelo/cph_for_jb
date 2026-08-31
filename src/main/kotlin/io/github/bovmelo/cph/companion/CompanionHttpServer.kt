package io.github.bovmelo.cph.companion

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.github.bovmelo.cph.model.CompanionProblemPayload
import io.github.bovmelo.cph.model.CphJson
import io.github.bovmelo.cph.submission.CodeforcesSubmitQueue
import io.github.bovmelo.cph.submission.CodeforcesSubmitResponse
import io.github.bovmelo.cph.submission.EmptySubmitResponse
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class CompanionHttpServer(
    private val requestedPort: Int,
    private val submitQueue: CodeforcesSubmitQueue,
    private val onProblem: (CompanionProblemPayload) -> Unit,
    private val onSubmissionDelivered: (CodeforcesSubmitResponse) -> Unit = {},
    private val onError: (Throwable) -> Unit = {},
) : AutoCloseable {
    private val lock = Any()
    private var server: HttpServer? = null
    private var executor: ExecutorService? = null

    fun start(): Int = synchronized(lock) {
        server?.address?.port?.let { return@synchronized it }
        val newServer = HttpServer.create(InetSocketAddress(LOOPBACK_ADDRESS, requestedPort), 0)
        val newExecutor = Executors.newFixedThreadPool(4) { runnable ->
            Thread(runnable, "cph-companion-http").apply { isDaemon = true }
        }
        try {
            newServer.executor = newExecutor
            newServer.createContext("/", ::handle)
            newServer.start()
            server = newServer
            executor = newExecutor
            newServer.address.port
        } catch (error: Throwable) {
            newServer.stop(0)
            newExecutor.shutdownNow()
            throw error
        }
    }

    fun port(): Int? = synchronized(lock) { server?.address?.port }

    override fun close() {
        synchronized(lock) {
            server?.stop(0)
            executor?.shutdownNow()
            server = null
            executor = null
        }
    }

    private fun handle(exchange: HttpExchange) {
        try {
            addResponseHeaders(exchange)
            if (exchange.requestMethod.equals("OPTIONS", ignoreCase = true)) {
                send(exchange, 204, "")
                return
            }

            val isSubmitPoll = exchange.requestHeaders.getFirst(SUBMIT_HEADER).equals("true", ignoreCase = true)
            if (isSubmitPoll) {
                if (exchange.requestURI.path !in SUBMIT_PATHS) {
                    sendError(exchange, 404, "Not found")
                    return
                }
                if (
                    !exchange.requestMethod.equals("POST", ignoreCase = true) &&
                    !exchange.requestMethod.equals("GET", ignoreCase = true)
                ) {
                    sendError(exchange, 405, "Method not allowed")
                    return
                }
                val submission = submitQueue.poll()
                val response = submission?.let(CphJson.format::encodeToString)
                    ?: CphJson.format.encodeToString(EmptySubmitResponse())
                send(exchange, 200, response)
                submission?.let(onSubmissionDelivered)
                return
            }

            if (exchange.requestURI.path != "/") {
                sendError(exchange, 404, "Not found")
                return
            }

            if (!exchange.requestMethod.equals("POST", ignoreCase = true)) {
                sendError(exchange, 405, "Method not allowed")
                return
            }

            val payload = CphJson.format.decodeFromString<CompanionProblemPayload>(readLimited(exchange))
            validate(payload)
            onProblem(payload)
            send(exchange, 200, CphJson.format.encodeToString(EmptySubmitResponse()))
        } catch (_: PayloadTooLargeException) {
            sendError(exchange, 413, "Payload too large")
        } catch (error: CompanionRequestException) {
            sendError(exchange, error.statusCode, error.clientMessage)
        } catch (error: Exception) {
            onError(error)
            sendError(exchange, 400, "Invalid Competitive Companion payload")
        } finally {
            exchange.close()
        }
    }

    private fun validate(payload: CompanionProblemPayload) {
        require(payload.name.isNotBlank()) { "Problem name is required." }
        require(payload.name.length <= MAX_NAME_LENGTH) { "Problem name is too long." }
        require(payload.group.length <= MAX_GROUP_LENGTH) { "Problem group is too long." }
        require(payload.url.length <= MAX_URL_LENGTH) { "Problem URL is too long." }
        require(payload.timeLimit == null || payload.timeLimit in 1..MAX_TIME_LIMIT_MS) {
            "Problem time limit is invalid."
        }
        require(payload.memoryLimit == null || payload.memoryLimit in 1..MAX_MEMORY_LIMIT_MB) {
            "Problem memory limit is invalid."
        }
        require(payload.tests.size <= MAX_TEST_CASES) { "Too many testcases." }
        payload.batch?.let { batch ->
            require(batch.id.length <= MAX_BATCH_ID_LENGTH) { "Batch id is too long." }
            require(batch.size in 1..MAX_TEST_CASES) { "Batch size is invalid." }
        }
        payload.tests.forEach { testCase ->
            require(testCase.input.length <= MAX_TEST_CASE_CHARACTERS) { "A testcase input is too large." }
            require(testCase.output.orEmpty().length <= MAX_TEST_CASE_CHARACTERS) { "A testcase output is too large." }
        }
    }

    private fun readLimited(exchange: HttpExchange): String {
        val declaredLength = exchange.requestHeaders.getFirst("Content-Length")?.toLongOrNull()
        if (declaredLength != null && declaredLength > MAX_REQUEST_BYTES) {
            throw PayloadTooLargeException()
        }

        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        exchange.requestBody.use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read < 0) {
                    break
                }
                total += read
                if (total > MAX_REQUEST_BYTES) {
                    throw PayloadTooLargeException()
                }
                output.write(buffer, 0, read)
            }
        }
        return output.toString(StandardCharsets.UTF_8)
    }

    private fun addResponseHeaders(exchange: HttpExchange) {
        exchange.responseHeaders.add("Access-Control-Allow-Origin", "*")
        exchange.responseHeaders.add("Access-Control-Allow-Headers", "Content-Type, $SUBMIT_HEADER")
        exchange.responseHeaders.add("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        exchange.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
        exchange.responseHeaders.add("Cache-Control", "no-store")
    }

    private fun sendError(exchange: HttpExchange, status: Int, message: String) {
        val body = buildJsonObject { put("error", JsonPrimitive(message)) }.toString()
        send(exchange, status, body)
    }

    private fun send(exchange: HttpExchange, status: Int, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        runCatching {
            exchange.sendResponseHeaders(status, if (status == 204) -1 else bytes.size.toLong())
            if (bytes.isNotEmpty()) {
                exchange.responseBody.use { it.write(bytes) }
            }
        }.onFailure(onError)
    }

    private class PayloadTooLargeException : RuntimeException()

    companion object {
        private const val LOOPBACK_ADDRESS = "127.0.0.1"
        private const val SUBMIT_HEADER = "cph-submit"
        private val SUBMIT_PATHS = setOf("/", "/getSubmit")
        private const val MAX_REQUEST_BYTES = 2 * 1024 * 1024
        private const val MAX_TEST_CASES = 1_000
        private const val MAX_NAME_LENGTH = 1_000
        private const val MAX_GROUP_LENGTH = 2_000
        private const val MAX_URL_LENGTH = 8_192
        private const val MAX_BATCH_ID_LENGTH = 1_000
        private const val MAX_TIME_LIMIT_MS = 86_400_000L
        private const val MAX_MEMORY_LIMIT_MB = 1_048_576
        private const val MAX_TEST_CASE_CHARACTERS = 1_000_000
    }
}

class CompanionRequestException(
    val statusCode: Int,
    val clientMessage: String,
) : RuntimeException(clientMessage)
