package io.github.bovmelo.cph.companion

import io.github.bovmelo.cph.model.CompanionProblemPayload
import io.github.bovmelo.cph.model.LanguageId
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TemplateEngineTest {
    private val engine = TemplateEngine(Clock.fixed(Instant.parse("2026-08-27T12:34:56Z"), ZoneOffset.UTC))

    @Test
    fun `expands metadata and returns cursor offset`() {
        val payload = CompanionProblemPayload(name = "Sample", url = "https://example.test/a")
        val expanded = engine.expand(
            "// \$name\$ \$date\$ \$time\$\nfun main() { \$CURSOR_PLACEHOLDER }",
            payload,
        )

        assertTrue(expanded.content.startsWith("// Sample 2026-08-27 12:34:56"))
        assertFalse(expanded.content.contains(TemplateEngine.CURSOR_PLACEHOLDER))
        assertEquals(expanded.content.indexOf(" }"), expanded.cursorOffset)
    }

    @Test
    fun `all built in templates contain a cursor placeholder`() {
        LanguageId.entries.forEach { language ->
            assertTrue(engine.builtIn(language).contains(TemplateEngine.CURSOR_PLACEHOLDER))
        }
    }

    @Test
    fun `built in Python template remains valid when the placeholder is removed`() {
        val expanded = engine.expand(
            engine.builtIn(LanguageId.PYTHON),
            CompanionProblemPayload(name = "Sample"),
        )

        assertTrue(expanded.content.contains("    pass"))
        assertEquals(expanded.content.indexOf("pass"), expanded.cursorOffset)
    }

    @Test
    fun `Java template receives the generated public class name`() {
        val expanded = engine.expand(
            engine.builtIn(LanguageId.JAVA),
            CompanionProblemPayload(name = "Sample"),
            className = "Problem4A",
        )

        assertTrue(expanded.content.contains("public class Problem4A"))
        assertFalse(expanded.content.contains(TemplateEngine.JAVA_CLASS_PLACEHOLDER))
    }
}
