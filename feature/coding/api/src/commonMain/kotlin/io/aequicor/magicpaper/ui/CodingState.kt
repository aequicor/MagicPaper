package io.aequicor.magicpaper.ui

import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.domain.AppSettings
import io.aequicor.magicpaper.domain.CodingMessage
import io.aequicor.magicpaper.domain.CodingProject
import io.aequicor.magicpaper.domain.CodingSession
import io.aequicor.magicpaper.domain.CodingSessionStatus
import io.aequicor.magicpaper.domain.LlmProfile
import io.aequicor.magicpaper.domain.ProviderType
import io.aequicor.magicpaper.domain.RuntimePhase
import io.aequicor.magicpaper.domain.aggregateCodingStatus
import io.aequicor.magicpaper.domain.ExecutionIntent
import io.aequicor.magicpaper.domain.interruptedCodingRequest

/** Кодинг-сессия в UI: журнал плюс живой прогон и статус для индикатора. */
data class CodingSessionUi(
    val session: CodingSession,
    val messages: List<CodingMessage> = emptyList(),
    val draft: CodingDraft = CodingDraft(),
    val running: Boolean = false,
    val plan: Plan? = null,
    val awaitingUser: Boolean = false,
    val interruptedRequest: Boolean = false,
    val failedRequest: Boolean = false,
    val interactions: List<UserInteractionRequest> = emptyList(),
    val immunityProposalPending: Boolean = false,
) {
    val canResume: Boolean
        get() {
            if (running || interactions.isNotEmpty() || session.archived) return false
            if (interruptedRequest || failedRequest) return messages.pendingPlanningQuestion() == null
            val current = plan
            if (current != null) {
                if (current.stopping) return false
                if (current.confirmedRevision == null || current.phase == ExecutionPhase.COMPLETE || current.proposalReadyForConfirmation ||
                    messages.pendingPlanningQuestion(setOf(current.id)) != null ||
                    current.selectedMilestones.any { it.attempts.lastOrNull()?.waitingForUser != null }) return false
                if (current.intent == ExecutionIntent.RUN && current.issue == null && current.selectedMilestones.any { it.attempts.lastOrNull()?.waitingForEvent != null }) return false
                return current.intent != ExecutionIntent.RUN || current.issue != null ||
                    current.phase == ExecutionPhase.WAITING
            }
            if (session.planningMode || session.stageId != null) return false
            return session.pendingRun?.let { !it.hasSuccessfulResponse(messages) }
                ?: (messages.interruptedCodingRequest() != null)
        }

    /** Вопрос пользователю имеет приоритет; очередь исполнителей не требует ответа. */
    // This is an immutable snapshot. Re-reading a sidebar status must not rescan
    // its entire history on every layout; copy() creates a fresh cache when it changes.
    val status: CodingSessionStatus by lazy { computeStatus() }

    val blockingReason: String? by lazy {
        if (status != CodingSessionStatus.BLOCKED) null
        else when {
            session.observedState == io.aequicor.magicpaper.domain.SessionObservedState.UNKNOWN ->
                "Результат запуска не подтверждён."
            session.desiredState == io.aequicor.magicpaper.domain.SessionDesiredState.QUARANTINE ->
                "Перед продолжением нужно проверить результат запуска."
            else -> draft.failedMessage
                ?: plan?.milestones?.firstOrNull { it.id == session.stageId }?.attempts?.lastOrNull()?.error?.message
                ?: plan?.issue?.message
                ?: "Запрос не удалось завершить."
        }
    }

    private fun computeStatus(): CodingSessionStatus {
        if (session.archived) return CodingSessionStatus.IDLE
        if (immunityProposalPending) return CodingSessionStatus.WAITING
        if (session.observedState == io.aequicor.magicpaper.domain.SessionObservedState.UNKNOWN ||
            session.desiredState == io.aequicor.magicpaper.domain.SessionDesiredState.QUARANTINE) return CodingSessionStatus.BLOCKED
        if (session.observedState == io.aequicor.magicpaper.domain.SessionObservedState.STOPPING) return CodingSessionStatus.WORKING
        if (plan?.stopping == true) return if (plan.issue != null) CodingSessionStatus.BLOCKED else CodingSessionStatus.WORKING
        // The composer and sidebar must use the same actionable queue. Runtime flags
        // and saved attempts can outlive a question while its answer is being handled.
        if (interactions.isNotEmpty()) return CodingSessionStatus.WAITING
        if (draft.failedMessage != null || (failedRequest && !running && !draft.active)) return CodingSessionStatus.BLOCKED
        val stage = plan?.milestones?.firstOrNull { it.id == session.stageId }
        if (stage != null) return when {
            stage.completed -> if (running || draft.active) CodingSessionStatus.WORKING else CodingSessionStatus.IDLE
            stage.attempts.lastOrNull()?.waitingForEvent != null -> CodingSessionStatus.SCHEDULED
            stage.attempts.lastOrNull()?.waitingForUser != null -> CodingSessionStatus.QUEUED
            stage.attempts.lastOrNull()?.let { it.awaitingPlanner && (it.error?.isPlannerAnswerWait != false) } == true -> CodingSessionStatus.QUEUED
            stage.attempts.lastOrNull()?.error?.requiresUser == true -> CodingSessionStatus.BLOCKED
            running || plan.isStageWorking(stage) -> CodingSessionStatus.WORKING
            else -> CodingSessionStatus.QUEUED
        }
        if (plan != null && session.id == plan.parentSessionId) return when {
            running || draft.active -> CodingSessionStatus.WORKING
            plan.pendingRequest.isNotBlank() || plan.milestones.any { plan.isStageWorking(it) } -> CodingSessionStatus.WORKING
            plan.issue?.requiresUser == true || plan.finalAttempt?.error?.requiresUser == true ||
                plan.selectedMilestones.any { !it.completed && it.attempts.lastOrNull()?.error?.requiresUser == true } -> CodingSessionStatus.BLOCKED
            plan.issue != null -> CodingSessionStatus.BLOCKED
            plan.intent == ExecutionIntent.RUN && plan.phase in listOf(ExecutionPhase.RECOVERING, ExecutionPhase.VERIFYING, ExecutionPhase.APPLYING) -> CodingSessionStatus.WORKING
            plan.scheduledMessages.any { it.status == io.aequicor.magicpaper.domain.ScheduledMessageStatus.WAITING } ||
                plan.selectedMilestones.any { !it.completed && it.attempts.lastOrNull()?.waitingForEvent != null } -> CodingSessionStatus.SCHEDULED
            else -> CodingSessionStatus.IDLE
        }
        return when {
            running || draft.active -> CodingSessionStatus.WORKING
            session.stageId != null -> CodingSessionStatus.QUEUED
            else -> CodingSessionStatus.IDLE
        }
    }
}

