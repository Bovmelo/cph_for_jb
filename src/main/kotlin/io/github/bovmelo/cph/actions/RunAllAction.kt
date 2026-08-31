package io.github.bovmelo.cph.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import io.github.bovmelo.cph.model.RunPhase
import io.github.bovmelo.cph.project.CphProjectService

class RunAllAction : AnAction() {
    override fun actionPerformed(event: AnActionEvent) {
        event.project?.service<CphProjectService>()?.runAll()
    }

    override fun update(event: AnActionEvent) {
        val service = event.project?.service<CphProjectService>()
        event.presentation.isEnabled = service != null &&
            service.currentState().phase == RunPhase.IDLE &&
            service.hasSupportedActiveFile()
    }
}
