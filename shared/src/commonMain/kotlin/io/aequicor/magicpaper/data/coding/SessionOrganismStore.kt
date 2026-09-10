package io.aequicor.magicpaper.data.coding

import io.aequicor.magicpaper.data.storage.KeyValueStore
import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.domain.tools.toolArgumentsFingerprint
import io.aequicor.magicpaper.domain.tools.requireTool
import io.aequicor.magicpaper.domain.tools.ToolExecutionContext
import io.aequicor.magicpaper.domain.tools.ToolRole
import io.aequicor.magicpaper.util.Id
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement

/**
 * A single application instance owns this store. A command commits entities, its receipt,
 * audit, outbox and resource allocation in one key replacement. No model/tool/user wait
 * happens inside a transaction. The key-value adapter supplies atomic durable replacement;
 * this is not a transaction with legacy projections, Git, native runtimes or external APIs.
 */
class SessionOrganismStore(private val storage: KeyValueStore, private val clock: () -> Long = Id::now) {
    private val lock = Mutex()
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private val state = MutableStateFlow<Map<String, SessionOrganism>>(emptyMap())
    val organisms = state.asStateFlow()
    var knownSecrets: () -> Set<String> = { emptySet() }
    private fun redact(text: String): String = PlanningDiagnostics.redact(text, knownSecrets())
    private fun SessionContextPacket.redacted() = copy(text = redact(text), sourceVersion = redact(sourceVersion),
        ruleVersion = redact(ruleVersion), resultIds = resultIds.map(::redact), attachments = attachments.map(::redact), omissions = redact(omissions))
    private fun SessionTask.redacted() = copy(text = redact(text), acceptance = redact(acceptance), sourceVersion = redact(sourceVersion))
    private fun key(id: String) = "session-organism-$id"
    private fun read(id: String): SessionOrganism = storage.read(key(id))?.let { migrateLimits(json.decodeFromString<SessionOrganism>(it)) }
        ?: error("Организм не найден")

    private fun commit(next: SessionOrganism): SessionOrganism {
        val encoded = json.encodeToString(next)
        try { storage.write(key(next.id), encoded) } catch (error: Exception) {
            // Some adapters can fail after atomic replacement (e.g. manifest update).
            // Read back the operation identity instead of blindly repeating its effect.
            if (runCatching { storage.read(key(next.id)) }.getOrNull() != encoded) throw error
        }
        state.value = state.value + (next.id to next)
        return next
    }

    suspend fun get(id: String): SessionOrganism = lock.withLock { read(id).also { state.value += id to it } }

    private fun Int?.allows(value: Int): Boolean = this == null || value <= this
    private fun Int?.hasRoom(occupied: Int): Boolean = this == null || occupied < this
    private fun String.takeConfigured(limit: Int?): String = if (limit == null) this else take(limit)
    private fun SessionOrganism.withinDuration(): Boolean = limits.durationMillis?.let { clock() - createdAt <= it } ?: true
    private fun SessionOrganism.hasTokenBudget(): Boolean = limits.tokens?.let { total -> sessions.values.sumOf { it.spentTokens } < total } ?: true

    /** Old persisted limits came from application defaults, not an explicit user choice. */
    private fun migrateLimits(old: SessionOrganism): SessionOrganism {
        if (old.limitPolicyVersion >= 1) return old
        return commit(old.copy(version = old.version + 1, limitPolicyVersion = 1, limits = OrganismLimits(),
            sessions = old.sessions.mapValues { (_, node) ->
                if (node.remainingTokens == 0L) node else node.copy(remainingTokens = 0, version = node.version + 1)
            }, audit = old.audit + SessionAuditEvent("limit-policy-${old.id}", "APPLICATION", "LIMIT_POLICY_MIGRATION",
                old.sessions.keys, "Неявные ограничения сняты; расход и состояния запусков сохранены", clock())))
    }

    /** Settings change accounting authority without reopening stopped or uncertain work. */
    suspend fun applyLimits(id: String, limits: OrganismLimits): SessionOrganism = lock.withLock {
        limits.validate()
        val old = read(id)
        if (old.limits == limits) return@withLock old
        val nodes = if (old.limits.tokens == limits.tokens) old.sessions else {
            val available = limits.tokens?.let { (it - old.sessions.values.sumOf { node -> node.spentTokens }).coerceAtLeast(0) } ?: 0
            val recovery = minOf(limits.recoveryTokens, available)
            old.sessions.mapValues { (sessionId, node) ->
                val remaining = when (sessionId) {
                    old.zygoteId -> available - recovery
                    old.immunityId -> recovery
                    else -> 0
                }
                if (node.remainingTokens == remaining) node else node.copy(remainingTokens = remaining, version = node.version + 1)
            }
        }
        commit(old.copy(version = old.version + 1, limitPolicyVersion = 1, limits = limits, sessions = nodes,
            audit = old.audit + SessionAuditEvent("limits-${old.id}-${old.version + 1}", "USER", "LIMITS_CHANGED",
                old.sessions.keys, "Применены ограничения из настроек; фактический расход сохранён", clock())))
    }

    /** Discover a committed aggregate even if the first legacy projection never completed. */
    suspend fun loadAll(): List<SessionOrganism> = lock.withLock {
        storage.keys("session-organism-").map { key ->
            migrateLimits(json.decodeFromString<SessionOrganism>(storage.read(key) ?: error("Сохранённый организм исчез")))
        }.also { state.value = it.associateBy(SessionOrganism::id) }
    }

    /** Internal integration intent precedes every Git/check effect and keeps immutable input. */
    suspend fun admitIntegration(scope: SessionAuthority, request: SessionIntegrationRequest, fingerprint: String): Pair<SessionOrganism, Boolean> = lock.withLock {
        val old = read(scope.organismId)
        requireTool(old.projectId == scope.projectId && old.id == scope.organismId && old.deletedAt == null && scope.sessionId !in old.historyDeletedIds) { "Другой проект, организм или удалённая сессия" }
        old.integrations[request.id]?.let { previous ->
            requireTool(previous.request.actorSessionId == scope.sessionId && previous.request.generation == scope.generation &&
                old.operations[request.id]?.fingerprint == fingerprint) { "Идентификатор интеграции использован с другими аргументами или поколением" }
            requireTool(old.sessions.getValue(scope.sessionId).generation == scope.generation && scope.mode == CodingInteractionMode.CODE) { "Полномочия запуска отозваны" }
            return@withLock old to false
        }
        val actor = old.actor(scope)
        requireTool(scope.mode == CodingInteractionMode.CODE && request.organismId == old.id && request.actorSessionId == actor.id && request.generation == actor.generation) { "Интеграция требует разрешённого режима разработки" }
        requireTool(request.id.isNotBlank() && request.id !in old.operations && request.sourcePath.isNotBlank() && request.sourceSnapshot.isNotBlank()) { "Некорректная интеграция или неподтверждённые исходники" }
        requireTool(request.resultIds.isNotEmpty() && request.resultIds.size <= 32 && request.resultIds.distinct() == request.resultIds) { "Укажите разные результаты для интеграции" }
        requireTool(request.checks.isNotEmpty() && request.checks.size <= 8 && request.checks.all { command -> command.isNotEmpty() && command.size <= 128 &&
            command.first().isNotBlank() && command.all { it.length <= 16_384 && '\u0000' !in it && redact(it) == it } }) { "Укажите итоговые проверки без секретов в аргументах" }
        requireTool(old.integrations.values.none { it.request.actorSessionId == actor.id && it.phase in setOf(SessionIntegrationPhase.INTENT,
            SessionIntegrationPhase.PREPARING, SessionIntegrationPhase.MERGING, SessionIntegrationPhase.VERIFYING, SessionIntegrationPhase.UNKNOWN) }) { "Сначала подтвердите исход предыдущей интеграции" }
        val sources = request.resultIds.map { resultId ->
            val result = old.results.firstOrNull { it.id == resultId } ?: error("Результат не найден")
            val source = old.sessions.getValue(result.sessionId)
            requireTool(result.accepted && result.recipient == actor.id && source.authorityParentId == actor.id && source.generation == result.generation &&
                source.settled && old.resultWorkspace(result) != null) { "Нужен принятый CODE-результат непосредственного ребёнка" }
            source.id
        }.toSet()
        val saved = commit(old.copy(version = old.version + 1, integrations = old.integrations + (request.id to SessionIntegration(request)),
            operations = old.operations + (request.id to OrganismOperation(request.id, fingerprint, actor.id, SessionOperationState.ACCEPTED)),
            audit = old.audit + SessionAuditEvent(request.id, actor.id, "INTEGRATE", sources + actor.id, "Сохранены состав интеграции, исходный снимок и итоговые проверки", clock())))
        saved to true
    }

    /** Host-produced checkpoints cannot change the request or publish across a revoked generation. */
    suspend fun checkpointIntegration(id: String, record: SessionIntegration): SessionOrganism = lock.withLock {
        val old = read(id)
        val before = old.integrations[record.request.id] ?: error("Намерение интеграции не сохранено")
        val owner = old.sessions.getValue(record.request.actorSessionId)
        require(old.deletedAt == null && owner.id !in old.historyDeletedIds && owner.generation == record.request.generation) { "Полномочия интеграции отозваны" }
        require(before.request == record.request && record.request.organismId == id) { "Аргументы интеграции изменились" }
        val safe = record.copy(error = redact(record.error), checkResults = record.checkResults.map { it.copy(output = redact(it.output), blockedReason = it.blockedReason?.let(::redact)) },
            acceptance = record.acceptance?.let { proof -> proof.copy(findings = proof.findings.map { it.copy(observed = redact(it.observed)) },
                evidence = proof.evidence.map { it.copy(detail = redact(it.detail)) }) })
        if (before == safe) return@withLock old
        val transitions = when (before.phase) {
            SessionIntegrationPhase.INTENT -> setOf(SessionIntegrationPhase.PREPARING, SessionIntegrationPhase.BLOCKED, SessionIntegrationPhase.UNKNOWN)
            SessionIntegrationPhase.PREPARING -> setOf(SessionIntegrationPhase.MERGING, SessionIntegrationPhase.UNKNOWN)
            SessionIntegrationPhase.MERGING -> setOf(SessionIntegrationPhase.MERGING, SessionIntegrationPhase.CONFLICT, SessionIntegrationPhase.VERIFYING, SessionIntegrationPhase.UNKNOWN)
            SessionIntegrationPhase.VERIFYING -> setOf(SessionIntegrationPhase.VERIFYING, SessionIntegrationPhase.VERIFIED, SessionIntegrationPhase.BLOCKED, SessionIntegrationPhase.UNKNOWN)
            SessionIntegrationPhase.CONFLICT, SessionIntegrationPhase.BLOCKED, SessionIntegrationPhase.UNKNOWN -> setOf(SessionIntegrationPhase.UNKNOWN)
            SessionIntegrationPhase.VERIFIED -> emptySet()
        }
        require(safe.phase in transitions && (before.workspace == null || before.workspace == safe.workspace) &&
            safe.mergedResultIds.take(before.mergedResultIds.size) == before.mergedResultIds && safe.mergedResultIds.distinct() == safe.mergedResultIds &&
            safe.mergedResultIds.all { it in record.request.resultIds } && safe.checkResults.take(before.checkResults.size) == before.checkResults &&
            safe.checkResults.map { it.command } == record.request.checks.take(safe.checkResults.size)) { "Недопустимый checkpoint интеграции" }
        if (safe.phase == SessionIntegrationPhase.VERIFIED) require(safe.mergedResultIds == safe.request.resultIds && safe.checkResults.size == safe.request.checks.size &&
            safe.checkResults.all { it.exitCode == 0 && it.blockedReason == null } && safe.acceptance?.permitsProgress == true && safe.snapshot.isNotBlank() &&
            safe.commitSha.isNotBlank() && safe.workspace?.git == true && safe.workspace.integrationPath != safe.request.sourcePath) { "Итоговая интеграция не подтверждена" }
        val state = when (safe.phase) {
            SessionIntegrationPhase.VERIFIED, SessionIntegrationPhase.BLOCKED, SessionIntegrationPhase.CONFLICT -> SessionOperationState.SUCCEEDED
            SessionIntegrationPhase.UNKNOWN -> SessionOperationState.UNKNOWN
            else -> SessionOperationState.ACCEPTED
        }
        commit(old.copy(version = old.version + 1, integrations = old.integrations + (safe.request.id to safe),
            operations = old.operations + (safe.request.id to old.operations.getValue(safe.request.id).copy(state = state)),
            audit = old.audit + SessionAuditEvent("${safe.request.id}-checkpoint-${old.version + 1}", owner.id, "INTEGRATION_${safe.phase}", setOf(owner.id),
                safe.error.ifBlank { "Состояние интеграции сохранено приложением" }, clock())))
    }