/** Состояние раздела «Проекты и код»: проект ↔ несколько кодинг-сессий. */
data class CodingUi(
    val organisms: Map<String, io.aequicor.magicpaper.domain.SessionOrganism> = emptyMap(),
    val computerSupported: Boolean = false,
    val computer: io.aequicor.magicpaper.domain.ComputerUseState = io.aequicor.magicpaper.domain.ComputerUseState(),
    val approvals: List<io.aequicor.magicpaper.domain.CodingApproval> = emptyList(),
    val interactions: List<UserInteractionRequest> = emptyList(),
    val projects: List<CodingProject> = emptyList(),
    val current: CodingProject? = null,
    /** Сессии текущего проекта с журналами и живыми прогонами. */
    val sessions: List<CodingSessionUi> = emptyList(),
    val currentSessionId: String? = null,
    /** Сводный кружок проекта (самый срочный статус среди его сессий). */
    val projectStatuses: Map<String, CodingSessionStatus> = emptyMap(),
    val runtime: RuntimeStatus = RuntimeStatus(RuntimePhase.UNKNOWN),
    val installing: Boolean = false,
    val engines: Map<io.aequicor.magicpaper.domain.CodingEngine, RuntimeStatus> = emptyMap(),
    val preparingEngines: Set<io.aequicor.magicpaper.domain.CodingEngine> = emptySet(),
    /** Активная вкладка проекта: диалог с агентом или панель плагина. */
    val sessionMode: CodingSessionMode = CodingSessionMode.DIALOG,
) {
    val currentSession: CodingSessionUi?
        get() = sessions.firstOrNull { it.session.id == currentSessionId } ?: sessions.firstOrNull()

    fun statusOf(projectId: String, fallback: CodingSessionStatus = CodingSessionStatus.IDLE): CodingSessionStatus {
        if (interactions.any { it.projectId == projectId }) return CodingSessionStatus.WAITING
        if (organisms.values.any { it.projectId == projectId && it.deletedAt == null &&
                it.interventions.any { proposal -> proposal.state == io.aequicor.magicpaper.domain.ImmunityInterventionState.PROPOSED } })
            return CodingSessionStatus.WAITING
        val own = sessions.filter { it.session.projectId == projectId && !it.session.archived }
        return if (own.isNotEmpty()) {
            aggregateCodingStatus(own.map { it.status })
        } else {
            projectStatuses[projectId] ?: fallback
        }
    }

    /** Сессии проекта (в состоянии лежат и фоновые сессии других проектов). */
    fun sessionsOf(projectId: String): List<CodingSessionUi> =
        sessions.filter { it.session.projectId == projectId }.sortedByDescending { it.session.createdAt }.map { item ->
            val pending = interactions.filter { it.affects(item.session) }
            val immunityPending = organisms[item.session.organismId]?.let { organism -> organism.immunityId == item.session.id &&
                organism.deletedAt == null && organism.interventions.any { it.state == io.aequicor.magicpaper.domain.ImmunityInterventionState.PROPOSED } } == true
            if (item.interactions == pending && item.immunityProposalPending == immunityPending) item
            else item.copy(interactions = pending, immunityProposalPending = immunityPending)
        }

    /**
     * Активная сессия проекта: выбранная либо первая, если id сбросился
     * (пустое состояние, удаление). Нужна и левому меню (подсветка), и
     * правой части (какой журнал показывать).
     */
    fun activeSessionIdOf(projectId: String): String? {
        return (sessions.firstOrNull { it.session.projectId == projectId && !it.session.archived && it.session.id == currentSessionId }
            ?: sessions.firstOrNull { it.session.projectId == projectId && !it.session.archived })?.session?.id
    }
}

/** Вкладки кодинг-сессии. */
enum class CodingSessionMode {
    /** Диалог с агентом (журнал проекта). */
    DIALOG,

    /** Панель плагина-носителя панели сессии (например, «Планирование»). */
    PLUGIN_PANEL,
}


/** Application-owned coding state; navigation belongs to the root component. */
data class CodingState(
    val coding: CodingUi = CodingUi(),
    val settings: AppSettings = AppSettings(),
    val llmProfiles: List<LlmProfile> = emptyList(),
    val subscriptionAvailable: Boolean = false,
    val subscriptionSignedIn: Boolean = false,
    val notice: String? = null,
) {
    val availableLlmProfiles: List<LlmProfile> get() = llmProfiles.filter {
        it.provider != ProviderType.OPENAI_SUBSCRIPTION || subscriptionAvailable
    }
}
