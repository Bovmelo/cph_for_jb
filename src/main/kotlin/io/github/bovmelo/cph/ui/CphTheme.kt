package io.github.bovmelo.cph.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.NamedColorUtil
import io.github.bovmelo.cph.model.Verdict
import java.awt.Color
import java.awt.Font
import javax.swing.Icon

/** Verdict colours carry meaning, so they stay semantic and are always paired with a distinct icon. */
internal enum class VerdictTone { PASS, FAIL, LIMIT, ACTIVE, NEUTRAL }

internal data class VerdictStyle(val icon: Icon, val tone: VerdictTone)

internal object CphTheme {
    private val pass = JBColor(0x2E7D32, 0x6AAB73)
    private val fail = JBColor(0xC62828, 0xF75464)
    private val limit = JBColor(0xB26A00, 0xF2C55C)
    private val active = JBColor(0x1565C0, 0x548AF7)

    val secondaryText: Color = JBColor.lazy { NamedColorUtil.getInactiveTextColor() }
    val divider: Color = JBColor.lazy { JBColor.border() }

    fun color(tone: VerdictTone): Color = when (tone) {
        VerdictTone.PASS -> pass
        VerdictTone.FAIL -> fail
        VerdictTone.LIMIT -> limit
        VerdictTone.ACTIVE -> active
        VerdictTone.NEUTRAL -> secondaryText
    }

    /** A translucent wash of [tone] used behind banners and highlighted diff lines. */
    fun wash(tone: VerdictTone, alpha: Double = 0.12): Color = JBColor.lazy {
        val base = color(tone)
        Color(base.red, base.green, base.blue, (alpha * 255).toInt().coerceIn(0, 255))
    }

    fun style(verdict: Verdict): VerdictStyle = when (verdict) {
        Verdict.ACCEPTED -> VerdictStyle(AllIcons.RunConfigurations.TestPassed, VerdictTone.PASS)
        Verdict.WRONG_ANSWER -> VerdictStyle(AllIcons.RunConfigurations.TestFailed, VerdictTone.FAIL)
        Verdict.RUNTIME_ERROR,
        Verdict.CHECKER_ERROR,
        Verdict.COMPILATION_ERROR,
        -> VerdictStyle(AllIcons.RunConfigurations.TestError, VerdictTone.FAIL)

        Verdict.TIME_LIMIT_EXCEEDED,
        Verdict.OUTPUT_LIMIT_EXCEEDED,
        -> VerdictStyle(AllIcons.General.Warning, VerdictTone.LIMIT)

        Verdict.RUNNING -> VerdictStyle(AllIcons.Actions.Execute, VerdictTone.ACTIVE)
        Verdict.UNSUPPORTED -> VerdictStyle(AllIcons.RunConfigurations.TestIgnored, VerdictTone.NEUTRAL)
        Verdict.CANCELLED -> VerdictStyle(AllIcons.RunConfigurations.TestTerminated, VerdictTone.NEUTRAL)
        Verdict.PENDING -> VerdictStyle(AllIcons.RunConfigurations.TestNotRan, VerdictTone.NEUTRAL)
    }

    /** Short enough that the header row still fits next to timing in a narrow tool window. */
    fun shortLabel(verdict: Verdict): String = when (verdict) {
        Verdict.TIME_LIMIT_EXCEEDED -> "Time Limit"
        Verdict.OUTPUT_LIMIT_EXCEEDED -> "Output Limit"
        Verdict.COMPILATION_ERROR -> "Compile Error"
        else -> verdict.label
    }

    fun isFailure(verdict: Verdict): Boolean = style(verdict).tone.let { it == VerdictTone.FAIL || it == VerdictTone.LIMIT }

    /** The editor's monospaced font, one point smaller so sample data fits a narrow side panel. */
    fun dataFont(): Font {
        val scheme = EditorColorsManager.getInstance().globalScheme
        val base = scheme.getFont(EditorFontType.PLAIN)
        return base.deriveFont((scheme.editorFontSize2D - 1f).coerceAtLeast(10f))
    }

    val gap: Int get() = JBUI.scale(8)
}