    /** Idempotent migration retains every legacy session and never moves or removes history. */
    suspend fun adopt(projectId: String, root: CodingSession, descendants: List<CodingSession>, limits: OrganismLimits = OrganismLimits()): SessionOrganism = lock.withLock {
        val id = root.organismId ?: root.id
        storage.read(key(id))?.let { return@withLock migrateLimits(json.decodeFromString<SessionOrganism>(it)) }
        require(root.projectId == projectId && descendants.all { it.projectId == projectId }) { "Другой проект" }
        limits.validate()
        val immunity = "$id-immunity"
        val migrated = (listOf(root) + descendants).distinctBy { it.id }
        require(migrated.none { it.id == immunity }) { "Идентификатор иммунитета занят" }
        val nodes = migrated.associate { session -> session.id to SessionNode(
            session.id, if (session.id == root.id) SessionKind.ZYGOTE else SessionKind.SESSION, session.name,
            originParentId = session.parentSessionId.takeUnless { session.id == root.id },
            generation = session.runtimeGeneration, archived = session.archived, mode = session.interactionMode,
            desired = session.desiredState ?: if (session.pendingRun?.intent == ExecutionIntent.STOP) SessionDesiredState.STOP else SessionDesiredState.RUN,
            // Legacy interrupted operations must be reconciled before they acquire authority.
            observed = when {
                session.observedState in setOf(SessionObservedState.UNKNOWN, SessionObservedState.RUNNING, SessionObservedState.WAITING_USER, SessionObservedState.STOPPING) || session.pendingRun != null -> SessionObservedState.UNKNOWN
                session.archived -> SessionObservedState.STOPPED
                else -> session.observedState ?: SessionObservedState.PENDING
            },
            remainingTokens = if (session.archived) 0 else limits.tokens?.let { (it - limits.recoveryTokens) / migrated.count { !it.archived }.coerceAtLeast(1) } ?: 0,
            rules = root.planningRulesSnapshot, lastObservedAt = clock(), nameManuallySet = session.nameManuallySet,
        ) } + (immunity to SessionNode(immunity, SessionKind.IMMUNITY, "Иммунитет", remainingTokens = if (limits.tokens == null) 0 else limits.recoveryTokens,
            observed = SessionObservedState.PENDING, rules = root.planningRulesSnapshot, lastObservedAt = clock()))
        val organism = SessionOrganism(id, projectId, root.id, immunity, clock(), limits, sessions = nodes, limitPolicyVersion = 1,
            audit = listOf(SessionAuditEvent("$id-migration", "USER", "MIGRATE", nodes.keys, "Сохранены происхождение и история сессий", clock())))
        nodes.values.filter { it.kind == SessionKind.SESSION }.forEach { organism.route(it.id, root.id) }
        commit(organism)
    }

    /** Application UI action. Metadata renaming does not restore runtime authority or reopen work. */
    suspend fun renameByUser(id: String, target: String, name: String, operationId: String): SessionOrganism {
        val normalized = name.trim()
        require(normalized.isNotBlank() && normalized.length <= 256 && operationId.isNotBlank()) { "Добавьте название сессии" }
        val fingerprint = "USER:" + toolArgumentsFingerprint(json.encodeToJsonElement(OrganismCommand(OrganismAction.RENAME, target, normalized)))
        return lock.withLock {
            val old = read(id)
            require(old.deletedAt == null && target !in old.historyDeletedIds) { "Сессия удалена" }
            val node = old.sessions[target] ?: error("Сессия не найдена")
            old.operations[operationId]?.let {
                require(it.fingerprint == fingerprint && old.audit.any { event -> event.operationId == operationId && event.actor == "USER" && event.action == "RENAME" }) { "Идентификатор команды использован с другими аргументами" }
                return@withLock old
            }
            commit(old.copy(version = old.version + 1, sessions = old.sessions + (target to node.copy(name = normalized,
                nameManuallySet = true, version = node.version + 1)),
                operations = old.operations + (operationId to OrganismOperation(operationId, fingerprint, target)),
                audit = old.audit + SessionAuditEvent(operationId, "USER", "RENAME", setOf(target), "Название изменено пользователем", clock())))
        }
    }

    private fun SessionOrganism.actor(scope: SessionAuthority, signal: Boolean = false): SessionNode {
        requireTool(projectId == scope.projectId && id == scope.organismId) { "Другой проект или организм" }
        requireTool(deletedAt == null && scope.sessionId !in historyDeletedIds) { "Сессия удалена пользователем" }
        val actor = sessions[scope.sessionId] ?: error("Сессия не найдена")
        requireTool(actor.generation == scope.generation && actor.mode == scope.mode) { "Полномочия запуска отозваны" }
        if (scope.expectedVersion != null && version != scope.expectedVersion) throw StaleSessionVersion()
        requireTool(!stoppedByUser && !actor.archived) { "Организм или сессия остановлены" }
        if (!signal) {
            requireTool(actor.acceptsWork) { "Сессия не принимает работу" }
            requireTool(withinDuration()) { "Время организма исчерпано" }
            requireTool(hasTokenBudget()) { "Бюджет сессии исчерпан" }
            var parent = actor.lifecycleParentId
            while (parent != null) {
                val node = sessions.getValue(parent)
                requireTool(node.acceptsWork) { "Рабочая область родителя закрыта" }
                parent = node.lifecycleParentId
            }
        }
        return actor
    }

    /** Recording already incurred usage cannot require admission for another unit of work.
     * A concurrent overrun, deadline or stop can revoke that admission while native usage
     * is still arriving. Identity and the live generation remain mandatory until it settles.
     */
    private fun SessionOrganism.usageActor(scope: SessionAuthority, auxiliary: Boolean = false): SessionNode {
        requireTool(projectId == scope.projectId && id == scope.organismId) { "Другой проект или организм" }
        requireTool(deletedAt == null && scope.sessionId !in historyDeletedIds) { "Сессия удалена пользователем" }
        val node = sessions[scope.sessionId] ?: error("Сессия не найдена")
        requireTool(node.generation == scope.generation && node.mode == scope.mode) { "Полномочия запуска отозваны" }
        if (scope.expectedVersion != null && version != scope.expectedVersion) throw StaleSessionVersion()
        requireTool(!node.archived && !node.settled && node.observed != SessionObservedState.UNKNOWN) { "Запуск уже закрыт или требует сверки" }
        requireTool(auxiliary || node.lastStartedGeneration == scope.generation && node.observed in setOf(
            SessionObservedState.RUNNING, SessionObservedState.WAITING_USER, SessionObservedState.STOPPING)) { "Запуск не выполняется" }
        return node
    }

    suspend fun check(scope: SessionAuthority) = lock.withLock { read(scope.organismId).actor(scope); Unit }

    /** A failed parent cannot leave separately scheduled legacy children admitting more work. */
    suspend fun requestFailureStop(id: String, rootId: String, generation: Long, reason: String): SessionOrganism = lock.withLock {
        val old = read(id); val root = old.sessions.getValue(rootId)
        require(root.generation == generation) { "Полномочия родителя отозваны" }
        val affected = old.subtree(rootId) - rootId
        val operationId = "failure-stop-$rootId-$generation"
        if (old.audit.any { it.operationId == operationId }) return@withLock old
        commit(old.copy(version = old.version + 1, sessions = old.sessions.mapValues { (sessionId, node) ->
            if (sessionId !in affected || node.settled) node else node.copy(desired = SessionDesiredState.STOP,
                observed = if (node.observed == SessionObservedState.UNKNOWN) node.observed else SessionObservedState.STOPPING,
                version = node.version + 1)
        }, outbox = old.outbox.map { delivery -> if ((delivery.sender in affected || delivery.recipient in affected) &&
            delivery.state != SessionDeliveryState.PROCESSED) delivery.copy(state = SessionDeliveryState.CANCELLED) else delivery },
            operations = old.operations + (operationId to OrganismOperation(operationId, "APPLICATION:FAILURE:$rootId:$generation", rootId, SessionOperationState.ACCEPTED)),
            audit = old.audit + SessionAuditEvent(operationId, "APPLICATION", "FAILURE_STOP", affected, redact(reason), clock())))
    }

    /** Host-only snapshot for a fresh user retry; saving the Plan makes this authority durable. */
    suspend fun authorizePlanRetry(id: String, sessionId: String, binding: SessionLegacyAttempt,
        continuationConfirmed: Boolean = false): PlanAttemptRetryAuthorization? = lock.withLock {
        val old = read(id)
        val node = old.sessions[sessionId] ?: return@withLock null
        if (node.desired == SessionDesiredState.RUN) return@withLock null
        val admitted = node.legacyAttempt ?: error("Попытка этапа не сохранена")
        requireRetryTurn(admitted, binding, continuationConfirmed)
        requirePlanRetryEligible(old, node, admitted)
        PlanAttemptRetryAuthorization(Id.new(), admitted, node.version, binding)
    }

    private fun requireRetryTurn(admitted: SessionLegacyAttempt, requested: SessionLegacyAttempt, continuationConfirmed: Boolean) {
        require(requested == admitted || continuationConfirmed && admitted.turnIndex != Int.MAX_VALUE &&
            requested == admitted.copy(turnIndex = admitted.turnIndex + 1)) { "Попытка или поколение этапа изменились" }
    }

