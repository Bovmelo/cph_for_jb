package io.github.bovmelo.cph.ui

import io.github.bovmelo.cph.model.RunPhase

internal object CphUiActionPolicy {
    fun canRunTestCases(phase: RunPhase, supportedActiveFile: Boolean): Boolean =
        phase == RunPhase.IDLE && supportedActiveFile
}
