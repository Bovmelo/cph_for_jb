package io.github.bovmelo.cph.ui

import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.components.service
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.ui.AsyncProcessIcon
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import io.github.bovmelo.cph.model.CphUiState
import io.github.bovmelo.cph.model.Problem
import io.github.bovmelo.cph.model.RunPhase
import io.github.bovmelo.cph.model.Verdict
import io.github.bovmelo.cph.project.CphProjectService
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Rectangle
import java.nio.file.Path
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ScrollPaneConstants
import javax.swing.SwingUtilities

/**
 * The CPH tool window. Everything the contestant needs while solving sits in three layers:
 * a control row, a result strip that shows the whole run at once, and a list of testcases.
 * Rare actions live in the overflow menu instead of competing for space.
 */
class CphToolWindowPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {
    private val service = project.service<CphProjectService>()

    private val runButton = object : JButton("Run", AllIcons.Actions.Execute) {
        override fun isDefaultButton(): Boolean = true
    }.apply { toolTipText = "Run the enabled testcases (Ctrl+Alt+B)" }

    private val titleLabel = JBLabel().apply { font = JBFont.label().asBold().biggerOn(1f) }
    private val groupLabel = JBLabel().apply {
        font = JBFont.small()
        foreground = CphTheme.secondaryText
    }
    private val timeChip = Chip("", AllIcons.Actions.ProfileCPU)
    private val memoryChip = Chip("")
    private val checkerChip = Chip("", AllIcons.General.Gear)
    private val openLink = ActionLink("Problem page") { service.currentState().problem?.url?.let(BrowserUtil::browse) }
    private val chips = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
        isOpaque = false
        add(timeChip)
        add(memoryChip)
        add(checkerChip)
        add(openLink)
    }
    private val problemHeader = JPanel(VerticalLayout(JBUI.scale(2))).apply {
        isOpaque = false
        add(titleLabel)
        add(groupLabel)
        add(chips)
    }

    private val strip = VerdictStrip()
    private val summaryLabel = JBLabel().apply { font = JBFont.small() }
    private val totalsLabel = JBLabel().apply {
        font = JBFont.small()
        foreground = CphTheme.secondaryText
    }
    private val resultBlock = JPanel(VerticalLayout(JBUI.scale(4))).apply {
        isOpaque = false
        add(strip)
        add(
            JPanel(BorderLayout()).apply {
                isOpaque = false
                add(summaryLabel, BorderLayout.WEST)
                add(totalsLabel, BorderLayout.EAST)
            },
        )
    }

    private val compileBanner = OutputBanner("Compiler output")
    private val rowsColumn = ScrollableColumn().apply { layout = VerticalLayout(0) }
    private val emptyState = createEmptyState()
    private val cards = CardLayout()
    private val center = JPanel(cards)

    private val busyIcon = AsyncProcessIcon("CPH busy").apply { isVisible = false }
    private val statusLabel = JBLabel().apply {
        font = JBFont.small()
        foreground = CphTheme.secondaryText
    }

    private val rows = linkedMapOf<Long, CaseRow>()
    private val expandedIds = mutableSetOf<Long>()
    private val autoOpenedIds = mutableSetOf<Long>()
    private var loadedProblem: String? = null
    private lateinit var leftToolbar: com.intellij.openapi.actionSystem.ActionToolbar
    private lateinit var moreToolbar: com.intellij.openapi.actionSystem.ActionToolbar

    private val caseCallbacks = CaseCallbacks(
        onEdited = { id, input, output -> service.updateTestCase(id, input, output) },
        onPersist = service::persistTestCases,
        onToggleEnabled = { id, enabled -> service.setTestCaseEnabled(id, enabled) },
        onRun = { id ->
            rows.values.forEach(CaseRow::flushEdits)
            service.runSingle(id)
        },
        onDelete = { id -> service.deleteTestCase(id) },
        onSetExpected = { id -> service.setExpectedFromResult(id) },
        onChooseInputFile = ::chooseInputFile,
        onSetInputFile = { id, path -> service.setTestCaseInputFile(id, path) },
        onExpandedChanged = { id, expanded -> if (expanded) expandedIds += id else expandedIds -= id },
    )

    init {
        border = JBUI.Borders.empty()
        add(createTop(), BorderLayout.NORTH)

        center.add(JBScrollPane(rowsColumn).apply {
            border = JBUI.Borders.customLine(CphTheme.divider, 1, 0, 0, 0)
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        }, LIST_CARD)
        center.add(emptyState, EMPTY_CARD)
        add(center, BorderLayout.CENTER)
        add(createStatusBar(), BorderLayout.SOUTH)

        runButton.addActionListener { runAll() }
        strip.onSegmentClick = ::reveal

        service.addListener(this, ::render)
    }

    override fun dispose() {
        busyIcon.dispose()
    }

    private fun runAll() {
        rows.values.forEach(CaseRow::flushEdits)
        service.runAll()
    }

    private fun createTop(): JComponent {
        val leftGroup = DefaultActionGroup(
            action("Stop", AllIcons.Actions.Suspend, "Stop the current run") { service.stop() }
                .enabledWhen { service.currentState().phase != RunPhase.IDLE },
            action("Add testcase", AllIcons.General.Add, "Add an empty testcase") { service.addTestCase() }
                .enabledWhen { hasProblem() && isIdle() },
            action("Stress test", AllIcons.Actions.Lightning, "Compare with a brute force on generated input") {
                configureAndRunStress()
            }.enabledWhen { hasProblem() && isIdle() },
        )
        leftToolbar = ActionManager.getInstance().createActionToolbar("CphToolbar", leftGroup, true).apply {
            targetComponent = this@CphToolWindowPanel
            setMinimumButtonSize(Dimension(JBUI.scale(26), JBUI.scale(26)))
        }
        val moreGroup = DefaultActionGroup().apply {
            isPopup = true
            templatePresentation.icon = AllIcons.Actions.More
            templatePresentation.text = "More"
            addAll(moreActions())
        }
        moreToolbar = ActionManager.getInstance()
            .createActionToolbar("CphToolbarMore", DefaultActionGroup(moreGroup), true).apply {
                targetComponent = this@CphToolWindowPanel
                setMinimumButtonSize(Dimension(JBUI.scale(26), JBUI.scale(26)))
            }

        val controls = JPanel(BorderLayout(JBUI.scale(6), 0)).apply {
            isOpaque = false
            add(runButton, BorderLayout.WEST)
            add(leftToolbar.component.apply { isOpaque = false }, BorderLayout.CENTER)
            add(moreToolbar.component.apply { isOpaque = false }, BorderLayout.EAST)
        }
        return JPanel(VerticalLayout(JBUI.scale(10))).apply {
            border = JBUI.Borders.empty(8, 10, 10, 6)
            add(controls)
            add(problemHeader)
            add(resultBlock)
        }
    }

    private fun createStatusBar(): JComponent = JPanel(BorderLayout(JBUI.scale(6), 0)).apply {
        border = JBUI.Borders.compound(
            JBUI.Borders.customLine(CphTheme.divider, 1, 0, 0, 0),
            JBUI.Borders.empty(4, 10),
        )
        add(busyIcon, BorderLayout.WEST)
        add(statusLabel, BorderLayout.CENTER)
    }

    private fun createEmptyState(): JComponent {
        val content = JPanel(VerticalLayout(JBUI.scale(8))).apply {
            isOpaque = false
            add(JBLabel("No problem yet").apply { font = JBFont.h3() })
            add(
                JBLabel(
                    "<html>Open a solution file and create testcases for it,<br>" +
                        "or send a problem here with Competitive Companion.</html>",
                ).apply { foreground = CphTheme.secondaryText },
            )
            add(
                JButton("Create testcases for this file").apply {
                    addActionListener { runAll() }
                    putClientProperty(CREATE_BUTTON_KEY, true)
                },
            )
            add(JBLabel("Shortcut: Ctrl+Alt+B").apply {
                font = JBFont.small()
                foreground = CphTheme.secondaryText
            })
        }
        return JPanel(GridBagLayout()).apply {
            add(content, GridBagConstraints())
            border = JBUI.Borders.empty(24)
        }
    }

    private fun moreActions(): List<AnAction> = listOf(
        action("Import testcases…", AllIcons.Actions.Download, null) { chooseImportFile() }
            .enabledWhen { hasProblem() && isIdle() },
        action("Export testcases…", AllIcons.ToolbarDecorator.Export, null) { chooseExportFile() }
            .enabledWhen { hasProblem() && isIdle() },
        Separator.getInstance(),
        flag("Define ONLINE_JUDGE", isOn = { service.isOnlineJudgeEnabled() }) { service.setOnlineJudge(it) },
        flag("Compile with -O2 (C/C++)", isOn = { service.isOptimizeO2Enabled() }) { service.setOptimizeO2(it) },
        flag("Compile with -fsanitize=address,undefined (C/C++)", isOn = { service.isSanitizersEnabled() }) {
            service.setSanitizers(it)
        },
        flag("Use a custom checker", isOn = { !currentProblem()?.customCheckerPath.isNullOrBlank() }) { on ->
            if (on) chooseCheckerFile() else service.setCustomCheckerPath("")
        }.enabledWhen { hasProblem() && isIdle() },
        Separator.getInstance(),
        action("Clear all testcases…", null, null) { confirmClearCases() }
            .enabledWhen { hasProblem() && isIdle() },
        action("Reset problem…", null, null) { confirmResetProblem() }
            .enabledWhen { hasProblem() && isIdle() },
        Separator.getInstance(),
        action("Settings", AllIcons.General.GearPlain, null) {
            ShowSettingsUtil.getInstance().showSettingsDialog(project, "CPH")
        },
    )

    private fun currentProblem(): Problem? = service.currentState().problem

    private fun hasProblem(): Boolean = currentProblem() != null

    private fun isIdle(): Boolean = service.currentState().phase == RunPhase.IDLE

    internal fun refreshToolbarsNow() {
        leftToolbar.updateActionsImmediately()
        moreToolbar.updateActionsImmediately()
    }

    internal fun render(state: CphUiState) {
        val idle = state.phase == RunPhase.IDLE
        val problem = state.problem
        runButton.isEnabled = CphUiActionPolicy.canRunTestCases(state.phase, service.hasSupportedActiveFile())
        leftToolbar.updateActionsAsync()
        moreToolbar.updateActionsAsync()

        statusLabel.text = state.statusMessage
        statusLabel.toolTipText = state.statusMessage
        busyIcon.isVisible = !idle
        if (idle) busyIcon.suspend() else busyIcon.resume()

        if (problem == null) {
            loadedProblem = null
            rows.clear()
            rowsColumn.removeAll()
            problemHeader.isVisible = false
            resultBlock.isVisible = false
            cards.show(center, EMPTY_CARD)
            return
        }
        cards.show(center, LIST_CARD)
        problemHeader.isVisible = true
        resultBlock.isVisible = true
        renderProblemHeader(problem)
        renderResults(problem, state)

        compileBanner.show(
            text = state.compileOutput,
            isError = state.results.values.any { it.verdict == Verdict.COMPILATION_ERROR },
        )
        reconcileRows(problem, state, idle)
        revalidate()
        repaint()
    }

    private fun renderProblemHeader(problem: Problem) {
        titleLabel.text = problem.name
        groupLabel.text = problem.group
        groupLabel.isVisible = problem.group.isNotBlank() && problem.group != "local"
        timeChip.text = formatDuration(problem.timeLimit)
        memoryChip.text = "${problem.memoryLimit} MB"
        // A local problem has no real limits, only the defaults, so showing them would mislead.
        timeChip.isVisible = !problem.local
        memoryChip.isVisible = !problem.local
        val checker = problem.customCheckerPath?.takeIf { it.isNotBlank() }
        checkerChip.isVisible = checker != null
        checkerChip.text = checker?.let { "Checker: ${Path.of(it).fileName}" }.orEmpty()
        openLink.isVisible = problem.url.startsWith("http")
    }

    private fun renderResults(problem: Problem, state: CphUiState) {
        strip.update(
            problem.tests.mapIndexed { index, testCase ->
                val verdict = state.results[testCase.id]?.verdict ?: Verdict.PENDING
                VerdictStrip.Segment(
                    id = testCase.id,
                    label = "Case ${index + 1}: ${if (testCase.enabled) verdict.label else "Disabled"}",
                    tone = CphTheme.style(verdict).tone,
                    enabled = testCase.enabled,
                )
            },
        )
        val summary = RunSummary.of(problem.tests, state.results)
        summaryLabel.text = summary.headline()
        summaryLabel.foreground = when {
            summary.failed > 0 -> CphTheme.color(VerdictTone.FAIL)
            summary.enabled > 0 && summary.passed == summary.enabled -> CphTheme.color(VerdictTone.PASS)
            else -> CphTheme.secondaryText
        }
        totalsLabel.text = listOfNotNull(
            summary.totalMs.takeIf { it > 0 }?.let(::formatDuration),
            summary.peakMemoryKb?.let(::formatMemory),
        ).joinToString("   ")
    }

    private fun reconcileRows(problem: Problem, state: CphUiState, idle: Boolean) {
        val firstRender = loadedProblem != problem.sourcePath
        if (firstRender) {
            loadedProblem = problem.sourcePath
            rows.clear()
            expandedIds.clear()
            autoOpenedIds.clear()
        }
        val presentIds = problem.tests.map { it.id }.toSet()
        rows.keys.retainAll(presentIds)
        expandedIds.retainAll(presentIds)

        val customChecker = !problem.customCheckerPath.isNullOrBlank()
        problem.tests.forEachIndexed { index, testCase ->
            val isNew = testCase.id !in rows
            val row = rows.getOrPut(testCase.id) { CaseRow(testCase.id, caseCallbacks) }
            if (isNew) {
                // Blank cases are waiting for input; a case added after load is one the user just asked for.
                if (!firstRender || (testCase.input.isBlank() && testCase.inputFile.isBlank() && testCase.output.isBlank())) {
                    expandedIds += testCase.id
                }
            }
            val result = state.results[testCase.id]
            val verdict = result?.verdict ?: Verdict.PENDING
            if (CphTheme.isFailure(verdict) && autoOpenedIds.add(testCase.id)) {
                expandedIds += testCase.id
            } else if (verdict == Verdict.PENDING) {
                autoOpenedIds -= testCase.id
            }
            row.update(CaseModel(index, testCase, result, idle, customChecker))
            row.setExpanded(testCase.id in expandedIds, notify = false)
        }

        val ordered = listOf<JComponent>(compileBanner) + problem.tests.mapNotNull { rows[it.id] }
        val current = rowsColumn.components.toList()
        if (current != ordered) {
            rowsColumn.removeAll()
            ordered.forEach(rowsColumn::add)
        }
    }

    private fun reveal(id: Long) {
        val row = rows[id] ?: return
        expandedIds += id
        row.setExpanded(true, notify = false)
        SwingUtilities.invokeLater {
            row.scrollRectToVisible(Rectangle(0, 0, row.width, row.height))
        }
    }

    // Menu and dialog plumbing ------------------------------------------------------------------------------

    private fun action(text: String, icon: javax.swing.Icon?, description: String?, perform: () -> Unit) =
        PanelAction(text, description, icon, perform)

    private fun flag(text: String, isOn: () -> Boolean, change: (Boolean) -> Unit) = PanelFlag(text, isOn, change)

    private fun configureAndRunStress() {
        val problem = currentProblem() ?: return
        val dialog = StressDialog(
            project,
            problem.stressGeneratorPath.orEmpty(),
            problem.stressBrutePath.orEmpty(),
        )
        if (dialog.showAndGet()) {
            rows.values.forEach(CaseRow::flushEdits)
            service.setStressFiles(dialog.generatorPath, dialog.brutePath)
            service.runStress(dialog.iterations)
        }
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

    private fun chooseInputFile(): String? = FileChooser.chooseFile(
        FileChooserDescriptorFactory.createSingleFileDescriptor().withTitle("Choose Input File"),
        this,
        project,
        initialVirtualFile(),
    )?.path

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
        ) ?: return
        service.setCustomCheckerPath(selected.path)
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

    private fun initialDirectory(): Path = project.basePath?.let(Path::of)
        ?: Path.of(System.getProperty("user.dir"))

    private fun initialVirtualFile(): VirtualFile? = LocalFileSystem.getInstance()
        .findFileByNioFile(initialDirectory())

    private companion object {
        const val LIST_CARD = "list"
        const val EMPTY_CARD = "empty"
        const val CREATE_BUTTON_KEY = "cph.createButton"
    }
}