    /** Native termination and exact operation outcomes are proved by the host, never by model prose.
     * Reconciliation and retry capture share a commit boundary so a newer stop invalidates both. */
    suspend fun reconcileAndAuthorizePlanRetry(id: String, request: PlanRetryRecoveryRequest, proof: PlanRetryRecoveryProof,
        requestedBinding: SessionLegacyAttempt, continuationConfirmed: Boolean = false): PlanAttemptRetryAuthorization = lock.withLock {
        val old = read(id)
        val node = old.sessions.getValue(request.session.id)
        require(old.projectId == request.session.projectId && old.deletedAt == null && node.id !in old.historyDeletedIds &&
            !old.stoppedByUser && !node.archived) { "Сессия удалена, архивирована или остановлена пользователем" }
        require(node.version == request.expectedNodeVersion && node.generation == request.binding.generation &&
            node.legacyAttempt == request.binding) { "Состояние изменилось во время проверки; повторите действие" }
        requireRetryTurn(request.binding, requestedBinding, continuationConfirmed)
        val quarantines = old.unresolvedQuarantines(node.id)
        val quarantinedStop = node.desired in setOf(SessionDesiredState.STOP, SessionDesiredState.QUARANTINE) && node.settled && quarantines.isNotEmpty()
        require(node.observed == SessionObservedState.UNKNOWN || quarantinedStop || node.desired == SessionDesiredState.STOP &&
            node.observed in setOf(SessionObservedState.PENDING, SessionObservedState.STOPPING)) { "Сначала подтвердите остановку предыдущего запуска" }
        val affected = old.subtree(node.id)
        require((affected - node.id).all { old.sessions.getValue(it).settled && old.unresolvedQuarantines(it).isEmpty() } &&
            old.auxiliaryRuns.values.none { it.ownerSessionId in affected && !it.settled }) { "Дочерние или вспомогательные запуски ещё не остановлены" }
        require(old.audit.none { it.action == "ARCHIVE" && node.id in it.affected &&
            old.operations[it.operationId]?.state == SessionOperationState.ACCEPTED }) { "Сессия архивируется" }
        require(old.integrations.values.none { it.request.actorSessionId in affected && it.phase in setOf(SessionIntegrationPhase.INTENT,
            SessionIntegrationPhase.PREPARING, SessionIntegrationPhase.MERGING, SessionIntegrationPhase.VERIFYING, SessionIntegrationPhase.UNKNOWN) }) {
            "Сначала подтвердите исход интеграции" }
        require(old.operations.values.none { operation -> operation.target in affected &&
            (operation.state == SessionOperationState.UNKNOWN || operation.state == SessionOperationState.ACCEPTED &&
                old.audit.firstOrNull { it.operationId == operation.id }?.action !in setOf("STOP", "PAUSE", "ARCHIVE", "QUARANTINE", "FAILURE_STOP")) }) {
            "Сначала подтвердите исход других операций" }
        require(quarantines == request.quarantines && proof.quarantineOperationIds == quarantines.map { it.operationId }.toSet() &&
            proof.evidence.isNotEmpty() && proof.evidence.all { it.isNotBlank() }) { "Нет подтверждения исхода всех операций карантина" }
        val parent = old.sessions.getValue(node.authorityParentId ?: error("Родитель не задан"))
        val stopped = node.copy(desired = SessionDesiredState.STOP, observed = SessionObservedState.STOPPED,
            remainingTokens = 0, version = node.version + 1, lastObservedAt = clock())
        val resolved = old.copy(version = old.version + 1, sessions = old.sessions + (node.id to stopped) +
            (parent.id to parent.copy(remainingTokens = parent.remainingTokens + node.remainingTokens, version = parent.version + 1)),
            audit = old.audit + quarantines.map { event -> SessionAuditEvent("resolved-${node.id}-${event.operationId}", "APPLICATION",
                "RECONCILE_QUARANTINE", setOf(node.id), proof.evidence.joinToString("\n") { redact(it) }, clock()) } +
                SessionAuditEvent("plan-reconciled-${node.id}-${stopped.version}", "APPLICATION", "PLAN_RETRY_RECONCILED", setOf(node.id),
                    proof.evidence.joinToString("\n") { redact(it) }, clock()))
        requirePlanRetryEligible(resolved, stopped, request.binding)
        val saved = commit(resolved)
        PlanAttemptRetryAuthorization(Id.new(), request.binding, saved.sessions.getValue(node.id).version, requestedBinding)
    }

    private fun requirePlanRetryEligible(old: SessionOrganism, node: SessionNode, binding: SessionLegacyAttempt) {
        require(old.deletedAt == null && node.id !in old.historyDeletedIds && !old.stoppedByUser && !node.archived) { "Сессия удалена или архивирована" }
        require(node.desired == SessionDesiredState.STOP && node.settled) { "Сначала подтвердите остановку предыдущего запуска" }
        require(node.kind == SessionKind.SESSION && node.legacyAttempt == binding && node.generation == binding.generation) { "Попытка или поколение этапа изменились" }
        require(old.unresolvedQuarantines(node.id).isEmpty()) { "Сначала проверьте фактический исход операции" }
        require(old.results.none { it.sessionId == node.id && it.accepted }) { "Работа уже принята" }
        val affected = old.subtree(node.id)
        require(affected.all { old.sessions.getValue(it).settled } && old.auxiliaryRuns.values.none { it.ownerSessionId in affected && !it.settled }) { "Рабочая область этапа ещё не остановлена" }
        require(old.limits.retries.hasRoom(node.retryCount)) { "Лимит восстановлений исчерпан" }
        val parent = old.sessions.getValue(node.authorityParentId ?: error("Родитель не задан"))
        old.actor(SessionAuthority(old.projectId, old.id, parent.id, parent.generation, parent.mode))
    }

    /** Adapter entry called only for an actual persisted, human-confirmed plan attempt. */
    suspend fun admitPlanWorker(id: String, session: CodingSession, task: SessionTask,
        binding: SessionLegacyAttempt, rules: PlanningRulesSnapshot?, unfinishedStageIds: Set<String>,
        retryAuthorization: PlanAttemptRetryAuthorization? = null, continuationConfirmed: Boolean = false): SessionOrganism = lock.withLock {
        val old = read(id).reconcileTokenBudget(session.parentSessionId ?: error("Родитель не задан"))
        require(binding.stageId in unfinishedStageIds) { "Этап уже завершён или не выбран" }
        require(session.projectId == old.projectId && session.parentSessionId == task.resultRecipient) { "Другой проект или получатель" }
        val parent = old.sessions[session.parentSessionId] ?: error("Родитель не входит в организм")
        old.actor(SessionAuthority(old.projectId, id, parent.id, parent.generation, parent.mode))
        val existing = old.sessions[session.id]
        require(existing == null || (existing.originParentId == parent.id && existing.authorityParentId == parent.id)) { "Другая ветка происхождения" }
        val retry = retryAuthorization?.takeIf { existing?.desired == SessionDesiredState.STOP }?.also { authorization ->
            requireRetryTurn(authorization.binding, binding, continuationConfirmed)
            requirePlanRetryEligible(old, existing!!, authorization.binding)
            require(authorization.id.isNotBlank() && authorization.requestedBinding == binding && authorization.expectedVersion == existing.version &&
                old.audit.none { it.operationId == "plan-retry-${authorization.id}" }) { "Разрешение повтора устарело; подтвердите повтор заново" }
        }
        require(existing?.archived != true && (existing == null || existing.desired == SessionDesiredState.RUN || retry != null) && existing?.observed != SessionObservedState.UNKNOWN) { "Сначала разрешите состояние старого запуска" }
        require(old.results.none { it.sessionId == session.id && it.accepted }) { "Работа уже принята" }
        require(existing?.observed !in setOf(SessionObservedState.RUNNING, SessionObservedState.WAITING_USER, SessionObservedState.STOPPING)) { "Предыдущий запуск ещё работает" }
        require(old.limits.depth.allows(old.route(parent.id, old.zygoteId).size)) { "Лимит рабочей области исчерпан" }
        val retained = existing?.remainingTokens ?: 0
        // Reserve an equal share for every unfinished stage and for the parent, whose
        // planner/verification turns use the same task budget. Already funded siblings
        // are removed under this lock so parallel admissions cannot dilute later shares.
        // These shares are accounting reservations, not independent stop limits.
        // Every worker can use the remaining task budget across continuation turns.
        val fundedStages = old.sessions.values.mapNotNull { sibling -> sibling.legacyAttempt?.takeIf {
            sibling.id != session.id && sibling.authorityParentId == parent.id && sibling.remainingTokens > 0 &&
                it.planId == binding.planId && it.runId == binding.runId && it.stageId != binding.stageId
        }?.stageId }.toSet()
        val unfundedStages = (unfinishedStageIds - fundedStages).size
        val allocated = if (retained > 0) 0 else parent.remainingTokens / (unfundedStages.toLong() + 1)
        require(old.hasTokenBudget()) { "Бюджет задачи исчерпан" }
        val generation = if (existing == null) 1 else maxOf(existing.generation + if (retry == null) 0 else 1,
            existing.lastStartedGeneration + 1).coerceAtLeast(1)
        val node = (existing ?: SessionNode(session.id, SessionKind.SESSION, redact(session.name), parent.id)).copy(
            generation = generation, previousGeneration = existing?.generation?.takeIf { it != generation },
            version = (existing?.version ?: 0) + 1, desired = SessionDesiredState.RUN, observed = SessionObservedState.PENDING,
            retryCount = (existing?.retryCount ?: 0) + if (retry == null) 0 else 1,
            mode = CodingInteractionMode.CODE, remainingTokens = retained + allocated, task = task.redacted(),
            rules = rules ?: parent.rules, legacyAttempt = binding.copy(generation = generation), lastObservedAt = clock())
        commit(old.copy(version = old.version + 1,
            sessions = old.sessions + (session.id to node) + (parent.id to parent.copy(remainingTokens = parent.remainingTokens - allocated, version = parent.version + 1)),
            audit = old.audit + listOfNotNull(retry?.let { SessionAuditEvent("plan-retry-${it.id}", "USER", "PLAN_RETRY", setOf(session.id),
                "Подтверждён повтор поколения ${it.binding.generation}; создано поколение $generation", clock()) }) +
                SessionAuditEvent("plan-attempt-${binding.attemptId}-${binding.turnIndex}-$generation", "APPLICATION", "PLAN_ATTEMPT", setOf(parent.id, session.id),
                "Подтверждённый этап; существующий бюджет и рабочая копия плана", clock())))
    }

    /** Native Finished is insufficient; only the scheduler's accepted integrated checkpoint enters here. */
    suspend fun acceptPlanResult(id: String, binding: SessionLegacyAttempt, result: SessionResult): SessionOrganism = lock.withLock {
        val old = read(id); val node = old.sessions.getValue(result.sessionId)
        val admitted = node.legacyAttempt
        require(admitted != null && admitted == binding.copy(turnIndex = admitted.turnIndex) &&
            node.generation == result.generation && binding.generation == result.generation) { "Поздний результат этапа другого запуска" }
        require(node.observed !in setOf(SessionObservedState.RUNNING, SessionObservedState.WAITING_USER, SessionObservedState.STOPPING, SessionObservedState.UNKNOWN) &&
            node.desired == SessionDesiredState.RUN && old.sessions.values.none { it.lifecycleParentId == node.id && !it.settled }) { "Рабочая область этапа ещё не завершена" }
        require(result.accepted && result.recipient == node.authorityParentId && result.checks.isNotEmpty()) { "Приёмка этапа не подтверждена" }
        val safe = result.copy(summary = redact(result.summary), evidence = result.evidence.map(::redact),
            artifacts = result.artifacts.map(::redact), checks = result.checks.map(::redact), sourceVersion = redact(result.sourceVersion))
        old.results.firstOrNull { it.id == result.id }?.let { require(it == safe) { "Результат этапа изменился" }; return@withLock old }
        val parent = old.sessions.getValue(result.recipient)
        require(old.limits.queueSize.hasRoom(old.outbox.count { it.state in setOf(SessionDeliveryState.ACCEPTED, SessionDeliveryState.DELIVERED) })) { "Очередь результатов заполнена" }
        val packet = SessionContextPacket(safe.summary.takeConfigured(old.limits.contextCharacters?.div(2)), result.sourceVersion, node.rules?.version.orEmpty(),
            resultIds = listOf(result.id), summarized = !old.limits.contextCharacters?.div(2).allows(safe.summary.length),
            omissions = if (!old.limits.contextCharacters?.div(2).allows(safe.summary.length)) "Полный результат: ${result.id}" else "")
        val delivery = SessionDelivery("result-${result.id}", node.id, parent.id, old.route(node.id, parent.id), packet,
            old.outbox.filter { it.recipient == parent.id }.maxOfOrNull { it.sequence }?.plus(1) ?: 1, parent.generation)
        commit(old.copy(version = old.version + 1, results = old.results + safe, outbox = old.outbox + delivery,
            sessions = old.sessions + (node.id to node.copy(observed = SessionObservedState.COMPLETED, remainingTokens = 0, version = node.version + 1)) +
                (parent.id to parent.copy(remainingTokens = parent.remainingTokens + node.remainingTokens, version = parent.version + 1)),
            audit = old.audit + SessionAuditEvent(result.id, "APPLICATION", "PLAN_ACCEPTANCE", setOf(node.id, parent.id), "Приёмка и интеграция этапа подтверждены", clock())))
    }

