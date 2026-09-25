package io.aequicor.magicpaper.data.coding

import io.aequicor.magicpaper.backend.BackendAgentCapability
import io.aequicor.magicpaper.data.llm.CodexAppServerOpenAiSubscription
import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.logging.AppLog
import io.aequicor.magicpaper.domain.checks.CommandChecks
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
    private val engines: List<NativeRuntimeBinding>,
    override val computerUse: NativeComputerUse?,
    private val skillSelection: suspend (String) -> CodingSkillSelection,
    private val checks: CommandChecks,
    private val recordSkillRun: suspend (CodingSkillRunRecord) -> Unit = {},
    private val runObserver: CodingRunObserver = CodingRunObserver { _, events, _ -> events },
    private val workspaceRootPath: String = engines.first().runtime.rootPath,
    private val selections: EngineExecutableSelections? = null,
) : CodingRuntime {
    private val byEngine = engines.associateBy { it.descriptor.engine }.also {
        require(it.isNotEmpty() && it.size == engines.size) { "Native engine registrations must be unique and non-empty" }
    }
    private fun binding(engine: CodingEngine) = checkNotNull(byEngine[engine]) { "Движок недоступен в этой сборке" }
    override val recovery = object : NativeRunRecovery {
        override suspend fun inspect(sessionId: String): NativeRunRecoverySnapshot {
            val snapshots = engines.map { checkNotNull(it.runtime.recovery).inspect(sessionId) }
            return NativeRunRecoverySnapshot(snapshots.flatMap { it.items }, snapshots.any { it.persistenceUnknown },
                snapshots.flatMap { it.noDispatch }, snapshots.flatMap { it.consumptions })
        }
        override suspend fun stop(ref: NativeRunRecoveryRef) = checkNotNull(binding(ref.engine).runtime.recovery).stop(ref)
        override suspend fun acknowledge(ref: NativeRunRecoveryRef, parentDecisionId: String) =
            checkNotNull(binding(ref.engine).runtime.recovery).acknowledge(ref, parentDecisionId)
        override suspend fun acknowledgeNoDispatch(proof: NativeRunNoDispatchProof, parentDecisionId: String) =
            checkNotNull(binding(proof.engine).runtime.recovery).acknowledgeNoDispatch(proof, parentDecisionId)
    }

    override val modelSources: Map<CodingEngine, CodingModelSource> =
        engines.flatMap { it.runtime.modelSources.entries }.associate { it.key to it.value }

    override var globalFeatureFlags: FeatureFlagState = FeatureFlagState()
        set(value) {
            field = value
            engines.forEach { it.runtime.globalFeatureFlags = value }
        }

    override fun configureEngineExecutables(paths: Map<CodingEngine, String>) { selections?.update(paths) }

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
            session.engine?.let { appendLine(backendCatalog.descriptor(it).skillLoadingSummary) }
            appendLine("Инструменты и доступ: ${when {
                session.researchMode -> "чтение проекта и Git; запись в исходники запрещена; проверки только через research_check в песочнице ОС; повышение прав и управление компьютером отключены"
                session.planningMode -> "режим чтения проекта"
                else -> "политика выбранного движка; доступ к компьютеру и приложениям задаётся в настройках, действует только на явно запущенный запрос"
            }}.")
        }
        sessionContextReport(effective, environment,
            codingSystemPrompt(session.engine, session.planningMode, effective?.advanced?.systemPromptOverride.orEmpty(), session.researchMode, session.runtimePlanningRules, flags), skills)
    }

    private val active = ConcurrentHashMap.newKeySet<String>()
    private val ownership = CodingRuntimeOwnership()
    private val runIds = ConcurrentHashMap<String, String>()
    private val cancelledRuns = ConcurrentHashMap.newKeySet<String>()
    override val approvals = MutableStateFlow<List<CodingApproval>>(emptyList())
    override val questionnaires = MutableStateFlow<List<UserInteractionRequest>>(emptyList())
    override suspend fun respondQuestionnaire(id: String, answers: List<PlanningAnswer>) {
        val owner = engines.firstOrNull { binding -> binding.runtime.questionnaires.value.any { it.id == id } }
            ?: error("Обращение уже закрыто")
        owner.runtime.respondQuestionnaire(id, answers)
    }

    override suspend fun respondApproval(id: String, decision: CodingApprovalDecision) {
        engines.firstOrNull { binding -> binding.runtime.approvals.value.any { it.id == id } }
            ?.runtime?.respondApproval(id, decision)
    }
    override fun runChat(session: ChatSession, prompt: String, profile: LlmProfile?, attachments: List<Attachment>): Flow<CodingEvent> = flow {
        val directory = chatDirectory(session.id)
        withContext(Dispatchers.IO) { check(directory.isDirectory || directory.mkdirs()) { "Не удалось создать рабочую папку чата" } }
        val project = CodingProject("chat-${session.id}", session.title, directory.absolutePath, session.createdAt)
        val coding = session.asResearchCodingSession(project.id)
        val history = if (session.nativeSessionId.isBlank()) session.messages.dropLast(1).map {
            CodingMessage(it.id, if (it.role == ChatRole.USER) CodingRole.USER else CodingRole.AGENT, it.text, createdAt = it.createdAt)
        } else emptyList()
        val request = researchRequest(session.messages.lastOrNull { it.role == ChatRole.USER }?.text ?: prompt)
        val seeded = researchContextSeed(history, profile?.advanced?.contextMessages ?: 20, "") + researchPrompt(prompt, session.resources, request)
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
    override val rootPath: String get() = workspaceRootPath
    override suspend fun status() = status(engines.first().descriptor.engine)
    override suspend fun status(engine: CodingEngine): RuntimeStatus = binding(engine).runtime.status().withCapabilities(engine)

    private fun RuntimeStatus.withCapabilities(engine: CodingEngine): RuntimeStatus {
        val capabilities = binding(engine).descriptor.capabilities
        return copy(dependenciesRemovable = BackendAgentCapability.MANAGED_INSTALLATION in capabilities,
            verifiesExternalInstall = BackendAgentCapability.EXTERNAL_INSTALLATION in capabilities)
    }
    override fun ensureReady() = ensureReady(engines.first().descriptor.engine)
    override fun ensureReady(engine: CodingEngine): Flow<RuntimeStatus> =
        binding(engine).runtime.ensureReady().map { it.withCapabilities(engine) }
    override suspend fun signIn(engine: CodingEngine) = binding(engine).runtime.signIn(engine)
    override suspend fun signOut(engine: CodingEngine) = binding(engine).runtime.signOut(engine)
    override suspend fun preflight(engine: CodingEngine, profile: LlmProfile) {
        require(profile.configured && profile.supportsCoding) { "Настройте подключение модели" }
        binding(engine).runtime.preflight(profile)
    }
    override suspend fun reconcile(sessionId: String) {
        checks.reconcile(sessionId)
        engines.forEach { it.runtime.reconcile(sessionId) }
    }
    override suspend fun nativeToolResults(session: CodingSession, callIds: Set<String>): List<CodingEvent.ToolFinished> {
        val selected = session.engine?.let(::binding) ?: return emptyList()
        return if (BackendAgentCapability.NATIVE_TOOL_HISTORY in selected.descriptor.capabilities && session.piSessionId.isNotBlank())
            selected.runtime.nativeToolResults(session, callIds) else emptyList()
    }
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
            val selected = binding(engine).runtime
            observeInteractions(session.id, selected, selected.runPlanning(project, input.session, input.prompt, profile).flowOn(NativeRunContext(runId)))
                .collect { emit(it) }
        } catch (e: CancellationException) { abort(session.id); throw e }
        finally { ownership.finish(lease); active.remove(session.id) }
    }.withWakeGuard(awaitingUser = { questionnaires.value.any { it.sessionId == session.id } },
        onStalled = { withContext(Dispatchers.IO) { abort(session.id) } }).flowOnPreservingOutput(Dispatchers.IO)

    private fun observeInteractions(sessionId: String, runtime: CodingRuntime,
        events: Flow<CodingEvent>): Flow<CodingEvent> = flow { coroutineScope {
        val questions = launch { runtime.questionnaires.collect { current -> questionnaires.update { old ->
            old.filterNot { it.sessionId == sessionId } + current.filter { it.sessionId == sessionId }
        } } }
        val permissions = launch { runtime.approvals.collect { current -> approvals.update { old ->
            old.filterNot { it.sessionId == sessionId } + current.filter { it.sessionId == sessionId }
        } } }
        try { events.collect { emit(it) } }
        finally { withContext(NonCancellable) {
            questions.cancelAndJoin()
            permissions.cancelAndJoin()
            questionnaires.update { old -> old.filterNot { it.sessionId == sessionId } }
            approvals.update { old -> old.filterNot { it.sessionId == sessionId } }
        } }
    } }

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
            try { coroutineScope {
            val activeTool = java.util.concurrent.atomic.AtomicReference<CodingEvent.ToolStarted?>()
            val progressJob = launch {
                checks.progress.collect { progress ->
                    if (progress.ref.scope.sessionId == session.id) activeTool.get()?.let { tool ->
                        send(CodingEvent.ToolProgress(tool.tool, tool.callId, progress.output))
                    }
                }
            }
            try { effective.collect { event ->
                if (event is CodingEvent.ToolStarted && event.tool.contains("research_check")) activeTool.set(event)
                send(event)
                if (event is CodingEvent.ToolFinished && event.tool.contains("research_check")) activeTool.set(null)
            } } finally { progressJob.cancel(); checks.abort(session.id) }
            } } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Throwable) {
                // Closing preserves queued output; throwing here would cancel and discard it.
                close(failure)
            }
        } else effective).onCompletion { runIds.remove(session.id, runId); cancelledRuns.remove(runId) }
    }

    private suspend fun prepareSkillInput(runId: String, project: CodingProject, session: CodingSession, prompt: String,
        publish: suspend (CodingEvent) -> Unit): CodingSkillInput? {
        val adapter = binding(checkNotNull(session.engine)).descriptor.adapterName
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
        var grant: ComputerLease? = if (session.researchMode) null else computerUse?.grant(session.id)
        val engineRun = flow {
            val fields = mapOf("operationId" to runId, "sessionId" to session.id, "projectId" to project.id, "engine" to engine.name)
            val input = prepareSkillInput(runId, project, session, prompt) { emit(it) } ?: return@flow
            preflight(engine, profile)
            ownership.checkCurrent(lease)
            if ((session.acquireComputerAccess || computerUse?.grant(session.id) != null) && !session.researchMode && !session.planningMode &&
                session.stageId == null && session.role != CodingSessionRole.WORKER) {
                grant = computerUse?.begin(session.id, runId)
            }
            ownership.checkCurrent(lease)
            val selected = binding(engine).runtime
            AppLog.debug("session", "skills.dispatched", fields)
            emitAll(observeInteractions(session.id, selected,
                selected.run(project, input.session, input.prompt, profile, attachments).flowOn(NativeRunContext(runId))))
        }.catch { failure ->
            // Only an engine failure becomes the run's reported result. A failure the collector throws from emit, such as a
            // refused journal dispatch, is not seen here and propagates as it is: emitting after it violates exception transparency.
            if (failure !is Exception || failure is CancellationException || failure is NativeRunRecoveryRequired) throw failure
            emit(CodingEvent.Failed(failure.message ?: "Не удалось запустить движок")); emit(CodingEvent.Finished)
        }
        try { emitAll(engineRun) }
        catch (e: CancellationException) { abort(session.id); throw e }
        finally {
            ownership.finish(lease)
            active.remove(session.id)
            grant?.let { computerUse?.release(it) }
        }
    }
    override fun abort(sessionId: String) {
        ownership.cancel(sessionId)
        checks.abort(sessionId)
        runIds[sessionId]?.let { cancelledRuns.add(it) }
        computerUse?.disable(sessionId)
        engines.forEach { it.runtime.abort(sessionId) }
    }
    override fun abortAll() {
        ownership.cancelAll()
        checks.abortAll()
        cancelledRuns.addAll(runIds.values)
        computerUse?.disable()
        engines.forEach { it.runtime.abortAll() }
    }
    override suspend fun uninstall() = uninstall(engines.first().descriptor.engine)
    override suspend fun uninstall(engine: CodingEngine) {
        check(active.isEmpty()) { "Сначала остановите выполняющиеся сессии" }
        val selected = binding(engine)
        require(BackendAgentCapability.MANAGED_INSTALLATION in selected.descriptor.capabilities) { "Движок установлен отдельно; управляйте им через установщик приложения" }
        selected.runtime.uninstall()
    }
}
