package io.github.bovmelo.cph.model

enum class RunPhase {
    IDLE,
    COMPILING,
    RUNNING,
    CHECKING,
    SUBMITTING,
    CANCELLING,
}

enum class Verdict(val label: String) {
    PENDING("Pending"),
    RUNNING("Running"),
    ACCEPTED("Accepted"),
    WRONG_ANSWER("Wrong Answer"),
    TIME_LIMIT_EXCEEDED("Time Limit Exceeded"),
    RUNTIME_ERROR("Runtime Error"),
    OUTPUT_LIMIT_EXCEEDED("Output Limit Exceeded"),
    CHECKER_ERROR("Checker Error"),
    COMPILATION_ERROR("Compilation Error"),
    UNSUPPORTED("Unsupported"),
    CANCELLED("Cancelled"),
}

data class LineDifference(
    val lineNumber: Int,
    val expected: String?,
    val actual: String?,
)

data class ComparisonResult(
    val matches: Boolean,
    val differences: List<LineDifference>,
)

data class TestRunResult(
    val testCaseId: Long,
    val verdict: Verdict,
    val stdout: String = "",
    val stderr: String = "",
    val exitCode: Int? = null,
    val durationMs: Long = 0,
    val differences: List<LineDifference> = emptyList(),
    val checkerStdout: String = "",
    val checkerStderr: String = "",
)

data class CphUiState(
    val problem: Problem? = null,
    val phase: RunPhase = RunPhase.IDLE,
    val results: Map<Long, TestRunResult> = emptyMap(),
    val compileOutput: String = "",
    val submissionOutput: String = "",
    val statusMessage: String = "Open a supported file or import a problem with Competitive Companion.",
)
