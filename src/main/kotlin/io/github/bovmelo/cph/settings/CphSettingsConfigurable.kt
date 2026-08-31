package io.github.bovmelo.cph.settings

import com.intellij.openapi.components.service
import com.intellij.openapi.options.Configurable
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.FormBuilder
import io.github.bovmelo.cph.companion.CompanionServerService
import io.github.bovmelo.cph.model.LanguageId
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel
import javax.swing.table.DefaultTableModel

class CphSettingsConfigurable : Configurable {
    private var component: JPanel? = null
    private val language = JComboBox(LanguageId.entries.map { it.displayName }.toTypedArray())
    private val port = JSpinner(SpinnerNumberModel(27_121, 1_024, 65_535, 1))
    private val timeout = JSpinner(SpinnerNumberModel(3_000L, 100L, 120_000L, 100L))
    private val compileTimeout = JSpinner(SpinnerNumberModel(60_000L, 1_000L, 600_000L, 1_000L))
    private val failOnStderr = JBCheckBox("Treat stderr output as a runtime error")
    private val onlineJudge = JBCheckBox("Define ONLINE_JUDGE for solution processes")
    private val autoLoadActiveFile = JBCheckBox("Load testcases when the active editor changes")
    private val problemDirectory = JBTextField()
    private val templatePath = JBTextField()
    private val toolchainModel = object : DefaultTableModel(
        arrayOf("Language", "Compiler", "Runtime", "Compiler arguments", "Runtime arguments"),
        0,
    ) {
        override fun isCellEditable(row: Int, column: Int): Boolean = column != 0
    }
    private val toolchainTable = JBTable(toolchainModel).apply {
        rowHeight = 24
        autoResizeMode = JBTable.AUTO_RESIZE_SUBSEQUENT_COLUMNS
    }

    override fun getDisplayName(): String = "CPH"

    override fun createComponent(): JComponent {
        if (component == null) {
            val toolchainScrollPane = JBScrollPane(toolchainTable).apply {
                preferredSize = Dimension(860, 300)
            }
            component = JPanel(BorderLayout()).apply {
                add(
                    FormBuilder.createFormBuilder()
                        .addLabeledComponent("Default language:", language)
                        .addLabeledComponent("Companion port:", port)
                        .addLabeledComponent("Testcase timeout (ms):", timeout)
                        .addLabeledComponent("Compilation timeout (ms):", compileTimeout)
                        .addComponent(failOnStderr)
                        .addComponent(onlineJudge)
                        .addComponent(autoLoadActiveFile)
                        .addLabeledComponent("Imported problem directory:", problemDirectory)
                        .addSeparator()
                        .addLabeledComponent("Language toolchains:", toolchainScrollPane)
                        .addSeparator()
                        .addLabeledComponent("Default template file:", templatePath)
                        .addComponentFillVertically(JPanel(), 0)
                        .panel,
                    BorderLayout.CENTER,
                )
            }
            reset()
        }
        return component!!
    }

    override fun isModified(): Boolean {
        toolchainTable.cellEditor?.stopCellEditing()
        return readFields() != service<CphSettings>().snapshot()
    }

    override fun apply() {
        toolchainTable.cellEditor?.stopCellEditing()
        val previousPort = service<CphSettings>().snapshot().port
        val updated = readFields()
        service<CphSettings>().update(updated)
        if (updated.port != previousPort) {
            service<CompanionServerService>().restart()
        }
    }

    override fun reset() {
        val settings = service<CphSettings>().snapshot()
        language.selectedItem = settings.defaultLanguage.displayName
        port.value = settings.port
        timeout.value = settings.timeoutMs
        compileTimeout.value = settings.compileTimeoutMs
        failOnStderr.isSelected = settings.failOnStderr
        onlineJudge.isSelected = settings.onlineJudge
        autoLoadActiveFile.isSelected = settings.autoLoadActiveFile
        problemDirectory.text = settings.problemDirectory
        templatePath.text = settings.templatePath

        toolchainModel.rowCount = 0
        LanguageId.entries.forEach { languageId ->
            val toolchain = settings.toolchain(languageId)
            toolchainModel.addRow(
                arrayOf(
                    languageId.displayName,
                    toolchain.compileCommand,
                    toolchain.runCommand,
                    toolchain.compileArguments,
                    toolchain.runArguments,
                ),
            )
        }
    }

    override fun disposeUIResources() {
        component = null
    }

    private fun readFields(): CphSettingsSnapshot = CphSettingsSnapshot(
        port = (port.value as Number).toInt(),
        defaultLanguage = LanguageId.entries.firstOrNull { it.displayName == language.selectedItem } ?: LanguageId.KOTLIN,
        timeoutMs = (timeout.value as Number).toLong(),
        compileTimeoutMs = (compileTimeout.value as Number).toLong(),
        failOnStderr = failOnStderr.isSelected,
        onlineJudge = onlineJudge.isSelected,
        autoLoadActiveFile = autoLoadActiveFile.isSelected,
        problemDirectory = problemDirectory.text.trim(),
        templatePath = templatePath.text.trim(),
        toolchains = LanguageId.entries.associateWith { languageId ->
            val row = languageId.ordinal
            LanguageToolchain(
                compileCommand = toolchainModel.getValueAt(row, 1)?.toString()?.trim().orEmpty(),
                runCommand = toolchainModel.getValueAt(row, 2)?.toString()?.trim().orEmpty(),
                compileArguments = toolchainModel.getValueAt(row, 3)?.toString()?.trim().orEmpty(),
                runArguments = toolchainModel.getValueAt(row, 4)?.toString()?.trim().orEmpty(),
            )
        },
    )
}
