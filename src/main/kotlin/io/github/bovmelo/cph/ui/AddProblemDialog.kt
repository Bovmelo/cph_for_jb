package io.github.bovmelo.cph.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import io.github.bovmelo.cph.model.LanguageId
import javax.swing.JComponent
import javax.swing.event.DocumentEvent

/** Asks for the name of a new problem and, where the IDE offers more than one, its language. */
class AddProblemDialog(
    project: Project,
    private val languages: List<LanguageId>,
    initialLanguage: LanguageId,
    private val nameHint: String,
    private val nameError: (name: String, language: LanguageId) -> String?,
) : DialogWrapper(project) {
    private val nameField = JBTextField()
    private val languageBox = ComboBox(languages.toTypedArray()).apply {
        selectedItem = initialLanguage.takeIf { it in languages } ?: languages.first()
        renderer = SimpleListCellRenderer.create("") { it.displayName }
    }

    val problemName: String get() = nameField.text.trim()
    val language: LanguageId get() = languageBox.selectedItem as LanguageId

    init {
        title = "Add Problem"
        setOKButtonText("Create")
        init()
        nameField.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(event: DocumentEvent) = validateName()
        })
        // Taken names and naming rules differ per language, so the same name can turn valid or invalid.
        languageBox.addActionListener { validateName() }
        validateName()
    }

    override fun getPreferredFocusedComponent(): JComponent = nameField

    public override fun createCenterPanel(): JComponent = panel {
        row("Name:") {
            cell(nameField).align(AlignX.FILL).comment(nameHint)
        }
        if (languages.size > 1) {
            row("Language:") {
                cell(languageBox)
            }
        }
    }.apply { minimumSize = JBUI.size(360, 0) }

    private fun validateName() {
        val error = nameError(problemName, language)
        isOKActionEnabled = error == null
        // An empty field only disables Create; an error before anything is typed would be noise.
        setErrorText(error.takeIf { problemName.isNotEmpty() }, nameField)
    }
}
