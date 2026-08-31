package io.github.bovmelo.cph.project

internal enum class RunTestCasesIntent {
    CREATE_LOCAL_PROBLEM,
    RUN_ALL,
}

internal object RunTestCasesPolicy {
    fun resolve(hasSavedProblem: Boolean): RunTestCasesIntent =
        if (hasSavedProblem) RunTestCasesIntent.RUN_ALL else RunTestCasesIntent.CREATE_LOCAL_PROBLEM
}