    suspend fun recordWorkspace(id: String, sessionId: String, generation: Long, workspace: SessionCodingWorkspace): SessionOrganism = lock.withLock {
        val old = read(id); val node = old.sessions.getValue(sessionId)
        require(node.generation == generation && workspace.generation == generation) { "Рабочая копия другого поколения" }
        require(workspace.attempt.sessionId == sessionId) { "Рабочая копия другой сессии" }
        node.workspace?.takeIf { it.generation == generation }?.let { previous ->
            require(previous.runId == workspace.runId && previous.attempt.id == workspace.attempt.id) { "Рабочая копия запуска уже назначена" }
        }
        commit(old.copy(version = old.version + 1, sessions = old.sessions + (sessionId to node.copy(workspace = workspace, version = node.version + 1)),
            audit = old.audit + SessionAuditEvent("workspace-$sessionId-$generation-${old.version + 1}", "APPLICATION", "WORKSPACE", setOf(sessionId), "Состояние выделенной рабочей копии сохранено", clock())))
    }

    /** An explicit user mode change revokes the old runtime; it never expands a child's rights. */
    suspend fun changeRootMode(id: String, sessionId: String, mode: CodingInteractionMode): SessionOrganism = lock.withLock {
        val old = read(id); val node = old.sessions.getValue(sessionId)
        require(node.kind == SessionKind.ZYGOTE && !node.archived) { "Режим дочерней сессии наследуется от родителя" }
        require(node.observed in setOf(SessionObservedState.PENDING, SessionObservedState.COMPLETED, SessionObservedState.STOPPED, SessionObservedState.FAILED) &&
            old.subtree(sessionId).all { it == sessionId || old.sessions.getValue(it).settled }) { "Сначала завершите рабочую область" }
        require(node.desired != SessionDesiredState.QUARANTINE) { "Сначала проверьте исход операции" }
        if (node.mode == mode) return@withLock old
        val changed = node.copy(mode = mode, generation = node.generation + 1, previousGeneration = node.generation,
            version = node.version + 1, observed = SessionObservedState.PENDING, desired = SessionDesiredState.RUN)
        commit(old.copy(version = old.version + 1, sessions = old.sessions + (sessionId to changed),
            outbox = old.outbox.map { if (it.recipient == sessionId && it.state != SessionDeliveryState.PROCESSED) it.copy(state = SessionDeliveryState.CANCELLED) else it },
            audit = old.audit + SessionAuditEvent("mode-$sessionId-${changed.generation}", "USER", "CHANGE_MODE", setOf(sessionId), "${node.mode} → $mode", clock())))
    }

    /** A fresh explicit human request may reopen a confirmed stopped root, retaining its budget. */
    suspend fun prepareUserTurn(id: String, sessionId: String, requestId: String): SessionOrganism = lock.withLock {
        val old = read(id); val node = old.sessions.getValue(sessionId)
        if (node.desired != SessionDesiredState.STOP || node.observed != SessionObservedState.STOPPED) return@withLock old
        require(node.kind in setOf(SessionKind.ZYGOTE, SessionKind.IMMUNITY) && !node.archived && !old.stoppedByUser) { "Сначала восстановите рабочую область" }
        require(old.subtree(sessionId).all { old.sessions.getValue(it).settled }) { "Остановка поддерева ещё не подтверждена" }
        require(old.audit.none { it.action == "QUARANTINE" && sessionId in it.affected }) { "Сначала проверьте фактический исход операции" }
        require(old.hasTokenBudget() && old.withinDuration()) { "Бюджет организма исчерпан; создайте новую сессию" }
        val next = node.copy(desired = SessionDesiredState.RUN, observed = SessionObservedState.PENDING,
            generation = node.generation + 1, previousGeneration = node.generation, version = node.version + 1)
        commit(old.copy(version = old.version + 1, sessions = old.sessions + (node.id to next),
            audit = old.audit + SessionAuditEvent("user-turn-$requestId", "USER", "RESUME", setOf(node.id), "Новый запрос после подтверждённой остановки", clock())))
    }

    /** Application-only turn boundary. Reopening a root never reopens its finished children. */
    suspend fun beginRun(id: String, sessionId: String): SessionNode = lock.withLock {
        val old = read(id); val node = old.sessions.getValue(sessionId)
        require(old.deletedAt == null && sessionId !in old.historyDeletedIds) { "Сессия удалена пользователем" }
        require(!old.stoppedByUser && !node.archived) { "Сессия остановлена" }
        require(node.kind != SessionKind.IMMUNITY || node.mode == CodingInteractionMode.RESEARCH) { "Диагностика доступна только в режиме исследования" }
        require(node.desired == SessionDesiredState.RUN) { "Возобновление требует явного восстановления" }
        require(old.withinDuration() && old.hasTokenBudget()) { "Бюджет или время организма исчерпаны" }
        require(node.observed != SessionObservedState.UNKNOWN && node.observed != SessionObservedState.STOPPING) { "Сначала сверяйте незавершённый запуск" }
        require(old.auxiliaryRuns.values.none { it.ownerSessionId == sessionId && !it.settled }) { "Сначала остановите вспомогательные запуски владельца" }
        require(node.kind in setOf(SessionKind.ZYGOTE, SessionKind.IMMUNITY) || node.acceptsWork) { "Восстановите сессию через родителя" }
        val lineage = if (node.kind == SessionKind.IMMUNITY) listOf(sessionId) else old.route(sessionId, old.zygoteId)
        require(old.limits.depth.allows(lineage.size)) { "Достигнута глубина дерева" }
        lineage.drop(1).forEach { require(old.sessions.getValue(it).acceptsWork) { "Рабочая область родителя закрыта" } }
        // Legacy history can contain more pending nodes than today's admission limits.
        // Retain that history while reserving each actual runtime slot in this commit.
        val occupied = old.sessions.values.count { other -> other.id != sessionId &&
            other.observed in setOf(SessionObservedState.RUNNING, SessionObservedState.WAITING_USER,
                    SessionObservedState.STOPPING, SessionObservedState.UNKNOWN) }
        require(old.limits.activeSessions.hasRoom(occupied + old.auxiliaryRuns.values.count { !it.settled })) { "Достигнут лимит активных сессий" }
        val generation = if (node.observed != SessionObservedState.PENDING || node.generation <= node.lastStartedGeneration)
            node.generation + 1 else node.generation.coerceAtLeast(1)
        val next = node.copy(generation = generation, lastStartedGeneration = generation, desired = SessionDesiredState.RUN,
            observed = SessionObservedState.RUNNING, version = node.version + 1, lastObservedAt = clock())
        commit(old.copy(version = old.version + 1, sessions = old.sessions + (sessionId to next),
            // Context packets belong to the enduring conversation. Unlike questionnaires,
            // an unprocessed packet can be explicitly rebound at a new ordinary turn.
            outbox = old.outbox.map { delivery -> if (delivery.recipient == sessionId && delivery.recipientGeneration == node.generation &&
                delivery.state in setOf(SessionDeliveryState.ACCEPTED, SessionDeliveryState.DELIVERED)) delivery.copy(recipientGeneration = next.generation) else delivery },
            audit = old.audit + SessionAuditEvent("run-$sessionId-${next.generation}-${next.version}", "APPLICATION", "BEGIN_RUN", setOf(sessionId), "Новое поколение; недоставленный контекст сохранён", clock())))
        next
    }

    suspend fun finishStop(id: String, sessionIds: Set<String>): SessionOrganism = lock.withLock {
        val old = read(id)
        require(sessionIds.all { old.sessions.getValue(it).settled }) { "Остановка ещё не подтверждена" }
        require(old.auxiliaryRuns.values.none { it.ownerSessionId in sessionIds && !it.settled }) { "Вспомогательный запуск ещё не остановлен" }
        val completed = old.operations.mapValues { (operationId, op) ->
            val audit = old.audit.firstOrNull { it.operationId == operationId }
            if (op.state == SessionOperationState.ACCEPTED && audit != null && audit.action in setOf("STOP", "ARCHIVE", "PAUSE", "QUARANTINE", "FAILURE_STOP") &&
                audit.affected.all { old.sessions.getValue(it).settled } && old.auxiliaryRuns.values.none { it.ownerSessionId in audit.affected && !it.settled }) op.copy(state = SessionOperationState.SUCCEEDED) else op
        }
        val archived = old.audit.filter { it.action == OrganismAction.ARCHIVE.name &&
            old.operations[it.operationId]?.state == SessionOperationState.ACCEPTED &&
            completed[it.operationId]?.state == SessionOperationState.SUCCEEDED }.flatMap { it.affected }.toSet()
        commit(old.copy(version = old.version + 1, operations = completed,
            sessions = old.sessions.mapValues { (id, node) -> if (id in archived) node.copy(archived = true) else node }))
    }

    /** This entry point is called only by explicit UI controls, never by model tool arguments. */
    suspend fun requestUserStop(id: String, target: String, operationId: String, archive: Boolean): SessionOrganism = lock.withLock {
        val old = read(id)
        old.operations[operationId]?.let { return@withLock old }
        val affected = old.subtree(target)
        val action = if (archive) OrganismAction.ARCHIVE else OrganismAction.STOP
        commit(old.copy(version = old.version + 1, sessions = old.sessions.mapValues { (sessionId, node) ->
            if (sessionId !in affected) node else node.copy(desired = SessionDesiredState.STOP,
                observed = if (node.settled || (node.kind == SessionKind.IMMUNITY && node.observed == SessionObservedState.PENDING)) SessionObservedState.STOPPED else SessionObservedState.STOPPING,
                version = node.version + 1)
        }, operations = old.operations + (operationId to OrganismOperation(operationId, "USER:$action:$target", target, SessionOperationState.ACCEPTED)),
            audit = old.audit + SessionAuditEvent(operationId, "USER", action.name, affected, "Действие пользователя", clock()),
            outbox = old.outbox.map { if ((it.sender in affected || it.recipient in affected) && it.state != SessionDeliveryState.PROCESSED) it.copy(state = SessionDeliveryState.CANCELLED) else it }))
    }

