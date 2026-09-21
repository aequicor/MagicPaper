package io.aequicor.magicpaper.domain.planning

import io.aequicor.magicpaper.domain.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Orchestration owns these Plan writes; persistence still owns revision allocation and CAS. */
@Serializable sealed interface PlanEvent

@Serializable sealed interface PlanRevisionEvent : PlanEvent {
    @Serializable @SerialName("io.aequicor.magicpaper.domain.planning.PlanRevisionEvent.RequestStarted") data class RequestStarted(val id: String, val text: String, val nodeId: String?) : PlanRevisionEvent
    @Serializable @SerialName("io.aequicor.magicpaper.domain.planning.PlanRevisionEvent.RequestCleared") data class RequestCleared(val requestId: String) : PlanRevisionEvent
    @Serializable @SerialName("io.aequicor.magicpaper.domain.planning.PlanRevisionEvent.DialogueAppended") data class DialogueAppended(val messages: List<PlanningMessage>) : PlanRevisionEvent
    @Serializable @SerialName("io.aequicor.magicpaper.domain.planning.PlanRevisionEvent.ProposalDeclined") data class ProposalDeclined(val expectedRevision: Long, val proposalId: String) : PlanRevisionEvent
    @Serializable @SerialName("io.aequicor.magicpaper.domain.planning.PlanRevisionEvent.ProposalPrepared") data class ProposalPrepared(val base: Plan, val effective: Plan, val result: Plan, val assistant: PlanningMessage) : PlanRevisionEvent
    @Serializable @SerialName("io.aequicor.magicpaper.domain.planning.PlanRevisionEvent.ProposalApplied") data class ProposalApplied(val base: Plan, val result: Plan) : PlanRevisionEvent
    @Serializable @SerialName("io.aequicor.magicpaper.domain.planning.PlanRevisionEvent.RefinementFinished") data class RefinementFinished(val base: Plan, val result: Plan, val selection: ModelSelection?, val search: SearchProvider, val at: Long) : PlanRevisionEvent
    @Serializable @SerialName("io.aequicor.magicpaper.domain.planning.PlanRevisionEvent.ProposalApproved") data class ProposalApproved(val proposal: PlanProposal, val expectedRevision: Long?, val openQuestions: Boolean,
        val pausedStages: Set<String>, val newRunId: String, val worktreeEnabled: Boolean, val at: Long) : PlanRevisionEvent
    @Serializable @SerialName("io.aequicor.magicpaper.domain.planning.PlanRevisionEvent.InitialConfirmed") data class InitialConfirmed(val expectedRevision: Long?, val openQuestions: Boolean, val newRunId: String, val at: Long) : PlanRevisionEvent
    @Serializable @SerialName("io.aequicor.magicpaper.domain.planning.PlanRevisionEvent.AssignmentsRecovered") data class AssignmentsRecovered(val base: Plan, val candidate: Plan) : PlanRevisionEvent
    @Serializable @SerialName("io.aequicor.magicpaper.domain.planning.PlanRevisionEvent.StageNumbersBound") data class StageNumbersBound(val numbers: Map<String, Int>) : PlanRevisionEvent
    @Serializable @SerialName("io.aequicor.magicpaper.domain.planning.PlanRevisionEvent.StageRenamed") data class StageRenamed(val id: String?, val name: String) : PlanRevisionEvent
    @Serializable @SerialName("io.aequicor.magicpaper.domain.planning.PlanRevisionEvent.LegacyLinked") data class LegacyLinked(val parent: String, val at: Long) : PlanRevisionEvent
    @Serializable @SerialName("io.aequicor.magicpaper.domain.planning.PlanRevisionEvent.LegacyQuestionsObserved") data class LegacyQuestionsObserved(val questions: List<OrchestrationQuestion>) : PlanRevisionEvent
    @Serializable @SerialName("io.aequicor.magicpaper.domain.planning.PlanRevisionEvent.LegacyPeersObserved") data class LegacyPeersObserved(val peers: List<Plan>, val admitted: Map<String, SessionLegacyAttempt>) : PlanRevisionEvent
    @Serializable @SerialName("io.aequicor.magicpaper.domain.planning.PlanRevisionEvent.EngineRestored") data class EngineRestored(val engine: CodingEngine) : PlanRevisionEvent
    @Serializable @SerialName("io.aequicor.magicpaper.domain.planning.PlanRevisionEvent.QuestionDelivered") data class QuestionDelivered(val stages: List<String>) : PlanRevisionEvent
}