/** A menu or toolbar entry whose enabled state is read from the live service state. */
internal class PanelAction(
    text: String,
    description: String?,
    icon: javax.swing.Icon?,
    private val perform: () -> Unit,
) : DumbAwareAction(text, description, icon) {
    private var enabledCheck: () -> Boolean = { true }

    fun enabledWhen(check: () -> Boolean): PanelAction = apply { enabledCheck = check }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(event: AnActionEvent) {
        event.presentation.isEnabled = enabledCheck()
    }

    override fun actionPerformed(event: AnActionEvent) = perform()
}

internal class PanelFlag(
    text: String,
    private val isOn: () -> Boolean,
    private val change: (Boolean) -> Unit,
) : ToggleAction(text), DumbAware {
    private var enabledCheck: () -> Boolean = { true }

    fun enabledWhen(check: () -> Boolean): PanelFlag = apply { enabledCheck = check }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(event: AnActionEvent) {
        super.update(event)
        event.presentation.isEnabled = enabledCheck()
    }

    override fun isSelected(event: AnActionEvent): Boolean = isOn()

    override fun setSelected(event: AnActionEvent, state: Boolean) = change(state)
}

/** A collapsible block for compiler output, tinted red when it holds an error. */
internal class OutputBanner(private val title: String) : JPanel(VerticalLayout(JBUI.scale(4))) {
    private val toggle = ActionLink(title) { setOpen(!open) }
    private val area = DataArea(editable = false, emptyText = "")
    private var open = false
    private var errorSeen = false

    init {
        isOpaque = false
        border = JBUI.Borders.customLine(CphTheme.divider, 0, 0, 1, 0)
        add(toggle)
        add(area)
        isVisible = false
        area.isVisible = false
    }

    fun show(text: String, isError: Boolean) {
        isVisible = text.isNotBlank()
        if (!isVisible) {
            errorSeen = false
            return
        }
        area.setTextIfIdle(text)
        toggle.icon = if (isError) AllIcons.RunConfigurations.TestError else null
        toggle.text = if (isError) "$title: errors" else title
        // Errors open themselves once; after that the user's choice sticks.
        if (isError && !errorSeen) setOpen(true)
        errorSeen = isError
        border = JBUI.Borders.compound(
            JBUI.Borders.customLine(CphTheme.divider, 0, 0, 1, 0),
            JBUI.Borders.empty(6, 10),
        )
    }

    private fun setOpen(value: Boolean) {
        open = value
        area.isVisible = value
        revalidate()
        repaint()
    }
}
