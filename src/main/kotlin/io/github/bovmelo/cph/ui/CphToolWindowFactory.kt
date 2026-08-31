package io.github.bovmelo.cph.ui

import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import io.github.bovmelo.cph.project.CphProjectService
import java.nio.file.Path

class CphToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = CphToolWindowPanel(project)
        val content = ContentFactory.getInstance().createContent(panel, null, false)
        content.setDisposer(panel)
        toolWindow.contentManager.addContent(content)
        val activePath = FileEditorManager.getInstance(project).selectedFiles.firstOrNull()
            ?.takeIf { it.isInLocalFileSystem }
            ?.let { Path.of(it.path).toAbsolutePath().normalize() }
        project.service<CphProjectService>().activeFileChanged(activePath)
    }

    override fun shouldBeAvailable(project: Project): Boolean = true
}
