package io.github.bovmelo.cph.project

import com.intellij.execution.RunManager
import com.intellij.execution.RunManagerListener
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
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
import io.github.bovmelo.cph.cmake.CMakeTargets
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.charset.StandardCharsets
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.seconds

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

    /**
     * Creates `<name>.<extension>` and opens it as a local problem. The file starts empty unless the user
     * configured a template for that language. A request with [NewProblemRequest.cmakeTarget] also registers
     * the file with `add_executable` in the root CMakeLists.txt and selects the matching run target.
     */
    fun addProblem(request: NewProblemRequest) {
        if (state.phase != RunPhase.IDLE) {
            return
        }
        coroutineScope.launch(Dispatchers.IO) {
            var createdSource: Path? = null
            var complete = false
            try {
                val projectDirectory = project.basePath?.let(Path::of)?.toAbsolutePath()?.normalize()
                    ?: error("The project has no local base directory.")
                val cmakeFile = projectDirectory.resolve(CMAKE_FILE_NAME)
                require(!request.cmakeTarget || Files.isRegularFile(cmakeFile)) {
                    "No $CMAKE_FILE_NAME found in the project root."
                }
                val settings = service<CphSettings>().snapshot()
                val destination = resolveProblemDirectory(projectDirectory, settings.problemDirectory)

                val takenNames = takenProblemNames(projectDirectory, destination, request.language, request.cmakeTarget)
                NewProblemRules.nameError(request, takenNames)?.let { error(it) }
                val name = request.name
                val sourcePath = destination.resolve("$name.${request.language.extension}").normalize()
                require(sourcePath.startsWith(destination)) { "Generated source path escaped the problem directory." }

                // No built-in boilerplate here; the configured template is written for the default language only.
                val expanded = settings.templatePath
                    .takeIf { it.isNotBlank() && settings.defaultLanguage == request.language }
                    ?.let { templateEngine.expand(loadTemplate(it, request.language), CompanionProblemPayload(name = name), name) }
                Files.createDirectories(sourcePath.parent)
                try {
                    Files.writeString(
                        sourcePath,
                        expanded?.content.orEmpty(),
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE_NEW,
                    )
                } catch (_: FileAlreadyExistsException) {
                    // The exception's own message is just the path.
                    error("${sourcePath.fileName} already exists.")
                }
                createdSource = sourcePath

                // Metadata goes first: the source and metadata can be taken back, the CMake edit cannot.
                val problem = LocalProblemFactory.create(sourcePath).also(store::save)
                if (request.cmakeTarget) {
                    appendCMakeTarget(cmakeFile, name, CMakeTargets.sourceReference(projectDirectory, sourcePath))
                }
                complete = true

                val status = if (request.cmakeTarget) {
                    "Added target $name to $CMAKE_FILE_NAME."
                } else {
                    "Created ${sourcePath.fileName}."
                }
                // A run that started while the files were being written keeps its state, or its process could
                // no longer be stopped. The new problem then loads from its file once the run ends.
                updateState { current ->
                    if (current.phase == RunPhase.IDLE) CphUiState(problem = problem, statusMessage = status) else current
                }
                openSource(sourcePath, expanded?.cursorOffset)
                if (request.cmakeTarget) {
                    selectRunTarget(name, problem)
                }
            } catch (error: Exception) {
                // A half-created problem would block a retry under the same name, so take it back entirely.
                if (!complete) {
                    createdSource?.let { source ->
                        runCatching { store.delete(source) }
                        runCatching { Files.deleteIfExists(source) }
                    }
                }
                if (error is CancellationException) throw error
                updateState { it.copy(statusMessage = "Could not add a problem: ${error.message}") }
            }
        }
    }

    /** Why a problem cannot be added to this project at all, or null. CMake targets need a root CMakeLists.txt. */
    fun newProblemError(cmakeTarget: Boolean): String? {
        val projectDirectory = project.basePath?.let(Path::of) ?: return "The project has no local base directory."
        return if (cmakeTarget && !Files.isRegularFile(projectDirectory.resolve(CMAKE_FILE_NAME))) {
            "No $CMAKE_FILE_NAME found in the project root."
        } else {
            null
        }
    }

    /**
     * Names a new problem may not reuse: sources of [language] already in the problem directory, plus the
     * CMake targets when the problem becomes one.
     */
    fun takenProblemNames(language: LanguageId, cmakeTarget: Boolean): Set<String> {
        val projectDirectory = project.basePath?.let(Path::of)?.toAbsolutePath()?.normalize() ?: return emptySet()
        val configured = service<CphSettings>().snapshot().problemDirectory
        val destination = problemDirectoryPath(projectDirectory, configured)
        // This feeds the dialog on the UI thread, where an unreadable CMakeLists.txt must not throw; creating
        // the problem reads the file again and reports the failure in the status line.
        return runCatching { takenProblemNames(projectDirectory, destination, language, cmakeTarget) }
            .getOrDefault(emptySet())
    }

    private fun takenProblemNames(
        projectDirectory: Path,
        destination: Path,
        language: LanguageId,
        cmakeTarget: Boolean,
    ): Set<String> {
        val cmakeFile = projectDirectory.resolve(CMAKE_FILE_NAME)
        val targets = if (cmakeTarget && Files.isRegularFile(cmakeFile)) {
            CMakeTargets.declaredTargets(readCMakeText(cmakeFile))
        } else {
            emptySet()
        }
        return targets + sourceStems(destination, language)
    }

    private fun readCMakeText(cmakeFile: Path): String = ReadAction.compute<String, java.io.IOException> {
        // An open editor may hold edits that are not on disk yet.
        LocalFileSystem.getInstance().findFileByNioFile(cmakeFile)
            ?.let { FileDocumentManager.getInstance().getCachedDocument(it)?.text }
            ?: Files.readString(cmakeFile, StandardCharsets.UTF_8)
    }

    /** Only an existing source of the same language collides with a new problem; `B.in` must not block `B`. */
    private fun sourceStems(directory: Path, language: LanguageId): Set<String> =
        runCatching {
            Files.list(directory).use { entries ->
                entries.map { it.fileName.toString() }
                    .filter { it.substringAfterLast('.', "").equals(language.extension, ignoreCase = true) }
                    .map { it.substringBeforeLast('.') }
                    .toList()
                    .toSet()
            }
        }.getOrDefault(emptySet())

    /** Edits through the document so unsaved editor changes survive and CLion sees an ordinary edit. */
    private fun appendCMakeTarget(cmakeFile: Path, target: String, sourceReference: String) {
        var failure: Throwable? = null
        ApplicationManager.getApplication().invokeAndWait {
            try {
                val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(cmakeFile)
                    ?: error("Cannot open $cmakeFile.")
                val documentManager = FileDocumentManager.getInstance()
                val document = documentManager.getDocument(file) ?: error("Cannot read $cmakeFile.")
                val before = document.text
                check(target !in CMakeTargets.declaredTargets(before)) { "Target $target already exists." }
                val addition = CMakeTargets.appendExecutable(before, target, sourceReference).substring(before.length)
                WriteCommandAction.runWriteCommandAction(project, "Add CPH Problem Target", null, {
                    document.insertString(before.length, addition)
                })
                documentManager.saveDocument(document)
                // Saving reports I/O errors and vetoes by itself and returns normally, so ask whether it worked.
                if (documentManager.isDocumentUnsaved(document)) {
                    // CMake reads the file, not the editor. Take the line back rather than keep a target that
                    // exists only in memory and names a source this failure is about to delete.
                    WriteCommandAction.runWriteCommandAction(project, "Add CPH Problem Target", null, {
                        if (document.text == before + addition) {
                            document.deleteString(before.length, document.textLength)
                        }
                    })
                    error("Could not save $CMAKE_FILE_NAME.")
                }
            } catch (error: Exception) {
                failure = error
            }
        }
        failure?.let { throw it }
    }

    /**
     * Asks CLion to reload CMake so the new target gets its run configuration; editing inside the IDE does
     * not trigger CLion's auto-reload. The action is looked up by id to keep the plugin free of CLion-only
     * dependencies.
     *
     * While a project is still opening, CLion either throws from the action or drops the request silently,
     * and its initial load may already have read the old file. So a request is retried while it throws and
     * then repeated a few times in case it was dropped. The repeats are few and far apart because each one
     * restarts a reload that is still running.
     */
    private suspend fun requestCMakeReloads(reload: AnAction, firstRequest: CompletableDeferred<Unit>) {
        for (pause in listOf(0.seconds) + CMAKE_RELOAD_REPEAT_DELAYS) {
            delay(pause)
            while (!invokeCMakeReload(reload)) {
                delay(CMAKE_RELOAD_RETRY_MILLIS)
            }
            firstRequest.complete(Unit)
        }
    }

    /** False when the action threw, which CLion does while its CMake workspace is not ready. */
    private suspend fun invokeCMakeReload(reload: AnAction): Boolean {
        val accepted = CompletableDeferred<Boolean>()
        ApplicationManager.getApplication().invokeLater {
            accepted.complete(
                project.isDisposed || runCatching {
                    ActionUtil.invokeAction(
                        reload,
                        SimpleDataContext.getProjectContext(project),
                        ActionPlaces.UNKNOWN,
                        null,
                        null,
                    )
                }.isSuccess,
            )
        }
        return accepted.await()
    }

    /**
     * CLion creates the run configuration for a new target when it reloads CMake, which can be later than the
     * edit, so wait for it (or pick it now if it is already there).
     */
    private fun selectRunTarget(name: String, problem: Problem) {
        // An IDE without CMake support never creates a run configuration for the target.
        val reloadAction = ActionManager.getInstance().getAction(CMAKE_RELOAD_ACTION_ID) ?: return
        coroutineScope.launch {
            val found = CompletableDeferred<Unit>()
            withTimeoutOrNull(RUN_TARGET_TIMEOUT_SECONDS.seconds) {
                fun trySelect(): Boolean {
                    // Only CLion's configuration for the target counts, not a shell script that shares its name.
                    val configuration = RunManager.getInstance(project).allSettings
                        .firstOrNull { it.name == name && it.type.id == CMAKE_RUN_CONFIGURATION_TYPE_ID }
                        ?: return false
                    ApplicationManager.getApplication().invokeLater {
                        // A slow reload can finish after the user has moved on to another problem and target.
                        if (!project.isDisposed && state.problem?.sourcePath == problem.sourcePath) {
                            RunManager.getInstance(project).selectedConfiguration = configuration
                        }
                    }
                    found.complete(Unit)
                    return true
                }
                // The connection ends with this scope, so there is nothing to clean up after the timeout.
                project.messageBus.connect(this).subscribe(
                    RunManagerListener.TOPIC,
                    object : RunManagerListener {
                        override fun runConfigurationAdded(settings: RunnerAndConfigurationSettings) {
                            if (settings.name == name) trySelect()
                        }
                    },
                )
                // The edit needs a reload even when a configuration of that name is left over from an earlier
                // target, so the first request always goes out; the repeats stop once the target is there.
                val firstRequest = CompletableDeferred<Unit>()
                val reloads = launch { requestCMakeReloads(reloadAction, firstRequest) }
                if (!trySelect()) {
                    found.await()
                }
                firstRequest.await()
                reloads.cancel()
            }
            if (!found.isCompleted) {
                // By now the user may be working on another problem, whose status this must not replace.
                updateStateFor(problem) {
                    it.copy(statusMessage = "Target $name is not in the run configurations yet. Reload CMake, then select it.")
                }
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
        return problemDirectoryPath(projectDirectory, configuredDirectory).also(Files::createDirectories)
    }

    private fun problemDirectoryPath(projectDirectory: Path, configuredDirectory: String): Path {
        if (configuredDirectory.isBlank()) {
            return projectDirectory
        }
        val configured = Path.of(configuredDirectory)
        val resolved = if (configured.isAbsolute) configured else projectDirectory.resolve(configured)
        return resolved.toAbsolutePath().normalize()
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
        private const val CMAKE_FILE_NAME = "CMakeLists.txt"
        private const val CMAKE_RELOAD_ACTION_ID = "CMake.ReloadCMakeProject"
        private const val CMAKE_RELOAD_RETRY_MILLIS = 1_000L
        private const val CMAKE_RUN_CONFIGURATION_TYPE_ID = "CMakeRunConfiguration"
        private val CMAKE_RELOAD_REPEAT_DELAYS = listOf(5.seconds, 15.seconds)
        private const val RUN_TARGET_TIMEOUT_SECONDS = 60
        private const val MAX_TEST_CASES = 1_000
        private const val MAX_STRESS_ITERATIONS = 100_000
    }

    private data class PreparedRun(
        val problem: Problem,
        val judgeRun: JudgeRun,
        val state: CphUiState,
    )
}
