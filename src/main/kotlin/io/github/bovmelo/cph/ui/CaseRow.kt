package io.github.bovmelo.cph.ui

import com.intellij.icons.AllIcons
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import io.github.bovmelo.cph.model.TestCase
import io.github.bovmelo.cph.model.TestRunResult
import io.github.bovmelo.cph.model.Verdict
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Rectangle
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ScrollPaneConstants
import javax.swing.Timer
import javax.swing.event.DocumentEvent
import javax.swing.text.Highlighter
import javax.swing.text.JTextComponent

internal data class CaseModel(
    val index: Int,
    val testCase: TestCase,
    val result: TestRunResult?,
    val idle: Boolean,
    val customChecker: Boolean,
)

internal class CaseCallbacks(
    val onEdited: (id: Long, input: String, output: String) -> Unit,
    val onPersist: () -> Unit,
    val onToggleEnabled: (id: Long, enabled: Boolean) -> Unit,
    val onRun: (id: Long) -> Unit,
    val onDelete: (id: Long) -> Unit,
    val onSetExpected: (id: Long) -> Unit,
    val onExpandedChanged: (id: Long, expanded: Boolean) -> Unit,
)

/**
 * A flat list row for one testcase: a one-line header that is readable at a glance, and a body with the data
 * that opens on demand. Rows are reused between renders so expansion, scroll position and caret survive a run.
 */
