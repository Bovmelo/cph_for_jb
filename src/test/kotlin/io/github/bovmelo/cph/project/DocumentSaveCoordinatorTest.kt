package io.github.bovmelo.cph.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DocumentSaveCoordinatorTest {
    @Test
    fun `save and action run in scheduled write-intent operation`() {
        val scheduled = mutableListOf<Runnable>()
        val events = mutableListOf<String>()
        val coordinator = DocumentSaveCoordinator(
            scheduleWithWriteIntent = scheduled::add,
            saveAllDocuments = { events += "save" },
        )

        coordinator.saveThen { events += "action" }

        assertTrue(events.isEmpty())
        assertEquals(1, scheduled.size)
        scheduled.single().run()
        assertEquals(listOf("save", "action"), events)
    }

    @Test
    fun `failed save does not start action`() {
        var actionStarted = false
        val coordinator = DocumentSaveCoordinator(
            scheduleWithWriteIntent = Runnable::run,
            saveAllDocuments = { error("save failed") },
        )

        assertFailsWith<IllegalStateException> {
            coordinator.saveThen { actionStarted = true }
        }

        assertFalse(actionStarted)
    }
}
