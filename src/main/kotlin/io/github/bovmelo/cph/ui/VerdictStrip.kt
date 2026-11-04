package io.github.bovmelo.cph.ui

import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.RoundRectangle2D
import javax.swing.JComponent

/** One segment per testcase, coloured by verdict: the whole run can be read in a single glance. */
internal class VerdictStrip : JComponent() {
    data class Segment(val id: Long, val label: String, val tone: VerdictTone, val enabled: Boolean)

    var onSegmentClick: (Long) -> Unit = {}

    private var segments: List<Segment> = emptyList()

    init {
        isOpaque = false
        toolTipText = ""
        val mouse = object : MouseAdapter() {
            override fun mouseClicked(event: MouseEvent) {
                segmentAt(event.x)?.let { onSegmentClick(it.id) }
            }

            override fun mouseMoved(event: MouseEvent) {
                cursor = if (segmentAt(event.x) != null) Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) else Cursor.getDefaultCursor()
            }
        }
        addMouseListener(mouse)
        addMouseMotionListener(mouse)
    }

    fun update(newSegments: List<Segment>) {
        if (newSegments != segments) {
            segments = newSegments
            repaint()
        }
    }

    override fun getPreferredSize(): Dimension = Dimension(JBUI.scale(120), JBUI.scale(8))

    override fun getMinimumSize(): Dimension = Dimension(JBUI.scale(40), JBUI.scale(8))

    override fun getToolTipText(event: MouseEvent): String? = segmentAt(event.x)?.label

    private fun layoutMetrics(): Pair<Double, Double> {
        val gap = JBUI.scale(2).toDouble()
        val count = segments.size.coerceAtLeast(1)
        val width = ((this.width - gap * (count - 1)) / count).coerceAtLeast(1.0)
        return width to gap
    }

    private fun segmentAt(x: Int): Segment? {
        if (segments.isEmpty()) return null
        val (width, gap) = layoutMetrics()
        val index = (x / (width + gap)).toInt()
        return segments.getOrNull(index)
    }

    override fun paintComponent(graphics: Graphics) {
        if (segments.isEmpty()) return
        val g = graphics.create() as Graphics2D
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val (width, gap) = layoutMetrics()
            val arc = JBUI.scale(3).toDouble()
            segments.forEachIndexed { index, segment ->
                val x = index * (width + gap)
                val shape = RoundRectangle2D.Double(x, 0.0, width, height.toDouble(), arc, arc)
                val color = CphTheme.color(segment.tone)
                if (segment.tone == VerdictTone.NEUTRAL || !segment.enabled) {
                    g.color = CphTheme.divider
                    g.fill(shape)
                    if (!segment.enabled) {
                        g.color = UIUtil.getPanelBackground()
                        g.fill(
                            RoundRectangle2D.Double(
                                x + 1,
                                1.0,
                                (width - 2).coerceAtLeast(0.0),
                                (height - 2).toDouble(),
                                arc,
                                arc,
                            ),
                        )
                    }
                } else {
                    g.color = color
                    g.fill(shape)
                }
            }
        } finally {
            g.dispose()
        }
    }
}
