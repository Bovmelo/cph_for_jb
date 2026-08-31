package io.github.bovmelo.cph.judge

/**
 * Splits user-configured arguments without invoking a shell. Single quotes,
 * double quotes, and backslash escaping are supported so paths can contain spaces.
 */
object CommandArguments {
    fun parse(raw: String): List<String> {
        if (raw.isBlank()) {
            return emptyList()
        }

        val arguments = mutableListOf<String>()
        val current = StringBuilder()
        var quote: Char? = null
        var tokenStarted = false
        var index = 0

        while (index < raw.length) {
            val character = raw[index]
            when {
                character == '\\' && quote == null -> {
                    require(index + 1 < raw.length) { "An argument cannot end with an escape character." }
                    current.append(raw[index + 1])
                    tokenStarted = true
                    index += 2
                }

                character == '\\' && quote == '"' -> {
                    val next = raw.getOrNull(index + 1)
                    if (next == '"' || next == '\\') {
                        current.append(next)
                        index += 2
                    } else {
                        current.append(character)
                        index += 1
                    }
                    tokenStarted = true
                }

                quote != null && character == quote -> {
                    quote = null
                    tokenStarted = true
                    index += 1
                }

                quote == null && (character == '\'' || character == '"') -> {
                    quote = character
                    tokenStarted = true
                    index += 1
                }

                quote == null && character.isWhitespace() -> {
                    if (tokenStarted) {
                        arguments += current.toString()
                        current.clear()
                        tokenStarted = false
                    }
                    index += 1
                }

                else -> {
                    current.append(character)
                    tokenStarted = true
                    index += 1
                }
            }
        }

        require(quote == null) { "An argument contains an unclosed quote." }
        if (tokenStarted) {
            arguments += current.toString()
        }
        return arguments
    }
}
