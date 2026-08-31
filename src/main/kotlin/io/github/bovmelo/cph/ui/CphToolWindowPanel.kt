package io.github.bovmelo.cph.ui

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.service
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import io.github.bovmelo.cph.model.CphUiState
import io.github.bovmelo.cph.model.RunPhase
import io.github.bovmelo.cph.model.TestCase
import io.github.bovmelo.cph.model.TestRunResult
import io.github.bovmelo.cph.model.Verdict
import io.github.bovmelo.cph.project.CphProjectService
import io.github.bovmelo.cph.submission.isCodeforcesUrl
import io.github.bovmelo.cph.submission.isKattisUrl
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.GridLayout
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.nio.file.Path
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.ScrollPaneConstants
import javax.swing.Timer
import javax.swing.event.DocumentEvent

class CphToolWindowPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {
    private val service = project.service<CphProjectService>()
    private val contentPanel = JPanel()
    private val statusLabel = JBLabel()
    private val runTestCasesButton = JButton("Run Testcases")
    private val stopButton = JButton("Stop")
    private val addButton = JButton("Add Case")
    private val importButton = JButton("Import JSON")
    private val exportButton = JButton("Export JSON")
    private val clearButton = JButton("Clear Cases")
    private val resetButton = JButton("Reset Problem")
    private val openUrlButton = JButton("Open Problem")
    private val submitCodeforcesButton = JButton("Submit CF")
    private val submitKattisButton = JButton("Submit Kattis")
    private val settingsButton = JButton("Settings")
    private val onlineJudgeEnabled = JBCheckBox("ONLINE_JUDGE")
    private val checkerEnabled = JBCheckBox("Custom checker")
    private val checkerPath = JBTextField()
    private val checkerBrowseButton = JButton("Browse...")
    private val testCasePanels = mutableListOf<TestCasePanel>()
    private var rendering = false

    init {
        border = JBUI.Borders.empty(6)
        add(createHeader(), BorderLayout.NORTH)

        contentPanel.layout = BoxLayout(contentPanel, BoxLayout.Y_AXIS)
        add(
            JBScrollPane(contentPanel).apply {
                horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
                border = JBUI.Borders.emptyTop(6)
            },
            BorderLayout.CENTER,
        )

        statusLabel.border = JBUI.Borders.empty(6, 2, 2, 2)
        add(statusLabel, BorderLayout.SOUTH)

        runTestCasesButton.addActionListener {
            testCasePanels.forEach { it.flushEdits(persist = false) }
            service.runAll()
        }
        stopButton.addActionListener { service.stop() }
        addButton.addActionListener { service.addTestCase() }
        importButton.addActionListener { chooseImportFile() }
        exportButton.addActionListener { chooseExportFile() }
        clearButton.addActionListener { confirmClearCases() }
        resetButton.addActionListener { confirmResetProblem() }
        openUrlButton.addActionListener { service.currentState().problem?.url?.let(BrowserUtil::browse) }
        submitCodeforcesButton.addActionListener { service.prepareCodeforcesSubmission() }
        submitKattisButton.addActionListener { confirmKattisSubmission() }
        settingsButton.addActionListener { ShowSettingsUtil.getInstance().showSettingsDialog(project, "CPH") }
        onlineJudgeEnabled.addActionListener {
            if (!rendering) {
                service.setOnlineJudge(onlineJudgeEnabled.isSelected)
            }
        }
        checkerEnabled.addActionListener {
            if (!rendering) {
                if (checkerEnabled.isSelected && checkerPath.text.isBlank()) {
                    chooseCheckerFile()
                } else {
                    service.setCustomCheckerPath(if (checkerEnabled.isSelected) checkerPath.text else "")
                }
            }
        }
        checkerBrowseButton.addActionListener { chooseCheckerFile() }
        checkerPath.addActionListener { saveCheckerPath() }
        checkerPath.addFocusListener(object : FocusAdapter() {
            override fun focusLost(event: FocusEvent) {
                saveCheckerPath()
            }
        })

        service.addListener(this, ::render)
    }

    override fun dispose() = Unit

