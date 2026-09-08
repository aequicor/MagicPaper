package io.aequicor.magicpaper.data.coding

import io.aequicor.magicpaper.data.llm.CodexAppServerOpenAiSubscription
import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.data.skills.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.concurrent.ConcurrentHashMap

/** The persisted session engine is the only routing input. Providers supply model access. */
class DesktopCodingRuntime(
    private val pi: PiCodingRuntime,
    private val subscription: CodexAppServerOpenAiSubscription,
    override val projectSkills: ProjectSkills? = null,
    private val skillSnapshot: suspend (String) -> List<SkillInstruction> = { emptyList() },
    private val skillSelection: suspend (String) -> CodingSkillSelection = { CodingSkillSelection(skillSnapshot(it)) },
    private val recordSkillRun: suspend (CodingSkillRunRecord) -> Unit = {},
    private val experience: () -> LocalSkillExperience? = { null },
    private val resultVerifier: SkillRunVerifier = SkillRunVerifier { SkillRunVerification() },
) : CodingRuntime {
    override val computerUse get() = subscription.computerUse
    private val active = ConcurrentHashMap.newKeySet<String>()
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
    override suspend fun reconcile(sessionId: String) { pi.reconcile(sessionId); (clients[sessionId] ?: subscription).reconcileCoding(sessionId) }
    override fun run(project: CodingProject, session: CodingSession, prompt: String, profile: LlmProfile?, attachments: List<Attachment>): Flow<CodingEvent> {
        val runId = session.pendingRun?.let { skillRunIdentity(session.id, it.runId) } ?: java.util.UUID.randomUUID().toString()
        return runIdentified(runId, project, session, prompt, profile, attachments)
            .withSkillExperience(runId, experience, resultVerifier, cancelled = { runId in cancelledRuns })
            .onCompletion { runIds.remove(session.id, runId); cancelledRuns.remove(runId) }
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
        runIds[session.id] = runId
        val grant = computerUse?.grant(session.id)
        try {
            val adapter = if (engine == CodingEngine.CODEX) "Codex" else "Pi"
            val selection = try { skillSelection(project.id).let { it.copy(instructions = it.instructions.map { s -> s.copy(permissions = s.permissions.toSet()) }) } } catch (e: CancellationException) { throw e } catch (_: Exception) {
                emit(CodingEvent.Failed("SKILLS $runId: привязки или пакеты повреждены; запуск заблокирован."))
                emit(CodingEvent.Finished)
                return@flow
            }
            recordSkillRun(CodingSkillRunRecord(runId, project.id, session.id, adapter, selection))
            val selected = selection.instructions
            if (selected.isNotEmpty() && (!selection.trustedText || !selection.freshSession)) {
                emit(CodingEvent.Notice("SKILLS run=$runId project=${project.id} session=${session.id} adapter=$adapter\n" +
                    selected.joinToString("\n") { "${it.id}@${it.version} sha256=${it.checksum}; заявлено=${it.permissions}" } +
                    "\nПередано: нет. Предоставлено пакету: нет. Результат задачи: не проверен."))
                emit(CodingEvent.Failed(CodingSkillProtection.reason(adapter) + " Требуется отдельное согласие на доверенный текст для точного состава в SKILLS."))
                emit(CodingEvent.Finished)
                return@flow
            }
            val input = prepareCodingSkillInput(session, prompt, selection)
            emit(CodingEvent.Notice("SKILLS run=$runId project=${project.id} session=${session.id} adapter=$adapter\n" +
                selected.joinToString("\n") { "${it.id}@${it.version} sha256=${it.checksum}; заявлено=${it.permissions}" } +
                "\nПодготовлено инструкций: ${selected.size}. Новая engine-сессия: ${selection.freshSession}. " +
                "Фактические инструменты: штатная политика $adapter, отдельной ACL пакета нет. Передача ещё не подтверждена; результат задачи не проверен."))
            preflight(engine, profile)
            when (engine) {
                CodingEngine.PI -> coroutineScope {
                    val questionsJob = launch { pi.questionnaires.collect { requests ->
                        questionnaires.update { previous -> previous.filterNot { it.sessionId == session.id } + requests.filter { it.sessionId == session.id } }
                    } }
                    try {
                        val events = pi.run(project, input.session, input.prompt, profile, attachments)
                        emit(CodingEvent.Notice("SKILLS run=$runId: вход передан адаптеру Pi; принятие моделью и результат задачи не подтверждены."))
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
                            questionnaires.update { previous -> previous.filterNot { it.sessionId == session.id } + requests }
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
                            emit(CodingEvent.Notice("SKILLS run=$runId: вход передан адаптеру Codex; принятие моделью и результат задачи не подтверждены."))
                            events.collect { emit(it) }
                        } else {
                            pi.startProviderBridge(profile.forCoding()).use { bridge ->
                                val events = client.runCoding(project, input.session, input.prompt, profile, attachments, bridge.providerId, bridge.configuration)
                                emit(CodingEvent.Notice("SKILLS run=$runId: вход передан адаптеру Codex; принятие моделью и результат задачи не подтверждены."))
                                events.collect { emit(it) }
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
            active.remove(session.id)
            if (grant != null) computerUse?.release(session.id, grant)
        }
    }
    override fun abort(sessionId: String) { runIds[sessionId]?.let { cancelledRuns.add(it) }; computerUse?.disable(sessionId); clients[sessionId]?.abortCoding(sessionId); pi.abort(sessionId) }
    override fun abortAll() { cancelledRuns.addAll(runIds.values); computerUse?.disable(); clients.forEach { (id, client) -> client.abortCoding(id) }; pi.abortAll() }
    override suspend fun uninstall() = uninstall(CodingEngine.PI)
    override suspend fun uninstall(engine: CodingEngine) {
        check(active.isEmpty() && !pi.hasActiveRuns) { "Сначала остановите выполняющиеся сессии" }
        require(engine == CodingEngine.PI) { "Codex установлен отдельно; управляйте им через установщик приложения" }
        pi.uninstall()
    }
}