fun reduce(plan: Plan, event: PlanEvent): Plan = when (event) {
    is CoordinationEvent -> reduce(plan, event)
    is PlanRevisionEvent -> reduce(plan, event)
}

fun reduce(plan: Plan, event: PlanRevisionEvent): Plan = when (event) {
    is PlanRevisionEvent.RequestStarted -> plan.copy(pendingRequest = event.text, requestId = event.id,
        pendingRecalculationNodeId = event.nodeId ?: if (plan.requestId == event.id) plan.pendingRecalculationNodeId else null,
        dialogue = if (plan.dialogue.any { it.id == event.id }) plan.dialogue else plan.dialogue + PlanningMessage(event.id, "user", event.text))
    is PlanRevisionEvent.RequestCleared -> if (plan.requestId == event.requestId) plan.copy(pendingRequest = "", requestId = "", pendingRecalculationNodeId = null) else plan
    is PlanRevisionEvent.DialogueAppended -> plan.copy(dialogue = plan.dialogue + event.messages)
    is PlanRevisionEvent.ProposalDeclined -> {
        plan.requireRevision(event.expectedRevision)
        require(plan.proposal?.id == event.proposalId) { "Предложение изменилось. Проверьте актуальную версию." }
        plan.copy(proposal = null)
    }
    is PlanRevisionEvent.ProposalPrepared -> plan.prepareProposal(event)
    is PlanRevisionEvent.ProposalApplied -> plan.applyPreparedProposal(event.base, event.result)
    is PlanRevisionEvent.RefinementFinished -> plan.copy(pendingRequest = "", requestId = "", pendingRecalculationNodeId = null,
        plannerSelection = event.selection, searchProvider = event.search,
        versions = if (event.result.tree != event.base.tree || event.result.milestones != event.base.milestones)
            plan.versions + PlanVersion(event.base.revision, event.base.tree, event.base.milestones.map { it.copy(attempts = emptyList()) }, event.at) else plan.versions)
    is PlanRevisionEvent.ProposalApproved -> plan.approveProposal(event)
    is PlanRevisionEvent.InitialConfirmed -> plan.confirmInitial(event)
    is PlanRevisionEvent.AssignmentsRecovered -> plan.mergeRecoveredAssignments(event.base, event.candidate)
    is PlanRevisionEvent.StageNumbersBound -> plan.bindStageNumbers(event.numbers)
    is PlanRevisionEvent.StageRenamed -> plan.copy(milestones = plan.milestones.map { if (it.id == event.id) it.copy(displayName = event.name) else it })
    is PlanRevisionEvent.LegacyLinked -> plan.copy(parentSessionId = event.parent,
        confirmedRevision = if (plan.intent != ExecutionIntent.STOP || plan.milestones.any { it.attempts.isNotEmpty() }) plan.revision else null,
        versions = if (plan.versions.isEmpty()) listOf(PlanVersion(plan.revision, plan.tree, plan.milestones.map { it.copy(attempts = emptyList()) }, event.at)) else plan.versions)
    is PlanRevisionEvent.LegacyQuestionsObserved -> plan.restoreQuestionWaits(event.questions)
    is PlanRevisionEvent.LegacyPeersObserved -> plan.cancelLegacyPeerCommands(event.peers, event.admitted)
    is PlanRevisionEvent.EngineRestored -> if (plan.engine == null) plan.copy(engine = event.engine) else plan
    is PlanRevisionEvent.QuestionDelivered -> plan.copy(issue = plan.issue?.takeUnless { it.isPlannerAnswerWait },
        milestones = plan.milestones.map { m -> if (m.id !in event.stages) m else m.copy(attempts = m.attempts.map {
            it.copy(waitingForUser = null, error = it.error?.takeUnless { issue -> issue.isPlannerAnswerWait })
        }) })
}

