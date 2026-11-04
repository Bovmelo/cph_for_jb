# AGENTS.md

## Scope

These instructions apply to the entire repository.

## Repository status

- This repository contains a Kotlin IntelliJ Platform plugin named CPH for JetBrains.
- `JetBrains黑客松参赛指南.pdf` is reference material. Treat it as read-only unless a task explicitly asks to replace or edit it.
- The plugin recreates the Competitive Programming Helper workflow with native JetBrains UI and Competitive Companion protocol compatibility.
- Production code is Kotlin. Gradle Kotlin DSL and the IntelliJ Platform Gradle Plugin are the canonical build system.
- The plugin targets IntelliJ Platform 2025.2 or newer and emits JVM 21 bytecode. A newer local JDK may be used to run Gradle.

## Working conventions

- Inspect the current tree and Git status before making changes; preserve unrelated work.
- Keep changes focused on the requested task. Avoid drive-by refactors or premature infrastructure.
- Do not commit generated build output, IDE-local state, caches, credentials, or secrets.
- Use UTF-8. Keep code identifiers in English and preserve the language used by adjacent documentation.
- Write production and test code in Kotlin unless an external interface requires another language.
- Use four-space indentation, lowercase package names, `PascalCase` types, `camelCase` members, and `UPPER_SNAKE_CASE` constants.
- Keep core parsing, persistence, command construction, and judging logic independent of IntelliJ UI APIs where practical so it can be unit tested directly.
- Depend only on `com.intellij.modules.platform` unless a task explicitly requires product-specific APIs; language execution is compiler-command based rather than PSI based.
- Bind local HTTP listeners to the loopback interface, validate untrusted payloads, and pass process arguments as arrays rather than shell command strings.
- Prefer small, cohesive classes and explicit dependencies. Add comments for intent or non-obvious constraints, not to restate the code.

## Project layout

- `src/main/kotlin/io/github/bovmelo/cph/model`: protocol and domain models.
- `src/main/kotlin/io/github/bovmelo/cph/persistence`: `.cph` metadata storage.
- `src/main/kotlin/io/github/bovmelo/cph/companion`: Competitive Companion listener and import flow.
- `src/main/kotlin/io/github/bovmelo/cph/judge`: language adapters, process execution, comparison, and verdicts.
- `src/main/kotlin/io/github/bovmelo/cph/submission`: cph-submit and Kattis submission boundaries.
- `src/main/kotlin/io/github/bovmelo/cph/ui`: native tool window UI.
- `src/test/kotlin`: unit tests for behavior that does not require a running IDE.

## Canonical commands

- Unit tests: `./gradlew test`
- Build plugin distribution: `./gradlew buildPlugin`
- Run a development IDE: `./gradlew runIde`
- Verify plugin compatibility: `./gradlew verifyPlugin`

## Verification

- Run the narrowest relevant checks first, followed by the full available test/build suite before handoff.
- `LanguageExecutionIntegrationTest` runs installed external toolchains and skips unavailable ones; set `CPH_TEST_<LANGUAGE>_COMPILER` or `CPH_TEST_<LANGUAGE>_RUNNER` to verify a tool outside `PATH`.
- IntelliJ application-level JUnit 5 tests use `TestFrameworkType.Platform` and `TestFrameworkType.JUnit5`; keep platform fixtures out of tests for pure model, persistence, command, and judging logic.
- If no automated check exists for a change, perform a suitable manual validation and state that limitation clearly.
- Gradle's build cache (`org.gradle.caching`) can restore stale `compileTestKotlin` output after a main-source constructor signature change, which surfaces as `NoSuchMethodError` in tests; rerun with `./gradlew cleanCompileTestKotlin test --no-build-cache` (`--rerun` alone only reruns the test task, not the stale compile).
- Never claim a command passed unless it was actually run successfully.
- Update this file when the repository gains concrete build, test, formatting, or architectural conventions.
