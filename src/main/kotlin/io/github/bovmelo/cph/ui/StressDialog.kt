package io.github.bovmelo.cph.ui

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JComponent

class StressDialog(
    private val project: Project,
    generatorPath: String,
    brutePath: String,
) : DialogWrapper(project) {
    private val generator = fileField("Select Generator", generatorPath)
    private val brute = fileField("Select Brute Force Solution", brutePath)
    private val iterationsField = JBTextField(
        PropertiesComponent.getInstance(project).getInt(ITERATIONS_KEY, DEFAULT_ITERATIONS).toString(),
    )

    val generatorPath: String get() = generator.text.trim()
    val brutePath: String get() = brute.text.trim()
    val iterations: Int get() = iterationsField.text.trim().toInt()

    init {
        title = "Stress Test"
        setOKButtonText("Run")
        init()
    }

    override fun createCenterPanel(): JComponent = panel {
        row {
            text(
                "Run your solution and a brute force on generated inputs. " +
                    "The first input where they disagree is added as a new testcase.",
            )
        }
        group("Programs") {
            row("Generator:") {
                cell(generator)
                    .align(AlignX.FILL)
                    .comment("Receives the iteration number as its first argument and prints one input.")
            }
            row("Brute force:") {
                cell(brute)
                    .align(AlignX.FILL)
                    .comment("Reads the same input and prints the correct answer. Any supported language works.")
            }
        }
        row("Iterations:") {
            cell(iterationsField).columns(8)
        }
    }.apply { minimumSize = JBUI.size(480, 0) }

    override fun doValidate(): ValidationInfo? {
        if (!isFile(generatorPath)) return ValidationInfo("Choose an existing generator file.", generator)
        if (!isFile(brutePath)) return ValidationInfo("Choose an existing brute force file.", brute)
        if (iterationsField.text.trim().toIntOrNull()?.let { it > 0 } != true) {
            return ValidationInfo("Iterations must be a positive number.", iterationsField)
        }
        return null
    }

    override fun doOKAction() {
        PropertiesComponent.getInstance(project).setValue(ITERATIONS_KEY, iterations, DEFAULT_ITERATIONS)
        super.doOKAction()
    }

    private fun isFile(path: String): Boolean =
        path.isNotBlank() && runCatching { Files.isRegularFile(Path.of(path)) }.getOrDefault(false)

    private fun fileField(title: String, initial: String) = TextFieldWithBrowseButton().apply {
        text = initial
        addBrowseFolderListener(
            project,
            FileChooserDescriptorFactory.createSingleFileDescriptor().withTitle(title),
        )
    }

    private companion object {
        const val DEFAULT_ITERATIONS = 1_000
        const val ITERATIONS_KEY = "cph.stress.iterations"
    }
}
