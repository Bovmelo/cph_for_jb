package io.github.bovmelo.cph.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.concurrent.atomic.AtomicLong

@Serializable
data class Problem(
    val name: String,
    val url: String,
    val interactive: Boolean = false,
    val memoryLimit: Int = 1024,
    val timeLimit: Long = 3_000,
    val group: String = "",
    val tests: List<TestCase> = emptyList(),
    @SerialName("srcPath") val sourcePath: String,
    val local: Boolean = false,
    val customCheckerPath: String? = null,
    val stressGeneratorPath: String? = null,
    val stressBrutePath: String? = null,
    val languageId: String? = null,
)

@Serializable
data class TestCase(
    val input: String = "",
    val output: String = "",
    val id: Long = TestCaseIds.next(),
    // Disabled cases are skipped by Run Testcases but still run when selected individually.
    val enabled: Boolean = true,
)

object TestCaseIds {
    private val sequence = AtomicLong(System.currentTimeMillis() * 1_000)

    fun next(): Long = sequence.incrementAndGet()
}
