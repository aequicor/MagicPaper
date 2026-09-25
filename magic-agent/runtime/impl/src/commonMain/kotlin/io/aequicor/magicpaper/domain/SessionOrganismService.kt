package io.aequicor.magicpaper.domain

import io.aequicor.magicpaper.data.coding.SessionOrganismStore
import io.aequicor.magicpaper.domain.tools.*
import io.aequicor.magicpaper.util.Id
import io.aequicor.magicpaper.logging.AppLog
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

/** Host evidence for the exact runtime that the user is explicitly retrying. */
data class PlanRetryRecoveryRequest(
    val session: CodingSession,
    val binding: SessionLegacyAttempt,
    val expectedNodeVersion: Long,
    val quarantines: List<SessionAuditEvent>,
)

/** Host evidence for a session whose run left an unknown tool outcome. */
data class SessionQuarantineRecoveryRequest(
    val session: CodingSession,
    val generation: Long,
    val quarantines: List<SessionAuditEvent>,
)
data class SessionQuarantineProof(val quarantineOperationIds: Set<String>, val evidence: List<String>)

enum class QuarantineRecoveryOutcome { RESOLVED, NEEDS_CONFIRMATION, NO_QUARANTINE }

/** The planning owner validates its saved user decision and child receipts at this owner boundary. */
internal fun interface PlanningQuarantineRecoveryAuthority {
    suspend fun validate(session: CodingSession, generation: Long, quarantineOperationIds: Set<String>)
}

internal fun SessionOrganism.unresolvedQuarantines(sessionId: String): List<SessionAuditEvent> = pendingQuarantines(sessionId)

