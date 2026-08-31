package io.github.bovmelo.cph.submission

import io.github.bovmelo.cph.judge.CommandSpec
import io.github.bovmelo.cph.model.LanguageId
import io.github.bovmelo.cph.model.Problem
import kotlinx.serialization.Serializable
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference

@Serializable
data class EmptySubmitResponse(val empty: Boolean = true)

@Serializable
data class CodeforcesSubmitResponse(
    val url: String,
    val empty: Boolean = false,
    val problemName: String,
    val sourceCode: String,
    val languageId: Int,
)

class CodeforcesSubmitQueue {
    private val pending = AtomicReference<CodeforcesSubmitResponse?>()

    fun prepare(problem: Problem, sourceCode: String, languageId: LanguageId): CodeforcesSubmitResponse {
        require(sourceCode.toByteArray(Charsets.UTF_8).size <= MAX_SOURCE_BYTES) {
            "The source file is larger than 2 MiB."
        }
        require(isCodeforcesUrl(problem.url)) { "The current problem is not hosted on Codeforces." }
        val problemName = codeforcesProblemName(problem.url)
        require(problemName.isNotBlank()) { "The Codeforces problem identifier could not be parsed from the URL." }
        val submissionLanguageId = requireNotNull(languageId.codeforcesLanguageId) {
            "${languageId.displayName} is not supported by cph-submit."
        }
        return CodeforcesSubmitResponse(
            url = problem.url,
            problemName = problemName,
            sourceCode = sourceCode,
            languageId = submissionLanguageId,
        )
    }

    /** Returns true when an older pending response was replaced. */
    fun offer(response: CodeforcesSubmitResponse): Boolean = pending.getAndSet(response) != null

    fun poll(): CodeforcesSubmitResponse? = pending.getAndSet(null)

    fun peek(): CodeforcesSubmitResponse? = pending.get()

    companion object {
        private const val MAX_SOURCE_BYTES = 2 * 1024 * 1024
    }
}

data class KattisSubmissionPlan(
    val command: CommandSpec,
    val stdin: String = "Y\n",
)

object KattisSubmission {
    fun plan(
        problem: Problem,
        sourcePath: Path,
        userHome: Path,
        pythonCommand: String,
    ): KattisSubmissionPlan {
        require(isKattisUrl(problem.url)) { "The current problem is not hosted on Kattis." }
        require(Files.isRegularFile(sourcePath)) { "The source file does not exist: $sourcePath" }
        require(pythonCommand.isNotBlank()) { "No Python interpreter is configured." }

        val kattisDirectory = userHome.toAbsolutePath().normalize().resolve(".kattis")
        val configuration = kattisDirectory.resolve(".kattisrc")
        val client = kattisDirectory.resolve("submit.py")
        require(Files.isRegularFile(configuration)) { "Kattis configuration was not found: $configuration" }
        require(Files.isRegularFile(client)) { "Kattis submission client was not found: $client" }

        return KattisSubmissionPlan(
            command = CommandSpec(
                executable = pythonCommand,
                arguments = listOf(client.toString(), "-f", sourcePath.toAbsolutePath().normalize().toString()),
                workingDirectory = sourcePath.toAbsolutePath().normalize().parent,
            ),
        )
    }
}

fun isCodeforcesUrl(rawUrl: String): Boolean {
    val host = runCatching { URI(rawUrl).host.orEmpty().lowercase() }.getOrDefault("")
    return host == "codeforces.com" || host.endsWith(".codeforces.com")
}

fun isKattisUrl(rawUrl: String): Boolean =
    runCatching { URI(rawUrl).host.equals("open.kattis.com", ignoreCase = true) }.getOrDefault(false)

fun codeforcesProblemName(rawUrl: String): String {
    val path = runCatching { URI(rawUrl).path.orEmpty() }.getOrDefault("")
    val patterns = listOf(
        Regex("/contest/(\\d+)/problem/([A-Za-z0-9]+)"),
        Regex("/gym/(\\d+)/problem/([A-Za-z0-9]+)"),
        Regex("/problemset/problem/(\\d+)/([A-Za-z0-9]+)"),
        Regex("/problemset/gymProblem/(\\d+)/([A-Za-z0-9]+)"),
        Regex("/problemsets/acmsguru/problem/(\\d+)/([A-Za-z0-9]+)"),
    )
    return patterns.firstNotNullOfOrNull { pattern ->
        pattern.find(path)?.let { match -> match.groupValues[1] + match.groupValues[2] }
    }.orEmpty()
}
