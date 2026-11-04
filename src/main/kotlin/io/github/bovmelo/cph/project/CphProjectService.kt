package io.github.bovmelo.cph.project

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.wm.ToolWindowManager
import io.github.bovmelo.cph.companion.CompanionServerService
import io.github.bovmelo.cph.companion.ProblemNaming
import io.github.bovmelo.cph.companion.TemplateEngine
import io.github.bovmelo.cph.judge.JudgeCallbacks
import io.github.bovmelo.cph.judge.JudgeEngine
import io.github.bovmelo.cph.judge.JudgeRun
import io.github.bovmelo.cph.judge.ProcessRunner
import io.github.bovmelo.cph.judge.StressConfig
import io.github.bovmelo.cph.judge.StressOutcome
import io.github.bovmelo.cph.model.CompanionProblemPayload
import io.github.bovmelo.cph.model.CphUiState
import io.github.bovmelo.cph.model.LanguageId
import io.github.bovmelo.cph.model.LocalProblemFactory
import io.github.bovmelo.cph.model.Problem
import io.github.bovmelo.cph.model.RunPhase
import io.github.bovmelo.cph.model.TestCase
import io.github.bovmelo.cph.model.TestRunResult
import io.github.bovmelo.cph.model.Verdict
import io.github.bovmelo.cph.persistence.ProblemStore
import io.github.bovmelo.cph.persistence.TestCaseExchange
import io.github.bovmelo.cph.settings.CphSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean

