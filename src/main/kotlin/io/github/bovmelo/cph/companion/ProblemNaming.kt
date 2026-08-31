package io.github.bovmelo.cph.companion

import io.github.bovmelo.cph.model.CompanionProblemPayload
import io.github.bovmelo.cph.model.LanguageId
import java.net.URI
import java.text.Normalizer

object ProblemNaming {
    private val codeforcesPatterns = listOf(
        Regex("/contest/(\\d+)/problem/([A-Za-z0-9]+)"),
        Regex("/gym/(\\d+)/problem/([A-Za-z0-9]+)"),
        Regex("/problemset/problem/(\\d+)/([A-Za-z0-9]+)"),
    )

    fun fileName(payload: CompanionProblemPayload, languageId: LanguageId): String {
        val stem = stem(payload).let { value ->
            if (languageId == LanguageId.JAVA) value.toJavaClassName() else value
        }
        return "$stem.${languageId.extension}"
    }

    fun stem(payload: CompanionProblemPayload): String {
        platformProblemId(payload.url)?.let { return it }

        val safeName = Normalizer.normalize(payload.name, Normalizer.Form.NFKC)
            .replace(Regex("[^\\p{L}\\p{N}]+"), "_")
            .trim('_')
            .take(MAX_STEM_LENGTH)

        val fallback = safeName.ifBlank { "Problem_${payload.url.hashCode().toUInt().toString(16)}" }
        return if (fallback.uppercase() in WINDOWS_RESERVED_NAMES) "Problem_$fallback" else fallback
    }

    private fun platformProblemId(rawUrl: String): String? {
        val uri = runCatching { URI(rawUrl) }.getOrNull() ?: return null
        val host = uri.host.orEmpty().lowercase()
        val path = uri.path.orEmpty()

        if (host == "codeforces.com" || host.endsWith(".codeforces.com")) {
            codeforcesPatterns.forEach { pattern ->
                pattern.find(path)?.let { match -> return match.groupValues[1] + match.groupValues[2] }
            }
        }
        if (host == "luogu.com.cn" || host.endsWith(".luogu.com.cn")) {
            Regex("/problem/([A-Za-z0-9_-]+)").find(path)?.let { return it.groupValues[1] }
        }
        if (host == "atcoder.jp" || host.endsWith(".atcoder.jp")) {
            Regex("/contests/([^/]+)/tasks/([^/]+)").find(path)?.let { return it.groupValues[2] }
        }
        if (host == "open.kattis.com") {
            Regex("/problems/([^/]+)").find(path)?.let { return it.groupValues[1] }
        }
        return null
    }

    private fun String.toJavaClassName(): String {
        val words = split(Regex("[^\\p{L}\\p{N}]+")).filter(String::isNotBlank)
        val joined = words.joinToString(separator = "") { word ->
            word.replaceFirstChar { character -> character.uppercase() }
        }.ifBlank { "Problem" }
        return if (joined.first().isDigit()) "Problem$joined" else joined
    }

    private const val MAX_STEM_LENGTH = 80
    private val WINDOWS_RESERVED_NAMES = setOf(
        "CON",
        "PRN",
        "AUX",
        "NUL",
        "COM1",
        "COM2",
        "COM3",
        "COM4",
        "COM5",
        "COM6",
        "COM7",
        "COM8",
        "COM9",
        "LPT1",
        "LPT2",
        "LPT3",
        "LPT4",
        "LPT5",
        "LPT6",
        "LPT7",
        "LPT8",
        "LPT9",
    )
}
