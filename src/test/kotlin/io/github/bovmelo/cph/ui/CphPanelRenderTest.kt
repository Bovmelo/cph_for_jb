package io.github.bovmelo.cph.ui

import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import com.intellij.util.ui.UIUtil
import io.github.bovmelo.cph.model.CphUiState
import io.github.bovmelo.cph.model.LanguageId
import io.github.bovmelo.cph.model.LineDifference
import io.github.bovmelo.cph.model.Problem
import io.github.bovmelo.cph.model.RunPhase
import io.github.bovmelo.cph.model.TestCase
import io.github.bovmelo.cph.model.TestRunResult
import io.github.bovmelo.cph.model.Verdict
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.Component
import java.awt.Container
import java.awt.Dimension
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.swing.SwingUtilities
import kotlin.test.assertTrue

/**
 * Renders the real tool window off-screen. Set CPH_UI_SNAPSHOT_DIR to also write PNG snapshots for review.
 */
@TestApplication
class CphPanelRenderTest {
    private val project = projectFixture()

    @Test
    fun `renders a finished run with every verdict kind`() {
        val tests = listOf(
            TestCase(input = "5\n1 2 3 4 5\n", output = "15\n", id = 1),
            TestCase(input = "3\n1 1 1\n", output = "3\n", id = 2),
            TestCase(input = "1000000\n", output = "500000500000\n", id = 3),
            TestCase(input = "0\n", output = "0\n", id = 4),
            TestCase(input = "7\n", output = "28\n", id = 5, enabled = false),
        )
        val results = mapOf(
            1L to TestRunResult(1, Verdict.ACCEPTED, stdout = "15\n", exitCode = 0, durationMs = 12, peakMemoryKb = 34_000),
            2L to TestRunResult(
                2,
                Verdict.WRONG_ANSWER,
                stdout = "4\n",
                exitCode = 0,
                durationMs = 14,
                peakMemoryKb = 34_500,
                differences = listOf(LineDifference(1, "3", "4")),
            ),
            3L to TestRunResult(3, Verdict.TIME_LIMIT_EXCEEDED, durationMs = 3000, peakMemoryKb = 118_000),
            4L to TestRunResult(4, Verdict.RUNTIME_ERROR, stderr = "Exception in thread main", exitCode = 1, durationMs = 40),
        )
        val problem = Problem(
            name = "4A. Watermelon",
            url = "https://codeforces.com/problemset/problem/4/A",
            group = "Codeforces - Codeforces Beta Round 4",
            timeLimit = 1000,
            memoryLimit = 64,
            tests = tests,
            sourcePath = "/tmp/4A.kt",
            customCheckerPath = null,
        )

        snapshot("finished-run", CphUiState(problem = problem, results = results, statusMessage = "Finished 4 testcase(s)."))
    }

    @Test
    fun `renders the empty state and a compile error`() {
        snapshot("empty", CphUiState())

        val problem = Problem(
            name = "Local: B",
            url = "/tmp/B.cpp",
            group = "local",
            tests = listOf(TestCase(id = 1)),
            sourcePath = "/tmp/B.cpp",
            local = true,
        )
        snapshot(
            "compile-error",
            CphUiState(
                problem = problem,
                results = mapOf(1L to TestRunResult(1, Verdict.COMPILATION_ERROR, stderr = "B.cpp:3:5: error: expected ';'")),
                compileOutput = "B.cpp:3:5: error: expected ';' before 'return'\n    3 |     int x = 1\n      |              ^",
                phase = RunPhase.IDLE,
                statusMessage = "Compilation failed.",
            ),
        )
    }

    @Test
    fun `renders the settings page`() {
        val configurable = io.github.bovmelo.cph.settings.CphSettingsConfigurable()
        SwingUtilities.invokeAndWait {
            val component = configurable.createComponent()
            val size = Dimension(760, 640)
            component.size = size
            layoutTree(component)
            val image = UIUtil.createImage(component, size.width, size.height, BufferedImage.TYPE_INT_RGB)
            val g = image.createGraphics()
            g.color = Color.WHITE
            g.fillRect(0, 0, size.width, size.height)
            component.paint(g)
            g.dispose()
            System.getenv("CPH_UI_SNAPSHOT_DIR")?.let { directory ->
                File(directory).mkdirs()
                ImageIO.write(image, "png", File(directory, "settings.png"))
            }
            configurable.disposeUIResources()
        }
    }

    @Test
    fun `renders the add problem dialog with a language choice`() {
        SwingUtilities.invokeAndWait {
            val dialog = AddProblemDialog(
                project = project.get(),
                languages = listOf(LanguageId.KOTLIN, LanguageId.JAVA),
                initialLanguage = LanguageId.JAVA,
                nameHint = "Used as the source file name.",
            ) { name, _ -> "Enter a problem name.".takeIf { name.isEmpty() } }

            assertTrue(dialog.language == LanguageId.JAVA)
            assertTrue(!dialog.isOKActionEnabled)

            val component = dialog.createCenterPanel()
            val size = Dimension(420, 120)
            component.size = size
            layoutTree(component)
            val image = UIUtil.createImage(component, size.width, size.height, BufferedImage.TYPE_INT_RGB)
            val g = image.createGraphics()
            g.color = Color.WHITE
            g.fillRect(0, 0, size.width, size.height)
            component.paint(g)
            g.dispose()
            System.getenv("CPH_UI_SNAPSHOT_DIR")?.let { directory ->
                File(directory).mkdirs()
                ImageIO.write(image, "png", File(directory, "add-problem.png"))
            }
            Disposer.dispose(dialog.disposable)
        }
    }

    @Test
    fun `highlighted lines account for leading blank lines`() {
        val differences = listOf(LineDifference(1, "3", "4"), LineDifference(2, null, "extra"))

        assertTrue(highlightedLines("\n\n3\n", differences, expectedSide = true) == listOf(2))
        assertTrue(highlightedLines("\n4\nextra", differences, expectedSide = false) == listOf(1, 2))
    }

    private fun snapshot(name: String, state: CphUiState) {
        val parent = Disposer.newDisposable()
        val panel = CphToolWindowPanel(project.get())
        Disposer.register(parent, panel)
        SwingUtilities.invokeAndWait {
            panel.render(state)
            panel.refreshToolbarsNow()
            val size = Dimension(380, 760)
            panel.size = size
            layoutTree(panel)
            val image = UIUtil.createImage(panel, size.width, size.height, BufferedImage.TYPE_INT_RGB)
            val g = image.createGraphics()
            g.color = Color.WHITE
            g.fillRect(0, 0, size.width, size.height)
            panel.paint(g)
            g.dispose()
            System.getenv("CPH_UI_SNAPSHOT_DIR")?.let { directory ->
                File(directory).mkdirs()
                ImageIO.write(image, "png", File(directory, "$name.png"))
            }
        }
        Disposer.dispose(parent)
    }

    private fun layoutTree(component: Component) {
        if (component is Container) {
            component.doLayout()
            component.components.forEach(::layoutTree)
            component.doLayout()
        }
    }
}