    suspend fun restoreByUser(id: String, target: String, operationId: String, rules: PlanningRulesSnapshot, sourceVersion: String?): SessionOrganism = lock.withLock {
        val old = read(id); val node = old.sessions.getValue(target)
        require(old.deletedAt == null && target !in old.historyDeletedIds) { "Удалённую историю нельзя восстановить как архив" }
        old.operations[operationId]?.let { return@withLock old }
        require(node.settled && node.archived) { "Сначала подтвердите остановку и архивирование" }
        require(old.audit.none { it.action == "QUARANTINE" && target in it.affected }) { "Исход операции неизвестен; сначала нужна проверка её фактического результата" }
        require(node.kind != SessionKind.IMMUNITY || node.observed == SessionObservedState.STOPPED)
        val parent = node.authorityParentId?.let { old.sessions.getValue(it) }
        require(parent == null || parent.acceptsWork) { "Сначала восстановите родителя" }
        require(node.rules == rules && (parent == null || parent.rules == node.rules)) { "Правила изменились; требуется новое задание" }
        require(node.task == null || (sourceVersion != null && sourceVersion == node.task.sourceVersion)) { "Исходники изменились или не проверены; требуется новое задание" }
        require(old.results.none { it.sessionId == target && it.accepted }) { "Задание уже принято" }
        require(old.limits.retries.hasRoom(node.retryCount) && old.withinDuration()) { "Бюджет восстановления исчерпан" }
        val budget = if (parent == null) node.remainingTokens else parent.remainingTokens / 2
        require(old.hasTokenBudget()) { "Бюджет задачи исчерпан" }
        val restored = node.copy(generation = node.generation + 1, previousGeneration = node.generation,
            version = node.version + 1, desired = SessionDesiredState.RUN, observed = SessionObservedState.PENDING,
            archived = false, retryCount = node.retryCount + 1, remainingTokens = budget)
        val nodes = old.sessions + (target to restored) + (if (parent == null) emptyMap() else mapOf(parent.id to parent.copy(remainingTokens = parent.remainingTokens - budget, version = parent.version + 1)))
        commit(old.copy(version = old.version + 1, sessions = nodes,
            operations = old.operations + (operationId to OrganismOperation(operationId, "USER:RESTORE:$target", target)),
            audit = old.audit + SessionAuditEvent(operationId, "USER", "RESTORE", setOf(target), "Задание, правила и исходники проверены; создано новое поколение", clock())))
    }

    suspend fun command(scope: SessionAuthority, operationId: String, request: OrganismCommand): SessionOrganism {
        requireTool(operationId.isNotBlank() && operationId.length <= 512) { "Некорректная операция" }
        // The digest distinguishes original arguments; only the transmitted safe content is stored.
        val fingerprint = toolArgumentsFingerprint(json.encodeToJsonElement(request))
        val command = request.copy(name = redact(request.name), reason = redact(request.reason),
            task = request.task?.redacted(), packet = request.packet?.redacted(), checks = request.checks.map(::redact))
        return lock.withLock {
            val old = read(scope.organismId)
            // Generation is always checked, including receipt replay after recreation.
            requireTool(old.projectId == scope.projectId && old.sessions[scope.sessionId]?.generation == scope.generation) { "Полномочия запуска отозваны" }
            old.operations[operationId]?.let {
                requireTool(it.fingerprint == fingerprint && old.audit.first { event -> event.operationId == operationId }.actor == scope.sessionId &&
                    old.sessions.getValue(scope.sessionId).mode == scope.mode) { "Идентификатор операции использован с другими аргументами или отправителем" }
                return@withLock old
            }
            val actor = old.actor(scope, signal = command.action == OrganismAction.SIGNAL)
            val targetId = if (command.action == OrganismAction.CREATE) "session-$operationId" else command.target
            requireTool(targetId !in old.historyDeletedIds) { "История адресата удалена пользователем" }
            val target = old.sessions[targetId]
            requireTool(actor.kind != SessionKind.IMMUNITY) { "Диагностический запуск не управляет сессиями. Передайте выводы пользователю." }
            val supervisor = actor.kind == SessionKind.IMMUNITY
            fun requireChild() { requireTool(target != null && (target.authorityParentId == actor.id || (supervisor && target.kind != SessionKind.IMMUNITY))) { "Разрешены только непосредственные дети" } }
            var next = old
            var affected = setOf(targetId)
            when (command.action) {
                OrganismAction.CREATE -> {
                    requireTool(!supervisor) { "Иммунитет не создаёт рабочие ветки" }
                    requireTool(command.name.isNotBlank() && command.name.length <= 256 && command.task != null && command.task.acceptance.isNotBlank()) { "Добавьте задание и критерии" }
                    requireTool(command.task.resultRecipient == actor.id) { "Получатель результата — родитель" }
                    requireTool(old.limits.contextCharacters.allows(command.task.text.length + command.task.acceptance.length)) { "Контекст слишком большой" }
                    requireTool(command.tokens >= 0) { "Бюджет не может быть отрицательным" }
                    val allocation = minOf(command.tokens, actor.remainingTokens)
                    requireTool(old.limits.queueSize.hasRoom(old.outbox.count { it.state in setOf(SessionDeliveryState.ACCEPTED, SessionDeliveryState.DELIVERED) } +
                        old.sessions.values.count { it.kind == SessionKind.SESSION && !it.settled })) { "Нет места для результата ребёнка" }
                    requireTool(old.limits.depth.hasRoom(old.route(actor.id, old.zygoteId).size)) { "Достигнута глубина дерева" }
                    requireTool(command.task.dependencies.all { old.sessions[it]?.authorityParentId == actor.id }) { "Зависимость вне рабочей области" }
                    val child = SessionNode(targetId, SessionKind.SESSION, command.name, actor.id, generation = 1,
                        mode = actor.mode, remainingTokens = allocation, task = command.task,
                        rules = actor.rules, failurePolicy = command.failurePolicy, lastObservedAt = clock())
                    next = old.copy(sessions = old.sessions + (targetId to child) + (actor.id to actor.copy(remainingTokens = actor.remainingTokens - allocation, version = actor.version + 1)))
                    affected = setOf(actor.id, targetId)
                }
                OrganismAction.SEND -> {
                    requireTool(target != null && target.acceptsWork && target.kind != SessionKind.IMMUNITY) { "Получатель не принимает контекст" }
                    val route = old.route(actor.id, targetId)
                    val grant = old.routeGrants.lastOrNull { it.source == actor.id && it.target == targetId &&
                        it.sourceGeneration == actor.generation && it.targetGeneration == target.generation && it.route == route &&
                        old.sessions.getValue(it.grantedBy).acceptsWork }
                    requireTool(target.authorityParentId == actor.id || actor.authorityParentId == target.id || grant != null) { "Маршрут в другую ветку должен разрешить общий родитель" }
                    requireTool(route.all { old.sessions.getValue(it).acceptsWork }) { "Рабочая область маршрута закрыта" }
                    val packet = command.packet ?: error("Нет контекста")
                    requireTool(old.limits.contextCharacters.allows(json.encodeToString(packet).length)) { "Контекст слишком большой; сократите явно" }
                    requireTool(old.limits.queueSize.hasRoom(old.outbox.count { it.state == SessionDeliveryState.ACCEPTED || it.state == SessionDeliveryState.DELIVERED } +
                        old.sessions.values.count { it.kind == SessionKind.SESSION && !it.settled })) { "Очередь заполнена; сохранён резерв результатов" }
                    val safe = packet.copy(text = PlanningDiagnostics.redact(packet.text))
                    val sequence = old.outbox.filter { it.recipient == targetId }.maxOfOrNull { it.sequence }?.plus(1) ?: 1
                    next = old.copy(outbox = old.outbox + SessionDelivery(operationId, actor.id, targetId, route, safe, sequence, target.generation, routeGrantId = grant?.id))
                }
                OrganismAction.ROUTE -> {
                    requireTool(!supervisor && command.reason.isNotBlank()) { "Укажите основание маршрута" }
                    val from = old.sessions[command.source]
                    requireTool(from != null && target != null && from.id != target.id && from.acceptsWork && target.acceptsWork) { "Обе сессии должны принимать работу" }
                    val route = old.route(from.id, target.id)
                    // The common ancestor, not an arbitrary model-provided address, authorizes transit.
                    requireTool(actor.id in route && from.id in old.subtree(actor.id) && target.id in old.subtree(actor.id) &&
                        actor.id != from.id && actor.id != target.id && route.all { old.sessions.getValue(it).acceptsWork }) { "Разрешить маршрут может общий родитель своих веток" }
                    requireTool(old.limits.queueSize.hasRoom(old.routeGrants.size)) { "Лимит маршрутов исчерпан" }
                    next = old.copy(routeGrants = old.routeGrants + SessionRouteGrant(operationId, actor.id, from.id, target.id,
                        from.generation, target.generation, route))
                    affected = route.toSet()
                }
                OrganismAction.REVIEW_RESULT -> {
                    val result = old.results.firstOrNull { it.id == command.resultId } ?: error("Результат не найден")
                    val owner = old.sessions.getValue(result.sessionId)
                    requireTool(result.recipient == actor.id && owner.authorityParentId == actor.id) { "Приёмку выполняет ответственный родитель" }
                    requireTool(!result.accepted && command.reason.isNotBlank()) { "Результат уже принят или отсутствуют основания проверки" }
                    requireTool(result.generation == owner.generation && owner.settled) { "Запуск ещё не завершён или заменён" }
                    requireTool(!command.accepted || (owner.observed == SessionObservedState.COMPLETED &&
                        owner.desired != SessionDesiredState.QUARANTINE && result.sourceVersion == command.sourceVersion && command.checks.isNotEmpty())) { "Приёмка требует успешного завершения, актуальных исходников и проверок" }
                    val review = SessionResultReview(operationId, result.id, actor.id, command.accepted,
                        PlanningDiagnostics.redact(command.reason), command.sourceVersion, command.checks.map(PlanningDiagnostics::redact), clock())
                    next = old.copy(results = old.results.map { if (it.id == result.id) it.copy(accepted = command.accepted) else it }, reviews = old.reviews + review)
                    affected = setOf(actor.id, owner.id)
                }
                OrganismAction.WAIT -> {
                    requireTool(command.dependencies.isNotEmpty() && command.dependencies.all { old.sessions[it]?.authorityParentId == actor.id }) { "Ожидать можно непосредственных детей" }
                    val edges = old.waitEdges + (actor.id to command.dependencies)
                    fun reaches(id: String, seen: Set<String>): Boolean = id == actor.id || (id !in seen && edges[id].orEmpty().any { reaches(it, seen + id) })
                    requireTool(command.dependencies.none { reaches(it, emptySet()) }) { "Цикл ожидания" }
                    next = old.copy(waitEdges = edges)
                    affected = command.dependencies + actor.id
                }
                OrganismAction.STOP, OrganismAction.PAUSE, OrganismAction.QUARANTINE, OrganismAction.ARCHIVE -> {
                    requireChild()
                    requireTool(!supervisor || command.reason.isNotBlank()) { "Укажите проверяемое основание вмешательства" }
                    affected = old.subtree(targetId)
                    val desired = when (command.action) {
                        OrganismAction.PAUSE -> SessionDesiredState.PAUSE
                        OrganismAction.QUARANTINE -> SessionDesiredState.QUARANTINE
                        else -> SessionDesiredState.STOP
                    }
                    next = old.copy(sessions = old.sessions.mapValues { (id, node) -> if (id !in affected) node else node.copy(
                        desired = desired, observed = if (node.settled) node.observed else SessionObservedState.STOPPING,
                        archived = if (command.action == OrganismAction.ARCHIVE && node.settled) true else node.archived,
                        version = node.version + 1,
                    ) }, outbox = old.outbox.map { if ((it.recipient in affected || it.sender in affected) && it.state != SessionDeliveryState.PROCESSED) it.copy(state = SessionDeliveryState.CANCELLED) else it })
                }
                OrganismAction.RESTORE -> {
                    requireChild()
                    requireTool(old.audit.none { it.action == "QUARANTINE" && targetId in it.affected }) { "Карантин требует проверки фактического исхода операции" }
                    requireTool(target!!.settled && (target.lifecycleParentId == null || old.sessions.getValue(target.lifecycleParentId).acceptsWork)) { "Восстановите рабочую область родителя" }
                    requireTool(old.limits.retries.hasRoom(target.retryCount)) { "Лимит восстановлений исчерпан" }
                    requireTool(old.results.none { it.sessionId == targetId && it.accepted }) { "Принятая работа уже выполнена" }
                    requireTool(command.reason.isNotBlank() && target.rules == actor.rules) { "Проверьте актуальность задания и правил" }
                    requireTool(command.tokens >= 0 && old.hasTokenBudget()) { "Бюджет задачи исчерпан" }
                    val allocation = minOf(command.tokens, actor.remainingTokens)
                    next = old.copy(sessions = old.sessions + (targetId to target.copy(generation = target.generation + 1,
                        previousGeneration = target.generation, archived = false, desired = SessionDesiredState.RUN,
                        observed = SessionObservedState.PENDING, version = target.version + 1, retryCount = target.retryCount + 1,
                        remainingTokens = allocation, lastObservedAt = clock())) +
                        (actor.id to actor.copy(remainingTokens = actor.remainingTokens - allocation, version = actor.version + 1)))
                }
                OrganismAction.RENAME -> { requireChild(); requireTool(command.name.isNotBlank() && command.name.length <= 256) { "Некорректное название" }
                    next = old.copy(sessions = old.sessions + (targetId to target!!.copy(name = command.name, version = target.version + 1))) }
                OrganismAction.SIGNAL -> {
                    requireTool(target != null && target.kind != SessionKind.IMMUNITY && command.reason.isNotBlank()) { "Укажите сессию и диагностический сигнал" }
                    requireTool(old.limits.queueSize.hasRoom(old.signals.count { signal -> old.diagnoses.none { it.signalId == signal.id } })) { "Очередь сигналов заполнена" }
                    next = old.copy(signals = old.signals + ImmunitySignal(operationId, actor.id, targetId, PlanningDiagnostics.redact(command.reason).takeConfigured(old.limits.contextCharacters), clock(), requestResearch = true))
                    affected = setOf(targetId, old.immunityId)
                }
            }
            commit(next.copy(version = old.version + 1,
                operations = old.operations + (operationId to OrganismOperation(operationId, fingerprint, targetId,
                    if (command.action in setOf(OrganismAction.STOP, OrganismAction.PAUSE, OrganismAction.QUARANTINE, OrganismAction.ARCHIVE)) SessionOperationState.ACCEPTED else SessionOperationState.SUCCEEDED)),
                audit = old.audit + SessionAuditEvent(operationId, actor.id, command.action.name, affected, PlanningDiagnostics.redact(command.reason), clock())))
        }
    }

