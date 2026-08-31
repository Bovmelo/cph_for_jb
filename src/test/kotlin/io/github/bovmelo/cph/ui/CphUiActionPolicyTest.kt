package io.github.bovmelo.cph.ui

import io.github.bovmelo.cph.model.RunPhase
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CphUiActionPolicyTest {
    @Test
    fun `run is enabled for a supported active file before a problem exists`() {
        assertTrue(CphUiActionPolicy.canRunTestCases(RunPhase.IDLE, supportedActiveFile = true))
    }

    @Test
    fun `run is disabled without a supported active file or while busy`() {
        assertFalse(CphUiActionPolicy.canRunTestCases(RunPhase.IDLE, supportedActiveFile = false))
        assertFalse(CphUiActionPolicy.canRunTestCases(RunPhase.COMPILING, supportedActiveFile = true))
    }
}
