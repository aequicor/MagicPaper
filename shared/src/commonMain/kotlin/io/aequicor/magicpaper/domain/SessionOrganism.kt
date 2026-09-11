package io.aequicor.magicpaper.domain

import kotlinx.serialization.Serializable

/** Persisted origin, authority, runtime and task dependencies are independent concepts. */
@Serializable enum class SessionKind { ZYGOTE, SESSION, IMMUNITY }
@Serializable enum class SessionDesiredState { RUN, PAUSE, STOP, QUARANTINE }
@Serializable enum class SessionObservedState { PENDING, RUNNING, WAITING_USER, STOPPING, COMPLETED, STOPPED, FAILED, UNKNOWN }
@Serializable enum class SessionFailurePolicy { ISOLATE, CANCEL_SIBLINGS }
@Serializable enum class MessageOrigin { USER, SESSION, TOOL }
@Serializable enum class SessionDeliveryState { ACCEPTED, DELIVERED, PROCESSED, CANCELLED }
@Serializable enum class SessionOperationState { ACCEPTED, SUCCEEDED, UNKNOWN }

/** Only explicit user settings impose resource ceilings. Null means unbounded. */
@Serializable data class OrganismLimits(
    val activeSessions: Int? = null,
    val depth: Int? = null,
    val tokens: Long? = null,
    val recoveryTokens: Long = 0,
    val durationMillis: Long? = null,
    val retries: Int? = null,
    val queueSize: Int? = null,
    val contextCharacters: Int? = null,
) {
    fun validate() {
        require(activeSessions == null || activeSessions > 0) { "Число активных сессий должно быть положительным" }
        require(depth == null || depth > 0) { "Глубина дерева должна быть положительной" }
        require(tokens == null || tokens > 0) { "Бюджет токенов должен быть положительным" }
        require(recoveryTokens >= 0 && (tokens == null || recoveryTokens < tokens)) { "Резерв превышает бюджет" }
        require(durationMillis == null || durationMillis > 0) { "Время работы должно быть положительным" }
        require(retries == null || retries >= 0) { "Число повторов не может быть отрицательным" }
        require(queueSize == null || queueSize > 0) { "Размер очереди должен быть положительным" }
        require(contextCharacters == null || contextCharacters > 0) { "Размер контекста должен быть положительным" }
    }
}

@Serializable data class SessionTask(
    val text: String,
    val resultRecipient: String,
    val acceptance: String,
    val sourceVersion: String = "",
    val dependencies: Set<String> = emptySet(),
)

@Serializable data class SessionNode(
    val id: String,
    val kind: SessionKind,
    val name: String,
    val originParentId: String? = null,
    val authorityParentId: String? = originParentId,
    val lifecycleParentId: String? = originParentId,
    val generation: Long = 0,
    val version: Long = 0,
    val desired: SessionDesiredState = SessionDesiredState.RUN,
    val observed: SessionObservedState = SessionObservedState.PENDING,
    val archived: Boolean = false,
    val mode: CodingInteractionMode = CodingInteractionMode.RESEARCH,
    val remainingTokens: Long = 0,
    val spentTokens: Long = 0,
    val retryCount: Int = 0,
    val failurePolicy: SessionFailurePolicy = SessionFailurePolicy.ISOLATE,
    val task: SessionTask? = null,
    val rules: PlanningRulesSnapshot? = null,
    val previousGeneration: Long? = null,
    val lastObservedAt: Long = 0,
    val workspace: SessionCodingWorkspace? = null,
    val lastStartedGeneration: Long = -1,
    val nameManuallySet: Boolean = false,
    val legacyAttempt: SessionLegacyAttempt? = null,
) {
    val settled: Boolean get() = observed in setOf(SessionObservedState.COMPLETED, SessionObservedState.STOPPED, SessionObservedState.FAILED)
    val acceptsWork: Boolean get() = !archived && !settled && observed != SessionObservedState.UNKNOWN && observed != SessionObservedState.STOPPING && desired == SessionDesiredState.RUN
}

@Serializable data class SessionContextPacket(
    val text: String,
    val sourceVersion: String = "",
    val ruleVersion: String = "",
    val resultIds: List<String> = emptyList(),
    val attachments: List<String> = emptyList(),
    val summarized: Boolean = false,
    val omissions: String = "",
)

@Serializable data class SessionDelivery(
    val id: String,
    val sender: String,
    val recipient: String,
    val route: List<String>,
    val packet: SessionContextPacket,
    val sequence: Long,
    val recipientGeneration: Long,
    val origin: MessageOrigin = MessageOrigin.SESSION,
    val state: SessionDeliveryState = SessionDeliveryState.ACCEPTED,
    val routeGrantId: String? = null,
)

/** Only the responsible common parent grants a route between its own branches. */
@Serializable data class SessionRouteGrant(
    val id: String, val grantedBy: String, val source: String, val target: String,
    val sourceGeneration: Long, val targetGeneration: Long, val route: List<String>,
)

@Serializable data class SessionResultReview(
    val operationId: String, val resultId: String, val reviewer: String, val accepted: Boolean,
    val evidence: String, val sourceVersion: String, val checks: List<String>, val createdAt: Long,
)

@Serializable data class SessionLegacyAttempt(
    val planId: String, val runId: String, val stageId: String, val attemptId: String,
    val turnIndex: Int, val generation: Long,
)

/** Host-issued authority captured when the user retries; a later stop invalidates its version. */
@Serializable data class PlanAttemptRetryAuthorization(
    val id: String,
    val binding: SessionLegacyAttempt,
    val expectedVersion: Long,
    val requestedBinding: SessionLegacyAttempt = binding,
)