    /** Runtime observations cannot complete a parent with unsettled children. */
    suspend fun observe(id: String, sessionId: String, generation: Long, observed: SessionObservedState): SessionOrganism = lock.withLock {
        val old = read(id); val node = old.sessions.getValue(sessionId)
        require(node.generation == generation) { "Поздний ответ отозванного запуска" }
        // Native completion can race a stop during cleanup. Never leave revoked work
        // pending, and never clear an unknown external effect with an ordinary completion.
        val actualObserved = if (observed in setOf(SessionObservedState.PENDING, SessionObservedState.COMPLETED) &&
            node.desired != SessionDesiredState.RUN) {
            if (node.observed == SessionObservedState.UNKNOWN || old.unresolvedQuarantines(sessionId).isNotEmpty()) SessionObservedState.UNKNOWN
            else SessionObservedState.STOPPED
        } else observed
        require(actualObserved !in setOf(SessionObservedState.COMPLETED, SessionObservedState.STOPPED, SessionObservedState.FAILED) ||
            old.auxiliaryRuns.values.none { it.ownerSessionId == sessionId && !it.settled }) { "Вспомогательные запуски ещё не остановлены" }
        require(actualObserved !in setOf(SessionObservedState.COMPLETED, SessionObservedState.STOPPED, SessionObservedState.FAILED) || old.sessions.values.none { it.lifecycleParentId == sessionId && !it.settled }) { "Дети ещё не остановлены" }
        require(actualObserved != SessionObservedState.RUNNING || node.acceptsWork) { "Рабочая область закрыта" }
        var nodes = old.sessions + (sessionId to node.copy(observed = actualObserved, version = node.version + 1, lastObservedAt = clock()))
        if (actualObserved in setOf(SessionObservedState.COMPLETED, SessionObservedState.STOPPED, SessionObservedState.FAILED) && !node.settled) {
            node.authorityParentId?.let { parent -> nodes = nodes + (parent to nodes.getValue(parent).let { it.copy(remainingTokens = it.remainingTokens + node.remainingTokens, version = it.version + 1) })
                nodes = nodes + (sessionId to nodes.getValue(sessionId).copy(remainingTokens = 0)) }
        }
        if (actualObserved == SessionObservedState.FAILED && node.authorityParentId?.let { nodes[it]?.failurePolicy } == SessionFailurePolicy.CANCEL_SIBLINGS) {
            val siblings = nodes.values.filter { it.lifecycleParentId == node.lifecycleParentId && it.id != sessionId }.flatMap { old.subtree(it.id) }.toSet()
            nodes = nodes.mapValues { (id, value) -> if (id in siblings && !value.settled) value.copy(desired = SessionDesiredState.STOP, observed = SessionObservedState.STOPPING) else value }
        }
        commit(old.copy(version = old.version + 1, sessions = nodes))
    }

    suspend fun acknowledge(id: String, deliveryId: String, recipient: String, generation: Long, processed: Boolean): SessionOrganism = lock.withLock {
        val old = read(id); val delivery = old.outbox.first { it.id == deliveryId }
        require(delivery.recipient == recipient && delivery.recipientGeneration == generation && old.sessions.getValue(recipient).generation == generation) { "Другой получатель или поколение" }
        require(delivery.state != SessionDeliveryState.CANCELLED) { "Доставка отменена" }
        require(old.outbox.none { it.recipient == recipient && it.sequence < delivery.sequence && it.state == SessionDeliveryState.ACCEPTED }) { "Нарушен порядок доставки" }
        require(!processed || delivery.state != SessionDeliveryState.ACCEPTED) { "Сначала подтвердите доставку" }
        commit(old.copy(version = old.version + 1, outbox = old.outbox.map { if (it.id == deliveryId) it.copy(state = if (processed || it.state == SessionDeliveryState.PROCESSED) SessionDeliveryState.PROCESSED else SessionDeliveryState.DELIVERED) else it }))
    }

    /** Usage arrives after a provider effect and can overrun a grant between observations.
     * Keep that actual spend, but remove the excess reservation before it can fund another
     * turn. Older aggregates may already contain this excess; admission repairs those too.
     * Spend is borne by the responsible branch/ancestors first, recovery only as a last resort.
     */
    private fun SessionOrganism.reconcileTokenBudget(preferredSessionId: String): SessionOrganism {
        val available = limits.tokens?.let { (it - sessions.values.sumOf { node -> node.spentTokens }).coerceAtLeast(0) } ?: return this
        var excess = (sessions.values.sumOf { it.remainingTokens } - available).coerceAtLeast(0)
        if (excess == 0L) return this
        val priority = mutableListOf<String>()
        var next: String? = preferredSessionId
        while (next != null && next !in priority) {
            priority += next
            next = sessions[next]?.authorityParentId
        }
        priority += sessions.keys.sorted()
        val ordered = priority.distinct().filter { it != immunityId } + immunityId
        val nodes = sessions.toMutableMap()
        val affected = mutableSetOf<String>()
        for (sessionId in ordered) {
            if (excess == 0L) break
            val node = nodes[sessionId] ?: continue
            val debit = minOf(node.remainingTokens, excess)
            if (debit == 0L) continue
            nodes[sessionId] = node.copy(remainingTokens = node.remainingTokens - debit, version = node.version + 1)
            excess -= debit
            affected += sessionId
        }
        return copy(sessions = nodes, audit = audit + SessionAuditEvent("budget-reconcile-$id-${version + 1}", "APPLICATION",
            "BUDGET_RECONCILE", affected, "Фактический расход учтён в оставшемся бюджете задачи", clock()))
    }

    suspend fun charge(scope: SessionAuthority, tokens: Long): SessionOrganism = lock.withLock {
        require(tokens >= 0)
        val old = read(scope.organismId); val node = old.usageActor(scope)
        val exhausted = old.limits.tokens?.let { tokens >= (it - old.sessions.values.sumOf { session -> session.spentTokens }).coerceAtLeast(0) } == true
        val updated = node.copy(remainingTokens = (node.remainingTokens - tokens).coerceAtLeast(0), spentTokens = node.spentTokens + tokens,
            desired = if (exhausted && node.desired == SessionDesiredState.RUN) SessionDesiredState.STOP else node.desired,
            observed = if (exhausted) SessionObservedState.STOPPING else node.observed, version = node.version + 1)
        commit(old.copy(sessions = old.sessions + (node.id to updated))
            .reconcileTokenBudget(node.id).copy(version = old.version + 1))
    }

    /** Only a host-created ToolExecutionContext reaches this application admission hook. */
    suspend fun beginAuxiliary(context: ToolExecutionContext): SessionAuxiliaryRun = lock.withLock {
        val old = read(context.organismId ?: error("Организм вспомогательного запуска не сохранён"))
        require(context.sessionId != context.ownerSessionId && context.requestId.isNotBlank() &&
            (context.auxiliaryExecution && context.planId != null || context.role in setOf(ToolRole.PLANNER, ToolRole.ORCHESTRATOR))) { "Вспомогательный запуск не подтверждён приложением" }
        val owner = old.sessions.getValue(context.ownerSessionId)
        old.actor(SessionAuthority(context.projectId, old.id, owner.id, context.runtimeGeneration, owner.mode))
        require(context.mode == owner.mode || context.auxiliaryExecution && context.mode == CodingInteractionMode.CODE) { "Режим вспомогательного запуска не разрешён" }
        require(old.auxiliaryRuns.values.none { it.sessionId == context.sessionId && !it.settled }) { "Предыдущий вспомогательный запуск ещё не остановлен" }
        val occupied = old.sessions.values.count { it.observed in setOf(SessionObservedState.RUNNING, SessionObservedState.WAITING_USER,
            SessionObservedState.STOPPING, SessionObservedState.UNKNOWN) }
        require(old.limits.activeSessions.hasRoom(occupied + old.auxiliaryRuns.values.count { !it.settled })) { "Достигнут лимит активных сессий" }
        val id = "aux-${context.sessionId}-${context.runtimeGeneration}-${old.version + 1}"
        val run = SessionAuxiliaryRun(id, context.sessionId, context.ownerSessionId, context.runtimeGeneration,
            context.runId.orEmpty(), context.requestId, context.mode, startedAt = clock())
        commit(old.copy(version = old.version + 1, auxiliaryRuns = old.auxiliaryRuns + (id to run),
            audit = old.audit + SessionAuditEvent(id, "APPLICATION", "AUXILIARY_START", setOf(owner.id), context.sessionId, clock())))
        run
    }

