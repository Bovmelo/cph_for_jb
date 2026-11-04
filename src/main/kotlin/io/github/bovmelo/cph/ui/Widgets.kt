package io.github.bovmelo.cph.ui

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.impl.ActionButton
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import java.awt.Dimension
import java.awt.FontMetrics
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.Rectangle
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.Scrollable
import javax.swing.SwingConstants

internal class IconButtonHandle(val component: JComponent, private val presentation: Presentation) {
    fun setActive(active: Boolean) {
        if (presentation.isEnabled != active) {
            presentation.isEnabled = active
            component.repaint()
        }
    }
}

/** A native toolbar-style icon button, usable outside an action toolbar. */
internal fun iconButton(icon: Icon, tooltip: String, onClick: () -> Unit): IconButtonHandle {
    val action = object : DumbAwareAction(tooltip, null, icon) {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun update(event: AnActionEvent) = Unit

        override fun actionPerformed(event: AnActionEvent) = onClick()
    }
    val presentation = action.templatePresentation.clone()
    val size = Dimension(JBUI.scale(22), JBUI.scale(22))
    return IconButtonHandle(ActionButton(action, presentation, "CphRow", size), presentation)
}

/** A small rounded label for a fact about the problem, such as its time limit. */
internal class Chip(text: String, private val icon: Icon? = null) : JComponent() {
    private var label = text

    var text: String
        get() = label
        set(value) {
            label = value
            revalidate()
            repaint()
        }

    init {
        isOpaque = false
        font = JBFont.small()
    }

    private fun metrics(): FontMetrics = getFontMetrics(font)

    override fun getPreferredSize(): Dimension {
        val iconWidth = icon?.let { it.iconWidth + JBUI.scale(4) } ?: 0
        return Dimension(
            metrics().stringWidth(label) + iconWidth + JBUI.scale(14),
            metrics().height + JBUI.scale(6),
        )
    }

    override fun paintComponent(graphics: Graphics) {
        val g = graphics.create() as Graphics2D
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val arc = JBUI.scale(8)
            g.color = CphTheme.divider
            g.drawRoundRect(0, 0, width - 1, height - 1, arc, arc)
            var x = JBUI.scale(7)
            icon?.let {
                it.paintIcon(this, g, x, (height - it.iconHeight) / 2)
                x += it.iconWidth + JBUI.scale(4)
            }
            g.font = font
            g.color = foreground ?: CphTheme.secondaryText
            g.drawString(label, x, (height + metrics().ascent - metrics().descent) / 2)
        } finally {
            g.dispose()
        }
    }
}

/** A vertical stack that follows the width of the scroll pane, so nothing scrolls sideways. */
internal class ScrollableColumn : JPanel(), Scrollable {
    override fun getPreferredScrollableViewportSize(): Dimension = preferredSize

    override fun getScrollableUnitIncrement(visibleRect: Rectangle, orientation: Int, direction: Int): Int =
        JBUI.scale(16)

    override fun getScrollableBlockIncrement(visibleRect: Rectangle, orientation: Int, direction: Int): Int =
        if (orientation == SwingConstants.VERTICAL) visibleRect.height - JBUI.scale(16) else visibleRect.width

    override fun getScrollableTracksViewportWidth(): Boolean = true

    override fun getScrollableTracksViewportHeight(): Boolean = false
}
