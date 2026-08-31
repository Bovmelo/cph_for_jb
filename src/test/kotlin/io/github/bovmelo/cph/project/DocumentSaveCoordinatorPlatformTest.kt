package io.github.bovmelo.cph.project

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.testFramework.junit5.TestApplication
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@TestApplication
class DocumentSaveCoordinatorPlatformTest {
    @Test
    fun `application scheduling supplies write-intent access for document save`() {
        val application = ApplicationManager.getApplication()
        val completed = CountDownLatch(1)
        val saveHadWriteIntent = AtomicBoolean(false)
        val actionStarted = AtomicBoolean(false)
        val coordinator = DocumentSaveCoordinator(
            scheduleWithWriteIntent = application::invokeLater,
            saveAllDocuments = {
                saveHadWriteIntent.set(application.isWriteIntentLockAcquired)
                FileDocumentManager.getInstance().saveAllDocuments()
            },
        )

        assertFalse(application.isDispatchThread)
        coordinator.saveThen {
            actionStarted.set(true)
            completed.countDown()
        }

        assertTrue(completed.await(10, TimeUnit.SECONDS))
        assertTrue(saveHadWriteIntent.get())
        assertTrue(actionStarted.get())
    }
}