@Service(Service.Level.PROJECT)
class CphProjectService(
    private val project: Project,
    private val coroutineScope: CoroutineScope,
) {
    private val stateLock = Any()
    private val listeners = CopyOnWriteArraySet<(CphUiState) -> Unit>()
    private val store = ProblemStore()
    private val templateEngine = TemplateEngine()
    private val judgeEngine = JudgeEngine()
    private val saveMutex = Mutex()
    private val documentSaveCoordinator = DocumentSaveCoordinator(
        scheduleWithWriteIntent = { operation ->
            ApplicationManager.getApplication().invokeLater {
                if (!project.isDisposed) {
                    operation.run()
                }
            }
        },
        saveAllDocuments = { FileDocumentManager.getInstance().saveAllDocuments() },
    )

    @Volatile
    private var state = CphUiState()

    @Volatile
    private var runJob: Job? = null

    fun currentState(): CphUiState = state

    fun addListener(parentDisposable: com.intellij.openapi.Disposable, listener: (CphUiState) -> Unit) {
        listeners.add(listener)
        Disposer.register(parentDisposable) { listeners.remove(listener) }
        deliver(state, listener)
    }

    fun importProblem(payload: CompanionProblemPayload) {
        coroutineScope.launch(Dispatchers.IO) {
            runCatching {
                val projectDirectory = project.basePath?.let(Path::of)?.toAbsolutePath()?.normalize()
                    ?: error("The target project has no local base directory.")
                val settings = service<CphSettings>().snapshot()
                val language = settings.defaultLanguage
                val destination = resolveProblemDirectory(projectDirectory, settings.problemDirectory)
                val sourcePath = destination.resolve(ProblemNaming.fileName(payload, language)).normalize()
                require(sourcePath.startsWith(destination)) { "Generated source path escaped the problem directory." }

                var cursorOffset: Int? = null
                if (!Files.exists(sourcePath)) {
                    val template = loadTemplate(settings.templatePath, language)
                    val className = sourcePath.fileName.toString().substringBeforeLast('.')
                    val expanded = templateEngine.expand(template, payload, className)
                    Files.createDirectories(sourcePath.parent)
                    Files.writeString(
                        sourcePath,
                        expanded.content,
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE_NEW,
                    )
                    cursorOffset = expanded.cursorOffset
                }

                val imported = payload.toProblem(sourcePath, language).let { problem ->
                    if (problem.tests.isEmpty()) problem.copy(tests = listOf(TestCase())) else problem
                }
                store.save(imported)
                updateState {
                    CphUiState(
                        problem = imported,
                        statusMessage = "Imported ${imported.tests.size} testcase(s) for ${imported.name}.",
                    )
                }
                openSource(sourcePath, cursorOffset)
            }.onFailure { error ->
                updateState { it.copy(phase = RunPhase.IDLE, statusMessage = "Import failed: ${error.message}") }
            }
        }
    }

    fun loadOrCreateForActiveFile(): Problem? {
        val sourcePath = activeSourcePath()
        if (sourcePath == null) {
            updateState { it.copy(statusMessage = "Open a supported source file first.") }
            return null
        }
        return loadForSourcePath(sourcePath, createIfMissing = true)
    }

    fun activeFileChanged(sourcePath: Path?) {
        if (!service<CphSettings>().snapshot().autoLoadActiveFile || state.phase != RunPhase.IDLE) {
            return
        }
        if (sourcePath == null || LanguageId.fromPath(sourcePath) == null) {
            return
        }
        loadForSourcePath(sourcePath, createIfMissing = false)
    }

    fun addTestCase() {
        mutateProblem(publish = true) { problem -> problem.copy(tests = problem.tests + TestCase()) }
    }

    fun setTestCaseEnabled(id: Long, enabled: Boolean) {
        mutateProblem(publish = true) { problem ->
            problem.copy(
                tests = problem.tests.map { testCase ->
                    if (testCase.id == id) testCase.copy(enabled = enabled) else testCase
                },
            )
        }
    }

    fun setTestCaseInputFile(id: Long, path: String) {
        val normalized = path.trim()
        mutateProblem(publish = true) { problem ->
            problem.copy(
                tests = problem.tests.map { testCase ->
                    if (testCase.id == id) testCase.copy(inputFile = normalized) else testCase
                },
            )
        }
    }

    /** Copies the stdout of the last run into the expected output and marks that run as accepted. */
    fun setExpectedFromResult(id: Long) {
        val updated = synchronized(stateLock) {
            val problem = state.problem ?: return
            val result = state.results[id] ?: return
            if (state.phase != RunPhase.IDLE || problem.tests.none { it.id == id }) {
                return
            }
            val changed = problem.copy(
                tests = problem.tests.map { if (it.id == id) it.copy(output = result.stdout) else it },
            )
            state = state.copy(
                problem = changed,
                results = state.results + (id to result.copy(verdict = Verdict.ACCEPTED, differences = emptyList())),
                statusMessage = "Expected output was set from the last run.",
            )
            changed to state
        }
        saveLatest(updated.first)
        notifyListeners(updated.second)
    }

    fun deleteTestCase(id: Long) {
        mutateProblem(publish = true) { problem ->
            val remaining = problem.tests.filterNot { it.id == id }
            problem.copy(tests = remaining.ifEmpty { listOf(TestCase()) })
        }
    }

    fun clearTestCases() {
        mutateProblem(publish = true, statusMessage = "All testcases were cleared.") { problem ->
            problem.copy(tests = listOf(TestCase()))
        }
    }

    fun deleteProblemMetadata() {
        val problem = state.problem ?: return
        coroutineScope.launch(Dispatchers.IO) {
            runCatching { store.delete(Path.of(problem.sourcePath)) }
                .onSuccess {
                    updateState {
                        CphUiState(statusMessage = "Deleted CPH metadata for ${problem.name}. The source file was kept.")
                    }
                }
                .onFailure { error ->
                    updateState { it.copy(statusMessage = "Could not delete problem metadata: ${error.message}") }
                }
        }
    }

    fun updateTestCase(id: Long, input: String, output: String) {
        mutateProblem(publish = false, persist = false) { problem ->
            problem.copy(
                tests = problem.tests.map { testCase ->
                    if (testCase.id == id) testCase.copy(input = input, output = output) else testCase
                },
            )
        }
    }

    fun persistTestCases() {
        val problem = synchronized(stateLock) { state.problem } ?: return
        saveLatest(problem)
    }

    fun importTestCases(path: Path, replaceExisting: Boolean) {
        val targetProblem = state.problem ?: return
        coroutineScope.launch(Dispatchers.IO) {
            runCatching {
                val imported = TestCaseExchange.read(path)
                val updated = mutateProblem(
                    publish = true,
                    statusMessage = "Imported ${imported.size} testcase(s) from ${path.fileName}.",
                ) { problem ->
                    require(problem.sourcePath == targetProblem.sourcePath) { "The active problem changed during import." }
                    val tests = if (replaceExisting) imported else problem.tests + imported
                    require(tests.size <= MAX_TEST_CASES) { "A problem can contain at most $MAX_TEST_CASES testcases." }
                    problem.copy(tests = tests)
                }
                requireNotNull(updated) { "No problem is loaded." }
            }.onFailure { error ->
                updateState { it.copy(statusMessage = "Testcase import failed: ${error.message}") }
            }
        }
    }

    fun exportTestCases(path: Path) {
        val problem = state.problem ?: return
        coroutineScope.launch(Dispatchers.IO) {
            runCatching { TestCaseExchange.write(path, problem.tests) }
                .onSuccess {
                    updateStateFor(problem) { it.copy(statusMessage = "Exported ${problem.tests.size} testcase(s) to ${path.fileName}.") }
                }
                .onFailure { error ->
                    updateStateFor(problem) { it.copy(statusMessage = "Testcase export failed: ${error.message}") }
                }
        }
    }

    fun setCustomCheckerPath(path: String, publish: Boolean = true) {
        val normalized = path.trim().ifBlank { null }
        mutateProblem(
            publish = publish,
            statusMessage = if (normalized == null) "Custom checker disabled." else "Custom checker enabled.",
        ) { problem -> problem.copy(customCheckerPath = normalized) }
    }

    fun setStressFiles(generatorPath: String, brutePath: String) {
        mutateProblem(publish = false, statusMessage = "Stress test files saved.") { problem ->
            problem.copy(
                stressGeneratorPath = generatorPath.trim().ifBlank { null },
                stressBrutePath = brutePath.trim().ifBlank { null },
            )
        }
    }

    fun runStress(iterations: Int) {
        val problem = state.problem ?: return
        val generator = problem.stressGeneratorPath
        val brute = problem.stressBrutePath
        if (generator.isNullOrBlank() || brute.isNullOrBlank()) {
            updateState { it.copy(statusMessage = "Choose a generator and a brute force solution first.") }
            return
        }
        persistTestCases()
        documentSaveCoordinator.saveThen { startStress(iterations.coerceIn(1, MAX_STRESS_ITERATIONS)) }
    }

    private fun startStress(iterations: Int) {
        val prepared = synchronized(stateLock) {
            if (runJob?.isActive == true || state.phase != RunPhase.IDLE) {
                return
            }
            val problem = state.problem ?: return
            val judgeRun = judgeEngine.prepareRun()
            state = state.copy(
                phase = RunPhase.RUNNING,
                compileOutput = "",
                statusMessage = "Preparing stress test...",
            )
            PreparedRun(problem, judgeRun, state)
        }
        notifyListeners(prepared.state)

        runJob = coroutineScope.launch(Dispatchers.IO) {
            val problem = prepared.problem
            runCatching {
                val config = StressConfig(
                    solutionPath = Path.of(problem.sourcePath),
                    generatorPath = Path.of(problem.stressGeneratorPath.orEmpty().trim()),
                    brutePath = Path.of(problem.stressBrutePath.orEmpty().trim()),
                    iterations = iterations,
                )
                val outcome = judgeEngine.stress(
                    run = prepared.judgeRun,
                    config = config,
                    settings = service<CphSettings>().snapshot(),
                    onProgress = { message -> updateStateFor(problem) { it.copy(statusMessage = message) } },
                )
                val message = when (outcome) {
                    is StressOutcome.Passed -> "Stress test passed ${outcome.iterations} iteration(s)."
                    is StressOutcome.Cancelled -> "Stress test cancelled."
                    is StressOutcome.Error -> "Stress test failed: ${outcome.message}"
                    is StressOutcome.Failed -> {
                        updateStateFor(problem) { current ->
                            val tests = current.problem?.tests.orEmpty() +
                                TestCase(input = outcome.input, output = outcome.expectedOutput)
                            current.copy(problem = current.problem?.copy(tests = tests), results = emptyMap())
                        }
                        saveLatest(problem)
                        "Stress test found ${outcome.verdict.label} at iteration ${outcome.iteration}; " +
                            "the failing input was added as a testcase."
                    }
                }
                updateStateFor(problem) { it.copy(phase = RunPhase.IDLE, statusMessage = message) }
            }.onFailure { error ->
                updateStateFor(problem) {
                    it.copy(phase = RunPhase.IDLE, statusMessage = "Stress test failed: ${error.message}")
                }
            }
            runJob = null
            reloadActiveFileAfterOperation()
        }
    }

    fun setOnlineJudge(enabled: Boolean) {
        val settings = service<CphSettings>()
        settings.update(settings.snapshot().copy(onlineJudge = enabled))
        updateState {
            it.copy(statusMessage = if (enabled) "ONLINE_JUDGE enabled." else "ONLINE_JUDGE disabled.")
        }
    }

    fun setOptimizeO2(enabled: Boolean) {
        val settings = service<CphSettings>()
        settings.update(settings.snapshot().copy(optimizeO2 = enabled))
        updateState { it.copy(statusMessage = if (enabled) "-O2 enabled." else "-O2 disabled.") }
    }

    fun isOptimizeO2Enabled(): Boolean = service<CphSettings>().snapshot().optimizeO2

    fun setSanitizers(enabled: Boolean) {
        val settings = service<CphSettings>()
        settings.update(settings.snapshot().copy(sanitizers = enabled))
        updateState {
            it.copy(statusMessage = if (enabled) "Sanitizers enabled." else "Sanitizers disabled.")
        }
    }

    fun isSanitizersEnabled(): Boolean = service<CphSettings>().snapshot().sanitizers

    fun isOnlineJudgeEnabled(): Boolean = service<CphSettings>().snapshot().onlineJudge

    fun hasSupportedActiveFile(): Boolean = activeSourcePath()?.let(LanguageId::fromPath) != null

    fun runAll() {
        val sourcePath = activeSourcePath()
        if (sourcePath == null) {
            updateState { it.copy(statusMessage = "Open a supported source file first.") }
            return
        }
        val intent = RunTestCasesPolicy.resolve(store.exists(sourcePath))
        val problem = loadForSourcePath(sourcePath, createIfMissing = true) ?: return
        showToolWindow()
        if (intent == RunTestCasesIntent.CREATE_LOCAL_PROBLEM) {
            updateStateFor(problem) {
                it.copy(statusMessage = "Local problem created. Enter testcases, then press Run Testcases again.")
            }
            return
        }
        runCases(selectedCaseIds = null)
    }

    fun runSingle(id: Long) {
        runCases(selectedCaseIds = setOf(id))
    }

    fun stop() {
        if (state.phase == RunPhase.IDLE) {
            return
        }
        updateState { it.copy(phase = RunPhase.CANCELLING, statusMessage = "Stopping current process...") }
        judgeEngine.cancel()
    }

    private fun loadForSourcePath(sourcePath: Path, createIfMissing: Boolean): Problem? {
        val normalizedSourcePath = sourcePath.toAbsolutePath().normalize()
        val language = LanguageId.fromPath(normalizedSourcePath)
        if (language == null) {
            if (createIfMissing) {
                updateState { it.copy(statusMessage = "Unsupported source file: ${normalizedSourcePath.fileName}") }
            }
            return null
        }

        val stored = store.load(normalizedSourcePath)?.let { loaded ->
            if (loaded.tests.isEmpty()) loaded.copy(tests = listOf(TestCase())).also(store::save) else loaded
        }
        if (stored == null && store.exists(normalizedSourcePath)) {
            updateState {
                CphUiState(
                    statusMessage = "CPH metadata for ${normalizedSourcePath.fileName} is invalid. Fix or remove its .prob file before continuing.",
                )
            }
            return null
        }
        if (stored == null && !createIfMissing) {
            updateState { current ->
                if (current.problem?.sourcePath == normalizedSourcePath.toString()) {
                    current
                } else {
                    CphUiState(
                        statusMessage = "No CPH testcases exist for ${normalizedSourcePath.fileName}. Press Run Testcases or Ctrl+Alt+B to create them.",
                    )
                }
            }
            return null
        }
        val problem = stored ?: LocalProblemFactory.create(normalizedSourcePath).also(store::save)

        updateState { current ->
            if (current.problem?.sourcePath == problem.sourcePath) {
                current
            } else {
                CphUiState(
                    problem = problem,
                    statusMessage = if (problem.local) "Local problem loaded." else "Problem loaded.",
                )
            }
        }
        return problem
    }

    private fun runCases(selectedCaseIds: Set<Long>?) {
        if (state.problem == null) {
            loadOrCreateForActiveFile() ?: return
        }
        persistTestCases()
        documentSaveCoordinator.saveThen { startRun(selectedCaseIds) }
    }

    private fun startRun(selectedCaseIds: Set<Long>?) {
        val prepared = synchronized(stateLock) {
            if (runJob?.isActive == true || state.phase != RunPhase.IDLE) {
                return
            }
            val problem = state.problem ?: return
            val judgeRun = judgeEngine.prepareRun()
            val retainedResults = if (selectedCaseIds == null) {
                emptyMap()
            } else {
                state.results.filterKeys { it !in selectedCaseIds }
            }
            state = state.copy(
                phase = RunPhase.COMPILING,
                results = retainedResults,
                compileOutput = "",
                statusMessage = "Preparing run...",
            )
            PreparedRun(problem, judgeRun, state)
        }
        notifyListeners(prepared.state)

        runJob = coroutineScope.launch(Dispatchers.IO) {
            runCatching {
                judgeEngine.run(
                    run = prepared.judgeRun,
                    problem = prepared.problem,
                    selectedCaseIds = selectedCaseIds,
                    settings = service<CphSettings>().snapshot(),
                    callbacks = object : JudgeCallbacks {
                        override fun onPhase(phase: RunPhase, message: String) {
                            updateStateFor(prepared.problem) { it.copy(phase = phase, statusMessage = message) }
                        }

                        override fun onCompileOutput(output: String) {
                            updateStateFor(prepared.problem) { it.copy(compileOutput = output) }
                        }

                        override fun onResult(result: TestRunResult) {
                            updateStateFor(prepared.problem) { current ->
                                current.copy(results = current.results + (result.testCaseId to result))
                            }
                        }
                    },
                )
            }.onFailure { error ->
                updateStateFor(prepared.problem) {
                    it.copy(phase = RunPhase.IDLE, statusMessage = "Run failed: ${error.message}")
                }
            }
            runJob = null
            reloadActiveFileAfterOperation()
        }
    }

    private fun mutateProblem(
        publish: Boolean,
        persist: Boolean = true,
        statusMessage: String? = null,
        transform: (Problem) -> Problem,
    ): Problem? {
        val snapshot = synchronized(stateLock) {
            val problem = state.problem ?: return null
            val changed = transform(problem)
            state = state.copy(
                problem = changed,
                results = emptyMap(),
                statusMessage = statusMessage ?: state.statusMessage,
            )
            state to changed
        }
        if (persist) {
            saveLatest(snapshot.second)
        }
        if (publish) {
            notifyListeners(snapshot.first)
        }
        return snapshot.second
    }

    private fun saveLatest(fallback: Problem) {
        coroutineScope.launch(Dispatchers.IO) {
            saveMutex.withLock {
                val latest = synchronized(stateLock) {
                    state.problem?.takeIf { it.sourcePath == fallback.sourcePath } ?: fallback
                }
                runCatching { store.save(latest) }
                    .onFailure { error ->
                        updateState { it.copy(statusMessage = "Could not save testcases: ${error.message}") }
                    }
            }
        }
    }

    private fun resolveProblemDirectory(projectDirectory: Path, configuredDirectory: String): Path {
        if (configuredDirectory.isBlank()) {
            return projectDirectory
        }
        val configured = Path.of(configuredDirectory)
        val resolved = if (configured.isAbsolute) configured else projectDirectory.resolve(configured)
        return resolved.toAbsolutePath().normalize().also(Files::createDirectories)
    }

    private fun loadTemplate(templatePath: String, language: LanguageId): String {
        if (templatePath.isNotBlank()) {
            val path = Path.of(templatePath).toAbsolutePath().normalize()
            require(Files.isRegularFile(path)) { "Template file does not exist: $path" }
            return Files.readString(path, StandardCharsets.UTF_8)
        }
        return templateEngine.builtIn(language)
    }

    private fun activeSourcePath(): Path? = FileEditorManager.getInstance(project)
        .selectedFiles
        .firstOrNull()
        ?.takeIf { it.isInLocalFileSystem }
        ?.let { Path.of(it.path).toAbsolutePath().normalize() }

    private fun openSource(sourcePath: Path, cursorOffset: Int?) {
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) {
                return@invokeLater
            }
            val file = LocalFileSystem.getInstance().refreshAndFindFileByPath(sourcePath.toString())
                ?: return@invokeLater
            val editors = FileEditorManager.getInstance(project).openFile(file, true)
            if (cursorOffset != null) {
                editors.filterIsInstance<TextEditor>().firstOrNull()?.editor?.let { editor ->
                    val boundedOffset = cursorOffset.coerceIn(0, editor.document.textLength)
                    editor.caretModel.moveToOffset(boundedOffset)
                    editor.scrollingModel.scrollToCaret(ScrollType.CENTER)
                }
            }
            ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID)?.show()
        }
    }

    private fun showToolWindow() {
        ApplicationManager.getApplication().invokeLater {
            if (!project.isDisposed) {
                ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID)?.show()
            }
        }
    }

    private fun reloadActiveFileAfterOperation() {
        ApplicationManager.getApplication().invokeLater {
            if (!project.isDisposed) {
                activeFileChanged(activeSourcePath())
            }
        }
    }

    private fun updateState(transform: (CphUiState) -> CphUiState) {
        val updated = synchronized(stateLock) {
            state = transform(state)
            state
        }
        notifyListeners(updated)
    }

    private fun updateStateFor(problem: Problem, transform: (CphUiState) -> CphUiState) {
        updateState { current ->
            if (current.problem?.sourcePath == problem.sourcePath) transform(current) else current
        }
    }

    private fun notifyListeners(snapshot: CphUiState) {
        val application = ApplicationManager.getApplication()
        val delivery = Runnable {
            if (!project.isDisposed) {
                listeners.forEach { listener -> listener(snapshot) }
            }
        }
        if (application.isDispatchThread) {
            delivery.run()
        } else {
            application.invokeLater(delivery)
        }
    }

    private fun deliver(snapshot: CphUiState, listener: (CphUiState) -> Unit) {
        val application = ApplicationManager.getApplication()
        if (application.isDispatchThread) {
            listener(snapshot)
        } else {
            application.invokeLater { if (!project.isDisposed) listener(snapshot) }
        }
    }

    companion object {
        const val TOOL_WINDOW_ID = "CPH"
        private const val MAX_TEST_CASES = 1_000
        private const val MAX_STRESS_ITERATIONS = 100_000
    }

    private data class PreparedRun(
        val problem: Problem,
        val judgeRun: JudgeRun,
        val state: CphUiState,
    )
}
