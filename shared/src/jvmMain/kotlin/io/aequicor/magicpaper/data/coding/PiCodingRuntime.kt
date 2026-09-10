package io.aequicor.magicpaper.data.coding

import io.aequicor.magicpaper.domain.tools.*
import io.aequicor.magicpaper.data.tools.*
import kotlinx.coroutines.currentCoroutineContext
import io.aequicor.magicpaper.domain.RuntimeQuestionnaires
import io.aequicor.magicpaper.domain.forPendingRun
import io.aequicor.magicpaper.data.research.*
import io.aequicor.magicpaper.domain.PlanningAnswer

import io.aequicor.magicpaper.domain.Attachment
import io.aequicor.magicpaper.domain.CodingEvent
import io.aequicor.magicpaper.domain.CodingProject
import io.aequicor.magicpaper.domain.CodingRuntime
import io.aequicor.magicpaper.domain.CodingSession
import io.aequicor.magicpaper.domain.LlmProfile
import io.aequicor.magicpaper.domain.ProviderType
import io.aequicor.magicpaper.domain.RuntimePhase
import io.aequicor.magicpaper.domain.RuntimeStatus
import io.aequicor.magicpaper.domain.TRUNCATED_HEADLINE
import io.aequicor.magicpaper.domain.forModel
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.CharsetDecoder
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Кодинг-бэкенд на пи-агенте (десктоп).
 *
 * Изоляция и жизненный цикл зависимостей:
 *  - всё живёт в корне [root] (по умолчанию ~/.MagicPaper/coding) —
 *    удаляется вместе с данными приложения ([uninstall]);
 *  - системный Node (>= 22.19) используется, если есть; иначе скачивается
 *    официальный дистрибутив Node в тот же корень — ничего не ставится в систему;
 *  - пи-агент ставится через `npm --prefix` в изолированную папку (без скриптов установки);
 *  - конфиг пи (модель из настроек приложения) и сессии — тоже в корне;
 *  - пи-процесс запускается с PI_OFFLINE/без телеметрии, расширения проекта не исполняются.
 */
