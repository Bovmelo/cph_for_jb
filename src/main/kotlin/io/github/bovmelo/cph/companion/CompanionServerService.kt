package io.github.bovmelo.cph.companion

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.wm.IdeFocusManager
import io.github.bovmelo.cph.project.CphProjectService
import io.github.bovmelo.cph.settings.CphSettings

@Service(Service.Level.APP)
class CompanionServerService : Disposable {
    private val lock = Any()
    private var server: CompanionHttpServer? = null

    fun ensureStarted() {
        synchronized(lock) {
            if (server != null) {
                return
            }
            val port = service<CphSettings>().snapshot().port
            try {
                val newServer = CompanionHttpServer(
                    requestedPort = port,
                    onProblem = { payload ->
                        val project = targetProject() ?: throw CompanionRequestException(
                            statusCode = 409,
                            clientMessage = "Open a JetBrains project first",
                        )
                        project.service<CphProjectService>().importProblem(payload)
                    },
                    onError = { error -> LOG.warn("Competitive Companion request failed", error) },
                )
                newServer.start()
                server = newServer
                LOG.info("CPH Competitive Companion listener started on 127.0.0.1:$port")
            } catch (error: Exception) {
                LOG.warn("Could not start CPH listener on port $port", error)
                notify(
                    title = "CPH listener could not start",
                    content = "Port $port is unavailable. Close another CPH instance or change the port in Settings | Tools | CPH.",
                    type = NotificationType.ERROR,
                    project = null,
                )
            }
        }
    }

    fun restart() {
        synchronized(lock) {
            stopLocked()
        }
        ensureStarted()
    }

    override fun dispose() {
        synchronized(lock) {
            stopLocked()
        }
    }

    private fun targetProject(): Project? {
        val openProjects = ProjectManager.getInstance().openProjects.filterNot(Project::isDisposed)
        val focused = IdeFocusManager.getGlobalInstance().lastFocusedFrame?.project
        return focused?.takeIf { it in openProjects } ?: openProjects.firstOrNull()
    }

    private fun stopLocked() {
        server?.close()
        server = null
    }

    private fun notify(
        title: String,
        content: String,
        type: NotificationType,
        project: Project?,
    ) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(NOTIFICATION_GROUP)
            .createNotification(title, content, type)
            .notify(project)
    }

    companion object {
        private val LOG = Logger.getInstance(CompanionServerService::class.java)
        private const val NOTIFICATION_GROUP = "CPH Notifications"
    }
}