private fun Plan.prepareProposal(event: PlanRevisionEvent.ProposalPrepared): Plan {
    require(tree == event.base.tree && milestones.specification() == event.base.milestones.specification()) {
        "План изменился во время подготовки предложения. Повторите запрос."
    }
    return copy(dialogue = event.base.dialogue + event.assistant,
        proposal = if (event.result.tree == event.effective.tree && event.result.milestones == event.effective.milestones && event.base.proposal == null) null
        else PlanProposal(event.base.requestId, runId, tree, milestones, event.result.tree, event.result.milestones, event.assistant.text))
}

/** Rebase model output over telemetry, never over intervening edits or newly started work. */
private fun Plan.applyPreparedProposal(base: Plan, result: Plan): Plan {
    require(tree == base.tree && goal == base.goal && priorities == base.priorities && dialogue == base.dialogue) {
        "Дерево изменилось во время ответа оркестратора; повторите запрос"
    }
    require(milestones.specification() == base.milestones.specification()) { "Этапы изменились во время ответа оркестратора" }
    val rebased = copy(sharedWorkspace = worktreeEnabled?.not() ?: if (confirmedRevision == null) result.sharedWorkspace else sharedWorkspace,
        tree = result.tree, dialogue = result.dialogue, wizardStep = result.wizardStep, milestones = result.milestones.map { proposed ->
            milestones.firstOrNull { it.id == proposed.id }?.takeIf { it.attempts.isNotEmpty() || it.status != MilestoneStatus.PENDING }?.let { current ->
                require(listOf(current).specification() == listOf(proposed).specification()) { "Этап «${current.title}» начался во время планирования" }
                current
            } ?: proposed
        })
    return validatePlanRevision(this, rebased)
}

fun validatePlanRevision(old: Plan, updated: Plan): Plan {
    DecisionCompiler.validateEdit(old, updated)
    val previous = old.finalAttempt ?: return updated
    if (updated.tree == old.tree && updated.milestones == old.milestones) return updated
    require(old.canExtendAfterFinalVerification) { "Итоговая проверка уже начата. Дождитесь её завершения перед изменением плана." }
    if (updated.selectedMilestones.all { it.completed }) return updated
    return updated.copy(finalAttempt = null, finalAttemptHistory = old.finalAttemptHistory + previous,
        issue = null, phase = ExecutionPhase.RECOVERING,
        status = if (updated.intent == ExecutionIntent.RUN) PlanStatus.RUNNING else PlanStatus.STOPPED)
}

private fun Plan.requireRevision(expected: Long?) = require(expected == null || revision == expected) { "План изменился. Проверьте новую редакцию." }

/** Repeat approval guards at the committed checkpoint, not just before profile/session I/O. */
private fun Plan.approveProposal(event: PlanRevisionEvent.ProposalApproved): Plan {
    requireRevision(event.expectedRevision)
    val approved = event.proposal
    require(proposal == approved) { "Предложение изменилось. Проверьте актуальную версию." }
    require(proposalReadyForConfirmation) { "Предложение сохранено. Дождитесь завершения текущей проверки и переноса результата." }
    require(!event.openQuestions) { "Сначала ответьте на уточнения" }
    require(runId == approved.baseRunId && tree == approved.baseTree && milestones.specification() == approved.baseMilestones.specification()) {
        "Основа предложения изменилась. Подготовьте его заново."
    }
    val reconciled = reconcileApprovedProposal(approved)
    require(reconciled.selectedMilestones.any { !it.completed }) { "В предложении нет новых этапов" }
    val completedRun = phase == ExecutionPhase.COMPLETE
    return reconciled.copy(revision = revision,
        runHistory = runHistory + PlanRunSnapshot(runId, tree, milestones, workspace, finalAttempt, event.at),
        deliveries = (deliveries + event.pausedStages.map { stageId -> PlanDelivery("${approved.id}-approved-$stageId", parentSessionId, stageId,
            "Подтверждённые уточнения плана:\n${approved.explanation}",
            state = if (milestones.firstOrNull { it.id == stageId }?.completed == true) DeliveryState.ANSWERED else DeliveryState.QUEUED) }).distinctBy { it.id },
        proposal = null, runId = if (completedRun) event.newRunId else runId,
        worktreeEnabled = if (completedRun) event.worktreeEnabled else worktreeEnabled, workspace = if (completedRun) null else workspace,
        finalAttemptHistory = finalAttemptHistory + listOfNotNull(finalAttempt), finalAttempt = null,
        phase = ExecutionPhase.RECOVERING, intent = ExecutionIntent.RUN, issue = null, transportRetries = 0,
        status = PlanStatus.RUNNING, confirmedRevision = revision, wizardStep = PlanningStep.STATUS)
}

