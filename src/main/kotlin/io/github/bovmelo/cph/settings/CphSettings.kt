package io.github.bovmelo.cph.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import io.github.bovmelo.cph.model.LanguageId

@Service(Service.Level.APP)
@State(name = "CphSettings", storages = [Storage("cph.xml")])
class CphSettings : PersistentStateComponent<CphSettings.StoredState> {
    private var storedState = StoredState()

    override fun getState(): StoredState = storedState

    override fun loadState(state: StoredState) {
        storedState = state
    }

    fun snapshot(): CphSettingsSnapshot = CphSettingsSnapshot(
        port = storedState.port,
        defaultLanguage = LanguageId.fromSetting(storedState.defaultLanguage),
        timeoutMs = storedState.timeoutMs,
        compileTimeoutMs = storedState.compileTimeoutMs,
        failOnStderr = storedState.failOnStderr,
        onlineJudge = storedState.onlineJudge,
        optimizeO2 = storedState.optimizeO2,
        sanitizers = storedState.sanitizers,
        autoLoadActiveFile = storedState.autoLoadActiveFile,
        problemDirectory = storedState.problemDirectory,
        templatePath = storedState.templatePath,
        toolchains = LanguageId.entries.associateWith { language ->
            val defaults = CphSettingsSnapshot.defaultToolchains().getValue(language)
            LanguageToolchain(
                compileCommand = storedState.compileCommands[language.settingValue] ?: defaults.compileCommand,
                runCommand = storedState.runCommands[language.settingValue] ?: defaults.runCommand,
                compileArguments = storedState.compileArguments[language.settingValue].orEmpty(),
                runArguments = storedState.runArguments[language.settingValue].orEmpty(),
            )
        },
    )

    fun update(snapshot: CphSettingsSnapshot) {
        storedState = StoredState().also {
            it.port = snapshot.port
            it.defaultLanguage = snapshot.defaultLanguage.settingValue
            it.timeoutMs = snapshot.timeoutMs
            it.compileTimeoutMs = snapshot.compileTimeoutMs
            it.failOnStderr = snapshot.failOnStderr
            it.onlineJudge = snapshot.onlineJudge
            it.optimizeO2 = snapshot.optimizeO2
            it.sanitizers = snapshot.sanitizers
            it.autoLoadActiveFile = snapshot.autoLoadActiveFile
            it.problemDirectory = snapshot.problemDirectory
            it.templatePath = snapshot.templatePath
            snapshot.toolchains.forEach { (language, toolchain) ->
                it.compileCommands[language.settingValue] = toolchain.compileCommand
                it.runCommands[language.settingValue] = toolchain.runCommand
                it.compileArguments[language.settingValue] = toolchain.compileArguments
                it.runArguments[language.settingValue] = toolchain.runArguments
            }
        }
    }

    class StoredState {
        var port: Int = 27_121
        var defaultLanguage: String = LanguageId.KOTLIN.settingValue
        var timeoutMs: Long = 3_000
        var compileTimeoutMs: Long = 60_000
        var failOnStderr: Boolean = false
        var onlineJudge: Boolean = false
        var optimizeO2: Boolean = true
        var sanitizers: Boolean = false
        var autoLoadActiveFile: Boolean = true
        var problemDirectory: String = ""
        var templatePath: String = ""
        var compileCommands: MutableMap<String, String> = linkedMapOf()
        var runCommands: MutableMap<String, String> = linkedMapOf()
        var compileArguments: MutableMap<String, String> = linkedMapOf()
        var runArguments: MutableMap<String, String> = linkedMapOf()
    }
}