internal class CaseRow(
    val id: Long,
    private val callbacks: CaseCallbacks,
) : JPanel(BorderLayout()) {
    private val header = Header()
    private val chevron = JBLabel()
    private val enabledBox = JBCheckBox()
    private val verdictIcon = JBLabel()
    private val title = JBLabel().apply { font = JBFont.label().asBold() }
    private val verdictLabel = JBLabel().apply { font = JBFont.small() }
    private val metrics = JBLabel().apply {
        font = JBFont.small()
        foreground = CphTheme.secondaryText
    }
    private val runButton = iconButton(AllIcons.Actions.Execute, "Run this testcase") { callbacks.onRun(id) }
    private val deleteLink = ActionLink("Delete testcase") { callbacks.onDelete(id) }

    private val detail = JBLabel().apply { font = JBFont.small() }
    private val input = DataArea(editable = true, emptyText = "Input for this case")
    private val expected = DataArea(editable = true, emptyText = "Expected output")
    private val output = DataArea(editable = false, emptyText = "No output")
    private val errors = DataArea(editable = false, emptyText = "")
    private val checker = DataArea(editable = false, emptyText = "")
    private val useOutput = ActionLink("Use this output as expected") { callbacks.onSetExpected(id) }

    private val inputSection = section("Input", input)
    private val expectedSection = section("Expected", expected, useOutput)
    private val outputSection = section("Output", output)
    private val errorSection = section("Standard error", errors)
    private val checkerSection = section("Checker", checker)
    private val body = JPanel(VerticalLayout(JBUI.scale(6))).apply {
        isOpaque = false
        border = JBUI.Borders.empty(2, 14, 10, 10)
        add(detail)
        add(inputSection)
        add(expectedSection)
        add(outputSection)
        add(errorSection)
        add(checkerSection)
        add(deleteLink)
    }

    private val saveTimer = Timer(300) { callbacks.onPersist() }.apply { isRepeats = false }
    private var expanded = false
    private var model: CaseModel? = null
    private var suppressEdits = false

    init {
        border = JBUI.Borders.customLine(CphTheme.divider, 0, 0, 1, 0)
        isOpaque = false

        header.layout = BorderLayout()
        header.border = JBUI.Borders.empty(0, 8, 0, 4)
        header.add(
            JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), JBUI.scale(4))).apply {
                isOpaque = false
                add(chevron)
                add(enabledBox)
                add(verdictIcon)
                add(title)
                add(verdictLabel)
            },
            BorderLayout.WEST,
        )
        header.add(
            JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(2), JBUI.scale(2))).apply {
                isOpaque = false
                add(metrics)
                add(runButton.component)
            },
            BorderLayout.EAST,
        )
        add(header, BorderLayout.NORTH)
        add(body, BorderLayout.CENTER)

        header.addComponentListener(object : java.awt.event.ComponentAdapter() {
            override fun componentResized(event: java.awt.event.ComponentEvent) {
                metrics.isVisible = header.width >= JBUI.scale(300)
            }
        })
        enabledBox.toolTipText = "Include this testcase when running all"
        enabledBox.addActionListener { callbacks.onToggleEnabled(id, enabledBox.isSelected) }
        listOf(header, chevron, verdictIcon, title, verdictLabel, metrics).forEach { it.onClick(::toggle) }

        val edited = object : DocumentAdapter() {
            override fun textChanged(event: DocumentEvent) {
                if (suppressEdits) return
                input.fitRows()
                expected.fitRows()
                callbacks.onEdited(id, input.text, expected.text)
                saveTimer.restart()
            }
        }
        input.area.document.addDocumentListener(edited)
        expected.area.document.addDocumentListener(edited)
        setExpanded(false, notify = false)
    }

    fun isExpanded(): Boolean = expanded

    fun setExpanded(value: Boolean, notify: Boolean = true) {
        expanded = value
        body.isVisible = value
        chevron.icon = if (value) AllIcons.General.ChevronDown else AllIcons.General.ChevronRight
        revalidate()
        repaint()
        if (notify) callbacks.onExpandedChanged(id, value)
    }

    /** Writes pending edits to the model before a run so the saved file never lags behind the screen. */
    fun flushEdits() {
        saveTimer.stop()
        callbacks.onEdited(id, input.text, expected.text)
    }

    fun update(newModel: CaseModel) {
        model = newModel
        val testCase = newModel.testCase
        val result = newModel.result
        val verdict = result?.verdict ?: Verdict.PENDING
        val style = CphTheme.style(verdict)
        val enabledCase = testCase.enabled

        title.text = "Case ${newModel.index + 1}"
        title.foreground = if (enabledCase) null else CphTheme.secondaryText
        verdictIcon.icon = style.icon
        verdictLabel.text = if (!enabledCase) "Disabled" else if (verdict == Verdict.PENDING) "" else CphTheme.shortLabel(verdict)
        verdictLabel.foreground = if (enabledCase) CphTheme.color(style.tone) else CphTheme.secondaryText
        header.accent = if (enabledCase && verdict != Verdict.PENDING) CphTheme.color(style.tone) else null
        metrics.text = listOfNotNull(
            result?.durationMs?.takeIf { it > 0 }?.let(::formatDuration),
            result?.peakMemoryKb?.let(::formatMemory),
        ).joinToString("   ")
        enabledBox.isSelected = enabledCase
        enabledBox.isEnabled = newModel.idle
        runButton.setActive(newModel.idle)
        deleteLink.isEnabled = newModel.idle

        suppressEdits = true
        try {
            input.setTextIfIdle(testCase.input)
            expected.setTextIfIdle(testCase.output)
        } finally {
            suppressEdits = false
        }
        input.area.isEditable = newModel.idle
        expected.area.isEditable = newModel.idle

        val finished = result != null && verdict != Verdict.PENDING && verdict != Verdict.RUNNING
        expectedSection.isVisible = !newModel.customChecker
        useOutput.isVisible = finished && verdict == Verdict.WRONG_ANSWER && !newModel.customChecker && newModel.idle
        detail.text = result?.takeIf { finished }?.let(::failureDetail).orEmpty()
        detail.foreground = CphTheme.color(style.tone)
        detail.isVisible = detail.text.isNotBlank()

        outputSection.isVisible = finished && verdict != Verdict.COMPILATION_ERROR && verdict != Verdict.UNSUPPORTED
        output.setTextIfIdle(result?.stdout.orEmpty().takeIf { finished }.orEmpty())
        val stderr = result?.stderr.orEmpty()
        errorSection.isVisible = finished && stderr.isNotBlank() && verdict != Verdict.COMPILATION_ERROR
        errors.setTextIfIdle(stderr)
        val checkerText = listOf(result?.checkerStdout.orEmpty(), result?.checkerStderr.orEmpty())
            .filter(String::isNotBlank)
            .joinToString("\n")
        checkerSection.isVisible = finished && checkerText.isNotBlank()
        checker.setTextIfIdle(checkerText)

        val differences = result?.differences.orEmpty().takeIf { finished && verdict == Verdict.WRONG_ANSWER }.orEmpty()
        expected.highlight(highlightedLines(testCase.output, differences, expectedSide = true))
        output.highlight(highlightedLines(result?.stdout.orEmpty(), differences, expectedSide = false))
        revalidate()
        repaint()
    }

    private fun toggle() = setExpanded(!expanded)

    private fun JComponent.onClick(action: () -> Unit) {
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(event: MouseEvent) = action()
        })
    }

    private fun section(label: String, area: DataArea, trailing: JComponent? = null): JPanel =
        JPanel(BorderLayout(0, JBUI.scale(2))).apply {
            isOpaque = false
            add(
                JPanel(BorderLayout()).apply {
                    isOpaque = false
                    add(JBLabel(label).apply {
                        font = JBFont.small()
                        foreground = CphTheme.secondaryText
                    }, BorderLayout.WEST)
                    trailing?.let { add(it, BorderLayout.EAST) }
                },
                BorderLayout.NORTH,
            )
            add(area, BorderLayout.CENTER)
        }

    /** Header row with a hover wash and a thin verdict-coloured bar on its leading edge. */
    private inner class Header : JPanel() {
        var accent: Color? = null
            set(value) {
                field = value
                repaint()
            }
        private var hovered = false

        init {
            isOpaque = false
            cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
            addMouseListener(object : MouseAdapter() {
                override fun mouseEntered(event: MouseEvent) {
                    hovered = true
                    repaint()
                }

                override fun mouseExited(event: MouseEvent) {
                    hovered = false
                    repaint()
                }
            })
        }

        override fun getPreferredSize(): Dimension = super.getPreferredSize().also {
            it.height = it.height.coerceAtLeast(JBUI.scale(30))
        }

        override fun paintComponent(graphics: Graphics) {
            if (hovered) {
                graphics.color = JBUI.CurrentTheme.List.Hover.background(true)
                graphics.fillRect(0, 0, width, height)
            }
            accent?.let {
                graphics.color = it
                graphics.fillRect(0, 0, JBUI.scale(3), height)
            }
            super.paintComponent(graphics)
        }
    }
}

