package io.aequicor.magicpaper.data.coding

import io.aequicor.magicpaper.data.llm.CodexAppServerOpenAiSubscription
import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.logging.AppLog
import io.aequicor.magicpaper.data.research.ResearchCheckRunner
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.concurrent.ConcurrentHashMap

internal fun ChatSession.asResearchCodingSession(projectId: String) = CodingSession(
    id = id,
    projectId = projectId,
    name = title,
    createdAt = createdAt,
    piSessionId = nativeSessionId,
    engine = checkNotNull(engine),
    modelSelection = modelSelection,
    researchMode = true,
    acquireComputerAccess = false,
)

/** The persisted session engine is the only routing input. Providers supply model access. */
class DesktopCodingRuntime(
    private val pi: PiCodingRuntime,
    private val subscription: CodexAppServerOpenAiSubscription,
    private val skillSnapshot: suspend (String) -> List<SkillInstruction> = { emptyList() },
    private val skillSelection: suspend (String) -> CodingSkillSelection = { CodingSkillSelection(skillSnapshot(it)) },
    private val recordSkillRun: suspend (CodingSkillRunRecord) -> Unit = {},
    private val runObserver: CodingRunObserver = CodingRunObserver { _, events, _ -> events },
) : CodingRuntime {
    override var globalFeatureFlags: FeatureFlagState = FeatureFlagState()
        set(value) {
            field = value
            // Propagate to child runtime so runAgent() reads the same flags.
            pi.globalFeatureFlags = value
        }

    // Optimization 1 (AGENT_SPEED_BOOST): in-memory cache for skill selection.
    // Key: projectId. Value: Pair(lastModifiedApprox, cached selection).
    // Invalidated by TTL (~5s) or when selection changes (bind/unbind/install).
    private val skillCache = ConcurrentHashMap<String, Pair<Long, CodingSkillSelection>>()
    private val SKILL_CACHE_TTL_MS = 5_000L

    private suspend fun cachedSkillSelection(projectId: String, flags: FeatureFlagState): CodingSkillSelection {
        if (!flags.isEnabled(FeatureFlag.AGENT_SPEED_BOOST)) return skillSelection(projectId)
        val now = System.currentTimeMillis()
        val cached = skillCache[projectId]
        if (cached != null && now - cached.first < SKILL_CACHE_TTL_MS) return cached.second
        val fresh = skillSelection(projectId)
        skillCache[projectId] = now to fresh
        return fresh
    }
    override suspend fun sessionContext(project: CodingProject, session: CodingSession, profile: LlmProfile?): String = withContext(Dispatchers.IO) {
        val effective = profile?.let { if (session.planningMode) it.forModel() else it.forCoding() }
        val flags = session.featureFlags.resolve(globalFeatureFlags)
        val skills = try {
            val selection = cachedSkillSelection(project.id, flags)
            buildString {
                if (selection.instructions.isEmpty()) appendLine("Пакеты проекта не подключены.")
                // Optimization 8 (AGENT_SPEED_BOOST): when ≥3 skills, inject only summaries
                // to save 2000+ tokens of system prompt. Full SKILL.md available on demand.
                val useSummaries = flags.isEnabled(FeatureFlag.AGENT_SPEED_BOOST) && selection.instructions.size >= 3
                selection.instructions.forEach { skill ->
                    appendLine("${skill.name} · ${skill.id}@${skill.version}")
                    appendLine("SHA-256: ${skill.checksum}")
                    appendLine("Заявленные разрешения: ${skill.permissions.joinToString().ifBlank { "нет" }}")
                    if (useSummaries) {
                        val summary = skill.description.ifBlank { skill.text.lineSequence().take(3).joinToString(" ") }
                        appendLine("Описание: $summary")
                        appendLine("(Полный SKILL.md доступен по запросу: прочитай навык ${skill.id})")
                    } else {
                        appendLine("Инструкция SKILL.md:")
                        appendLine(skill.text)
                    }
                    appendLine()
                }
                appendLine("Передача как доверенного пользовательского текста: ${if (selection.trustedText) "включена" else "выключана"}.")
                if (selection.freshSession) appendLine("Каждый запуск использует новую сессию движка без прежней истории.")
            }
        } catch (e: CancellationException) { throw e } catch (_: Exception) {
            "Не удалось проверить привязки навыков. Состав не подтверждён; при запуске будет повторная проверка."
        }
        val environment = buildString {
            appendLine("Проект: ${project.name}\nРабочая папка: ${project.path}")
            appendLine("Движок: ${session.engine?.title ?: "не выбран"}; режим: ${session.interactionMode.title}; роль: ${session.role}")
            appendLine("Дополнительные источники: встроенные инструкции движка, настройки инструментов и доступа, инструкции проекта (например, AGENTS.md). Они могут загружаться при запуске и чтении файлов; полный контекст движка здесь недоступен.")
            if (session.engine == CodingEngine.PI) appendLine("Нативная автозагрузка навыков Pi отключена (--no-skills).")
            else appendLine("Нативные навыки и плагины Codex определяются его конфигурацией при запуске; список ниже относится к пакетам MagicPaper.")
            appendLine("Инструменты и доступ: ${when {
                session.researchMode -> "чтение проекта и Git; запись в исходники запрещена; проверки только через research_check в песочнице ОС; повышение прав и управление компьютером отключены"
                session.planningMode -> "режим чтения проекта"
                else -> "политика выбранного движка; доступ к компьютеру и приложениям задаётся в настройках, действует только на явно запущенный запрос"
            }}.")
        }
        sessionContextReport(effective, environment,
            codingSystemPrompt(session.engine, session.planningMode, effective?.advanced?.systemPromptOverride.orEmpty(), session.researchMode, session.runtimePlanningRules, flags), skills)
    }

    override val computerUse get() = subscription.computerUse
    private val active = ConcurrentHashMap.newKeySet<String>()
    private val ownership = CodingRuntimeOwnership()
    private val runIds = ConcurrentHashMap<String, String>()
    private val cancelledRuns = ConcurrentHashMap.newKeySet<String>()
    private val clients = ConcurrentHashMap<String, CodexAppServerOpenAiSubscription>()
    override val approvals = MutableStateFlow<List<CodingApproval>>(emptyList())
    override val questionnaires = MutableStateFlow<List<UserInteractionRequest>>(emptyList())
    override suspend fun respondQuestionnaire(id: String, answers: List<PlanningAnswer>) {
        if (pi.questionnaires.value.any { it.id == id }) pi.respondQuestionnaire(id, answers)
        else (clients.values.firstOrNull { it.codingQuestionnaires.value.any { q -> q.id == id } } ?: error("Обращение уже закрыто"))
            .respondCodingQuestionnaire(id, answers)
    }

    override suspend fun respondApproval(id: String, decision: CodingApprovalDecision) {
        clients.values.firstOrNull { client -> client.codingApprovals.value.any { it.id == id } }?.respondCodingApproval(id, decision)
    }
    override fun runChat(session: ChatSession, prompt: String, profile: LlmProfile?, attachments: List<Attachment>): Flow<CodingEvent> = flow {
        val directory = chatDirectory(session.id)
        withContext(Dispatchers.IO) { check(directory.isDirectory || directory.mkdirs()) { "Не удалось создать рабочую папку чата" } }
        val project = CodingProject("chat-${session.id}", session.title, directory.absolutePath, session.createdAt)
        val coding = session.asResearchCodingSession(project.id)
        val history = if (session.nativeSessionId.isBlank()) session.messages.dropLast(1).map {
            CodingMessage(it.id, if (it.role == ChatRole.USER) CodingRole.USER else CodingRole.AGENT, it.text, createdAt = it.createdAt)
        } else emptyList()
        val seeded = researchContextSeed(history, profile?.advanced?.contextMessages ?: 20, "") + researchPrompt(prompt, session.resources)
        run(project, coding, seeded, profile?.forModel()?.let { it.copy(codingModelId = "") }, attachments).collect { emit(it) }
    }

    private fun chatDirectory(id: String): java.io.File {
        require(id.isNotBlank() && id.all { it.isLetterOrDigit() || it in "-_" }) { "Некорректный идентификатор чата" }
        return java.io.File(rootPath, "chat-workspaces/$id")
    }

    override suspend fun deleteChatSession(session: ChatSession) {
        abort(session.id)
        reconcile(session.id)
        withContext(Dispatchers.IO) {
            val directory = chatDirectory(session.id)
            check(!directory.exists() || directory.deleteRecursively()) { "Не удалось удалить рабочую папку чата" }
        }
    }

    override val supported = true
    override val rootPath: String get() = pi.rootPath
    override suspend fun status() = status(CodingEngine.PI)
    override suspend fun status(engine: CodingEngine): RuntimeStatus = when (engine) {
        CodingEngine.PI -> pi.status()
        CodingEngine.CODEX -> subscription.runtimeStatus()
    }
    override fun ensureReady() = ensureReady(CodingEngine.PI)
    override fun ensureReady(engine: CodingEngine): Flow<RuntimeStatus> = flow {
        if (engine == CodingEngine.CODEX) {
            val codex = subscription.runtimeStatus()
            if (!codex.ready) { emit(codex); return@flow }
        }
        var adaptersReady = false
        pi.ensureReady().collect { adaptersReady = it.ready; emit(it) }
        if (engine == CodingEngine.CODEX && adaptersReady) emit(subscription.runtimeStatus())
    }
    override suspend fun preflight(engine: CodingEngine, profile: LlmProfile) {
        require(profile.configured && profile.supportsCoding) { "Настройте подключение модели" }
        if (profile.provider == ProviderType.OPENAI_SUBSCRIPTION) check(subscription.account().signedIn) { "Войдите в ChatGPT в настройках движков" }
        if (engine == CodingEngine.CODEX) { val status = subscription.runtimeStatus(); check(status.ready) { status.detail } }
        if (engine == CodingEngine.PI || profile.provider != ProviderType.OPENAI_SUBSCRIPTION) {
            val status = pi.ensureReady().last(); check(status.ready) { status.detail }
        }
    }
    override suspend fun reconcile(sessionId: String) { ResearchCheckRunner.shared.reconcile(sessionId); pi.reconcile(sessionId); (clients[sessionId] ?: subscription).reconcileCoding(sessionId) }
    override suspend fun nativeToolResults(session: CodingSession, callIds: Set<String>): List<CodingEvent.ToolFinished> =
        if (session.engine == CodingEngine.CODEX && session.piSessionId.isNotBlank())
            // Reconciliation may have disposed the worker client; history reads use the long-lived metadata connection.
            subscription.readCodingToolResults(session.piSessionId, callIds) else emptyList()
    override fun runPlanning(project: CodingProject, session: CodingSession, prompt: String, profile: LlmProfile): Flow<CodingEvent> = flow {
        require(session.projectId == project.id) { "План принадлежит другому проекту." }
        check(java.io.File(project.path).isDirectory) { "Папка проекта недоступна: ${project.path}" }
        val engine = checkNotNull(session.engine) { "Движок планировщика не сохранён." }
        check(active.add(session.id)) { "Запрос планирования уже выполняется." }
        val lease = try { ownership.begin(session.id, session.runtimeGeneration, currentCoroutineContext().job) }
        catch (e: Exception) { active.remove(session.id); throw e }
        val fresh = session.copy(piSessionId = "")
        try {
            val runId = java.util.UUID.randomUUID().toString()
            val input = prepareSkillInput(runId, project, fresh, prompt) { emit(it) } ?: return@flow
            preflight(engine, profile)
            ownership.checkCurrent(lease)
            when (engine) {
                CodingEngine.PI -> observeQuestionnaires(session.id, pi.questionnaires,
                    pi.runPlanning(project, input.session, input.prompt, subscription.withCachedContextWindow(profile))).collect { emit(it) }
                CodingEngine.CODEX -> {
                    val client = subscription.newCodingClient()
                    clients[session.id] = client
                    try {
                        if (profile.provider == ProviderType.OPENAI_SUBSCRIPTION) {
                            observeQuestionnaires(session.id, client.codingQuestionnaires,
                                client.runCoding(project, input.session, input.prompt, profile, emptyList(), planning = true))
                                .collect { emit(if (it is CodingEvent.UsageObserved) it.copy(accounting = false) else it) }
                        } else pi.startProviderBridge(profile.forModel()).use { bridge ->
                            observeQuestionnaires(session.id, client.codingQuestionnaires,
                                client.runCoding(project, input.session, input.prompt, profile, emptyList(), bridge.providerId, bridge.configuration,
                                    planning = true)).collect { emit(if (it is CodingEvent.UsageObserved) it.copy(accounting = false) else it) }
                        }
                    } finally {
                        withContext(NonCancellable) {
                            try { client.abortCoding(session.id); client.close() }
                            finally { clients.remove(session.id) }
                        }
                    }
                }
            }
        } catch (e: CancellationException) { abort(session.id); throw e }
        finally { ownership.finish(lease); active.remove(session.id) }
    }.withWakeGuard(awaitingUser = { questionnaires.value.any { it.sessionId == session.id } },
        onStalled = { withContext(Dispatchers.IO) { abort(session.id) } }).flowOn(Dispatchers.IO)

    private fun observeQuestionnaires(sessionId: String, source: StateFlow<List<UserInteractionRequest>>,
        events: Flow<CodingEvent>): Flow<CodingEvent> = channelFlow {
        val observer = launch { source.collect { current -> questionnaires.update { old ->
            old.filterNot { it.sessionId == sessionId } + current.filter { it.sessionId == sessionId }
        } } }
        try { events.collect { send(it) } }
        finally { withContext(NonCancellable) {
            observer.cancelAndJoin()
            questionnaires.update { old -> old.filterNot { it.sessionId == sessionId } }
        } }
    }

    override fun run(project: CodingProject, session: CodingSession, prompt: String, profile: LlmProfile?, attachments: List<Attachment>): Flow<CodingEvent> {
        val runId = session.pendingRun?.let { java.util.UUID.nameUUIDFromBytes((session.id.length.toString() + ":" + session.id + it.runId).encodeToByteArray()).toString() } ?: java.util.UUID.randomUUID().toString()
        session.forPendingRun()
        require(!session.planningMode) { "Планирование требует отдельного защищённого маршрута" }
        val events = runIdentified(runId, project, session, prompt, profile, attachments).withWakeGuard(
            awaitingUser = {
                approvals.value.any { it.sessionId == session.id } || questionnaires.value.any { it.sessionId == session.id }
            },
            onStalled = { withContext(Dispatchers.IO) { abort(session.id) } },
        )
        val effective = if (session.researchMode) events else runObserver.observe(runId, events) { runId in cancelledRuns }
        return (if (session.researchMode) channelFlow {
            val activeTool = java.util.concurrent.atomic.AtomicReference<CodingEvent.ToolStarted?>()
            val progressJob = launch {
                ResearchCheckRunner.shared.progress.collect { progress ->
                    if (progress.sessionId == session.id) activeTool.get()?.let { tool ->
                        send(CodingEvent.ToolProgress(tool.tool, tool.callId, progress.output))
                    }
                }
            }
            try { effective.collect { event ->
                if (event is CodingEvent.ToolStarted && event.tool.contains("research_check")) activeTool.set(event)
                send(event)
                if (event is CodingEvent.ToolFinished && event.tool.contains("research_check")) activeTool.set(null)
            } } finally { progressJob.cancel(); ResearchCheckRunner.shared.abort(session.id) }
        } else effective).onCompletion { runIds.remove(session.id, runId); cancelledRuns.remove(runId) }
    }

    private suspend fun prepareSkillInput(runId: String, project: CodingProject, session: CodingSession, prompt: String,
        publish: suspend (CodingEvent) -> Unit): CodingSkillInput? {
        val adapter = if (session.engine == CodingEngine.CODEX) "Codex" else "Pi"
        val fields = mapOf("operationId" to runId, "sessionId" to session.id, "projectId" to project.id,
            "engine" to checkNotNull(session.engine).name)
        val runFlags = session.featureFlags.resolve(globalFeatureFlags)
        val selection = try { cachedSkillSelection(project.id, runFlags).let { it.copy(instructions = it.instructions.map { s -> s.copy(permissions = s.permissions.toSet()) }) } } catch (e: CancellationException) { throw e } catch (error: Exception) {
            AppLog.error("session", "skills.load.failed", error, fields)
            publish(CodingEvent.Failed("Не удалось загрузить навыки. Проверьте подключённые пакеты."))
            publish(CodingEvent.Finished)
            return null
        }
        try {
            recordSkillRun(CodingSkillRunRecord(runId, project.id, session.id, adapter, selection))
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            // Audit record failure must not block the coding run — the engine
            // and skill selection are still valid; only the on-disk journal entry is missing.
            AppLog.error("session", "skills.audit.failed", e, fields)
            publish(CodingEvent.Notice("Не удалось сохранить сведения о подключённых навыках."))
        }
        val selected = selection.instructions
        if (selected.isNotEmpty() && (!selection.trustedText || !selection.freshSession)) {
            publish(CodingEvent.Notice("SKILLS run=$runId project=${project.id} session=${session.id} adapter=$adapter\n" +
                selected.joinToString("\n") { "${it.id}@${it.version} sha256=${it.checksum}; заявлено=${it.permissions}" } +
                "\nПередано: нет. Предоставлено пакету: нет. Результат задачи: не проверен."))
            publish(CodingEvent.Failed(CodingSkillProtection.reason(adapter) + " Требуется отдельное согласие на доверенный текст для точного состава в SKILLS."))
            publish(CodingEvent.Finished)
            return null
        }
        val input = prepareCodingSkillInput(session, prompt, selection)
        AppLog.debug("session", "skills.prepared", fields + mapOf(
            "count" to selected.size.toString(), "freshSession" to selection.freshSession.toString()))
        return input
    }

    private fun runIdentified(runId: String, project: CodingProject, session: CodingSession, prompt: String, profile: LlmProfile?, attachments: List<Attachment>): Flow<CodingEvent> = flow {
        // Reject stale UI/project routing before reading pins or resuming engine history.
        if (session.projectId != project.id) {
            emit(CodingEvent.Failed("SKILLS: сессия принадлежит другому проекту; запуск заблокирован."))
            emit(CodingEvent.Finished)
            return@flow
        }
        val engine = session.engine
        if (engine == null || profile == null) {
            emit(CodingEvent.Failed(if (engine == null) "Движок сессии не сохранён. Переоткройте проект." else "Выберите модель сессии.")); emit(CodingEvent.Finished); return@flow
        }
        check(active.add(session.id)) { "Сессия уже выполняется" }
        val lease = try { ownership.begin(session.id, session.runtimeGeneration, currentCoroutineContext().job) }
        catch (e: Exception) { active.remove(session.id); throw e }
        runIds[session.id] = runId
        var grant: Long? = if (session.researchMode) null else computerUse?.grant(session.id)
        try {
            val fields = mapOf("operationId" to runId, "sessionId" to session.id, "projectId" to project.id, "engine" to engine.name)
            val input = prepareSkillInput(runId, project, session, prompt) { emit(it) } ?: return@flow
            preflight(engine, profile)
            ownership.checkCurrent(lease)
            if (session.acquireComputerAccess && !session.researchMode && !session.planningMode &&
                session.stageId == null && session.role != CodingSessionRole.WORKER) {
                grant = computerUse?.begin(session.id)
            }
            ownership.checkCurrent(lease)
            when (engine) {
                CodingEngine.PI -> coroutineScope {
                    val questionsJob = launch { pi.questionnaires.collect { requests ->
                        questionnaires.update { previous -> previous.filterNot { it.sessionId == session.id } + requests.filter { it.sessionId == session.id } }
                    } }
                    try {
                        val events = pi.run(project, input.session, input.prompt, subscription.withCachedContextWindow(profile), attachments)
                        AppLog.debug("session", "skills.dispatched", fields)
                        events.collect { emit(it) }
                    }
                    finally { withContext(NonCancellable) {
                        questionsJob.cancelAndJoin()
                        questionnaires.update { previous -> previous.filterNot { it.sessionId == session.id } }
                    } }
                }
                CodingEngine.CODEX -> coroutineScope {
                    // A fresh connection makes provider overrides effective on resume. Codex ignores
                    // them for an already loaded thread; history itself remains in the same thread.
                    val client = subscription.newCodingClient()
                    clients[session.id] = client
                    val questionnairesJob = launch {
                        client.codingQuestionnaires.collect { requests ->
                            questionnaires.update { previous -> previous.filterNot { it.sessionId == session.id } + requests.filter { it.sessionId == session.id } }
                        }
                    }
                    val approvalsJob = launch {
                        client.codingApprovals.collect { current ->
                            approvals.update { previous -> previous.filterNot { it.sessionId == session.id } + current }
                        }
                    }
                    try {
                        if (profile.provider == ProviderType.OPENAI_SUBSCRIPTION) {
                            val events = client.runCoding(project, input.session, input.prompt, profile, attachments)
                            AppLog.debug("session", "skills.dispatched", fields)
                            events.collect { emit(it) }
                        } else {
                            pi.startProviderBridge(profile.forCoding()).use { bridge ->
                                val events = client.runCoding(project, input.session, input.prompt, profile, attachments, bridge.providerId, bridge.configuration)
                                AppLog.debug("session", "skills.dispatched", fields)
                                events.collect { emit(if (it is CodingEvent.UsageObserved) it.copy(accounting = false) else it) }
                            }
                        }
                    } finally {
                        withContext(NonCancellable) {
                            try { questionnairesJob.cancelAndJoin(); approvalsJob.cancelAndJoin(); client.close() }
                            finally {
                                questionnaires.update { previous -> previous.filterNot { it.sessionId == session.id } }
                                clients.remove(session.id)
                                approvals.update { previous -> previous.filterNot { it.sessionId == session.id } }
                            }
                        }
                    }
                }
            }
        } catch (e: CancellationException) { abort(session.id); throw e }
        catch (e: Exception) { emit(CodingEvent.Failed(e.message ?: "Не удалось запустить движок")); emit(CodingEvent.Finished) }
        finally {
            ownership.finish(lease)
            active.remove(session.id)
            grant?.let { computerUse?.release(session.id, it) }
        }
    }
    override fun abort(sessionId: String) { ownership.cancel(sessionId); ResearchCheckRunner.shared.abort(sessionId); runIds[sessionId]?.let { cancelledRuns.add(it) }; computerUse?.disable(sessionId); clients[sessionId]?.abortCoding(sessionId); pi.abort(sessionId) }
    override fun abortAll() { ownership.cancelAll(); ResearchCheckRunner.shared.abortAll(); cancelledRuns.addAll(runIds.values); computerUse?.disable(); clients.forEach { (id, client) -> client.abortCoding(id) }; pi.abortAll() }
    override suspend fun uninstall() = uninstall(CodingEngine.PI)
    override suspend fun uninstall(engine: CodingEngine) {
        check(active.isEmpty() && !pi.hasActiveRuns) { "Сначала остановите выполняющиеся сессии" }
        require(engine == CodingEngine.PI) { "Codex установлен отдельно; управляйте им через установщик приложения" }
        pi.uninstall()
    }
}