private fun Plan.confirmInitial(event: PlanRevisionEvent.InitialConfirmed): Plan {
    requireRevision(event.expectedRevision)
    require(proposal == null) { "Подтвердите актуальное предложение доработки" }
    if (confirmedRevision != null) return this
    require(wizardStep != PlanningStep.CLARIFY && selectedMilestones.isNotEmpty() && DecisionCompiler.compile(this).valid && !event.openQuestions) { "План ещё не готов" }
    return copy(confirmedRevision = revision, intent = ExecutionIntent.RUN, phase = ExecutionPhase.RECOVERING,
        status = PlanStatus.RUNNING, runId = runId.ifBlank { event.newRunId },
        versions = versions + PlanVersion(revision, tree, milestones.map { it.copy(attempts = emptyList()) }, event.at))
}

/** Recovery may change missing assignments only while their original bindings still own the checkpoint. */
private fun Plan.mergeRecoveredAssignments(base: Plan, next: Plan): Plan {
    if (runId != base.runId) return this
    fun StageAttempt.recover(original: StageAttempt?, candidate: StageAttempt?): StageAttempt =
        if (original == null || candidate == null || phase == AttemptPhase.COMPLETE ||
            sessionId != original.sessionId || sessionGeneration != original.sessionGeneration ||
            assignment != original.assignment || mergeAssignment != original.mergeAssignment) this
        else copy(assignment = candidate.assignment, mergeAssignment = candidate.mergeAssignment)
    return copy(plannerSelection = if (plannerSelection == base.plannerSelection) next.plannerSelection else plannerSelection,
        milestones = milestones.map { stage ->
            val original = base.milestones.firstOrNull { it.id == stage.id } ?: return@map stage
            val recovered = next.milestones.firstOrNull { it.id == stage.id } ?: return@map stage
            if (stage.completed) stage else stage.copy(
                assignment = if (stage.assignment == original.assignment) recovered.assignment else stage.assignment,
                attempts = stage.attempts.map { attempt -> attempt.recover(
                    original.attempts.firstOrNull { it.id == attempt.id }, recovered.attempts.firstOrNull { it.id == attempt.id }) })
        }, finalAttempt = finalAttempt?.let { attempt -> attempt.recover(
            base.finalAttempt?.takeIf { it.id == attempt.id }, next.finalAttempt?.takeIf { it.id == attempt.id }) })
}

fun Plan.bindStageNumbers(numbers: Map<String, Int>): Plan {
    fun bind(stages: List<Milestone>) = stages.map { it.copy(displayNumber = numbers["$id:${it.id}"] ?: it.displayNumber) }
    return copy(milestones = bind(milestones), proposal = proposal?.let { it.copy(milestones = bind(it.milestones)) })
}

private fun Plan.restoreQuestionWaits(open: List<OrchestrationQuestion>): Plan = copy(
    issue = issue?.takeUnless { it.isPlannerAnswerWait && open.isNotEmpty() },
    milestones = milestones.map { stage -> stage.copy(attempts = stage.attempts.map { attempt ->
        val request = open.firstOrNull { it.stageIds.isEmpty() || stage.id in it.stageIds }
        if (attempt.error?.isPlannerAnswerWait == true && request != null) attempt.copy(waitingForUser = request.id, error = null) else attempt
    }) })