    private fun createHeader(): JPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        add(buttonGrid(3, runTestCasesButton, stopButton, addButton))
        add(buttonGrid(2, importButton, exportButton, clearButton, resetButton))
        add(buttonGrid(2, openUrlButton, submitCodeforcesButton, submitKattisButton, settingsButton))
        add(
            JPanel(BorderLayout(4, 0)).apply {
                border = JBUI.Borders.emptyTop(4)
                add(
                    JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
                        add(onlineJudgeEnabled)
                        add(checkerEnabled)
                    },
                    BorderLayout.WEST,
                )
                add(checkerPath, BorderLayout.CENTER)
                add(checkerBrowseButton, BorderLayout.EAST)
            },
        )
    }

    private fun buttonGrid(columns: Int, vararg buttons: JButton): JPanel = JPanel(GridLayout(0, columns, 4, 4)).apply {
        border = JBUI.Borders.emptyTop(4)
        buttons.forEach(::add)
    }

    private fun render(state: CphUiState) {
        val idle = state.phase == RunPhase.IDLE
        val problem = state.problem
        runTestCasesButton.isEnabled = CphUiActionPolicy.canRunTestCases(
            phase = state.phase,
            supportedActiveFile = service.hasSupportedActiveFile(),
        )
        stopButton.isEnabled = !idle
        addButton.isEnabled = problem != null && idle
        importButton.isEnabled = problem != null && idle
        exportButton.isEnabled = problem != null && idle
        clearButton.isEnabled = problem != null && idle
        resetButton.isEnabled = problem != null && idle
        openUrlButton.isEnabled = problem?.url?.startsWith("http") == true
        submitCodeforcesButton.isEnabled = problem != null && idle && isCodeforcesUrl(problem.url)
        submitKattisButton.isEnabled = problem != null && idle && isKattisUrl(problem.url)
        checkerEnabled.isEnabled = problem != null && idle
        onlineJudgeEnabled.isEnabled = idle
        checkerPath.isEnabled = problem != null && idle && checkerEnabled.isSelected
        checkerBrowseButton.isEnabled = problem != null && idle
        statusLabel.text = state.statusMessage

        rendering = true
        try {
            checkerEnabled.isSelected = !problem?.customCheckerPath.isNullOrBlank()
            onlineJudgeEnabled.isSelected = service.isOnlineJudgeEnabled()
            val path = problem?.customCheckerPath.orEmpty()
            if (checkerPath.text != path) {
                checkerPath.text = path
            }
            checkerPath.isEnabled = problem != null && idle && checkerEnabled.isSelected
        } finally {
            rendering = false
        }

        contentPanel.removeAll()
        testCasePanels.clear()
        if (problem == null) {
            contentPanel.add(JBLabel("No problem loaded."))
            contentPanel.add(Box.createVerticalStrut(8))
            contentPanel.add(JBLabel("Press Run Testcases or Ctrl+Alt+B to create a local problem."))
        } else {
            contentPanel.add(JBLabel(problem.name).apply { font = font.deriveFont(Font.BOLD, font.size2D + 2f) })
            if (problem.group.isNotBlank()) {
                contentPanel.add(JBLabel(problem.group).apply { foreground = JBColor.GRAY })
            }
            contentPanel.add(
                JBLabel(
                    "${problem.tests.size} case(s)  |  ${problem.timeLimit} ms  |  ${problem.memoryLimit} MB",
                ).apply { foreground = JBColor.GRAY },
            )
            if (state.compileOutput.isNotBlank()) {
                contentPanel.add(Box.createVerticalStrut(8))
                contentPanel.add(section("Compiler output", state.compileOutput))
            }
            if (state.submissionOutput.isNotBlank()) {
                contentPanel.add(Box.createVerticalStrut(8))
                contentPanel.add(section("Submission output", state.submissionOutput))
            }
            contentPanel.add(Box.createVerticalStrut(8))
            problem.tests.forEachIndexed { index, testCase ->
                val testCasePanel = TestCasePanel(
                    index = index,
                    testCase = testCase,
                    result = state.results[testCase.id],
                    enabled = idle,
                    customCheckerEnabled = !problem.customCheckerPath.isNullOrBlank(),
                    onChanged = { input, output -> service.updateTestCase(testCase.id, input, output) },
                    onPersist = service::persistTestCases,
                    onRun = { service.runSingle(testCase.id) },
                    onDelete = { service.deleteTestCase(testCase.id) },
                )
                testCasePanels += testCasePanel
                contentPanel.add(
                    testCasePanel,
                )
                contentPanel.add(Box.createVerticalStrut(8))
            }
        }
        contentPanel.revalidate()
        contentPanel.repaint()
    }

    private fun chooseImportFile() {
        val selected = FileChooser.chooseFile(
            FileChooserDescriptorFactory.createSingleFileDescriptor("json").withTitle("Import CPH Testcases"),
            this,
            project,
            initialVirtualFile(),
        ) ?: return
        val choice = Messages.showYesNoCancelDialog(
            project,
            "Replace the current testcases, or append the imported cases?",
            "Import Testcases",
            "Replace",
            "Append",
            "Cancel",
            Messages.getQuestionIcon(),
        )
        when (choice) {
            Messages.YES -> service.importTestCases(Path.of(selected.path), replaceExisting = true)
            Messages.NO -> service.importTestCases(Path.of(selected.path), replaceExisting = false)
        }
    }

    private fun chooseExportFile() {
        val descriptor = FileSaverDescriptor(
            "Export CPH Testcases",
            "Save testcases as portable CPH JSON.",
            "json",
        )
        val selected = FileChooserFactory.getInstance()
            .createSaveFileDialog(descriptor, project)
            .save(initialDirectory(), "testcases.json")
            ?: return
        service.exportTestCases(selected.file.toPath())
    }

    private fun chooseCheckerFile() {
        val selected = FileChooser.chooseFile(
            FileChooserDescriptorFactory.createSingleFileDescriptor("py").withTitle("Select Python Custom Checker"),
            this,
            project,
            initialVirtualFile(),
        )
        if (selected != null) {
            rendering = true
            checkerEnabled.isSelected = true
            checkerPath.text = selected.path
            rendering = false
            service.setCustomCheckerPath(checkerPath.text)
        } else if (checkerPath.text.isBlank()) {
            rendering = true
            checkerEnabled.isSelected = false
            rendering = false
        }
    }

    private fun saveCheckerPath() {
        if (!rendering && checkerEnabled.isSelected) {
            service.setCustomCheckerPath(checkerPath.text, publish = false)
        }
    }

    private fun confirmClearCases() {
        if (
            Messages.showYesNoDialog(
                project,
                "Clear every testcase and keep one blank case?",
                "Clear Testcases",
                Messages.getWarningIcon(),
            ) == Messages.YES
        ) {
            service.clearTestCases()
        }
    }

    private fun confirmResetProblem() {
        if (
            Messages.showYesNoDialog(
                project,
                "Delete this problem's .cph metadata? The source file will be kept, but the testcases cannot be recovered.",
                "Reset CPH Problem",
                Messages.getWarningIcon(),
            ) == Messages.YES
        ) {
            service.deleteProblemMetadata()
        }
    }

    private fun confirmKattisSubmission() {
        if (
            Messages.showYesNoDialog(
                project,
                "Run the local Kattis submission client for this source file? This may create a real submission.",
                "Submit to Kattis",
                Messages.getQuestionIcon(),
            ) == Messages.YES
        ) {
            service.submitToKattis()
        }
    }

    private fun initialDirectory(): Path = project.basePath?.let(Path::of)
        ?: Path.of(System.getProperty("user.dir"))

    private fun initialVirtualFile(): VirtualFile? = LocalFileSystem.getInstance()
        .findFileByNioFile(initialDirectory())

    private fun section(title: String, text: String): JPanel = JPanel(BorderLayout()).apply {
        border = BorderFactory.createTitledBorder(title)
        add(readOnlyArea(text, rows = 5), BorderLayout.CENTER)
        maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
    }
}