/** A scrollable, monospaced text box sized to its content between two and eight lines. */
internal class DataArea(editable: Boolean, emptyText: String) : JBScrollPane() {
    val area = JBTextArea().apply {
        font = CphTheme.dataFont()
        isEditable = editable
        lineWrap = false
        margin = JBUI.insets(2, 4)
        if (emptyText.isNotEmpty()) this.emptyText.text = emptyText
    }
    private val painters = mutableListOf<Any>()

    val text: String get() = area.text

    init {
        setViewportView(area)
        border = JBUI.Borders.customLine(CphTheme.divider, 1)
        horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED
        verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
        fitRows()
    }

    /** Replaces the text unless the user is typing here, so a re-render never moves the caret. */
    fun setTextIfIdle(value: String) {
        if (area.text != value && !area.hasFocus()) {
            area.text = value
            area.caretPosition = 0
        }
        fitRows()
    }

    fun fitRows() {
        val lines = area.lineCount.coerceIn(MIN_ROWS, MAX_ROWS)
        if (area.rows != lines) {
            area.rows = lines
            revalidate()
        }
    }

    fun highlight(lines: List<Int>) {
        val highlighter = area.highlighter
        painters.forEach { highlighter.removeHighlight(it) }
        painters.clear()
        lines.distinct().forEach { line ->
            if (line in 0 until area.lineCount) {
                runCatching {
                    painters += highlighter.addHighlight(
                        area.getLineStartOffset(line),
                        area.getLineEndOffset(line).coerceAtLeast(area.getLineStartOffset(line)),
                        FullLinePainter,
                    )
                }
            }
        }
    }

    private object FullLinePainter : Highlighter.HighlightPainter {
        override fun paint(g: Graphics, p0: Int, p1: Int, bounds: java.awt.Shape, c: JTextComponent) {
            val start = runCatching { c.modelToView2D(p0) }.getOrNull() ?: return
            g.color = CphTheme.wash(VerdictTone.FAIL, 0.18)
            val clip: Rectangle = bounds.bounds
            g.fillRect(0, start.y.toInt(), maxOf(c.width, clip.x + clip.width), start.height.toInt())
        }
    }

    private companion object {
        const val MIN_ROWS = 2
        const val MAX_ROWS = 8
    }
}
