package io.aequicor.magicpaper.ui

import io.aequicor.magicpaper.domain.*

/** One observation of the application owners, with no live flows or jobs in the projection. */
internal data class PlanningSessionSnapshot(
    val planning: CodingPlanningState,
    val runningSessions: Set<String>,
)

private val emptyPlanningDraft = CodingDraft()

internal fun projectCodingPlanning(current: CodingUi, snapshot: PlanningSessionSnapshot): CodingUi {
    val sessions = current.sessions.map { projectPlanningSession(it, snapshot) }
    val planning = snapshot.planning
    if (current.planning == planning && sessions.indices.all { sessions[it] === current.sessions[it] }) return current
    return current.copy(sessions = sessions, planning = planning)
}

/** The transcript and its plan cards consume the same snapshot as the sidebar. */
internal fun projectPlanningTranscript(active: CodingSessionUi, current: CodingUi): CodingSessionUi {
    val planning = current.planning
    val plan = planning.plans.firstOrNull { it.id == active.session.planId } ?: active.plan
    val parentMessages = current.sessions.firstOrNull { it.session.id == active.session.parentSessionId }?.messages.orEmpty()
    val stageChat = active.withStageChat(plan, planning.liveAttempts, parentMessages)
    val draft = planning.drafts[active.session.id] ?: stageChat.draft
    return stageChat.copy(draft = draft.copy(awaitingApproval = active.draft.awaitingApproval),
        running = stageChat.running || draft.active)
}

internal fun projectPlanningSession(item: CodingSessionUi, snapshot: PlanningSessionSnapshot): CodingSessionUi {
    val session = item.session
    val planning = snapshot.planning
    val activePlanId = planning.states[session.id]?.activePlanId
    val plan = planning.plans.firstOrNull { it.id == session.planId }
        ?: planning.plans.firstOrNull { it.id == activePlanId && it.parentSessionId == session.id }
        ?: planning.plans.filter { it.parentSessionId == session.id }
            .let { plans -> plans.firstOrNull { it.phase != ExecutionPhase.COMPLETE } ?: plans.lastOrNull() }
    val workerRunning = plan?.milestones?.firstOrNull { it.id == session.stageId }?.let { plan.isStageWorking(it) } == true
    val inputStatus = planning.states[session.id]?.inputs?.lastOrNull()?.status
    val failedRequest = inputStatus == OrchestrationInputStatus.FAILED
    val interruptedRequest = inputStatus == OrchestrationInputStatus.CANCELLED || failedRequest
    val awaitingUser = planning.states[session.parentSessionId ?: session.id]?.openQuestions(plan?.id).orEmpty().any {
        session.stageId == null || it.stageIds.isEmpty() || session.stageId in it.stageIds
    }
    val draft = planning.drafts[session.id] ?: if (plan != null || session.planningMode) emptyPlanningDraft else item.draft
    val running = workerRunning || planning.drafts[session.id]?.active == true || session.id in snapshot.runningSessions
    if (item.plan === plan && item.draft == draft && item.interruptedRequest == interruptedRequest &&
        item.failedRequest == failedRequest && item.awaitingUser == awaitingUser && item.running == running) return item
    return item.copy(plan = plan, interruptedRequest = interruptedRequest, failedRequest = failedRequest,
        awaitingUser = awaitingUser, draft = draft, running = running)
}

/** Apply a completed repository read to the latest UI state, preserving concurrent drafts/creation. */
internal fun mergeStoredCodingSessions(
    current: List<CodingSessionUi>,
    stored: List<CodingSession>,
    projectIds: Set<String>,
    histories: Map<String, List<CodingMessage>>,
): List<CodingSessionUi> {
    val currentById = current.associateBy { it.session.id }
    val storedIds = stored.map { it.id }.toSet()
    return stored.map { session ->
        val previous = currentById[session.id] ?: CodingSessionUi(session)
        val messages = histories[session.id] ?: previous.messages
        if (previous.session == session && previous.messages == messages) previous
        else previous.copy(session = session, messages = messages)
    } + current.filter { it.session.id !in storedIds && it.session.projectId in projectIds }
}