class PiCodingRuntime(
    rootDir: File = File(File(System.getProperty("user.home"), ".MagicPaper"), "coding"),
    override val computerUse: io.aequicor.magicpaper.data.computer.DesktopComputerUse? = null,
    private val subscriptionToken: (suspend () -> String)? = null,
) : CodingRuntime {
    private val questionnaireRegistry = RuntimeQuestionnaires(FileRuntimeQuestionnaireStore(
        // Uninstalling the execution engine must not delete confirmed user answers or their audit.
        File(rootDir.absoluteFile.parentFile, "${rootDir.name}-questionnaires")))
    override val questionnaires = questionnaireRegistry.requests
    override suspend fun respondQuestionnaire(id: String, answers: List<PlanningAnswer>) { questionnaireRegistry.respond(id, answers) }


    internal fun piAiDirectory(): File {
        val agent = File(prefix, "node_modules/@earendil-works/pi-coding-agent")
        return listOf(File(agent, "node_modules/@earendil-works/pi-ai/dist"), File(prefix, "node_modules/@earendil-works/pi-ai/dist"))
            .firstOrNull { File(it, "index.js").isFile } ?: error("Подготовьте зависимости движков в настройках")
    }
    @Synchronized internal fun resourceScript(name: String): File {
        val content = checkNotNull(javaClass.getResourceAsStream("/coding/$name")) { "Нет адаптера $name" }.use { it.readBytes() }
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(content).take(8).joinToString("") { "%02x".format(it) }
        val target = File(root, "adapters/$digest/$name")
        target.parentFile.mkdirs()
        if (!target.isFile) target.writeBytes(content)
        return target
    }
    internal suspend fun startProviderBridge(profile: LlmProfile): CodexProviderBridge {
        val state = ensureReady().last()
        check(state.ready) { state.detail }
        return CodexProviderBridge.start(checkNotNull(findNode { }), resourceScript("provider-bridge.mjs"), piAiDirectory(), profile)
    }
    internal val hasActiveRuns: Boolean get() = runningProcesses.isNotEmpty()

    override val supported: Boolean = true
    override val rootPath: String get() = root.absolutePath

    private val root: File = rootDir
    private val prefix = File(root, "prefix")
    private val nodeDir = File(root, "node")
    private val pihome = File(root, "pihome")
    private val sessionsDir = File(root, "sessions")
    /** Вложения кодинг-сессий: файлы лежат вне папки проекта и удаляются с зависимостями. */
    private val uploadsDir = File(root, "uploads")
    /** Автономный MinGit для bash-инструмента агента на Windows (см. [ensureWindowsShell]). */
    private val shellDir = File(root, "shell")
    private val piCli = File(prefix, "node_modules/@earendil-works/pi-coding-agent/dist/bundle/cli.js")

    private val installLock = Mutex()
    private val ownedProcesses = OwnedCodingProcess(File(root, "owned-processes"))
    override suspend fun reconcile(sessionId: String) = withContext(Dispatchers.IO) { ownedProcesses.reconcile(sessionId) }
    private fun sessionHome(id: String) = File(root, "session-configs/" + id.replace(Regex("[^a-zA-Z0-9_-]"), "_"))
    private var cachedNode: File? = null

    /**
     * Процессы агентов по идентификаторам кодинг-сессий: прогоны разных
     * сессий идут параллельно и прерываются независимо.
     */
    private val runningProcesses = java.util.concurrent.ConcurrentHashMap<String, Process>()

    /** Сессии, которым пользователь запретил продолжать («ошибкой чтения» не сбой). */
    private val abortedSessions = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    override fun abort(sessionId: String) {
        ResearchCheckRunner.shared.abort(sessionId)
        computerUse?.disable(sessionId)
        abortedSessions.add(sessionId)
        runningProcesses.remove(sessionId)?.let { process ->
            process.destroy()
            if (!process.waitFor(3, TimeUnit.SECONDS)) process.destroyForcibly()
        }
    }

    override fun abortAll() {
        computerUse?.disable()
        runningProcesses.keys.forEach { abort(it) }
    }

    // ---- Состояние -------------------------------------------------------

    override suspend fun status(): RuntimeStatus = withContext(Dispatchers.IO) {
        if (!piCli.isFile) {
            RuntimeStatus(
                phase = RuntimePhase.CHECKING,
                detail = "Движок не установлен. Подготовка скачает и установит его автоматически.",
            )
        } else {
            readyStatus()
        }
    }

    override fun ensureReady(): Flow<RuntimeStatus> = flow {
        installLock.withLock {
            emit(RuntimeStatus(RuntimePhase.CHECKING, "Проверяю зависимости…"))
            try {
                if (piCli.isFile) {
                    // Установка старше защиты кодировки — дупатчим на месте (идемпотентно).
                    patchBundleFuzzySafety()
                    emit(readyStatus())
                    return@flow
                }
                emit(RuntimeStatus(RuntimePhase.INSTALLING, "Ищу подходящий Node…"))
                val node = findNode { detail -> emit(RuntimeStatus(RuntimePhase.INSTALLING, detail)) }
                emit(RuntimeStatus(RuntimePhase.INSTALLING, "Ставлю пи-агент (изолированно)…"))
                installPi(node)
                if (onWindows()) {
                    emit(RuntimeStatus(RuntimePhase.INSTALLING, "Проверяю bash для команд агента…"))
                    ensureWindowsShell { detail -> emit(RuntimeStatus(RuntimePhase.INSTALLING, detail)) }
                }
                emit(readyStatus())
            } catch (e: Exception) {
                emit(
                    RuntimeStatus(
                        phase = RuntimePhase.ERROR,
                        detail = "Не удалось подготовить движок: ${e.message}",
                    )
                )
            }
        }
    }.flowOn(Dispatchers.IO)
    override suspend fun uninstall(): Unit = withContext(Dispatchers.IO) {
        cachedNode = null
        resetWindowsShellProbe()
        fuzzySafetyDone = false
        root.deleteRecursively()
    }

    // ---- Запуск агента ----------------------------------------------------

    override fun run(
        project: CodingProject,
        session: CodingSession,
        prompt: String,
        profile: LlmProfile?,
        attachments: List<Attachment>,
    ): Flow<CodingEvent> = runAgent(project, session.forPendingRun().also { require(!it.planningMode) { "Используйте защищённый маршрут планирования" } }, prompt, profile, attachments, planning = false)

    override fun runPlanning(project: CodingProject, session: CodingSession, prompt: String, profile: LlmProfile): Flow<CodingEvent> =
        runAgent(project, session.copy(piSessionId = ""), prompt, profile, emptyList(), planning = true)

    private fun runAgent(project: CodingProject, session: CodingSession, prompt: String, profile: LlmProfile?,
        attachments: List<Attachment>, planning: Boolean): Flow<CodingEvent> = flow {
        val dir = File(project.path)
        val research = !planning && session.researchMode
        val restricted = planning || research
        if (!piCli.isFile) {
            emit(CodingEvent.Failed("Движок не установлен. Нажмите «Подготовить движок»."))
            emit(CodingEvent.Finished)
            return@flow
        }
        if (!dir.isDirectory) {
            emit(CodingEvent.Failed("Папка проекта недоступна: ${project.path}"))
            emit(CodingEvent.Finished)
            return@flow
        }
        if (profile == null || !profile.configured) {
            emit(CodingEvent.Failed("Не настроен источник модели: подключите провайдера в настройках."))
            emit(CodingEvent.Finished)
            return@flow
        }
        if (profile.provider == ProviderType.OPENAI_SUBSCRIPTION && subscriptionToken == null) {
            emit(CodingEvent.Failed("Подписка ChatGPT не подключена в настройках движков."))
            emit(CodingEvent.Finished)
            return@flow
        }
        val node = runCatching { findNode { } }.getOrNull()
        if (node == null) {
            emit(CodingEvent.Failed("Node не найден. Подготовьте движок заново."))
            emit(CodingEvent.Finished)
            return@flow
        }

        // Кодинг-контур: модель и всё, что из неё выводится (models.json,
        // effort, maxTokens), берётся из codingModelId профиля, если задана.
        val codingProfile = if (planning) profile.forModel() else profile.forCoding()
        writePiConfig(codingProfile, sessionHome(session.id), imageInput = !restricted && computerUse?.grant(session.id) != null)
        writeAtomically(File(sessionHome(session.id), HINTS_FILE), codingSystemPrompt(io.aequicor.magicpaper.domain.CodingEngine.PI, planning, codingProfile.advanced.systemPromptOverride, research, session.planningRulesSnapshot))
        // Вложения раскладываем в изолированную папку; пути уходят в промпт —
        // агент читает их своими инструментами (текст и изображения).
        val attachedPaths = materializeAttachments(session.id, attachments)
        val effectivePrompt = promptWithAttachments(prompt, attachedPaths)
        // Лечим и старые установки (до защиты кодировки) — без пересоздания движка.
        ensureFuzzySafety()
        if (!restricted && onWindows() && windowsBashProbe() == null) {
            emit(
                CodingEvent.Notice(
                    "Рабочего bash не найдено (в WSL нет дистрибутива) — команды агент выполняет " +
                        "через PowerShell. Для bash: установите Git for Windows или перевыполните «Подготовить движок»."
                )
            )
        }

        // Один запуск pi — один процесс с одним промптом. Ответ, сгоревший в лимите
        // вывода, лечится продолжением ТОЙ ЖЕ pi-сессии: контекст уже набран, и агент
        // просит «продолжай» ровно то, что пользователь иначе дописывает руками.
        var promptText = effectivePrompt
        var piSessionId = session.piSessionId.ifBlank { null }
        var continues = 0
        // Инициализатор формален: тело цикла выполняется раньше проверки выхода.
        var outcome = AttemptOutcome(launchError = "агент не запущен")
        val emitEvent: suspend (CodingEvent) -> Unit = { emit(it) }
        val computerBridge = if (restricted) null else computerUse?.bridge(session.id)
        val researchBridge = if (research) ResearchCheckBridge(session, project) else null
        val agentTools = currentCoroutineContext()[ToolSession]
        val agentBridge = agentTools?.let { AgentToolBridge(it) }
        val questionnaireBridge = if (planning || agentTools != null) null else io.aequicor.magicpaper.data.questionnaire.QuestionnaireBridge(questionnaireRegistry, session)
        try {
            if (agentTools != null) writeAtomically(File(sessionHome(session.id), "agent-tools.mjs"), PiAgentToolExtension.source(agentTools))
            if (research) writeAtomically(File(sessionHome(session.id), "research.mjs"), PiResearchExtension.source)
            writeAtomically(File(sessionHome(session.id), "questionnaire.mjs"), io.aequicor.magicpaper.data.questionnaire.PiQuestionnaireExtension.source)
            if (computerBridge != null) {
                writeAtomically(File(sessionHome(session.id), "computer-use.mjs"), io.aequicor.magicpaper.data.computer.PiComputerExtension.source)
            }
            while (true) {
                outcome = runPiAttempt(node, dir, session, codingProfile, promptText, piSessionId, emitEvent, computerBridge, questionnaireBridge, planning, researchBridge, agentBridge, agentTools)
                val canContinue = outcome.truncated != null && !outcome.answerSeen &&
                    !outcome.aborted && outcome.exitCode == 0 && !outcome.piSessionId.isNullOrBlank() &&
                    continues < MAX_OUTPUT_CONTINUES && !abortedSessions.contains(session.id)
                if (!canContinue) break
                continues++
                piSessionId = outcome.piSessionId
                promptText = CONTINUATION_PROMPT
                emit(
                    CodingEvent.Notice(
                        "$TRUNCATED_HEADLINE — продолжаю прогон, попытка $continues из $MAX_OUTPUT_CONTINUES…"
                    )
                )
            }
        } finally {
            researchBridge?.close()
            agentBridge?.close()
            questionnaireBridge?.close()
            computerBridge?.close()
            abortedSessions.remove(session.id)
        }
        if (!outcome.answerSeen) {
            emit(CodingEvent.Failed(failureReason(codingProfile, outcome, continues)))
        }
        emit(CodingEvent.Finished)
    }.flowOn(Dispatchers.IO)

    /**
     * Один запуск pi-агента: процесс, поток событий, id сессии пи и признаки
     * обрезки вывода. Id сессии сохраняет ViewModel (событием SessionStarted) —
     * здесь он нужен только чтобы продолжить прогон тем же `--session-id`.
     */
    private suspend fun runPiAttempt(
        node: File,
        dir: File,
        session: CodingSession,
        profile: LlmProfile,
        prompt: String,
        piSessionId: String?,
        emit: suspend (CodingEvent) -> Unit,
        computerBridge: io.aequicor.magicpaper.data.computer.ComputerUseBridge? = null,
        questionnaireBridge: io.aequicor.magicpaper.data.questionnaire.QuestionnaireBridge? = null,
        planning: Boolean = false,
        researchBridge: ResearchCheckBridge? = null,
        agentBridge: AgentToolBridge? = null,
        agentTools: ToolSession? = null,
    ): AttemptOutcome {
        val research = researchBridge != null
        val restricted = planning || research
        var tokenBroker: SubscriptionTokenBroker? = null
        val args = mutableListOf(
            node.absolutePath, piCli.absolutePath,
            "--mode", "json",
            "--provider", PiModelsConfig.PROVIDER_ID,
            "--model", profile.modelId,
            "--session-dir", sessionsDir.absolutePath,
            "--no-extensions", "--no-skills", "--no-prompt-templates", "--no-themes",
            "--no-approve",
        )
        // Подсказка модели — ФАЙЛОМ: пи сам читает путь к существующему файлу
        // (см. resolvePromptInput). Свободный текст с кавычками в аргументах на
        // Windows ломается при сборке командной строки ProcessBuilder: аргумент
        // распадается на части, и обрывки уходят в «сообщения» — агент видит
        // мусор вместо запроса (воспроизведено: промпт превратился в «for»).
        args += listOf(if (restricted) "--system-prompt" else "--append-system-prompt", File(sessionHome(session.id), HINTS_FILE).absolutePath)
        if (restricted) args += listOf("--tools", (if (research) "read,grep,find,ls,planning_git,research_check" + if (agentTools == null) ",questionnaire" else "" else "read,grep,find,ls,planning_git") + agentTools?.definitions.orEmpty().joinToString("", prefix = "") { ",${it.wireName}" },
            "--extension", resourceScript("planning-tools.mjs").absolutePath)
        if (research) args += listOf("--extension", File(sessionHome(session.id), "research.mjs").absolutePath)
        args += listOf("--extension", File(sessionHome(session.id), "model-options.mjs").absolutePath)
        args += listOf("--extension", resourceScript("usage-context.mjs").absolutePath)
        if (agentBridge != null) args += listOf("--extension", File(sessionHome(session.id), "agent-tools.mjs").absolutePath)
        if (questionnaireBridge != null) args += listOf("--extension", File(sessionHome(session.id), "questionnaire.mjs").absolutePath)
        if (computerBridge != null) args += listOf("--extension", File(sessionHome(session.id), "computer-use.mjs").absolutePath)
        // Уровень мышления — явным флагом: выбор из профиля иначе до pi не доходит
        // (PI_REASONING_LEVEL — то, что pi отдаёт инструментам, а не вход запуска),
        // а без него включается дефолт pi, и рассуждающая модель молча съедает maxTokens.
        PiModelsConfig.thinkingLevel(profile)?.let { args += listOf("--thinking", it) }
        // Контекст продолжает КОДИНГ-СЕССИЯ (у проекта их может быть несколько).
        if (!piSessionId.isNullOrBlank()) args += listOf("--session-id", piSessionId)

        val stderrFile = File.createTempFile("magicpaper-pi-stderr", ".log")
        stderrFile.deleteOnExit()
        var process: Process? = null
        var answerSeen = false
        var truncated: CodingEvent.OutputTruncated? = null
        var capturedId: String? = null
        var streamBroken: String? = null
        try {
            if (profile.provider == ProviderType.OPENAI_SUBSCRIPTION) {
                args += listOf("--extension", resourceScript("subscription-provider.mjs").absolutePath)
                tokenBroker = SubscriptionTokenBroker(checkNotNull(subscriptionToken))
            }
            process = ProcessBuilder(args)
                .directory(dir)
                .redirectError(stderrFile)
                .apply {
                    environment().putAll(piEnv(node, sessionHome(session.id)))
                    environment().remove("MAGICPAPER_RESEARCH_MODE")
                    environment().remove("MAGICPAPER_RESEARCH_URL")
                    environment().remove("MAGICPAPER_RESEARCH_TOKEN")
                    if (researchBridge != null) {
                        environment()["MAGICPAPER_RESEARCH_MODE"] = "1"
                        environment()["MAGICPAPER_RESEARCH_URL"] = researchBridge.url
                        environment()["MAGICPAPER_RESEARCH_TOKEN"] = researchBridge.token
                    }
                    environment().remove("MAGICPAPER_COMPUTER_URL")
                    environment().remove("MAGICPAPER_COMPUTER_TOKEN")
                    if (agentBridge != null) {
                        environment()["MAGICPAPER_AGENT_TOOLS_URL"] = agentBridge.url
                        environment()["MAGICPAPER_AGENT_TOOLS_TOKEN"] = agentBridge.token
                        environment()["MAGICPAPER_AGENT_TOOLS_NAMES"] = kotlinx.serialization.json.JsonArray(agentTools!!.definitions.map { kotlinx.serialization.json.JsonPrimitive(it.wireName) }).toString()
                    }
                    if (questionnaireBridge != null) {
                        environment()["MAGICPAPER_QUESTIONNAIRE_URL"] = questionnaireBridge.url
                        environment()["MAGICPAPER_QUESTIONNAIRE_TOKEN"] = questionnaireBridge.token
                    }
                    if (computerBridge != null) {
                        environment()["MAGICPAPER_COMPUTER_URL"] = computerBridge.url
                        environment()["MAGICPAPER_COMPUTER_TOKEN"] = computerBridge.token
                    }
                    tokenBroker?.let { environment().putAll(it.environment); environment()["MAGICPAPER_PI_AI"] = piAiDirectory().toURI().toString() }
                }
                .start()
            // Persist ownership before sending a prompt that can change files.
            ownedProcesses.record(session.id, process)
            // Запрос пользователя передаём пайп-стандарт-вводом (пи читает пайп как
            // UTF-8 и берёт его первоначальным промптом, --mode json неинтерактивен).
            // Байтовый канал невосприимчив к кавычкам/пробелам/переводам строк, в
            // отличие от аргументов командной строки. Пи читает до EOF — пишем и закрываем.
            runCatching {
                process.outputStream.use { out ->
                    out.write(prompt.toByteArray(StandardCharsets.UTF_8))
                    out.flush()
                }
            }
            runningProcesses[session.id] = process
            // Читаем строго UTF-8 с заменой битых байт: на Windows консольные
            // кодовые страницы (cp866/cp1251) иначе роняют поток MalformedInputException.
            readUtf8Tolerant(process.inputStream).use { reader ->
                while (true) {
                    val line = try {
                        reader.readLine()
                    } catch (e: IOException) {
                        // На Windows destroy()/закрытие процесса активное чтение
                        // прерывает IOException («Read error») вместо чистого EOF.
                        streamBroken = e.message
                        break
                    } ?: break
                    for (event in PiEventParser.parseEvents(line,
                        summaryOnly = profile.provider in setOf(ProviderType.OPENAI_SUBSCRIPTION, ProviderType.GOOGLE))) {
                        when (event) {
                            is CodingEvent.SessionStarted ->
                                if (event.sessionId.isNotBlank()) capturedId = event.sessionId
                            is CodingEvent.FinalText -> answerSeen = true
                            is CodingEvent.Failed -> answerSeen = true
                            is CodingEvent.OutputTruncated -> truncated = event
                            else -> Unit
                        }
                        emit(event)
                    }
                }
            }
            val exit = process.waitFor()
            return AttemptOutcome(
                answerSeen = answerSeen,
                truncated = truncated,
                aborted = abortedSessions.contains(session.id),
                piSessionId = capturedId,
                exitCode = exit,
                stderr = tailOfFile(stderrFile),
                streamBroken = streamBroken,
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            return AttemptOutcome(
                answerSeen = answerSeen,
                truncated = truncated,
                aborted = abortedSessions.contains(session.id),
                piSessionId = capturedId,
                stderr = runCatching { tailOfFile(stderrFile) }.getOrDefault(""),
                streamBroken = streamBroken,
                launchError = e.message ?: e.javaClass.simpleName,
            )
        } finally {
            tokenBroker?.close()
            stderrFile.delete()
            runningProcesses.remove(session.id, process)
            process?.let { running ->
                if (running.isAlive) {
                    running.destroy()
                    if (!running.waitFor(3, TimeUnit.SECONDS)) running.destroyForcibly()
                }
                if (!running.isAlive) ownedProcesses.clear(session.id)
            }
        }
    }

    /** Итог одного запуска pi-агента (см. [runPiAttempt]). */
    private data class AttemptOutcome(
        val answerSeen: Boolean = false,
        val truncated: CodingEvent.OutputTruncated? = null,
        val aborted: Boolean = false,
        val piSessionId: String? = null,
        val exitCode: Int? = null,
        val stderr: String = "",
        val streamBroken: String? = null,
        val launchError: String? = null,
    )

    /**
     * Причина прогона без ответа. Порядок важнее содержания: обрезка вывода —
     * диагноз точнее, чем шум в stderr (предупреждения node пишутся туда и при успехе),
     * а «без ответа» — вообще не диагноз: он прятал ровно эту причину.
     */
    private fun failureReason(profile: LlmProfile, outcome: AttemptOutcome, continues: Int): String {
        val err = outcome.stderr.trim()
        return when {
            outcome.aborted -> "Прогон прерван по команде пользователя."
            outcome.launchError != null -> "Сбой запуска агента: ${outcome.launchError}"
            outcome.exitCode != null && outcome.exitCode != 0 -> buildString {
                append("Агент завершился с кодом ${outcome.exitCode}.")
                // Код отказа важнее диагностики обрезки, но и её терять нельзя:
                // чисел usage тут уже нет, поэтому только факт.
                if (outcome.truncated != null) append(" Последний ход обрезан по лимиту вывода.")
            }
            outcome.truncated != null -> {
                val advice = PiModelsConfig.truncationAdvice(
                    profile,
                    outputTokens = outcome.truncated.outputTokens,
                    reasoningTokens = outcome.truncated.reasoningTokens,
                )
                if (continues > 0) "$advice Автопродолжение ($continues попыток) не помогло." else advice
            }
            err.isNotBlank() -> err
            outcome.streamBroken != null -> "Поток агента прервался: ${outcome.streamBroken}"
            else -> "Агент завершился без ответа."
        }
    }

    // ---- Windows: оболочка для bash-инструмента агента ---------------------

    private fun onWindows(): Boolean =
        System.getProperty("os.name").lowercase().contains("win")

    /**
     * Pi выполняет shell-команды агента только через bash: на Windows он ищет
     * Git Bash в Program Files, затем bash.exe в PATH, и доходит до System32\
     * bash.exe — заглушки WSL, которая падает с «execvpe(/bin/bash) failed»,
     * если в WSL нет дистрибутива. Здесь мы повторяем тот же порядок, но
     * отбрасываем WSL-заглушку, а при отсутствии bash скачиваем переносимый
     * MinGit в корень изоляции (установщик не нужен, удаляется с uninstall).
     * Кэш: решение принимается один раз за сессию приложения.
     */
    @Volatile
    private var cachedBash: String? = null

    /** Защита кодировки применена к текущей установке (не перечитывать чанки каждый прогон). */
    @Volatile
    private var fuzzySafetyDone = false

    private fun ensureFuzzySafety() {
        if (fuzzySafetyDone || !piCli.isFile) return
        runCatching { patchBundleFuzzySafety() }
        fuzzySafetyDone = true
    }

    private fun resetWindowsShellProbe() {
        cachedBash = null
    }

    /** Быстрый поиск готового bash без скачивания; null — работать через PowerShell. */
    private fun windowsBashProbe(): String? {
        cachedBash?.takeIf { File(it).isFile }?.let { return it }
        // Явный override (например, нестандартная установка MSYS2/Cygwin).
        System.getenv(WINDOWS_SHELL_ENV)?.takeIf { File(it).isFile }?.let {
            cachedBash = it
            return it
        }
        val candidates = listOfNotNull(System.getenv("ProgramFiles"), System.getenv("ProgramFiles(x86)"))
            .map { File(it, "Git/bin/bash.exe") }
            .plus((System.getenv("PATH") ?: "").split(File.pathSeparator)
                .filter { it.isNotBlank() }
                .map { File(it, "bash.exe") })
            .filter { it.isFile }
        val found = candidates.firstOrNull { !isLegacyWslBashStub(it) }
        if (found != null) {
            cachedBash = found.absolutePath
            return cachedBash
        }
        bundledBash()?.let {
            cachedBash = it.absolutePath
            return cachedBash
        }
        return null
    }

    /**
     * Оболочка для команд агента: найденный bash или скачанный MinGit.
     * null — bash недоступен (нет сети/архив повреждён): агенту включается
     * PowerShell-инструмент вместо bash.
     */
    private suspend fun ensureWindowsShell(progress: suspend (String) -> Unit): String? {
        if (!onWindows()) return null
        windowsBashProbe()?.let { return it }
        return runCatching { downloadMinGit(progress) }
            .onFailure {
                // Не ломаем подготовку движка: без bash агент умеет PowerShell.
                progress("MinGit скачать не удалось (${it.message?.take(120)}) — команды пойдут через PowerShell.")
            }
            .getOrNull()?.absolutePath
    }

    /** System32/sysnative\bash.exe — реликер WSL, а не настоящий bash. */
    private fun isLegacyWslBashStub(file: File): Boolean {
        val normalized = file.absolutePath.replace('/', '\\').lowercase()
        return Regex("^[a-z]:\\\\windows\\\\(system32|sysnative)\\\\bash\\.exe$").matches(normalized)
    }

    private fun bundledBash(): File? {
        val bash = File(shellDir, "usr/bin/bash.exe")
        if (bash.isFile) return bash
        // Минимальный срез: sh.exe в MinGit — это GNU bash (полный режим при
        // имени argv[0]=bash); копия делается сразу после распаковки, но на
        // случай полу-установки проверяем и оригинал.
        val sh = File(shellDir, "usr/bin/sh.exe")
        return sh.takeIf { it.isFile }
    }

    private suspend fun downloadMinGit(progress: suspend (String) -> Unit): File {
        val arch = System.getProperty("os.arch").lowercase()
        val archPart = when {
            arch.contains("arm64") || arch.contains("aarch64") -> "arm64"
            arch.contains("64") -> "64-bit"
            else -> "32-bit"
        }
        val archiveName = "MinGit-$GIT_VERSION-$archPart.zip"
        val url = URL("$GIT_RELEASE_URL/$archiveName")
        root.mkdirs()
        val archive = File(root, archiveName)
        url.openConnection().let { conn ->
            val http = conn as HttpURLConnection
            http.instanceFollowRedirects = true
            http.connectTimeout = 20_000
            http.readTimeout = 60_000
            http.connect()
            if (http.responseCode !in 200..299) error("сервер вернул код ${http.responseCode}")
            val total = http.contentLengthLong
            var received = 0L
            var lastReported = -1L
            http.inputStream.use { input ->
                archive.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        received += n
                        val mb = received / (1024 * 1024)
                        if (mb / 2 != lastReported / 2) {
                            lastReported = mb
                            val totalMb = if (total > 0) " из ${total / (1024 * 1024)} МБ" else ""
                            progress("Скачиваю bash для команд агента (MinGit): $mb МБ$totalMb…")
                        }
                    }
                }
            }
        }
        progress("Распаковываю MinGit…")
        shellDir.deleteRecursively()
        shellDir.mkdirs()
        unzipToDirectory(archive, shellDir)
        archive.delete()
        val sh = File(shellDir, "usr/bin/sh.exe")
        if (!sh.isFile) error("в архиве MinGit не найден usr/bin/sh.exe")
        // bash-совместимый вызов по имени: копируем sh.exe в bash.exe.
        val bash = File(shellDir, "usr/bin/bash.exe")
        runCatching { sh.copyTo(bash, overwrite = true) }
        cachedBash = bash.absolutePath
        return bash
    }

    // ---- Установка --------------------------------------------------------

    private suspend fun readyStatus(): RuntimeStatus {
        val node = runCatching { findNode { } }.getOrNull()
        val version = node?.let { runCatching { execLine(listOf(it.absolutePath, piCli.absolutePath, "--version")) }.getOrNull() }.orEmpty()
        val shellNote = when {
            !onWindows() -> ""
            else -> {
                val bash = windowsBashProbe()
                if (bash != null) " Bash для команд: $bash." else " Bash не найден — команды агент будет выполнять через PowerShell."
            }
        }
        return RuntimeStatus(
            phase = RuntimePhase.READY,
            detail = "Пи-агент готов. Изоляция: $rootPath.$shellNote",
            version = version.trim(),
        )
    }

    /** Ищет системный подходящий Node; иначе скачивает дистрибутив в корень изоляции. */
    private suspend fun findNode(progress: suspend (String) -> Unit): File {
        cachedNode?.takeIf { it.isFile }?.let { return it }

        for (candidate in nodeCandidates()) {
            val version = runCatching { execLine(listOf(candidate.absolutePath, "--version")) }.getOrNull()?.trim()
                ?: continue
            if (versionSatisfies(version)) {
                cachedNode = candidate
                return candidate
            }
        }

        // Ранее скачанный дистрибутив используем только если он подходит по версии
        // (NODE_VERSION могла измениться с прошлых версий приложения).
        val bundled = File(nodeDir, "bin/node").takeIf { it.isFile }
            ?: File(nodeDir, "node.exe").takeIf { it.isFile }
        if (bundled != null) {
            val bundledVersion = runCatching { execLine(listOf(bundled.absolutePath, "--version")) }
                .getOrNull()?.trim()
            if (bundledVersion != null && versionSatisfies(bundledVersion)) {
                cachedNode = bundled
                return bundled
            }
            nodeDir.deleteRecursively()
        }

        progress("Подходящий Node не найден — скачиваю дистрибутив…")
        return downloadNode(progress).also { cachedNode = it }
    }

    private fun nodeCandidates(): List<File> {
        val isWindows = System.getProperty("os.name").lowercase().contains("win")
        // На Windows бинарник — node.exe; «node» без расширения ничего не найдёт.
        val names = if (isWindows) listOf("node.exe", "node") else listOf("node")
        val fromPath = (System.getenv("PATH") ?: "")
            .split(File.pathSeparator)
            .filter { it.isNotBlank() }
            .flatMap { dir -> names.map { name -> File(dir, name) } }
        val wellKnown = if (isWindows) {
            val programFiles = System.getenv("ProgramFiles") ?: "C:\\Program Files"
            names.map { name -> File(File(programFiles, "nodejs"), name) }
        } else {
            listOf(
                "/opt/homebrew/bin/node",
                "/usr/local/bin/node",
                "/opt/local/bin/node",
                "/usr/bin/node",
                File(System.getProperty("user.home"), ".local/bin/node").absolutePath,
            ).map { File(it) }
        }
        return (fromPath + wellKnown).filter { it.isFile }
    }

    private suspend fun downloadNode(progress: suspend (String) -> Unit): File {
        val target = nodeTarget()
        val isWindows = System.getProperty("os.name").lowercase().contains("win")
        val ext = if (isWindows) "zip" else "tar.gz"
        val archiveName = "node-$NODE_VERSION-$target.$ext"
        val url = URL("https://nodejs.org/dist/$NODE_VERSION/$archiveName")
        root.mkdirs()
        val archive = File(root, archiveName)

        url.openConnection().let { conn ->
            val http = conn as HttpURLConnection
            http.instanceFollowRedirects = true
            http.connectTimeout = 20_000
            http.readTimeout = 60_000
            http.connect()
            if (http.responseCode !in 200..299) error("сервер вернул код ${http.responseCode}")
            val total = http.contentLengthLong
            var received = 0L
            var lastReported = -1L
            http.inputStream.use { input ->
                archive.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        received += n
                        val mb = received / (1024 * 1024)
                        if (mb != lastReported) {
                            lastReported = mb
                            val totalMb = if (total > 0) " из ${total / (1024 * 1024)} МБ" else ""
                            progress("Скачиваю Node $NODE_VERSION: $mb МБ$totalMb…")
                        }
                    }
                }
            }
        }

        progress("Распаковываю Node…")
        nodeDir.mkdirs()
        if (isWindows) {
            // Системный tar на Windows капризен: в PATH может стоять GNU tar без
            // поддержки zip, а bsdtar не читает 8.3-имена и нелатиницу в %TEMP%.
            // Распаковываем штатным JVM-архиватором, без внешних бинарников.
            unzipToDirectory(archive, root)
        } else {
            runCommand(listOf("tar", "-xf", archive.absolutePath, "-C", root.absolutePath), root)
        }
        archive.delete()
        val unpacked = File(root, "node-$NODE_VERSION-$target")
        if (!unpacked.isDirectory) error("архив Node распаковался неожиданно")
        // Кладём дистрибутив прямо в корень изоляции.
        if (nodeDir.exists()) nodeDir.deleteRecursively()
        if (!unpacked.renameTo(nodeDir)) {
            // Переименование между томами — копируем и чистим.
            unpacked.copyRecursively(nodeDir)
            unpacked.deleteRecursively()
        }
        val binary = if (isWindows) File(nodeDir, "node.exe") else File(nodeDir, "bin/node")
        if (!binary.isFile) error("после распаковки не найден исполняемый файл Node")
        if (!isWindows) binary.setExecutable(true)
        return binary
    }

    private suspend fun installPi(node: File) {
        root.mkdirs()
        val npmCommand = npmCommandFor(node)
        val command = npmCommand + listOf(
            "install",
            "--prefix", prefix.absolutePath,
            "--ignore-scripts", "--no-audit", "--no-fund", "--no-progress",
            "$PI_PACKAGE@$PI_VERSION",
        )
        val env = if (node.parentFile != null) mapOf("PATH" to pathWith(node.parentFile)) else emptyMap()
        runCommand(command, root, extraEnv = env, timeoutSeconds = 600)
        if (!piCli.isFile) error("установка завершилась, но движок не найден в изоляции")
        patchBundleFuzzySafety()
        writePiHomeDefaults()
    }

    /**
     * Точечный патч бандла пи (проверено на 0.84.4 — живое repro в этой сессии).
     *
     * Встроенный edit при несовпадении oldText уходит в fuzzy-режим: переписывает
     * затронутые правкой СТРОКИ из нормализованной копии файла, по пути заменяя
     * типографику на ASCII (— → -, – ‘’“” → - ' "). Для русских текстов это
     * тихая порча файлов («поехала кодировка»): модель правит одну строку, а
     * тире/кавычки деградируют во всей затронутой области. После патча
     * снисходительность fuzzy остаёт только к концевым пробелам; несовпадение
     * символов даёт честную «Could not find the exact text» — агент перечитывает
     * файл и повторяет правку точно, ничего не портя.
     *
     * Идемпотентно (маркер в теле функции); при изменении внутренностей пи
     * просто не применяется и не мешает работе.
     */
    internal fun patchBundleFuzzySafety() {
        val chunksDir = File(piCli.parentFile, "chunks")
        // esbuild может положить в бандл несколько копий функции с суффиксами
        // («normalizeForFuzzyMatch2» — её использует json-режим) — правим все.
        val fn = Regex("function normalizeForFuzzyMatch\\d*\\(text\\)\\{")
        val patchedMarker = "/*magicpaper-fuzzy-safety*/"
        // Тело каждой копии заканчивается «}» перед следующим объявлением функции.
        val safeBody =
            "return $patchedMarker text.split(\"\\n\").map(line=>line.trimEnd()).join(\"\\n\")}"
        // Повторный проход даёт updated == text (тело уже safeBody) — не пишем.
        chunksDir.listFiles { f -> f.isFile && f.extension == "js" }?.forEach { chunk ->
            val text = runCatching { chunk.readText(StandardCharsets.UTF_8) }.getOrNull() ?: return@forEach
            if (!fn.containsMatchIn(text)) return@forEach
            val updated = buildString {
                var last = 0
                var searchFrom = 0
                while (true) {
                    val match = fn.find(text, searchFrom) ?: break
                    val bodyStart = match.range.last + 1
                    val close = text.indexOf("}function", bodyStart)
                    if (close < 0) {
                        // Неизвестная структура — не рискуем, файл остаётся как есть.
                        append(text, last, text.length)
                        last = text.length
                        break
                    }
                    append(text, last, bodyStart)
                    append(safeBody)
                    last = close + 1
                    searchFrom = last
                }
                append(text, last, text.length)
            }
            if (updated != text) {
                runCatching { chunk.writeText(updated, StandardCharsets.UTF_8) }
            }
        }
    }

    /**
     * npm рядом с Node. Надёжнее запускать npm-cli.js тем же node-бинарником
     * (cmd-обёртки на Windows требуют cmd.exe, shell-обёртки — sh), поэтому
     * сначала ищем js-скрипт в обоих макетах дистрибутива:
     * unix — `<home>/lib/node_modules/npm`, Windows — `<home>/node_modules/npm`
     * (node.exe лежит прямо в home, в отличие от unix `bin/node`).
     */
    private fun npmCommandFor(node: File): List<String> {
        val dir = node.parentFile
        val homes = listOfNotNull(dir?.parentFile, dir)
        for (home in homes) {
            val npmCli = listOf(
                File(home, "lib/node_modules/npm/bin/npm-cli.js"),
                File(home, "node_modules/npm/bin/npm-cli.js"),
            ).firstOrNull { it.isFile }
            if (npmCli != null) return listOf(node.absolutePath, npmCli.absolutePath)
        }
        val sibling = dir?.let {
            val names = listOf("npm.cmd", "npm") // на Windows npm без расширения — shell-скрипт
            names.map { n -> File(it, n) }.firstOrNull { f -> f.isFile }
        }
        if (sibling != null) return listOf(sibling.absolutePath)
        error("npm не найден рядом с Node (${node.absolutePath})")
    }

    /** Распаковка zip чистым JVM с защитой от path traversal в записях архива. */
    private fun unzipToDirectory(zip: File, destDir: File) {
        val destRoot = destDir.canonicalFile
        ZipFile(zip).use { archive ->
            val entries = archive.entries().toList()
            // Сначала каталоги: иначе файл может оказаться раньше своего каталога.
            for (entry in entries.filter { it.isDirectory }) {
                val out = File(destRoot, entry.name)
                require(out.canonicalFile.startsWith(destRoot)) { "архив Node содержит опасный путь: ${entry.name}" }
                out.mkdirs()
            }
            for (entry in entries.filter { !it.isDirectory }) {
                val out = File(destRoot, entry.name)
                require(out.canonicalFile.startsWith(destRoot)) { "архив Node содержит опасный путь: ${entry.name}" }
                out.parentFile?.mkdirs()
                archive.getInputStream(entry).use { input ->
                    out.outputStream().use { output -> input.copyTo(output, 128 * 1024) }
                }
            }
        }
    }

    private fun writePiHomeDefaults(home: File = pihome) {
        home.mkdirs()
        // На Windows без bash агент не может выполнять команды вообще:
        // переключаем набор инструментов на powershell (нативный, есть в каждой Windows).
        val bash = windowsBashProbe()
        val toolsField = if (onWindows() && bash == null) {
            "\"defaultTools\":[\"read\",\"powershell\",\"edit\",\"write\",\"grep\",\"find\",\"ls\"],"
        } else {
            ""
        }
        val shellField = if (bash != null) "\"shellPath\":\"${jsonEscape(bash)}\"," else ""
        File(home, "settings.json").writeText(
            """{"defaultProjectTrust":"never",${shellField}${toolsField}"telemetry":false}"""
        )
        // Подсказка модели про точное совпадение текста правок: путь к этому
        // файлу уходит в --append-system-prompt (файл читает сам пи, см. run).
        File(home, HINTS_FILE).writeText(
            PI_CODING_INSTRUCTIONS,
            StandardCharsets.UTF_8
        )
    }

    /** Модель из профиля подключения мостится в конфиг пи изолированно. */
    private fun writePiConfig(profile: LlmProfile, home: File, imageInput: Boolean = false) {
        home.mkdirs()
        writePiHomeDefaults(home)
        sessionsDir.mkdirs()
        // Сборка конфига — в общем коде (PiModelsConfig: лимиты из профиля,
        // reasoning и thinkingLevelMap согласованы с возможностями модели), там же
        // и тестируется. Атомарная замена (tmp+rename): параллельные прогоны сессий
        // не должны прочитать наполовину записанный models.json.
        writeAtomically(File(home, "models.json"), PiModelsConfig.json(profile, imageInput = imageInput))
        writeAtomically(File(home, "model-options.mjs"), PiModelOptions.extension(profile))
        File(home, HINTS_FILE).writeText(codingSystemPrompt(io.aequicor.magicpaper.domain.CodingEngine.PI, false, profile.advanced.systemPromptOverride))

    }

    private fun writeAtomically(target: File, content: String) {
        val tmp = File(target.parentFile, "${target.name}.tmp-${System.nanoTime()}")
        tmp.writeText(content)
        runCatching {
            java.nio.file.Files.move(
                tmp.toPath(), target.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            )
        }.getOrElse {
            // rename между томам или антивирусная блокировка — обычная перезапись.
            target.writeText(content)
            tmp.delete()
        }
    }

    private fun piEnv(node: File, home: File = pihome): Map<String, String> = mapOf(
        "PI_CODING_AGENT_DIR" to home.absolutePath,
        "PI_OFFLINE" to "1",
        "PI_SKIP_VERSION_CHECK" to "1",
        "PI_TELEMETRY" to "0",
        // На Windows в PATH добавляем бинарники автономного MinGit: подстраховка
        // для «where bash.exe» и unix-утилит, если shellPath когда-то разъедется.
        "PATH" to pathWithAll(
            listOfNotNull(node.parentFile) +
                if (onWindows()) listOf(File(shellDir, "usr/bin"), File(shellDir, "mingw64/bin"))
                else emptyList()
        ),
    )

    private fun pathWith(dir: File?): String = pathWithAll(listOfNotNull(dir))

    private fun pathWithAll(dirs: List<File>): String {
        val current = System.getenv("PATH") ?: if (onWindows()) "" else "/usr/bin:/bin"
        val prefix = dirs.filter { it.isDirectory }.map { it.absolutePath }
        return (prefix + current.split(File.pathSeparator).filter { it.isNotBlank() })
            .distinct()
            .joinToString(File.pathSeparator)
    }

    // ---- Утилиты -----------------------------------------------------------

    private fun nodeTarget(): String {
        val os = System.getProperty("os.name").lowercase()
        val arch = System.getProperty("os.arch").lowercase()
        val osPart = when {
            os.contains("mac") || os.contains("darwin") -> "darwin"
            os.contains("win") -> "win"
            else -> "linux"
        }
        val archPart = when {
            arch.contains("aarch64") || arch.contains("arm64") -> "arm64"
            else -> "x64"
        }
        return "$osPart-$archPart"
    }

    private fun versionSatisfies(version: String): Boolean {
        // "v22.23.2" -> [22, 23, 2]
        val parts = version.removePrefix("v").split(".").mapNotNull { it.toIntOrNull() }
        if (parts.size < 2) return false
        val (major, minor) = parts
        return major > MIN_NODE_MAJOR || (major == MIN_NODE_MAJOR && minor >= MIN_NODE_MINOR)
    }

    private fun execLine(command: List<String>, timeoutSeconds: Long = 20): String {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        // Закрытие stdin: часть инструментов ждёт EOF и иначе висит до таймаута.
        runCatching { process.outputStream.close() }
        val output = readUtf8Tolerant(process.inputStream).use { it.readText() }
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            error("команда зависла: ${command.joinToString(" ")}")
        }
        return output.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
    }

    /**
     * Буферизованный читатель со строгой кодировкой UTF-8: битые последовательности
     * (на Windows stdout может быть в кодовой странице консоли) заменяются, а не
     * роняют чтение MalformedInputException.
     */
    private fun readUtf8Tolerant(source: java.io.InputStream): BufferedReader =
        BufferedReader(InputStreamReader(source, utf8Lenient()))

    private fun utf8Lenient(): CharsetDecoder = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE)
        .replaceWith("\uFFFD")

    /** Хвост файла ошибок (после завершения процесса) — устойчиво к блокировкам Windows. */
    private fun tailOfFile(file: File, limit: Int = 800): String = runCatching {
        if (!file.isFile) return@runCatching ""
        // Читаем байты и декодируем UTF-8 вручную с заменой: на Windows файл
        // может содержать мусор чужой кодовой страницы, readText() на этом падает.
        String(file.readBytes(), StandardCharsets.UTF_8)
            .filter { it.code >= 32 || it == '\n' }
            .takeLast(limit)
            .trim()
    }.getOrElse { "" }

    private fun runCommand(
        command: List<String>,
        dir: File,
        extraEnv: Map<String, String> = emptyMap(),
        timeoutSeconds: Long = 300,
    ) {
        val process = ProcessBuilder(command)
            .directory(dir)
            .redirectErrorStream(true)
            .apply { environment().putAll(extraEnv) }
            .start()
        runCatching { process.outputStream.close() }
        val output = readUtf8Tolerant(process.inputStream).use { it.readText() }
        val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            error("команда не завершилась за $timeoutSeconds с: ${command.take(3).joinToString(" ")}")
        }
        if (process.exitValue() != 0) {
            error("${command.first()} завершился с кодом ${process.exitValue()}: ${output.takeLast(400).trim()}")
        }
    }

    private fun jsonEscape(value: String): String = value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")

    // ---- Вложения кодинг-сессии -------------------------------------------

    /**
     * Раскладывает вложения сессии в изолированную папку [uploadsDir]
     * (вне папки проекта — журнал и рабочая директория не засоряются).
     * Возвращает абсолютные пути в порядке вложений.
     */
    private fun materializeAttachments(sessionId: String, attachments: List<Attachment>): List<File> {
        if (attachments.isEmpty()) return emptyList()
        val dir = File(uploadsDir, sessionId)
        dir.mkdirs()
        return attachments.map { attachment ->
            val file = File(dir, uniqueName(dir, attachment.name))
            file.writeBytes(attachment.bytes)
            file
        }
    }

    /** Промпт с блоком вложений: агент получает абсолютные пути к файлам. */
    private fun promptWithAttachments(prompt: String, files: List<File>): String {
        if (files.isEmpty()) return prompt
        return buildString {
            append(prompt)
            append("\n\nК запросу приложены файлы (лежат вне папки проекта, пути абсолютные):\n")
            files.forEach { append("- ").append(it.absolutePath).append('\n') }
            append("Если файл нужен для задачи — прочитай его инструментом чтения; изображения тоже читаются.")
        }
    }

    /** Имя без опасных символов + префикс времени: файлы не перетирают друг друга. */
    private fun uniqueName(dir: File, name: String): String {
        val safe = name.replace(Regex("[^\\p{L}\\p{N}._\\-\\u0400-\\u04FF]+"), "_").take(120).ifBlank { "file" }
        val candidate = "${System.currentTimeMillis()}-$safe"
        var file = File(dir, candidate)
        var counter = 1
        while (file.exists()) {
            file = File(dir, "${counter++}-$candidate")
        }
        return file.name
    }

    private companion object {
        const val PI_PACKAGE = "@earendil-works/pi-coding-agent"
        const val PI_VERSION = "0.84.4"
        const val NODE_VERSION = "v22.23.2"
        const val MIN_NODE_MAJOR = 22
        const val MIN_NODE_MINOR = 19

        /**
         * Сколько раз продолжать обрезанный ответ в той же pi-сессии. Двух хватает:
         * если модель и с подсказкой не может уместить намерение в потолок вывода,
         * причина в профиле (лимит/усилие), а не в везении, и третий прогон —
         * просто ещё один счёт за токены.
         */
        const val MAX_OUTPUT_CONTINUES = 2

        /**
         * Что отправляем pi-сессии при автопродолжении. Коротко и без извинений:
         * контекст уже набран, модели нужно только перестать рассуждать и сделать
         * шаг — поэтому прямо сказано про длину ответа и про одно действие.
         */
        const val CONTINUATION_PROMPT =
            "Предыдущий ответ обрезан лимитом токенов. Не пересказывай разбор: " +
                "сделай одно следующее действие (правка файла или команда) и опиши его кратко."

        /**
         * MinGit — переносимый срез Git for Windows: полный нативный
         * MSYS2 bash.exe без установщика (распаковывается в корень изоляции).
         */
        const val GIT_VERSION = "2.55.0.5"
        const val GIT_RELEASE_URL =
            "https://github.com/git-for-windows/git/releases/download/v2.55.0.windows.5"

        /** Пользовательский escape hatch: явный путь к bash.exe для пи. */
        const val WINDOWS_SHELL_ENV = "MAGICPAPER_SHELL_PATH"

        /** Подсказка модели в пи-доме (уходит в --append-system-prompt как файл). */
        const val HINTS_FILE = "agent-hints.md"
    }
}
