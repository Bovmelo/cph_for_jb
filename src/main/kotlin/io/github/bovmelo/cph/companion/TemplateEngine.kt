package io.github.bovmelo.cph.companion

import io.github.bovmelo.cph.model.CompanionProblemPayload
import io.github.bovmelo.cph.model.LanguageId
import java.time.Clock
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

data class ExpandedTemplate(
    val content: String,
    val cursorOffset: Int?,
)

class TemplateEngine(private val clock: Clock = Clock.systemDefaultZone()) {
    fun expand(
        rawTemplate: String,
        payload: CompanionProblemPayload,
        className: String = "Main",
    ): ExpandedTemplate {
        val now = LocalDateTime.now(clock)
        val replacements = mapOf(
            "name" to payload.name,
            "url" to payload.url,
            "group" to payload.group,
            "className" to className,
            "date" to now.format(DateTimeFormatter.ISO_LOCAL_DATE),
            "time" to now.format(DateTimeFormatter.ofPattern("HH:mm:ss")),
        )

        var expanded = rawTemplate
        replacements.forEach { (key, value) ->
            expanded = expanded.replace("\$$key\$", value)
        }
        expanded = expanded.replace(JAVA_CLASS_PLACEHOLDER, className)

        val cursorOffset = expanded.indexOf(CURSOR_PLACEHOLDER).takeIf { it >= 0 }
        expanded = expanded.replace(CURSOR_PLACEHOLDER, "")
        return ExpandedTemplate(expanded, cursorOffset)
    }

    fun builtIn(languageId: LanguageId): String = when (languageId) {
        LanguageId.KOTLIN -> """
            import java.io.BufferedReader
            import java.io.InputStreamReader

            fun main() {
                val reader = BufferedReader(InputStreamReader(System.`in`))
                $CURSOR_PLACEHOLDER
            }
        """.trimIndent() + "\n"

        LanguageId.CPP -> """
            #include <bits/stdc++.h>
            using namespace std;

            int main() {
                ios::sync_with_stdio(false);
                cin.tie(nullptr);

                $CURSOR_PLACEHOLDER
                return 0;
            }
        """.trimIndent() + "\n"

        LanguageId.C -> """
            #include <stdio.h>

            int main(void) {
                $CURSOR_PLACEHOLDER
                return 0;
            }
        """.trimIndent() + "\n"

        LanguageId.CSHARP -> """
            using System;

            internal static class Program
            {
                private static void Main()
                {
                    $CURSOR_PLACEHOLDER
                }
            }
        """.trimIndent() + "\n"

        LanguageId.RUST -> """
            use std::io::{self, Read};

            fn main() {
                let mut input = String::new();
                io::stdin().read_to_string(&mut input).unwrap();
                $CURSOR_PLACEHOLDER
            }
        """.trimIndent() + "\n"

        LanguageId.GO -> """
            package main

            import (
                "bufio"
                "fmt"
                "os"
            )

            func main() {
                in := bufio.NewReader(os.Stdin)
                out := bufio.NewWriter(os.Stdout)
                defer out.Flush()
                _, _ = in, fmt.Fprint
                $CURSOR_PLACEHOLDER
            }
        """.trimIndent() + "\n"

        LanguageId.HASKELL -> """
            main :: IO ()
            main = do
                $CURSOR_PLACEHOLDER
                pure ()
        """.trimIndent() + "\n"

        LanguageId.PYTHON -> """
            import sys


            def main() -> None:
                ${CURSOR_PLACEHOLDER}pass


            if __name__ == "__main__":
                main()
        """.trimIndent() + "\n"

        LanguageId.RUBY -> """
            input = STDIN.read
            $CURSOR_PLACEHOLDER
        """.trimIndent() + "\n"

        LanguageId.JAVA -> """
            import java.io.BufferedReader;
            import java.io.InputStreamReader;

            public class $JAVA_CLASS_PLACEHOLDER {
                public static void main(String[] args) throws Exception {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
                    $CURSOR_PLACEHOLDER
                }
            }
        """.trimIndent() + "\n"

        LanguageId.JAVASCRIPT -> """
            'use strict';

            const input = require('fs').readFileSync(0, 'utf8');
            $CURSOR_PLACEHOLDER
        """.trimIndent() + "\n"

        LanguageId.CANGJIE -> """
            main() {
                $CURSOR_PLACEHOLDER
            }
        """.trimIndent() + "\n"
    }

    companion object {
        const val CURSOR_PLACEHOLDER = "\$CURSOR_PLACEHOLDER"
        const val JAVA_CLASS_PLACEHOLDER = "CLASS_NAME"
    }
}