    suspend fun chargeAuxiliary(organismId: String, auxiliaryId: String, sourceId: String, totalTokens: Long): SessionOrganism = lock.withLock {
        require(sourceId.isNotBlank() && totalTokens >= 0)
        val old = read(organismId); val run = old.auxiliaryRuns.getValue(auxiliaryId)
        require(!run.settled && run.observed != SessionObservedState.UNKNOWN) { "Вспомогательный запуск закрыт" }
        val owner = old.sessions.getValue(run.ownerSessionId)
        old.usageActor(SessionAuthority(old.projectId, old.id, owner.id, run.generation, owner.mode), auxiliary = true)
        val previous = run.usage[sourceId] ?: 0
        if (totalTokens <= previous) return@withLock old
        val tokens = totalTokens - previous
        val exhausted = old.limits.tokens?.let { tokens >= (it - old.sessions.values.sumOf { session -> session.spentTokens }).coerceAtLeast(0) } == true
        val node = owner.copy(remainingTokens = (owner.remainingTokens - tokens).coerceAtLeast(0), spentTokens = owner.spentTokens + tokens,
            desired = if (exhausted && owner.desired == SessionDesiredState.RUN) SessionDesiredState.STOP else owner.desired,
            observed = if (exhausted) SessionObservedState.STOPPING else owner.observed, version = owner.version + 1)
        commit(old.copy(sessions = old.sessions + (owner.id to node),
            auxiliaryRuns = old.auxiliaryRuns + (run.id to run.copy(usage = run.usage + (sourceId to totalTokens)))).reconcileTokenBudget(owner.id)
            .copy(version = old.version + 1))
    }

    suspend fun finishAuxiliary(organismId: String, auxiliaryId: String, observed: SessionObservedState): SessionOrganism = lock.withLock {
        require(observed in setOf(SessionObservedState.COMPLETED, SessionObservedState.FAILED, SessionObservedState.STOPPED, SessionObservedState.UNKNOWN))
        val old = read(organismId); val run = old.auxiliaryRuns.getValue(auxiliaryId)
        require(old.sessions.getValue(run.ownerSessionId).generation == run.generation) { "Вспомогательный запуск другого поколения" }
        commit(old.copy(version = old.version + 1, auxiliaryRuns = old.auxiliaryRuns + (run.id to run.copy(observed = observed, endedAt = clock())),
            audit = old.audit + SessionAuditEvent("${run.id}-end-${old.version + 1}", "APPLICATION", "AUXILIARY_$observed", setOf(run.ownerSessionId), run.sessionId, clock())))
    }

    suspend fun recover(id: String): SessionOrganism = lock.withLock {
        val old = read(id)
        if (old.deletedAt != null) return@withLock old
        val interrupted = old.integrations.values.filter { it.phase in setOf(SessionIntegrationPhase.INTENT, SessionIntegrationPhase.PREPARING,
            SessionIntegrationPhase.MERGING, SessionIntegrationPhase.VERIFYING, SessionIntegrationPhase.UNKNOWN) }
        val affected = interrupted.flatMap { old.subtree(it.request.actorSessionId) }.toSet()
        commit(old.copy(version = old.version + 1, auxiliaryRuns = old.auxiliaryRuns.mapValues { (_, run) -> if (run.settled) run else run.copy(observed = SessionObservedState.UNKNOWN) }, sessions = old.sessions.mapValues { (_, node) ->
            if (node.id in affected) node.copy(desired = SessionDesiredState.QUARANTINE, observed = SessionObservedState.UNKNOWN, version = node.version + 1)
            else if (node.observed in setOf(SessionObservedState.RUNNING, SessionObservedState.STOPPING, SessionObservedState.WAITING_USER)) node.copy(observed = SessionObservedState.UNKNOWN, version = node.version + 1) else node
        }, integrations = old.integrations + interrupted.associate { it.request.id to it.copy(phase = SessionIntegrationPhase.UNKNOWN,
            error = it.error.ifBlank { "Интеграция прервана; сначала подтвердите фактический исход" }) },
            operations = old.operations.mapValues { (operationId, operation) -> if (interrupted.any { it.request.id == operationId }) operation.copy(state = SessionOperationState.UNKNOWN) else operation },
            outbox = old.outbox.map { if (it.state != SessionDeliveryState.PROCESSED && (it.sender in affected || it.recipient in affected)) it.copy(state = SessionDeliveryState.CANCELLED) else it },
            audit = old.audit + interrupted.filter { record -> old.audit.none { it.operationId == "recover-integration-${record.request.id}" } }.map { record ->
                SessionAuditEvent("recover-integration-${record.request.id}", "APPLICATION", "QUARANTINE", old.subtree(record.request.actorSessionId),
                    "Незавершённая интеграция ${record.request.id}; повтор Git и проверок заблокирован", clock())
            }))
    }

    /** App-only deletion follows confirmed cancellation; model tools never expose this action. */
    suspend fun deleteHistoryByUser(id: String, target: String?): SessionOrganism = lock.withLock {
        val old = read(id)
        val whole = target == null || target == old.zygoteId
        val affected = if (whole) old.sessions.keys else old.subtree(target!!)
        if (old.historyDeletedIds.containsAll(affected) && (!whole || old.deletedAt != null)) return@withLock old
        require(affected.all { old.sessions.getValue(it).settled }) { "Сначала подтвердите завершение всех удаляемых сессий" }
        require(old.auxiliaryRuns.values.none { it.ownerSessionId in affected && !it.settled }) { "Сначала подтвердите остановку вспомогательных запусков" }
        val operation = "delete-history-${target ?: old.zygoteId}-${old.version + 1}"
        commit(old.copy(version = old.version + 1,
            historyDeletedIds = old.historyDeletedIds + affected, deletedAt = if (whole) clock() else old.deletedAt,
            stoppedByUser = old.stoppedByUser || whole,
            sessions = old.sessions.mapValues { (sessionId, node) -> if (sessionId !in affected) node else node.copy(
                archived = true, desired = SessionDesiredState.STOP, observed = SessionObservedState.STOPPED,
                generation = node.generation + 1, previousGeneration = node.generation, version = node.version + 1, remainingTokens = 0) },
            outbox = old.outbox.map { if (it.state != SessionDeliveryState.PROCESSED &&
                (it.sender in affected || it.recipient in affected || it.route.any { hop -> hop in affected })) it.copy(state = SessionDeliveryState.CANCELLED) else it },
            waitEdges = old.waitEdges.filterKeys { it !in affected }.mapValues { (_, dependencies) -> dependencies - affected },
            operations = old.operations + (operation to OrganismOperation(operation, "USER:DELETE_HISTORY:$target", target ?: old.zygoteId)),
            audit = old.audit + SessionAuditEvent(operation, "USER", "DELETE_HISTORY", affected, "История удалена после подтверждённого завершения", clock())))
    }

    suspend fun recordResult(id: String, result: SessionResult): SessionOrganism = lock.withLock {
        val old = read(id); val source = old.sessions.getValue(result.sessionId)
        require(source.generation == result.generation && source.task?.resultRecipient == result.recipient) { "Результат другого запуска или получателя" }
        require(!result.accepted) { "Приёмку выполняет ответственный родитель" }
        result.integrationId?.let { integrationId ->
            val workspace = old.resultWorkspace(result)
            require(workspace != null && result.checks == old.integrations.getValue(integrationId).resultChecks() &&
                workspace.attempt.path in result.artifacts) { "Результат не связан с подтверждённой интеграцией этого поколения" }
        }
        val safe = result.copy(summary = redact(result.summary), evidence = result.evidence.map(::redact),
            artifacts = result.artifacts.map(::redact), checks = result.checks.map(::redact), sourceVersion = redact(result.sourceVersion))
        old.results.firstOrNull { it.id == result.id }?.let { require(it == safe) { "Результат уже сохранён с другим содержимым" }; return@withLock old }
        require(old.limits.queueSize.hasRoom(old.outbox.count { it.state in setOf(SessionDeliveryState.ACCEPTED, SessionDeliveryState.DELIVERED) })) { "Очередь результатов заполнена" }
        val recipient = old.sessions.getValue(result.recipient)
        val shortened = !old.limits.contextCharacters?.div(2).allows(safe.summary.length)
        val delivery = SessionDelivery("result-${result.id}", source.id, recipient.id, old.route(source.id, recipient.id),
            SessionContextPacket(safe.summary.takeConfigured(old.limits.contextCharacters?.div(2)), safe.sourceVersion, source.rules?.version.orEmpty(), resultIds = listOf(result.id),
                summarized = shortened, omissions = if (shortened) "Полный результат сохранён: ${result.id}" else ""),
            old.outbox.filter { it.recipient == recipient.id }.maxOfOrNull { it.sequence }?.plus(1) ?: 1, recipient.generation)
        commit(old.copy(version = old.version + 1, results = old.results + safe, outbox = old.outbox + delivery,
            audit = old.audit + SessionAuditEvent(result.id, source.id, "RESULT", setOf(source.id, recipient.id), "Результат сохранён; приёмка не подтверждена", clock())))
    }

    /** Only an application diagnostic with saved evidence can create an intervention proposal. */
    suspend fun proposeImmunityInterventions(id: String): SessionOrganism = lock.withLock {
        val old = read(id)
        if (old.deletedAt != null || old.stoppedByUser || !old.sessions.getValue(old.immunityId).acceptsWork) return@withLock old
        val available = old.limits.queueSize?.let { (it - old.interventions.count { proposal -> proposal.state in setOf(ImmunityInterventionState.PROPOSED, ImmunityInterventionState.ACCEPTED, ImmunityInterventionState.UNKNOWN) }).coerceAtLeast(0) }
        val proposals = old.diagnoses.filter { diagnosis -> diagnosis.evidence.isNotEmpty() &&
            diagnosis.generation == old.sessions[diagnosis.target]?.generation && diagnosis.generation != null &&
            diagnosis.target !in old.historyDeletedIds && old.interventions.none { it.signalId == diagnosis.signalId } }.let { if (available == null) it else it.take(available) }.map { diagnosis ->
            val node = old.sessions.getValue(diagnosis.target)
            val actions = buildSet {
                addAll(setOf(ImmunityAction.PAUSE, ImmunityAction.QUARANTINE, ImmunityAction.STOP, ImmunityAction.ARCHIVE, ImmunityAction.DELETE_HISTORY))
                // Legacy attempts are resumed by their human-confirmed plan, not a generic native replay.
                if (node.settled && node.legacyAttempt == null && old.limits.retries.hasRoom(node.retryCount) &&
                    old.results.none { it.sessionId == node.id && it.accepted }) add(ImmunityAction.RECREATE)
            }
            ImmunityIntervention("intervention-${diagnosis.signalId}", diagnosis.signalId, node.id, node.generation,
                actions, diagnosis.evidence.map(::redact), old.subtree(node.id), clock())
        }
        if (proposals.isEmpty()) return@withLock old
        commit(old.copy(version = old.version + 1, interventions = old.interventions + proposals,
            audit = old.audit + proposals.map { proposal -> SessionAuditEvent(proposal.id, old.immunityId, "PROPOSE_INTERVENTION",
                proposal.affected, proposal.evidence.joinToString("; "), clock()) }))
    }