/** Application-owned authority and projection bridge. Never treats model prose as a command. */
class SessionOrganismService(
    val store: SessionOrganismStore,
    private val projects: CodingProjectOwner,
    private val settings: SettingsRepository,
    private val sourceSnapshot: suspend (CodingProject) -> String? = { null },
    private val execution: SessionOrganismExecution,
    private val sources: SessionOrganismSourceAccess,
    private val integrationWorkspaces: SessionIntegrationWorkspaces? = null,
    private val canRecreateAfterQuarantine: suspend (Set<String>, SessionOrganism) -> Boolean = { _, _ -> false },
    private val reconcilePlanRetry: suspend (PlanRetryRecoveryRequest) -> PlanRetryRecoveryProof? = { null },
    private val reconcileUnknownOutcomes: suspend (SessionQuarantineRecoveryRequest) -> SessionQuarantineProof? = { null },
) {
    private val projectionLock = Mutex()
    private val limitPolicyLock = Mutex()
    private val supervisionLock = Mutex()
    private val deletingProjects = kotlinx.coroutines.flow.MutableStateFlow<Set<String>>(emptySet())
    private val supervision = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val json = Json { encodeDefaults = true }
    val projections get() = store.projections
    private val operationFailures = MutableStateFlow<Map<String, String>>(emptyMap())
    val failures = combine(store.failures, operationFailures) { persistence, execution -> persistence + execution }
        .stateIn(supervision, SharingStarted.Eagerly, emptyMap())
    private fun reportFailure(organismId: String, operation: String, failure: Throwable) {
        AppLog.error("organism", "operation_failed", mapOf("organismId" to organismId, "operation" to operation,
            "causeType" to failure::class.simpleName.orEmpty()))
        operationFailures.update { it + (organismId to "Не удалось подтвердить состояние задачи. Проверьте восстановление перед продолжением.") }
    }
    internal fun executionFailed(sessionId: String, operation: String, failure: Throwable) {
        val owner = store.organisms.value.values.firstOrNull { sessionId in it.sessions ||
            it.auxiliaryRuns.values.any { run -> run.sessionId == sessionId } }?.id ?: sessionId
        reportFailure(owner, operation, failure)
    }
    suspend fun stopSubtree(sessionIds: Set<String>) = execution.stop(sessionIds)

    private fun integrationSummary(record: SessionIntegration) = buildJsonObject {
        put("integrationId", record.request.id); put("phase", record.phase.name)
        put("verified", record.phase == SessionIntegrationPhase.VERIFIED)
        put("workspace", record.workspace?.integrationPath.orEmpty()); put("commitSha", record.commitSha)
        put("snapshot", record.snapshot); put("error", record.error)
        put("checks", record.checkResults.size); put("resultIds", json.encodeToJsonElement(record.request.resultIds))
    }

    private suspend fun integrateResults(context: ToolExecutionContext, operation: String, arguments: JsonObject,
        session: CodingSession, organism: SessionOrganism): JsonElement {
        val args = json.decodeFromJsonElement<SessionIntegrateArgs>(arguments)
        requireTool(context.mode in setOf(CodingInteractionMode.CODE, CodingInteractionMode.PLANNING) && !context.auxiliaryExecution) { "Интеграция требует разрешённого режима разработки" }
        val runner = integrationWorkspaces ?: throw ToolArgumentRejection("Интеграция с итоговыми проверками недоступна на этой платформе")
        val fingerprint = toolArgumentsFingerprint(arguments)
        val previous = organism.integrations[operation]
        val source = if (previous == null) sources.project(session, projects.all().first { it.id == session.projectId }) else null
        val request = previous?.request ?: SessionIntegrationRequest(operation, organism.id, session.id, context.runtimeGeneration,
            args.resultIds, args.checks, source!!.path, sourceSnapshot(source) ?: throw ToolArgumentRejection("Снимок исходников недоступен"))
        var admitted: Pair<SessionOrganism, Boolean>? = null
        repeat(4) { attempt ->
            if (admitted == null) try {
                admitted = store.admitIntegration(authority(context, organism).copy(expectedVersion = args.expectedVersion ?: store.get(organism.id).version), request, fingerprint)
            } catch (conflict: StaleSessionVersion) { if (args.expectedVersion != null || attempt == 3) throw conflict }
        }
        val (saved, fresh) = checkNotNull(admitted)
        if (!fresh) return integrationSummary(saved.integrations.getValue(operation))
        project(saved)
        val inputs = args.resultIds.map { id ->
            val result = saved.results.first { it.id == id }
            SessionIntegrationInput(result, saved.resultWorkspace(result) ?: error("Рабочая копия результата не подтверждена"))
        }
        val record = runner.integrate(source!!, saved.integrations.getValue(operation), inputs, sources.lease(session, source)) { checkpoint ->
            project(store.checkpointIntegration(organism.id, checkpoint))
        }
        if (record.phase !in setOf(SessionIntegrationPhase.VERIFIED, SessionIntegrationPhase.BLOCKED, SessionIntegrationPhase.CONFLICT))
            error(record.error.ifBlank { "Исход интеграции не подтверждён; повторное выполнение заблокировано" })
        return integrationSummary(store.get(organism.id).integrations.getValue(operation))
    }

    /** Cleanup is safe to retry; interrupted Git effects and checks are never re-executed. */
    suspend fun reconcileIntegrationsForSession(sessionId: String) {
        val pending = store.loadAll().flatMap { it.integrations.values }.filter { it.request.actorSessionId == sessionId &&
            it.phase !in setOf(SessionIntegrationPhase.VERIFIED, SessionIntegrationPhase.BLOCKED, SessionIntegrationPhase.CONFLICT) }
        if (pending.isEmpty()) return
        val runner = integrationWorkspaces ?: error("Не удалось подтвердить остановку интеграции")
        var failure: Throwable? = null
        pending.forEach { record ->
            try { runner.reconcile(record) }
            catch (cancelled: CancellationException) { failure?.let(cancelled::addSuppressed); throw cancelled }
            catch (problem: Exception) { if (failure == null) failure = problem else failure!!.addSuppressed(problem) }
        }
        failure?.let { throw it }
    }
    /** A terminal coroutine alone cannot prove that a native/tool effect is known. */
    private val immunityActions = Mutex()
    /** Called only by an explicit plan retry. Null preserves the unresolved stop. */
    /** Called only by an explicit recovery. Null preserves the quarantine; it never re-executes the effect. */

    suspend fun changeMode(session: CodingSession, mode: CodingInteractionMode): CodingSession {
        val organism = ensure(session)
        project(store.changeRootMode(organism.id, session.id, mode))
        return projects.sessions(session.projectId).first { it.id == session.id }
    }

    suspend fun prepareUserTurn(session: CodingSession, requestId: String) {
        // Admit a new explicit user conversation before its first native checkpoint is
        // persisted; a checkpoint discovered only during migration is interrupted work.
        val organism = ensure(session)
        project(store.prepareUserTurn(organism.id, session.id, requestId))
    }

    /** Explicit human recovery of a quarantined session. The unknown operation is never repeated here. */
    suspend fun reconcileQuarantine(session: CodingSession, userConfirmed: Boolean,
        expectedQuarantineOperationIds: Set<String>? = null): QuarantineRecoveryOutcome {
        val organism = ensure(session)
        val saved = store.get(organism.id)
        val node = saved.sessions[session.id] ?: return QuarantineRecoveryOutcome.NO_QUARANTINE
        val quarantines = saved.unresolvedQuarantines(node.id)
        require(expectedQuarantineOperationIds == null || expectedQuarantineOperationIds == quarantines.map { it.operationId }.toSet()) {
            "Состояние восстановления изменилось; повторите сверку"
        }
        if (quarantines.isEmpty()) return QuarantineRecoveryOutcome.NO_QUARANTINE
        require(saved.deletedAt == null && !saved.stoppedByUser && !node.archived && node.id !in saved.historyDeletedIds) {
            "Сессия удалена, архивирована или остановлена пользователем"
        }
        val stored = projects.sessions(session.projectId).firstOrNull { it.id == node.id } ?: error("Сессия удалена")
        // The organism owns the generation fence; the sidebar projection may lag behind it.
        val proof = reconcileUnknownOutcomes(SessionQuarantineRecoveryRequest(
            stored.copy(runtimeGeneration = node.generation), node.generation, quarantines))
        if (proof == null && !userConfirmed) return QuarantineRecoveryOutcome.NEEDS_CONFIRMATION
        val resolution = if (proof != null) {
            require(proof.quarantineOperationIds == quarantines.map { it.operationId }.toSet() &&
                proof.evidence.isNotEmpty() && proof.evidence.all { it.isNotBlank() }) {
                "Подтверждение исхода неполно; повторите сверку"
            }
            SessionQuarantineResolution(proven = true, userConfirmed = false,
                quarantineOperationIds = proof.quarantineOperationIds, evidence = proof.evidence)
        } else SessionQuarantineResolution(proven = false, userConfirmed = true,
            quarantineOperationIds = quarantines.map { it.operationId }.toSet(),
            evidence = quarantines.map { "Пользователь подтвердил фактический исход: ${it.reason}" })
        project(store.resolveSessionQuarantine(organism.id, node.id, resolution))
        return QuarantineRecoveryOutcome.RESOLVED
    }

    /** A saved plan decision acknowledges unknown effects; it never turns native UNKNOWN into success. */
    internal suspend fun reconcilePlanningQuarantine(session: CodingSession,
        authority: PlanningQuarantineRecoveryAuthority): QuarantineRecoveryOutcome {
        val organism = ensure(session)
        // Native termination does not prove that an independent integration released its workspace.
        reconcileIntegrationsForSession(session.id)
        val saved = store.get(organism.id)
        val node = checkNotNull(saved.sessions[session.id]) { "Сессия недоступна" }
        val quarantines = saved.unresolvedQuarantines(node.id)
        require(saved.deletedAt == null && !saved.stoppedByUser && !node.archived && node.id !in saved.historyDeletedIds) {
            "Сессия удалена, архивирована или остановлена пользователем"
        }
        val current = projects.sessions(session.projectId).singleOrNull { it.id == node.id } ?: error("Сессия удалена")
        val operations = quarantines.map { it.operationId }.toSet()
        authority.validate(current, node.generation, operations)
        if (operations.isEmpty()) return QuarantineRecoveryOutcome.NO_QUARANTINE
        project(store.resolveSessionQuarantine(organism.id, node.id, SessionQuarantineResolution(
            proven = false, userConfirmed = true, quarantineOperationIds = operations,
            evidence = listOf("Пользователь подтвердил фактический исход операций при восстановлении плана"))))
        return QuarantineRecoveryOutcome.RESOLVED
    }

    suspend fun authorizePlanRetry(plan: Plan, stageId: String, attempt: StageAttempt): PlanAttemptRetryAuthorization? {
        require(plan.projectId !in deletingProjects.value && plan.confirmedRevision != null) { "План недоступен для повтора" }
        require(plan.selectedMilestones.firstOrNull { it.id == stageId }?.attempts?.lastOrNull() == attempt) { "Попытка этапа заменена" }
        val parent = projects.sessions(plan.projectId).firstOrNull { it.id == plan.parentSessionId } ?: return null
        if (parent.organismId == null) return null
        val organism = ensure(parent)
        val node = organism.sessions[attempt.sessionId] ?: return null
        val requested = planBinding(plan, stageId, attempt)
        val continuation = confirmedContinuation(plan, stageId, attempt, node.legacyAttempt)
        val quarantines = organism.unresolvedQuarantines(node.id)
        if (node.observed == SessionObservedState.UNKNOWN || node.desired == SessionDesiredState.QUARANTINE ||
            node.desired == SessionDesiredState.STOP && (!node.settled || quarantines.isNotEmpty())) {
            require(organism.deletedAt == null && !organism.stoppedByUser && !node.archived && node.id !in organism.historyDeletedIds) {
                "Сессия удалена, архивирована или остановлена пользователем"
            }
            val admitted = node.legacyAttempt ?: error("Попытка этапа не сохранена")
            require(requested == admitted || continuation) { "Попытка или поколение этапа изменились" }
            require(node.observed !in setOf(SessionObservedState.RUNNING, SessionObservedState.WAITING_USER)) { "Предыдущий запуск ещё работает" }
            val session = projects.sessions(plan.projectId).firstOrNull { it.id == node.id } ?: error("Сессия удалена")
            // The plan checkpoint owns the worker's native history; the sidebar projection may lag.
            val request = PlanRetryRecoveryRequest(session.copy(runtimeGeneration = node.generation,
                engine = attempt.engine ?: session.engine, piSessionId = attempt.engineSessionId), admitted,
                node.version, quarantines)
            val proof = requireNotNull(reconcilePlanRetry(request)) { "Не удалось подтвердить исход предыдущего запуска; повтор пока недоступен" }
            val authorization = store.reconcileAndAuthorizePlanRetry(organism.id, OrganismRetryRequest(request.session.projectId, request.session.id, request.binding, request.expectedNodeVersion, request.quarantines), proof, requested, continuation)
            project(store.get(organism.id))
            return authorization
        }
        return store.authorizePlanRetry(organism.id, attempt.sessionId, requested, continuation)
    }

    suspend fun preparePlanAttempt(plan: Plan, stageId: String, attempt: StageAttempt): StageAttempt {
        require(plan.projectId !in deletingProjects.value) { "Проект удаляется" }
        require(plan.confirmedRevision != null && plan.intent == ExecutionIntent.RUN) { "План не подтверждён для выполнения" }
        val stage = plan.selectedMilestones.firstOrNull { it.id == stageId } ?: error("Этап не выбран")
        require(stage.attempts.lastOrNull()?.let { it.id == attempt.id && it.turnIndex == attempt.turnIndex } == true) { "Попытка этапа заменена" }
        val saved = projects.sessions(plan.projectId)
        val parent = saved.firstOrNull { it.id == plan.parentSessionId } ?: error("Родитель удалён")
        val organism = ensure(parent.copy(planningRulesSnapshot = parent.planningRulesSnapshot ?: plan.planningRulesSnapshot))
        val session = saved.firstOrNull { it.id == attempt.sessionId } ?: CodingSession(attempt.sessionId, plan.projectId, stage.title,
            attempt.startedAt, planId = plan.id, parentSessionId = parent.id, stageId = stageId,
            engine = attempt.engine, role = CodingSessionRole.WORKER,
            modelSelection = ModelSelection(attempt.assignment.profileId, attempt.assignment.modelId, attempt.assignment.effort))
        val task = SessionTask(stage.description, parent.id, stage.acceptance.ifBlank { stage.description }, attempt.baseCommit,
            stage.dependsOn.mapNotNull { dependency -> plan.milestones.firstOrNull { it.id == dependency }?.attempts?.lastOrNull()?.sessionId }.toSet())
        val admitted = store.admitPlanWorker(organism.id, session, task, planBinding(plan, stageId, attempt), plan.planningRulesSnapshot,
            plan.selectedMilestones.filterNot { it.completed }.map { it.id }.toSet(), attempt.retryAuthorization,
            confirmedContinuation(plan, stageId, attempt, organism.sessions[attempt.sessionId]?.legacyAttempt))
        project(admitted)
        return attempt.copy(sessionGeneration = admitted.sessions.getValue(session.id).generation)
    }

    private fun confirmedContinuation(plan: Plan, stageId: String, attempt: StageAttempt, admitted: SessionLegacyAttempt?): Boolean {
        if (admitted == null || admitted.turnIndex == Int.MAX_VALUE ||
            planBinding(plan, stageId, attempt) != admitted.copy(turnIndex = admitted.turnIndex + 1)) return false
        return plan.coordination.any { record -> record.id == "${attempt.id}-turn-${admitted.turnIndex}" &&
            record.runId == plan.runId && record.attemptId == attempt.id && record.stageId == stageId &&
            record.sourceSessionId == attempt.sessionId && record.turnIndex == admitted.turnIndex && record.status == HandoffStatus.RESOLVED }
    }

    private fun planBinding(plan: Plan, stageId: String, attempt: StageAttempt) = SessionLegacyAttempt(
        plan.id, plan.runId, stageId, attempt.id, attempt.turnIndex, attempt.sessionGeneration)

    suspend fun planAttemptCheckpoint(plan: Plan, stageId: String, attempt: StageAttempt) {
        if (attempt.sessionGeneration == 0L || attempt.phase != AttemptPhase.COMPLETE) return
        val acceptance = attempt.acceptanceRecord ?: error("Отсутствует приёмка этапа")
        require(acceptance.permitsProgress && acceptance.runId == plan.runId && acceptance.attemptId == attempt.id) { "Приёмка другого запуска или не завершена" }
        require(plan.workspace?.git != true || attempt.resultCommit.isNotBlank()) { "Git-результат не подтверждён" }
        val session = projects.sessions(plan.projectId).firstOrNull { it.id == attempt.sessionId } ?: error("Сессия результата удалена")
        val organismId = session.organismId ?: error("Организм этапа не сохранён")
        val result = SessionResult("plan-result-${plan.runId}-${attempt.id}", attempt.sessionId, attempt.sessionGeneration,
            plan.parentSessionId, attempt.report, artifacts = listOf(attempt.path).filter(String::isNotBlank) + acceptance.evidence.flatMap { it.artifacts },
            evidence = acceptance.evidence.map { "${it.criterionId}: ${it.status} — ${it.detail}" },
            sourceVersion = attempt.verificationSnapshot ?: acceptance.snapshotId, commitSha = attempt.resultCommit,
            checks = acceptance.findings.map { "${it.criterionId}: ${it.status} — ${it.observed}" }, accepted = true)
        project(store.acceptPlanResult(organismId, planBinding(plan, stageId, attempt), result))
        deliver(organismId)
    }

    /** Called after the planner has reconciled every native alias; never resolves UNKNOWN effects. */
    suspend fun planStopped(plan: Plan) {
        val sessions = projects.sessions(plan.projectId).associateBy { it.id }
        plan.milestones.flatMap { stage -> stage.attempts.map { stage.id to it } }.forEach { (stageId, attempt) ->
            val id = sessions[attempt.sessionId]?.organismId ?: return@forEach
            val node = store.get(id).sessions[attempt.sessionId] ?: return@forEach
            val binding = node.legacyAttempt
            if (binding != null && binding == planBinding(plan, stageId, attempt).copy(turnIndex = binding.turnIndex) && !node.settled && node.observed != SessionObservedState.UNKNOWN) {
                execution.stop(store.get(id).subtree(node.id))
                project(store.get(id))
            }
        }
    }

    suspend fun synchronizeCompletedPlan(plan: Plan) {
        plan.milestones.forEach { stage -> stage.attempts.filter { it.phase == AttemptPhase.COMPLETE && it.sessionGeneration > 0 }
            .forEach { planAttemptCheckpoint(plan, stage.id, it) } }
        if (plan.phase != ExecutionPhase.COMPLETE) return
        val root = projects.sessions(plan.projectId).firstOrNull { it.id == plan.parentSessionId } ?: return
        val id = root.organismId ?: return
        val organism = store.get(id); val node = organism.sessions.getValue(root.id)
        if (node.observed == SessionObservedState.PENDING && organism.subtree(root.id).all { it == root.id || organism.sessions.getValue(it).settled })
            project(store.observe(id, root.id, node.generation, SessionObservedState.COMPLETED))
    }

    /** One event-bound diagnostic pass; ordinary text never becomes evidence of a fault. */
    fun wakeImmunity(id: String) {
        supervision.launch {
            try { supervisionLock.withLock {
                val before = store.get(id)
                val inspected = store.inspectSignals(id)
                project(inspected)
                val affected = inspected.diagnoses.filter { diagnosis -> diagnosis.action == "QUARANTINE" &&
                    before.diagnoses.none { it.signalId == diagnosis.signalId } }.flatMap { it.affected }.toSet()
                if (affected.isNotEmpty()) {
                    try { execution.stop(affected) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { reportFailure(id, "immunity_stop", failure) }
                    finally { project(store.get(id)) }
                }
                project(store.proposeImmunityInterventions(id))
            } } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { reportFailure(id, "immunity_inspection", failure) }
        }
    }

    /** App-owned human action; the callback is supplied only after the separate deletion confirmation. */
    suspend fun approveImmunityIntervention(organismId: String, proposalId: String, action: ImmunityAction,
        deleteConfirmed: suspend (projectId: String, target: String) -> Unit = { _, _ -> error("Удаление требует отдельного подтверждения пользователя") },
    ) = immunityActions.withLock {
        val before = store.get(organismId)
        val proposal = before.interventions.single { it.id == proposalId }
        require(proposal.state != ImmunityInterventionState.REJECTED && (proposal.action == null || proposal.action == action)) { "Предложение уже рассмотрено иначе" }
        if (proposal.state == ImmunityInterventionState.COMPLETED) return@withLock
        if (proposal.state != ImmunityInterventionState.PROPOSED) {
            // Repeated confirmation may prove completion, but never repeats a possibly external effect.
            project(store.finishImmunityIntervention(organismId, proposalId))
            return@withLock
        }
        val node = before.sessions.getValue(proposal.target)
        val project = projects.all().first { it.id == before.projectId }
        var source: String? = null
        var reconciled = false
        if (action == ImmunityAction.RECREATE) {
            reconciled = canRecreateAfterQuarantine(before.subtree(node.id), before)
            require(reconciled) { "Сначала проверьте фактический исход выполнявшихся операций" }
            if (node.kind != SessionKind.ZYGOTE) {
                val parent = projects.sessions(before.projectId).firstOrNull { it.id == node.lifecycleParentId }
                    ?: error("Сначала восстановите рабочую область родителя")
                // Runtime-owned sourceProject also proves that this exact parent generation has a scope.
                source = sourceSnapshot(sources.project(parent, project))
                require(node.task != null && source != null && source == node.task!!.sourceVersion) { "Исходники задания изменились или не проверены" }
            } else {
                // A root is reopened for a new human task; no old task or native context is replayed.
                source = sourceSnapshot(project)
                require(node.mode != CodingInteractionMode.CODE || source != null) { "Исходники не проверены" }
            }
        }
        val admitted = store.acceptImmunityIntervention(organismId, proposalId, action,
            settings.load().planningRules.snapshot(), source, reconciled)
        try {
            project(admitted)
            when (action) {
                ImmunityAction.RECREATE -> if (node.kind != SessionKind.ZYGOTE) {
                    val child = projects.sessions(before.projectId).single { it.id == node.id }
                    execution.start(child, admitted.sessions.getValue(node.id).task ?: error("Задание не сохранено"))
                }
                ImmunityAction.DELETE_HISTORY -> deleteConfirmed(before.projectId, node.id)
                else -> {
                    execution.stop(admitted.interventions.single { it.id == proposalId }.affected)
                    project(store.finishStop(organismId, admitted.interventions.single { it.id == proposalId }.affected))
                }
            }
            project(store.finishImmunityIntervention(organismId, proposalId))
        } catch (failure: Throwable) {
            withContext(NonCancellable) {
                try { project(store.finishImmunityIntervention(organismId, proposalId, "Результат вмешательства неизвестен")) }
                catch (cleanup: Exception) { failure.addSuppressed(cleanup); reportFailure(organismId, "intervention_checkpoint", cleanup) }
            }
            throw failure
        }
    }

    suspend fun dismissImmunityIntervention(organismId: String, proposalId: String) {
        project(store.dismissImmunityIntervention(organismId, proposalId))
    }

    suspend fun pauseForReset() {
        val children = supervision.coroutineContext[Job]?.children?.toList().orEmpty()
        children.forEach { it.cancel() }
        children.joinAll()
        deletingProjects.value = emptySet()
        operationFailures.value = emptyMap()
    }

    suspend fun shutdown() { supervision.coroutineContext[Job]?.cancelAndJoin() }

    suspend fun restoreFromArchive(session: CodingSession) {
        val organism = ensure(session)
        val project = projects.all().first { it.id == session.projectId }
        project(store.restoreByUser(organism.id, session.id, Id.new(), settings.load().planningRules.snapshot(), sourceSnapshot(project)))
    }

    suspend fun setArchiveVisibility(session: CodingSession, archived: Boolean, stillReady: () -> Boolean = { true }) {
        val organismId = requireNotNull(session.organismId)
        project(store.setArchiveVisibility(organismId, session.id, session.runtimeGeneration, archived, stillReady))
    }

    /** Invoked only by the application's explicit rename control, never by a model tool adapter. */
    suspend fun renameByUser(session: CodingSession, name: String, operationId: String = Id.new()) {
        val organism = ensure(session)
        project(store.renameByUser(organism.id, session.id, name, operationId))
    }

    private suspend fun publishHistory(projectId: String, sessionId: String, messages: List<CodingMessage>) {
        val session = projects.sessions(projectId).first { it.id == sessionId }
        projects.dispatch(projectId, CodingMachine.Fact.HistoryPublished(
            CodingMachine.SessionRef(session.id, session.runtimeGeneration), messages))
    }

    suspend fun appendHistory(projectId: String, sessionId: String, message: CodingMessage) = projectionLock.withLock {
        require(projects.sessions(projectId).any { it.id == sessionId }) { "Сессия удалена" }
        store.organisms.value.values.firstOrNull { it.projectId == projectId && sessionId in it.sessions }?.let {
            require(sessionId !in store.get(it.id).historyDeletedIds) { "История удалена пользователем" }
        }
        val history = projects.messages(projectId, sessionId)
        val previous = history.firstOrNull { it.id == message.id }
        require(previous == null || previous == message) { "Сохранённое сообщение имеет другое содержимое" }
        if (previous == null) publishHistory(projectId, sessionId, history + message)
    }

    suspend fun pendingContext(session: CodingSession): List<SessionDelivery> {
        val id = session.organismId ?: return emptyList()
        deliver(id)
        val organism = store.get(id)
        var remaining = organism.limits.contextCharacters?.toLong()
        return organism.outbox.filter { it.recipient == session.id && it.recipientGeneration == session.runtimeGeneration && it.state == SessionDeliveryState.DELIVERED }
            .sortedBy { it.sequence }.takeWhile { delivery ->
                val size = json.encodeToString(SessionContextPacket.serializer(), delivery.packet).length
                (remaining == null || size <= remaining!!).also { if (it) remaining = remaining?.minus(size) }
            }
    }

    suspend fun acknowledgeContext(session: CodingSession, ids: List<String>) {
        val id = session.organismId ?: return
        ids.forEach { store.acknowledge(id, it, session.id, session.runtimeGeneration, processed = true) }
        project(store.get(id))
    }

    suspend fun recover() {
        val sessions = projects.all().flatMap { projects.sessions(it.id) }
        (store.loadAll().map { it.id } + sessions.mapNotNull { it.organismId }).distinct().forEach { id ->
            val saved = store.get(id)
            if (saved.deletedAt != null) { project(saved); return@forEach }
            // A previously removed project/root is not an invitation to recreate it.
            if (sessions.none { it.projectId == saved.projectId && it.id == saved.zygoteId }) return@forEach
            var restored = store.recover(id)
            val obsoleteImmunityId = restored.immunityId?.takeIf {
                restored.sessions[restored.zygoteId]?.mode != CodingInteractionMode.PLANNING &&
                    sessions.none { session -> session.projectId == restored.projectId &&
                        session.id == restored.zygoteId && session.planningMode }
            }
            if (obsoleteImmunityId != null) {
                try {
                    // Reuse the durable stop and history tombstone path. A crash after the
                    // tombstone is replayed by project(), without repeating a native effect.
                    deleteHistory(restored.projectId, obsoleteImmunityId)
                    restored = store.get(id)
                    AppLog.info("organism", "obsolete_immunity.deleted", mapOf("organismId" to id,
                        "sessionId" to obsoleteImmunityId))
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) {
                    reportFailure(id, "delete_obsolete_immunity", failure)
                    restored = store.get(id)
                }
            }
            project(restored)
            restored.integrations.values.filter { it.phase == SessionIntegrationPhase.UNKNOWN }.map { it.request.actorSessionId }.distinct().forEach { owner ->
                // Failure remains UNKNOWN. Cleanup never authorizes a repeated Git effect.
                try { reconcileIntegrationsForSession(owner) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { reportFailure(id, "integration_restore", failure) }
            }
            deliver(id)
            wakeImmunity(id)
        }
    }

    private suspend fun deleteProjection(projectId: String, sessionId: String) {
        val session = projects.sessions(projectId).firstOrNull { it.id == sessionId } ?: return
        projects.dispatch(projectId, CodingMachine.Intent.DeleteSession(CodingMachine.SessionRef(session.id, session.runtimeGeneration)))
    }

    /** Explicit UI deletion, including the independent immunity only when disposing its root. */
    suspend fun deleteHistory(projectId: String, sessionId: String? = null,
        stopLegacy: suspend (Set<String>) -> Unit = {},
    ): Set<String> = withContext(NonCancellable) { supervisionLock.withLock {
        if (sessionId == null) deletingProjects.value = deletingProjects.value + projectId
        try {
            val savedSessions = projects.sessions(projectId)
            val legacyIds = if (sessionId == null) savedSessions.map { it.id }.toSet() else savedSessions.sessionTreeIds(sessionId)
            val selected = store.loadAll().filter { it.projectId == projectId && (sessionId == null || sessionId in it.sessions) }
            val failures = mutableListOf<Throwable>()
            selected.forEach { organism ->
                if (organism.deletedAt == null) {
                    val roots = if (sessionId == null || sessionId == organism.zygoteId) listOfNotNull(organism.zygoteId, organism.immunityId) else listOf(sessionId)
                    roots.filterNot { it in organism.historyDeletedIds }.forEach { root ->
                        runCatching { store.requestUserStop(organism.id, root, Id.new(), archive = false) }.exceptionOrNull()?.let(failures::add)
                    }
                }
            }
            // Stop intent fences creation; read its final subtree after that fence is durable.
            val settledTargets = selected.associate { original ->
                val current = store.get(original.id)
                current.id to (if (sessionId == null || sessionId == current.zygoteId) current.sessions.keys else current.subtree(sessionId))
            }
            val ids = legacyIds + settledTargets.values.flatten()
            // A failed history/question write cannot prevent cancellation of other live owners.
            runCatching { stopLegacy(ids) }.exceptionOrNull()?.let(failures::add)
            if (settledTargets.isNotEmpty()) runCatching { execution.stop(settledTargets.values.flatten().toSet()) }.exceptionOrNull()?.let(failures::add)
            failures.firstOrNull()?.let { throw it }
            projectionLock.withLock {
                selected.forEach { organism ->
                    store.finishStop(organism.id, settledTargets.getValue(organism.id))
                    val deleted = store.deleteHistoryByUser(organism.id, sessionId)
                    // A crash partway through projection deletion is replayed from this tombstone.
                    deleted.historyDeletedIds.forEach { deleteProjection(projectId, it) }
                }
            }
            ids
        } finally { if (sessionId == null) deletingProjects.value = deletingProjects.value - projectId }
    } }

    /** Serialize policy reads and writes so an old snapshot cannot restore a removed limit. */
    suspend fun synchronizeLimits(organismId: String): SessionOrganism = limitPolicyLock.withLock {
        store.applyLimits(organismId, settings.load().agentLimits)
    }

    suspend fun synchronizeAllLimits() = limitPolicyLock.withLock {
        applyLimitsToAll(settings.load().agentLimits)
    }

    /** The settings owner has committed before calling this. Serialize propagation with policy
     * reads so an older captured read cannot overwrite the newly applied limits. */
    suspend fun applySettingsLimits(updated: AppSettings): Result<Unit> = limitPolicyLock.withLock {
        try {
            applyLimitsToAll(updated.agentLimits)
            Result.success(Unit)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    private suspend fun applyLimitsToAll(limits: OrganismLimits) {
        store.loadAll().filter { it.deletedAt == null }.forEach { store.applyLimits(it.id, limits) }
    }

    suspend fun ensure(session: CodingSession): SessionOrganism {
        require(session.projectId !in deletingProjects.value) { "Проект удаляется" }
        require(projects.all().any { it.id == session.projectId }) { "Проект удалён" }
        session.organismId?.let { return synchronizeLimits(it) }
        val sessions = projects.sessions(session.projectId)
        var root = sessions.firstOrNull { it.id == session.id } ?: session
        val seen = mutableSetOf<String>()
        while (root.parentSessionId != null) {
            require(seen.add(root.id)) { "Цикл происхождения сессий" }
            root = sessions.firstOrNull { it.id == root.parentSessionId } ?: error("Восстановите родительскую сессию")
        }
        root.organismId?.let { id -> return synchronizeLimits(id) }
        val ids = sessions.sessionTreeIds(root.id)
        val organism = limitPolicyLock.withLock {
            val configured = settings.load()
            val rules = root.planningRulesSnapshot ?: configured.planningRules.snapshot()
            val adopted = store.adopt(root.projectId, root.copy(planningRulesSnapshot = rules),
                sessions.filter { it.id in ids && it.id != root.id }, configured.agentLimits)
            // An earlier adoption may exist before its legacy projection has completed.
            store.applyLimits(adopted.id, configured.agentLimits)
        }
        project(organism)
        return organism
    }

    fun authority(context: ToolExecutionContext, organism: SessionOrganism): SessionAuthority = SessionAuthority(
        context.projectId, organism.id, context.ownerSessionId, context.runtimeGeneration, context.mode, context.stateVersion)

    suspend fun execute(context: ToolExecutionContext, operation: String, tool: String, arguments: JsonObject): JsonElement {
        val session = projects.sessions(context.projectId).firstOrNull { it.id == context.ownerSessionId } ?: error("Сессия удалена")
        val organism = ensure(session)
        requireTool(context.organismId == null || context.organismId == organism.id) { "Другой организм" }
        val actor = organism.sessions[session.id] ?: error("Сессия не входит в организм")
        val auxiliarySignal = context.auxiliaryExecution && tool == "immunity.signal"
        requireTool(actor.generation == context.runtimeGeneration && (actor.mode == context.mode || auxiliarySignal)) { "Полномочия запуска отозваны" }
        if (tool == "session.integration.get") {
            val args = json.decodeFromJsonElement<SessionIntegrationGetArgs>(arguments)
            val record = organism.integrations[args.integrationId] ?: throw ToolArgumentRejection("Интеграция не найдена")
            requireTool(record.request.actorSessionId == session.id || organism.immunityId == session.id) { "Интеграция другой рабочей области" }
            requireTool(args.offset >= 0 && args.limit in 1..16_000) { "Некорректная часть результата" }
            val content = json.encodeToString(SessionIntegration.serializer(), record)
            return buildJsonObject { put("integrationId", args.integrationId); put("totalCharacters", content.length)
                put("offset", args.offset); put("content", content.drop(args.offset).take(args.limit))
                put("truncated", args.offset + args.limit < content.length) }
        }
        if (tool == "session.results.integrate") return integrateResults(context, operation, arguments, session, organism)
        if (tool == "session.result.get") {
            val args = json.decodeFromJsonElement<SessionResultGetArgs>(arguments)
            val result = organism.results.firstOrNull { it.id == args.resultId } ?: error("Результат не найден")
            requireTool(result.sessionId == session.id || result.recipient == session.id || organism.immunityId == session.id) { "Результат другой рабочей области" }
            return json.encodeToJsonElement(result)
        }
        val suppliedVersion = arguments["expectedVersion"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.longOrNull
        requireTool(arguments["expectedVersion"] == null || arguments["expectedVersion"] is JsonNull || suppliedVersion != null) { "Некорректная версия состояния" }
        val scope = authority(context, organism).copy(expectedVersion = suppliedVersion ?: organism.version,
            mode = if (auxiliarySignal) actor.mode else context.mode)
        val command = when (tool) {
            "session.create" -> json.decodeFromJsonElement<SessionCreateArgs>(arguments).let { args ->
                // Reusing the caller's mutable checkout would bypass writer isolation.
                requireTool((args.mode ?: context.mode) != CodingInteractionMode.CODE || sources.canCreateCodeChild(session)) { "Дочерняя разработка требует выделенной рабочей копии" }
                val sourceSession = if (context.sessionId == session.id) session else session.copy(id = context.sessionId)
                val project = sources.project(sourceSession, projects.all().first { it.id == session.projectId })
                OrganismCommand(OrganismAction.CREATE, name = args.name, tokens = args.tokens,
                    task = SessionTask(args.task, session.id, args.acceptance, sourceSnapshot(project) ?: args.sourceVersion, args.dependencies), failurePolicy = args.failurePolicy, childMode = args.mode)
            }
            "session.send" -> json.decodeFromJsonElement<SessionSendArgs>(arguments).let { OrganismCommand(OrganismAction.SEND, it.sessionId, packet = it.context) }
            "session.route" -> json.decodeFromJsonElement<SessionRouteArgs>(arguments).let { OrganismCommand(OrganismAction.ROUTE, it.targetSessionId, reason = it.reason, source = it.sourceSessionId) }
            "session.result.review" -> json.decodeFromJsonElement<SessionReviewResultArgs>(arguments).let { args ->
                val result = organism.results.firstOrNull { it.id == args.resultId } ?: error("Результат не найден")
                if (args.accepted) {
                    val actual = sources.inspect(result)
                    requireTool(actual != null && result.sourceVersion == actual && args.sourceVersion == actual) { "Исходники изменились или не проверены; результат нельзя принять" }
                }
                OrganismCommand(OrganismAction.REVIEW_RESULT, result.sessionId, reason = args.evidence, resultId = args.resultId,
                    accepted = args.accepted, sourceVersion = args.sourceVersion, checks = args.checks)
            }
            "session.wait" -> json.decodeFromJsonElement<SessionWaitArgs>(arguments).let { OrganismCommand(OrganismAction.WAIT, dependencies = it.sessionIds) }
            "session.control" -> json.decodeFromJsonElement<SessionControlArgs>(arguments).let {
                requireTool(it.action in setOf(OrganismAction.STOP, OrganismAction.ARCHIVE, OrganismAction.RESTORE, OrganismAction.PAUSE, OrganismAction.QUARANTINE, OrganismAction.RENAME)) { "Недопустимая команда" }
                if (it.action == OrganismAction.RESTORE) {
                    val task = organism.sessions[it.sessionId]?.task
                    val sourceSession = if (context.sessionId == session.id) session else session.copy(id = context.sessionId)
                    val actual = sourceSnapshot(sources.project(sourceSession, projects.all().first { project -> project.id == session.projectId }))
                    requireTool(task != null && actual != null && task.sourceVersion == actual) { "Исходники изменились или не проверены; создайте новое задание" }
                }
                OrganismCommand(it.action, it.sessionId, it.name, it.reason, it.tokens)
            }
            "immunity.signal" -> json.decodeFromJsonElement<ImmunitySignalArgs>(arguments).let { OrganismCommand(OrganismAction.SIGNAL, it.sessionId, reason = it.diagnostic) }
            else -> error("Неизвестный инструмент сессии")
        }.copy(expectedVersion = suppliedVersion)
        suspend fun commit(): SessionOrganismMachine.Transition {
            var expected = scope
            repeat(4) { attempt ->
                try { return store.commandTransition(expected, operation, command) }
                catch (conflict: StaleSessionVersion) {
                    if (suppliedVersion != null || attempt == 3) throw conflict
                    // No effect occurred. Recheck authority and all command invariants in the
                    // transaction; a model-provided expectation is never silently rebased.
                    expected = expected.copy(expectedVersion = store.get(organism.id).version)
                }
            }
            error("Не удалось проверить версию")
        }
        val admitted = commit()
        val saved = checkNotNull(admitted.state.organism)
        val output = admitted.outputs.filterIsInstance<SessionOrganismMachine.Output.CommandAccepted>().single()
        project(saved)
        val target = output.target
        if (output.fresh) when (output.action) {
            OrganismAction.CREATE, OrganismAction.RESTORE -> {
                val child = projects.sessions(session.projectId).first { it.id == target }
                val node = saved.sessions.getValue(target)
                if (!node.settled) execution.start(child, node.task ?: error("Задание не сохранено"), context.sessionId)
            }
            OrganismAction.SEND -> deliver(saved.id)
            OrganismAction.SIGNAL -> wakeImmunity(saved.id)
            OrganismAction.WAIT -> execution.await(output.affected)
            OrganismAction.STOP, OrganismAction.ARCHIVE, OrganismAction.PAUSE, OrganismAction.QUARANTINE -> {
                val ids = output.affected
                execution.stop(ids)
                // The runtime owner must record each actual terminal observation before returning.
                project(store.finishStop(saved.id, ids))
            }
            else -> Unit
        }
        val final = store.get(saved.id)
        return buildJsonObject { put("operationId", operation); put("sessionId", target); put("organismId", saved.id)
            put("status", final.operations.getValue(operation).state.name); put("version", final.version) }
    }

    /** Read-only proof of a saved command. Never restarts a runtime or replays an external effect. */
    suspend fun reconcile(context: ToolExecutionContext, receipt: ToolReceipt): JsonElement? {
        if (receipt.native || !receipt.argumentsComplete || receipt.operationId.isBlank()) return null
        requireTool(receipt.id.startsWith("${context.projectId}/${context.ownerSessionId}/") &&
            receipt.runtimeGeneration == context.runtimeGeneration) { "Чужая квитанция или поколение" }
        val session = projects.sessions(context.projectId).firstOrNull { it.id == context.ownerSessionId } ?: return null
        val organismId = session.organismId ?: return null
        val organism = store.get(organismId)
        val actor = organism.sessions[session.id] ?: return null
        val auxiliarySignal = context.auxiliaryExecution && receipt.toolId == "immunity.signal"
        requireTool(organism.projectId == context.projectId && (context.organismId == null || context.organismId == organismId) &&
            actor.generation == context.runtimeGeneration && (actor.mode == context.mode || auxiliarySignal)) { "Полномочия запуска отозваны" }
        if (receipt.toolId == "session.results.integrate") {
            val record = organism.integrations[receipt.operationId] ?: return null
            if (record.request.actorSessionId != session.id || record.request.generation != context.runtimeGeneration ||
                organism.operations[receipt.operationId]?.fingerprint != toolArgumentsFingerprint(receipt.arguments) ||
                record.phase !in setOf(SessionIntegrationPhase.VERIFIED, SessionIntegrationPhase.BLOCKED, SessionIntegrationPhase.CONFLICT)) return null
            return integrationSummary(record)
        }
        val operation = organism.operations[receipt.operationId] ?: return null
        val audit = organism.audit.singleOrNull { it.operationId == operation.id } ?: return null
        if (audit.actor != actor.id || operation.state != SessionOperationState.SUCCEEDED) return null
        val target = organism.sessions[operation.target]
        // Reconstruct the normalized persisted command. A masked argument that no longer
        // reproduces its original fingerprint stays unresolved; secrets are never recovered.
        val command = runCatching {
            val args = receipt.arguments
            val decoded = when (receipt.toolId) {
                "session.create" -> json.decodeFromJsonElement<SessionCreateArgs>(args).let {
                    val source = target?.task?.sourceVersion ?: return null
                    if (operation.target != "session-${operation.id}") return null
                    OrganismCommand(OrganismAction.CREATE, name = it.name, tokens = it.tokens,
                        task = SessionTask(it.task, actor.id, it.acceptance, source, it.dependencies), failurePolicy = it.failurePolicy, childMode = it.mode)
                }
                "session.send" -> json.decodeFromJsonElement<SessionSendArgs>(args).let { OrganismCommand(OrganismAction.SEND, it.sessionId, packet = it.context) }
                "session.control" -> json.decodeFromJsonElement<SessionControlArgs>(args).let {
                    if (it.action !in setOf(OrganismAction.STOP, OrganismAction.ARCHIVE, OrganismAction.RESTORE,
                            OrganismAction.RENAME, OrganismAction.PAUSE, OrganismAction.QUARANTINE)) return null
                    OrganismCommand(it.action, it.sessionId, it.name, it.reason, it.tokens)
                }
                "session.wait" -> json.decodeFromJsonElement<SessionWaitArgs>(args).let { OrganismCommand(OrganismAction.WAIT, dependencies = it.sessionIds) }
                "session.route" -> json.decodeFromJsonElement<SessionRouteArgs>(args).let { OrganismCommand(OrganismAction.ROUTE, it.targetSessionId, reason = it.reason, source = it.sourceSessionId) }
                "session.result.review" -> json.decodeFromJsonElement<SessionReviewResultArgs>(args).let {
                    val result = organism.results.firstOrNull { result -> result.id == it.resultId } ?: return null
                    OrganismCommand(OrganismAction.REVIEW_RESULT, result.sessionId, reason = it.evidence, resultId = it.resultId,
                        accepted = it.accepted, sourceVersion = it.sourceVersion, checks = it.checks)
                }
                "immunity.signal" -> json.decodeFromJsonElement<ImmunitySignalArgs>(args).let { OrganismCommand(OrganismAction.SIGNAL, it.sessionId, reason = it.diagnostic) }
                else -> return null
            }
            decoded.copy(expectedVersion = args["expectedVersion"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.longOrNull)
        }.getOrNull() ?: return null
        if (audit.action != command.action.name || toolArgumentsFingerprint(json.encodeToJsonElement(command)) != operation.fingerprint) return null
        val proven = when (command.action) {
            OrganismAction.CREATE, OrganismAction.RESTORE -> target != null && (target.settled ||
                (target.lastStartedGeneration == target.generation && target.observed in setOf(SessionObservedState.RUNNING, SessionObservedState.WAITING_USER)))
            OrganismAction.SEND -> organism.outbox.any { it.id == operation.id && it.sender == actor.id && it.recipient == operation.target &&
                it.state in setOf(SessionDeliveryState.DELIVERED, SessionDeliveryState.PROCESSED) }
            OrganismAction.STOP, OrganismAction.ARCHIVE, OrganismAction.PAUSE, OrganismAction.QUARANTINE ->
                audit.affected.isNotEmpty() && audit.affected.all { organism.sessions[it]?.settled == true }
            OrganismAction.WAIT -> command.dependencies.isNotEmpty() && command.dependencies.all { organism.sessions[it]?.settled == true }
            OrganismAction.ROUTE -> organism.routeGrants.any { it.id == operation.id && it.grantedBy == actor.id }
            OrganismAction.REVIEW_RESULT -> organism.reviews.any { it.operationId == operation.id && it.reviewer == actor.id }
            OrganismAction.SIGNAL -> organism.signals.any { it.id == operation.id && it.sender == actor.id }
            OrganismAction.RENAME -> true
        }
        if (!proven) return null
        return buildJsonObject { put("operationId", operation.id); put("sessionId", operation.target); put("organismId", organism.id)
            put("status", operation.state.name); put("version", organism.version); put("reconciled", true)
            put("ownerDesiredState", actor.desired.name) }
    }

    /** Legacy session/history keys are rebuildable projections of the committed aggregate. */
    suspend fun project(snapshot: SessionOrganism) = projectionLock.withLock {
        // A slower projection must not overwrite a newer concurrently committed state.
        val organism = store.get(snapshot.id)
        if (projects.all().none { it.id == organism.projectId }) return@withLock
        organism.historyDeletedIds.forEach { deleteProjection(organism.projectId, it) }
        if (organism.deletedAt != null) return@withLock
        val projection = store.projection(organism.id)
        val revision = projection.revision
        projects.dispatch(organism.projectId, CodingMachine.Fact.OrganismProjected(projection.organism,
            CodingMachine.ChildRevision(revision.stream, revision.seq, revision.resetEpoch, revision.inputId)))
        // Иммунитет может отсутствовать в обычном режиме — проекция только для режима планирования
        organism.immunityId?.let { immunityId ->
        val immunityMessages = projects.messages(organism.projectId, immunityId)
        val missing = organism.signals.filter { signal -> immunityMessages.none { it.id == "signal-${signal.id}" } }.map { signal ->
            CodingMessage("signal-${signal.id}", CodingRole.USER, signal.diagnostic, createdAt = signal.createdAt,
                origin = MessageOrigin.SESSION, route = MessageRoute(SessionAddress(signal.sender,
                    organism.sessions.getValue(signal.sender).name, "Сессия"), SessionAddress(immunityId, "Иммунитет", "Иммунитет"), kind = "Диагностический сигнал"))
        }
        if (missing.isNotEmpty() && immunityId !in organism.historyDeletedIds)
            publishHistory(organism.projectId, immunityId, immunityMessages + missing)
        // Project the exact signal at its source, and repair a crash between saving a research
        // reply and delivering it back. Deterministic IDs never re-run the model.
        val researched = projects.messages(organism.projectId, immunityId)
        for (signal in organism.signals) {
            if (signal.sender in organism.historyDeletedIds) continue
            val source = organism.sessions[signal.sender] ?: continue
            val route = MessageRoute(SessionAddress(source.id, source.name, "Сессия"),
                SessionAddress(immunityId, "Иммунитет", "Иммунитет"), kind = "Диагностический сигнал")
            var history = projects.messages(organism.projectId, source.id)
            val sentId = "sent-signal-${signal.id}"
            if (history.none { it.id == sentId }) history = history + CodingMessage(sentId, CodingRole.AGENT,
                signal.diagnostic, createdAt = signal.createdAt, origin = MessageOrigin.SESSION, route = route)
            researched.firstOrNull { it.id == "immunity-report-${signal.id}" }?.let { report ->
                val receivedId = report.id + "-received"
                if (history.none { it.id == receivedId }) history = history + report.copy(id = receivedId,
                    origin = MessageOrigin.SESSION, route = MessageRoute(route.target, route.source, kind = "Результат диагностики"))
            }
            if (history != projects.messages(organism.projectId, source.id)) publishHistory(organism.projectId, source.id, history)
        }
        organism.diagnoses.forEach { diagnosis ->
            (diagnosis.affected + immunityId).filterNot { it in organism.historyDeletedIds }.forEach { sessionId ->
                val history = projects.messages(organism.projectId, sessionId)
                val messageId = "diagnosis-${diagnosis.signalId}-$sessionId"
                if (history.none { it.id == messageId }) publishHistory(organism.projectId, sessionId, history + CodingMessage(
                    messageId, CodingRole.AGENT,
                    if (diagnosis.evidence.isEmpty()) "Автоматическая проверка состояния: оснований для карантина не найдено. Содержание обращения требует отдельной диагностики."
                    else "Иммунитет: карантин. ${diagnosis.evidence.joinToString("; ")}", createdAt = diagnosis.createdAt,
                    origin = MessageOrigin.TOOL, systemNotice = true))
            }
        }
        } // immunityId?.let
        organism.outbox.flatMap { listOf(it.sender, it.recipient) }.distinct().filterNot { it in organism.historyDeletedIds }.forEach { sessionId ->
            val history = projects.messages(organism.projectId, sessionId)
            val updated = history.map { message ->
                val delivery = organism.outbox.firstOrNull { it.id == message.deliveryId }
                if (delivery == null || message.route == null) message else message.copy(
                    route = message.route!!.copy(sessionDeliveryState = delivery.state),
                    text = if (message.id == "sent-${delivery.id}" && message.contextPacket == null) delivery.packet.text else message.text,
                    contextPacket = if (message.id == "sent-${delivery.id}") delivery.packet else message.contextPacket)
            }
            if (updated != history) publishHistory(organism.projectId, sessionId, updated)
        }
    }

    /** Outbox survives a crash before either projection; deterministic IDs deduplicate both logs. */
    suspend fun deliver(organismId: String) {
        val organism = store.get(organismId)
        if (organism.deletedAt != null) return
        organism.outbox.filter { it.state in setOf(SessionDeliveryState.ACCEPTED, SessionDeliveryState.DELIVERED) }.forEach { candidate ->
            projectionLock.withLock {
                val latest = store.get(organismId)
                val delivery = latest.outbox.first { it.id == candidate.id }
                if (latest.deletedAt != null || delivery.route.any { it in latest.historyDeletedIds }) return@withLock
                if (delivery.state !in setOf(SessionDeliveryState.ACCEPTED, SessionDeliveryState.DELIVERED)) return@withLock
                val sessions = projects.sessions(organism.projectId).associateBy { it.id }
                val source = sessions[delivery.sender] ?: error("Отправитель удалён")
                val recipient = sessions[delivery.recipient] ?: error("Получатель удалён")
                require(recipient.runtimeGeneration == delivery.recipientGeneration) { "Поколение получателя изменилось" }
                val route = MessageRoute(SessionAddress(source.id, source.name, source.subtitle()), SessionAddress(recipient.id, recipient.name, recipient.subtitle()),
                    kind = "Контекст от сессии ${source.name}", deliveryId = delivery.id,
                    sessionDeliveryState = SessionDeliveryState.DELIVERED,
                    hops = delivery.route.map { id -> val hop = sessions.getValue(id); SessionAddress(hop.id, hop.name, hop.subtitle()) })
                suspend fun append(id: String, message: CodingMessage) {
                    val messages = projects.messages(organism.projectId, id)
                    if (messages.none { it.id == message.id }) publishHistory(organism.projectId, id, messages + message)
                }
                append(recipient.id, CodingMessage("context-${delivery.id}", CodingRole.USER, delivery.packet.text,
                    createdAt = Id.now(), deliveryId = delivery.id, route = route, origin = MessageOrigin.SESSION, contextPacket = delivery.packet))
                append(source.id, CodingMessage("sent-${delivery.id}", CodingRole.AGENT, delivery.packet.text,
                    createdAt = Id.now(), deliveryId = delivery.id, route = route, origin = MessageOrigin.TOOL, contextPacket = delivery.packet))
                store.acknowledge(organism.id, delivery.id, recipient.id, delivery.recipientGeneration, processed = false)
            }
        }
    }
}

/** Keep transient tool/process context outside the durable organism owner. */
internal fun ToolExecutionContext.organismAdmission() = OrganismAuxiliaryAdmission(projectId,
    requireNotNull(organismId), ownerSessionId, sessionId, runtimeGeneration, runId, requestId,
    mode, role, planId, auxiliaryExecution)
