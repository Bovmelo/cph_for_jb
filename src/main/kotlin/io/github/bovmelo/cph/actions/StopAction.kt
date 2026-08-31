package io.github.bovmelo.cph.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import io.github.bovmelo.cph.model.RunPhase
import io.github.bovmelo.cph.project.CphProjectService

class StopAction : AnAction() {
    override fun actionPerformed(event: AnActionEvent) {
        event.project?.service<CphProjectService>()?.stop()
    }

    override fun update(event: AnActionEvent) {
        event.presentation.isEnabled = event.project
            ?.service<CphProjectService>()
            ?.currentState()
            ?.phase != RunPhase.IDLE
    }
}
