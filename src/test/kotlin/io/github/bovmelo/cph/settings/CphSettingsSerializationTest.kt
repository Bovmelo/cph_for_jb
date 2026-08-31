package io.github.bovmelo.cph.settings

import com.intellij.util.xmlb.XmlSerializer
import io.github.bovmelo.cph.model.LanguageId
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CphSettingsSerializationTest {
    @Test
    fun `toolchain maps survive IntelliJ XML persistence`() {
        val state = CphSettings.StoredState().apply {
            port = 31_337
            defaultLanguage = LanguageId.RUST.settingValue
            compileCommands[LanguageId.RUST.settingValue] = "/opt/rust/bin/rustc"
            compileArguments[LanguageId.RUST.settingValue] = "-C target-cpu=native"
            onlineJudge = true
        }

        val xml = XmlSerializer.serialize(state)
        val restored = XmlSerializer.deserialize(xml, CphSettings.StoredState::class.java)

        assertEquals(31_337, restored.port)
        assertEquals("rust", restored.defaultLanguage)
        assertEquals("/opt/rust/bin/rustc", restored.compileCommands["rust"])
        assertEquals("-C target-cpu=native", restored.compileArguments["rust"])
        assertTrue(restored.onlineJudge)
    }
}