data class RefinementPreparation(val plan: Plan, val needsApproval: Boolean)
fun Plan.prepareRefinement(requireApproval: Boolean, selection: ModelSelection?, search: SearchProvider, selectedEngine: CodingEngine?): RefinementPreparation {
    val continuation = (requireApproval && confirmedRevision != null) || phase == ExecutionPhase.COMPLETE || proposal != null ||
        (finalAttempt != null && !canExtendAfterFinalVerification)
    val effective = copy(plannerSelection = selection, searchProvider = search, engine = selectedEngine ?: engine,
        tree = proposal?.tree ?: tree, milestones = proposal?.milestones ?: milestones,
        finalAttempt = if (continuation) null else finalAttempt,
        finalAttemptHistory = finalAttemptHistory + if (continuation) listOfNotNull(finalAttempt) else emptyList())
    return RefinementPreparation(if (continuation) effective.refinementView() else effective, continuation)
}

/**
 * Profile resolution is validation of supplied values; no provider or repository is consulted here.
 * [catalog] is the last known snapshot of the engine's own model catalog: a native assignment whose
 * model left it is replaced only by the orchestrator session's own native choice that the catalog
 * still offers. Otherwise it stays as it is and admission refuses it by name (reassign the stage).
 */
fun Plan.recoveredAssignments(roster: List<LlmProfile>, sessions: List<CodingSession>, catalog: CodingModelSnapshot? = null): Plan {
    val parent = sessions.firstOrNull { it.id == parentSessionId }
    val selected = parent?.modelSelection?.let { ProfileResolver.selection(it, roster) }
    val replacement = selected?.takeIf { it.configured && it.supportsCoding }
    fun recover(assignment: StageAssignment, stage: Milestone? = null): StageAssignment {
        if (runCatching { assignment.executionProfile(roster, catalog) }.isSuccess) return assignment
        assignment.native?.let { return recoverNative(assignment, it, parent, roster, catalog) }
        val workerChoice = stage?.let { m -> sessions.firstOrNull { it.planId == id && it.stageId == m.id }?.modelSelection }
        val workerProfile = workerChoice?.let { ProfileResolver.selection(it, roster) }?.takeIf { it.configured && it.supportsCoding }
        val candidate = when {
            workerProfile != null -> assignment.copy(profileId = workerProfile.id, modelId = workerChoice.modelId,
                effort = workerChoice.effort, effectiveEffort = EffortSelection.Default, options = null)
            replacement != null -> assignment.copy(profileId = replacement.id)
            else -> return assignment
        }
        return candidate.takeIf { runCatching { it.executionProfile(roster, catalog) }.isSuccess } ?: assignment
    }
    return copy(plannerSelection = parent?.modelSelection?.takeIf { selected != null } ?: plannerSelection,
        milestones = milestones.map { stage -> if (stage.completed) stage else stage.copy(
            assignment = stage.assignment?.let { recover(it, stage) },
            attempts = stage.attempts.map { attempt -> if (attempt.phase == AttemptPhase.COMPLETE) attempt else attempt.copy(
                assignment = recover(attempt.assignment, stage), mergeAssignment = attempt.mergeAssignment?.let { recover(it, stage) }) }) },
        finalAttempt = finalAttempt?.takeIf { it.phase != AttemptPhase.COMPLETE }?.let { it.copy(
            assignment = recover(it.assignment), mergeAssignment = it.mergeAssignment?.let(::recover)) } ?: finalAttempt)
}

private fun recoverNative(assignment: StageAssignment, native: CodingModelSelection, parent: CodingSession?,
    roster: List<LlmProfile>, catalog: CodingModelSnapshot?): StageAssignment {
    val choice = parent?.codingModel?.takeIf { it.engine == native.engine && catalog?.resolve(it) is CodingModelResolution.Available }
    val model = choice?.let { catalog?.find(it.provider, it.modelId) } ?: return assignment
    val connection = roster.firstOrNull { it.id == assignment.profileId && it.isNativeConnectionFor(native.engine) }
        ?: roster.firstOrNull { it.isNativeConnectionFor(native.engine) } ?: return assignment
    return nativeStageAssignment(connection, choice.engine, model, choice.level, assignment.explanation, assignment.manual)
}
