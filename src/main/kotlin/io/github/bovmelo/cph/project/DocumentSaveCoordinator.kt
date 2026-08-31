package io.github.bovmelo.cph.project

internal class DocumentSaveCoordinator(
    private val scheduleWithWriteIntent: (Runnable) -> Unit,
    private val saveAllDocuments: () -> Unit,
) {
    fun saveThen(action: () -> Unit) {
        scheduleWithWriteIntent(
            Runnable {
                saveAllDocuments()
                action()
            },
        )
    }
}