private class TestCasePanel(
    index: Int,
    testCase: TestCase,
    result: TestRunResult?,
    enabled: Boolean,
    customCheckerEnabled: Boolean,
    private val onChanged: (String, String) -> Unit,
    private val onPersist: () -> Unit,
    onRun: () -> Unit,
    onDelete: () -> Unit,
) : JPanel(BorderLayout()) {
    private val input = editableArea(testCase.input)
    private val expected = editableArea(testCase.output)
    private val saveTimer = Timer(300) { onPersist() }.apply { isRepeats = false }

    init {
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(JBColor.border()),
            JBUI.Borders.empty(4),
        )
        val verdict = result?.verdict ?: Verdict.PENDING
        add(
            JPanel(BorderLayout()).apply {
                add(
                    JBLabel("Case ${index + 1} — ${verdict.label}${durationSuffix(result)}").apply {
                        font = font.deriveFont(Font.BOLD)
                        foreground = verdictColor(verdict)
                    },
                    BorderLayout.WEST,
                )
                add(
                    JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply {
                        add(JButton("Run").apply {
                            isEnabled = enabled
                            addActionListener {
                                flushEdits(persist = false)
                                onRun()
                            }
                        })
                        add(JButton("Delete").apply {
                            isEnabled = enabled
                            addActionListener { onDelete() }
                        })
                    },
                    BorderLayout.EAST,
                )
            },
            BorderLayout.NORTH,
        )

        add(
            JPanel(GridLayout(0, 1, 0, 4)).apply {
                add(labeledArea("Input", input))
                if (!customCheckerEnabled) {
                    add(labeledArea("Expected output", expected))
                }
                if (result != null && result.verdict != Verdict.PENDING && result.verdict != Verdict.RUNNING) {
                    add(labeledArea("Result", readOnlyArea(formatResult(result), rows = 6)))
                }
            },
            BorderLayout.CENTER,
        )

        val listener = object : DocumentAdapter() {
            override fun textChanged(event: DocumentEvent) {
                onChanged(input.text, expected.text)
                saveTimer.restart()
            }
        }
        input.document.addDocumentListener(listener)
        expected.document.addDocumentListener(listener)
        maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
    }

    fun flushEdits(persist: Boolean = true) {
        saveTimer.stop()
        onChanged(input.text, expected.text)
        if (persist) {
            onPersist()
        }
    }

    private fun labeledArea(label: String, area: JBScrollPane): JPanel = JPanel(BorderLayout(0, 2)).apply {
        add(JBLabel(label), BorderLayout.NORTH)
        add(area, BorderLayout.CENTER)
    }

    companion object {
        private fun durationSuffix(result: TestRunResult?): String =
            result?.durationMs?.takeIf { it > 0 }?.let { " (${it} ms)" }.orEmpty()

        private fun formatResult(result: TestRunResult): String = buildString {
            if (result.stdout.isNotEmpty()) {
                append("stdout:\n")
                append(result.stdout)
                if (!result.stdout.endsWith('\n')) append('\n')
            }
            if (result.stderr.isNotEmpty()) {
                append("stderr:\n")
                append(result.stderr)
                if (!result.stderr.endsWith('\n')) append('\n')
            }
            if (result.checkerStdout.isNotEmpty()) {
                append("checker stdout:\n")
                append(result.checkerStdout)
                if (!result.checkerStdout.endsWith('\n')) append('\n')
            }
            if (result.checkerStderr.isNotEmpty()) {
                append("checker stderr:\n")
                append(result.checkerStderr)
                if (!result.checkerStderr.endsWith('\n')) append('\n')
            }
            if (result.differences.isNotEmpty()) {
                append("differences:\n")
                result.differences.take(100).forEach { difference ->
                    append("line ${difference.lineNumber}: expected=")
                    append(difference.expected ?: "<missing>")
                    append(", actual=")
                    append(difference.actual ?: "<missing>")
                    append('\n')
                }
            }
            if (isEmpty()) {
                append("Process exited with code ${result.exitCode ?: "unknown"}.")
            }
        }

        private fun verdictColor(verdict: Verdict): Color = when (verdict) {
            Verdict.ACCEPTED -> JBColor(0x2E7D32, 0x6AAB73)
            Verdict.WRONG_ANSWER,
            Verdict.RUNTIME_ERROR,
            Verdict.OUTPUT_LIMIT_EXCEEDED,
            Verdict.CHECKER_ERROR,
            Verdict.COMPILATION_ERROR,
            -> JBColor(0xC62828, 0xF75464)

            Verdict.TIME_LIMIT_EXCEEDED -> JBColor(0xEF6C00, 0xF2C55C)
            Verdict.CANCELLED,
            Verdict.UNSUPPORTED,
            -> JBColor.GRAY

            Verdict.RUNNING -> JBColor(0x1565C0, 0x548AF7)
            Verdict.PENDING -> JBColor.foreground()
        }
    }
}

private fun editableArea(text: String): JBScrollPane {
    val area = JBTextArea(text, 4, 40).apply {
        font = JBFont.create(Font(Font.MONOSPACED, Font.PLAIN, font.size))
        lineWrap = false
    }
    return JBScrollPane(area).apply {
        horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED
        verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
        putClientProperty(TEXT_AREA_PROPERTY, area)
    }
}

private fun readOnlyArea(text: String, rows: Int): JBScrollPane {
    val area = JBTextArea(text, rows, 40).apply {
        isEditable = false
        font = JBFont.create(Font(Font.MONOSPACED, Font.PLAIN, font.size))
        lineWrap = false
    }
    return JBScrollPane(area).apply {
        horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED
        verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
        putClientProperty(TEXT_AREA_PROPERTY, area)
    }
}

private val JBScrollPane.textArea: JBTextArea
    get() = getClientProperty(TEXT_AREA_PROPERTY) as JBTextArea

private val JBScrollPane.text: String
    get() = textArea.text

private val JBScrollPane.document: javax.swing.text.Document
    get() = textArea.document

private const val TEXT_AREA_PROPERTY = "cph.textArea"