    /** App-only confirmation. This persists intent before any runtime or deletion callback. */
    suspend fun acceptImmunityIntervention(id: String, proposalId: String, action: ImmunityAction,
        rules: PlanningRulesSnapshot? = null, sourceVersion: String? = null, reconciled: Boolean = false,
    ): SessionOrganism = lock.withLock {
        val old = read(id)
        val proposal = old.interventions.firstOrNull { it.id == proposalId } ?: error("Предложение не найдено")
        require(old.deletedAt == null && proposal.target !in old.historyDeletedIds) { "История уже удалена" }
        if (proposal.state != ImmunityInterventionState.PROPOSED) {
            require(proposal.action == action && proposal.state != ImmunityInterventionState.REJECTED) { "Предложение уже рассмотрено иначе" }
            return@withLock old
        }
        require(!old.stoppedByUser && old.sessions.getValue(old.immunityId).acceptsWork) { "Иммунитет остановлен пользователем" }
        val node = old.sessions.getValue(proposal.target)
        require(node.kind != SessionKind.IMMUNITY && node.generation == proposal.generation) { "Предложение другого поколения" }
        val diagnosis = old.diagnoses.single { it.signalId == proposal.signalId }
        require(diagnosis.target == node.id && diagnosis.generation == node.generation && diagnosis.evidence.isNotEmpty() && diagnosis.evidence.map(::redact) == proposal.evidence && action in proposal.actions) { "Нет сохранённых оснований вмешательства" }
        val affected = if (action == ImmunityAction.DELETE_HISTORY && node.kind == SessionKind.ZYGOTE) old.sessions.keys else old.subtree(node.id)
        var nodes = old.sessions
        if (action == ImmunityAction.RECREATE) {
            require(affected.all { old.sessions.getValue(it).settled } && reconciled) { "Сначала подтвердите остановку и исход всех операций" }
            require(old.integrations.values.none { it.request.actorSessionId in affected && it.phase in setOf(SessionIntegrationPhase.INTENT,
                SessionIntegrationPhase.PREPARING, SessionIntegrationPhase.MERGING, SessionIntegrationPhase.VERIFYING, SessionIntegrationPhase.UNKNOWN) }) { "Сначала подтвердите исход интеграции" }
            require(node.legacyAttempt == null && old.results.none { it.sessionId == node.id && it.accepted }) { "Принятую работу и попытку плана нельзя повторить" }
            val parent = node.lifecycleParentId?.let { old.sessions.getValue(it) }
            require(parent == null || parent.acceptsWork) { "Сначала восстановите рабочую область родителя" }
            require(rules != null && node.rules == rules && (parent == null || parent.rules == rules)) { "Правила изменились; требуется новое задание" }
            require(node.task == null || (sourceVersion != null && sourceVersion == node.task.sourceVersion)) { "Исходники задания изменились или не проверены" }
            require(old.limits.retries.hasRoom(node.retryCount) && old.withinDuration()) { "Лимит восстановления исчерпан" }
            val immunity = old.sessions.getValue(old.immunityId)
            val allocation = immunity.remainingTokens / 2
            require(old.hasTokenBudget()) { "Бюджет задачи исчерпан" }
            nodes = nodes + (node.id to node.copy(generation = node.generation + 1, previousGeneration = node.generation,
                desired = SessionDesiredState.RUN, observed = SessionObservedState.PENDING, archived = false,
                retryCount = node.retryCount + 1, remainingTokens = node.remainingTokens + allocation,
                version = node.version + 1, lastObservedAt = clock())) +
                (immunity.id to immunity.copy(remainingTokens = immunity.remainingTokens - allocation, version = immunity.version + 1))
        } else {
            val desired = when (action) {
                ImmunityAction.PAUSE -> SessionDesiredState.PAUSE
                ImmunityAction.QUARANTINE -> SessionDesiredState.QUARANTINE
                else -> SessionDesiredState.STOP
            }
            nodes = nodes.mapValues { (sessionId, value) -> if (sessionId !in affected) value else value.copy(desired = desired,
                observed = if (value.settled || value.observed == SessionObservedState.UNKNOWN) value.observed else SessionObservedState.STOPPING,
                version = value.version + 1) }
        }
        val operation = "${proposal.id}-${action.name}"
        commit(old.copy(version = old.version + 1, sessions = nodes,
            interventions = old.interventions.map { if (it.id == proposal.id) it.copy(state = ImmunityInterventionState.ACCEPTED, action = action, affected = affected, confirmedAt = clock()) else it },
            operations = old.operations + (operation to OrganismOperation(operation, "IMMUNITY:${proposal.id}:${proposal.generation}:$action", node.id, SessionOperationState.ACCEPTED)),
            outbox = old.outbox.map { delivery -> if (delivery.state != SessionDeliveryState.PROCESSED &&
                (delivery.sender in affected || delivery.recipient in affected)) delivery.copy(state = SessionDeliveryState.CANCELLED) else delivery },
            audit = old.audit + SessionAuditEvent(operation, old.immunityId, "IMMUNITY_${action.name}", affected,
                "Подтверждено пользователем; ${proposal.evidence.joinToString("; ")}", clock())))
    }

    /** Completion requires saved observations; an exception never becomes a successful intervention. */
    suspend fun finishImmunityIntervention(id: String, proposalId: String, error: String? = null): SessionOrganism = lock.withLock {
        val old = read(id); val proposal = old.interventions.single { it.id == proposalId }
        if (proposal.state == ImmunityInterventionState.COMPLETED) return@withLock old
        require(proposal.state in setOf(ImmunityInterventionState.ACCEPTED, ImmunityInterventionState.UNKNOWN))
        val action = proposal.action ?: kotlin.error("Действие не подтверждено")
        val node = old.sessions.getValue(proposal.target)
        if (error == null) when (action) {
            ImmunityAction.RECREATE -> require(node.generation == proposal.generation + 1 &&
                (node.kind == SessionKind.ZYGOTE && node.observed == SessionObservedState.PENDING ||
                    node.lastStartedGeneration == node.generation && (node.settled || node.observed in setOf(SessionObservedState.RUNNING, SessionObservedState.WAITING_USER)))) { "Запуск нового поколения не подтверждён" }
            ImmunityAction.DELETE_HISTORY -> require(proposal.affected.all { it in old.historyDeletedIds }) { "Удаление истории не подтверждено" }
            else -> require(proposal.affected.all { old.sessions.getValue(it).settled }) { "Остановка ещё не подтверждена" }
        }
        val operation = "${proposal.id}-${action.name}"
        val nodes = if (error == null && action == ImmunityAction.ARCHIVE) old.sessions.mapValues { (sessionId, value) ->
            if (sessionId in proposal.affected) value.copy(archived = true, version = value.version + 1) else value
        } else old.sessions
        commit(old.copy(version = old.version + 1, sessions = nodes,
            interventions = old.interventions.map { if (it.id == proposalId) it.copy(state = if (error == null) ImmunityInterventionState.COMPLETED else ImmunityInterventionState.UNKNOWN, error = error?.let(::redact).orEmpty()) else it },
            operations = old.operations + (operation to old.operations.getValue(operation).copy(state = if (error == null) SessionOperationState.SUCCEEDED else SessionOperationState.UNKNOWN)),
            audit = old.audit + SessionAuditEvent("$operation-result-${old.version + 1}", old.immunityId,
                if (error == null) "INTERVENTION_COMPLETED" else "INTERVENTION_UNKNOWN", proposal.affected, error?.let(::redact) ?: "Результат подтверждён сохранённым состоянием", clock())))
    }

    suspend fun dismissImmunityIntervention(id: String, proposalId: String): SessionOrganism = lock.withLock {
        val old = read(id); val proposal = old.interventions.single { it.id == proposalId }
        if (proposal.state == ImmunityInterventionState.REJECTED) return@withLock old
        require(proposal.state == ImmunityInterventionState.PROPOSED) { "Выполнение уже принято" }
        commit(old.copy(version = old.version + 1, interventions = old.interventions.map { if (it.id == proposalId) it.copy(state = ImmunityInterventionState.REJECTED) else it },
            audit = old.audit + SessionAuditEvent("$proposalId-dismissed", "USER", "INTERVENTION_REJECTED", proposal.affected, "Предложение отклонено пользователем", clock())))
    }

    /** A complaint schedules inspection; only saved host observations justify intervention. */
    suspend fun inspectSignals(id: String): SessionOrganism = lock.withLock {
        val old = read(id)
        val immunity = old.sessions.getValue(old.immunityId)
        if (!immunity.acceptsWork || old.stoppedByUser) return@withLock old
        val pending = old.signals.filter { signal -> signal.target !in old.historyDeletedIds && old.diagnoses.none { it.signalId == signal.id } }
        if (pending.isEmpty()) return@withLock old
        var next = old
        pending.forEach { signal ->
            val node = next.sessions.getValue(signal.target)
            val evidence = buildList {
                if (node.observed == SessionObservedState.UNKNOWN) add("Наблюдаемый исход неизвестен: поколение ${node.generation}, версия ${node.version}")
                if (node.observed == SessionObservedState.FAILED) add("Runtime подтвердил ошибку поколения ${node.generation}")
                if (!next.hasTokenBudget() && !node.settled) add("Бюджет задачи исчерпан")
                if (!node.settled && !next.withinDuration()) add("Превышен срок организма")
                if (!node.settled && node.lifecycleParentId?.let { next.sessions.getValue(it).settled } == true) add("Рабочая область родителя завершена")
            }
            val affected = if (evidence.isEmpty()) setOf(node.id) else next.subtree(node.id)
            val action = if (evidence.isEmpty()) "NO_INTERVENTION" else "QUARANTINE"
            val diagnosis = ImmunityDiagnosis(signal.id, node.id, evidence, action, affected, clock(), node.generation)
            next = next.copy(diagnoses = next.diagnoses + diagnosis,
                sessions = if (evidence.isEmpty()) next.sessions else next.sessions.mapValues { (sessionId, value) ->
                    if (sessionId !in affected) value else value.copy(desired = SessionDesiredState.QUARANTINE,
                        observed = if (value.settled) value.observed else SessionObservedState.STOPPING, version = value.version + 1)
                },
                outbox = if (evidence.isEmpty()) next.outbox else next.outbox.map { delivery ->
                    if ((delivery.sender in affected || delivery.recipient in affected) && delivery.state != SessionDeliveryState.PROCESSED)
                        delivery.copy(state = SessionDeliveryState.CANCELLED) else delivery
                },
                audit = next.audit + SessionAuditEvent("diagnosis-${signal.id}", immunity.id, action, affected,
                    if (evidence.isEmpty()) "Проверяемые основания вмешательства отсутствуют" else evidence.joinToString("; "), clock()))
        }
        commit(next.copy(version = old.version + 1))
    }

    /** Safety does not depend on the immunity model accepting or diagnosing an unknown effect. */
    suspend fun quarantine(id: String, sessionId: String, generation: Long, operationId: String, reason: String): SessionOrganism = lock.withLock {
        val old = read(id); val node = old.sessions.getValue(sessionId)
        require(node.generation == generation) { "Полномочия отозваны" }
        if (old.audit.any { it.operationId == "quarantine-$operationId" }) return@withLock old
        val affected = old.subtree(sessionId)
        commit(old.copy(version = old.version + 1, sessions = old.sessions.mapValues { (id, current) ->
            if (id in affected) current.copy(desired = SessionDesiredState.QUARANTINE, observed = SessionObservedState.UNKNOWN, version = current.version + 1) else current
        }, audit = old.audit + SessionAuditEvent("quarantine-$operationId", "APPLICATION", "QUARANTINE", affected, PlanningDiagnostics.redact(reason), clock()),
            outbox = old.outbox.map { if ((it.sender in affected || it.recipient in affected) && it.state != SessionDeliveryState.PROCESSED) it.copy(state = SessionDeliveryState.CANCELLED) else it }))
    }
}