@Serializable data class ImmunityDiagnosis(
    val signalId: String, val target: String, val evidence: List<String>, val action: String,
    val affected: Set<String>, val createdAt: Long, val generation: Long? = null,
)

@Serializable enum class ImmunityAction { PAUSE, QUARANTINE, STOP, ARCHIVE, RECREATE, DELETE_HISTORY }
@Serializable enum class ImmunityInterventionState { PROPOSED, ACCEPTED, COMPLETED, REJECTED, UNKNOWN }

/** A saved diagnostic proposal is never a human approval or a claim that an effect completed. */
@Serializable data class ImmunityIntervention(
    val id: String, val signalId: String, val target: String, val generation: Long,
    val actions: Set<ImmunityAction>, val evidence: List<String>, val affected: Set<String>, val createdAt: Long,
    val state: ImmunityInterventionState = ImmunityInterventionState.PROPOSED,
    val action: ImmunityAction? = null, val error: String = "", val confirmedAt: Long? = null,
)

@Serializable data class SessionResult(
    val id: String, val sessionId: String, val generation: Long,
    val recipient: String, val summary: String,
    val artifacts: List<String> = emptyList(), val evidence: List<String> = emptyList(),
    val sourceVersion: String = "", val commitSha: String = "", val checks: List<String> = emptyList(),
    val accepted: Boolean = false,
    val integrationId: String? = null,
)

@Serializable data class ImmunitySignal(
    val id: String, val sender: String, val target: String, val diagnostic: String, val createdAt: Long,
    val requestResearch: Boolean = false,
)
@Serializable data class SessionAuditEvent(
    val operationId: String, val actor: String, val action: String,
    val affected: Set<String>, val reason: String, val createdAt: Long,
)
@Serializable data class OrganismOperation(
    val id: String, val fingerprint: String, val target: String,
    val state: SessionOperationState = SessionOperationState.SUCCEEDED,
)

/** One immutable aggregate is the internal transaction boundary. External effects use its outbox. */
@Serializable data class SessionOrganism(
    val id: String,
    val projectId: String,
    val zygoteId: String,
    val immunityId: String? = null,
    val createdAt: Long,
    val limits: OrganismLimits = OrganismLimits(),
    val version: Long = 0,
    val sessions: Map<String, SessionNode> = emptyMap(),
    val operations: Map<String, OrganismOperation> = emptyMap(),
    val audit: List<SessionAuditEvent> = emptyList(),
    val outbox: List<SessionDelivery> = emptyList(),
    val results: List<SessionResult> = emptyList(),
    val signals: List<ImmunitySignal> = emptyList(),
    val waitEdges: Map<String, Set<String>> = emptyMap(),
    val stoppedByUser: Boolean = false,
    val routeGrants: List<SessionRouteGrant> = emptyList(),
    val reviews: List<SessionResultReview> = emptyList(),
    val diagnoses: List<ImmunityDiagnosis> = emptyList(),
    val integrations: Map<String, SessionIntegration> = emptyMap(),
    val auxiliaryRuns: Map<String, SessionAuxiliaryRun> = emptyMap(),
    /** Explicit user deletion hides projections without discarding mandatory audit records. */
    val historyDeletedIds: Set<String> = emptySet(),
    val deletedAt: Long? = null,
    val interventions: List<ImmunityIntervention> = emptyList(),
    /** Version 0 records predate user-configurable limits and contain implicit defaults. */
    val limitPolicyVersion: Int = 0,
) {
    fun subtree(root: String): Set<String> {
        require(root in sessions) { "Сессия не найдена" }
        val result = mutableSetOf(root)
        while (result.addAll(sessions.values.filter { it.lifecycleParentId in result }.map { it.id })) Unit
        return result
    }

    fun route(source: String, target: String): List<String> {
        fun lineage(id: String): List<String> {
            val path = mutableListOf<String>()
            var next: String? = id
            while (next != null) {
                require(next !in path) { "Цикл происхождения" }
                path += next
                next = sessions[next]?.authorityParentId
            }
            return path
        }
        require(source in sessions && target in sessions) { "Сессия не найдена" }
        val from = lineage(source); val to = lineage(target)
        val ancestor = from.firstOrNull { it in to } ?: error("Нет допустимого маршрута")
        return from.takeWhile { it != ancestor } + ancestor + to.takeWhile { it != ancestor }.asReversed()
    }
}

/** Constructed by the host from an authenticated run; never decode this from model text. */
data class SessionAuthority(
    val projectId: String, val organismId: String, val sessionId: String,
    val generation: Long, val mode: CodingInteractionMode, val expectedVersion: Long? = null,
)

/** Proven pre-commit conflict; only the host may retry an omitted expectation. */
class StaleSessionVersion : IllegalArgumentException("Состояние изменилось"), io.aequicor.magicpaper.domain.tools.RejectedToolCall

@Serializable enum class OrganismAction { CREATE, SEND, WAIT, STOP, ARCHIVE, RESTORE, RENAME, SIGNAL, PAUSE, QUARANTINE, ROUTE, REVIEW_RESULT }
@Serializable data class OrganismCommand(
    val action: OrganismAction,
    val target: String = "",
    val name: String = "",
    val reason: String = "",
    val tokens: Long = 0,
    val task: SessionTask? = null,
    val packet: SessionContextPacket? = null,
    val dependencies: Set<String> = emptySet(),
    val failurePolicy: SessionFailurePolicy = SessionFailurePolicy.ISOLATE,
    val source: String = "",
    val resultId: String = "",
    val accepted: Boolean = false,
    val sourceVersion: String = "",
    val checks: List<String> = emptyList(),
    val expectedVersion: Long? = null,
)
