package io.github.bovmelo.cph.startup

import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import io.github.bovmelo.cph.companion.CompanionServerService
import io.github.bovmelo.cph.project.CphProjectService
import java.nio.file.Path

class CphProjectActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        service<CompanionServerService>().ensureStarted()
        project.messageBus.connect(project).subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun selectionChanged(event: FileEditorManagerEvent) {
                    val path = event.newFile
                        ?.takeIf { it.isInLocalFileSystem }
                        ?.let { Path.of(it.path).toAbsolutePath().normalize() }
                    project.service<CphProjectService>().activeFileChanged(path)
                }
            },
        )
    }
}
