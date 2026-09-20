package io.aequicor.magicpaper.domain.planning

import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.domain.tools.ToolPhase

/**
 * The machine retains the existing record verbatim. In particular, null coordination flags
 * and legacy merge phases must not be normalized by reading a checkpoint. A later migration
 * can replace redundant fields only after the transition vocabulary has proved their shape.
 */
data class StageState internal constructor(val attempt: StageAttempt)
fun StageAttempt.toState(): StageState = StageState(this)
fun StageState.applyTo(checkpoint: StageAttempt): StageAttempt {
    require(attempt.id == checkpoint.id && attempt.sessionId == checkpoint.sessionId) { "Attempt identity changed" }
    return attempt
}

data class StageRetryInputs(val limit: Int?, val now: Long, val jitter: Long)

sealed interface StageEvent {
    data object InspectPreparation : StageEvent
    data class EngineResolved(val engine: CodingEngine) : StageEvent
    data class WorkspacePrepared(val prepared: StageAttempt) : StageEvent
    data class EngineOutput(val event: CodingEvent, val track: StageRunTrack, val steps: List<CodingStep>) : StageEvent
    data object Inspect : StageEvent
    data class Reconciled(val journalUnsettled: Boolean) : StageEvent
    data class TurnRequested(val coordinatorAvailable: Boolean, val coordinationRecorded: Boolean) : StageEvent
    data class CheckpointObserved(val drift: CheckpointDrift) : StageEvent
    data class WorkerStarting(val prompt: String, val at: Long) : StageEvent
    data class WorkerAdmitted(val admitted: StageAttempt) : StageEvent
    data class WorkerTurnEnded(val at: Long, val submittedReport: String?) : StageEvent
    data class WorkerAccepted(val snapshot: String?, val coordinatorAvailable: Boolean) : StageEvent
    data class PlannerDecided(val decision: StageTurnDecision?, val restored: Boolean = false) : StageEvent
    data object UserAnswered : StageEvent
    data object EventFired : StageEvent
    data class AcceptanceRecorded(val record: AcceptanceRecord) : StageEvent
    data class VerificationDecided(val verdict: Verdict, val retry: StageRetryInputs) : StageEvent
    data class Captured(val commit: String) : StageEvent
    data class MergeStarted(val integrated: Boolean) : StageEvent
    data class ConflictRequested(val path: String, val retryLimit: Int?) : StageEvent
    data object ConflictStarted : StageEvent
    data object ConflictTurnEnded : StageEvent
    data class MergeFinished(val merged: Boolean) : StageEvent
    data object Completed : StageEvent
    data class TransportFailed(val issue: PlanningIssue, val retry: StageRetryInputs, val workerFailed: Boolean = false) : StageEvent
    data class Interrupted(val live: StageAttempt, val waiting: Boolean, val at: Long) : StageEvent
}

/** Effects are descriptions; this file never performs I/O, reads a clock, or launches work. */
sealed interface StageEffect {
    data object ResolveEngine : StageEffect
    data object PrepareWorkspace : StageEffect
    data object Persist : StageEffect
    data object RunWorker : StageEffect
    data object PrepareWorker : StageEffect
    data object Coordinate : StageEffect
    data object RunVerifier : StageEffect
    data object Capture : StageEffect
    data object RunMerge : StageEffect
    data object RunConflictAgent : StageEffect
    data object RunMergeVerifier : StageEffect
    data object AskUser : StageEffect
    data object WaitForEvent : StageEffect
    data object Finish : StageEffect
    data object Yield : StageEffect
    data object RecordVerification : StageEffect
    data class Block(val issue: PlanningIssue) : StageEffect
    data class Delay(val millis: Long) : StageEffect
}

data class StageTransition(val state: StageState, val effects: List<StageEffect> = emptyList()) {
    fun has(effect: StageEffect): Boolean = effect in effects
    val issue: PlanningIssue? get() = effects.filterIsInstance<StageEffect.Block>().singleOrNull()?.issue
}

/** Closed dispatch; helpers below own the individual rules and preserve their checkpoint order. */
fun reduce(state: StageState, event: StageEvent): StageTransition {
    val attempt = state.attempt
    return when (event) {
        StageEvent.InspectPreparation -> StageTransition(state, buildList {
            if (attempt.engine == null) add(StageEffect.ResolveEngine)
            if (attempt.path.isBlank()) add(StageEffect.PrepareWorkspace)
        })
        is StageEvent.EngineResolved -> attempt.copy(engine = event.engine).transition(StageEffect.Persist)
        is StageEvent.WorkspacePrepared -> event.prepared.transition(StageEffect.Persist)
        is StageEvent.EngineOutput -> attempt.after(event.event, event.track).copy(steps = event.steps).transition()
        StageEvent.Inspect -> attempt.transition(when (attempt.phase) {
            AttemptPhase.PREPARED, AttemptPhase.EXECUTING, AttemptPhase.FAILED -> StageEffect.RunWorker
            AttemptPhase.VERIFYING -> StageEffect.RunVerifier
            AttemptPhase.INTEGRATING -> StageEffect.RunMerge
            AttemptPhase.COMPLETE -> StageEffect.Finish
        })
        is StageEvent.Reconciled -> attempt.reconciled(event)
        is StageEvent.TurnRequested -> attempt.requestTurn(event)
        is StageEvent.CheckpointObserved -> when (val drift = event.drift) {
            CheckpointDrift.Gone, CheckpointDrift.TakenOver -> attempt.transition(StageEffect.Yield)
            CheckpointDrift.None -> attempt.transition(StageEffect.PrepareWorker)
            is CheckpointDrift.Advanced -> drift.attempt.transition(
                if (drift.attempt.phase == AttemptPhase.VERIFYING) StageEffect.RunVerifier else StageEffect.Yield)
        }
        is StageEvent.WorkerStarting -> attempt.coordinationSettled().copy(phase = AttemptPhase.EXECUTING,
            error = null, prompt = event.prompt, chatTurns = attempt.effectiveChatTurns() +
                StageChatTurn(attempt.steps.count { it.isVisibleActivity }, event.at, prompt = event.prompt)).transition(StageEffect.Persist)
        is StageEvent.WorkerAdmitted -> attempt.admit(event.admitted)
        is StageEvent.WorkerTurnEnded -> attempt.copy(
            report = if (attempt.report.isBlank()) event.submittedReport ?: attempt.report else attempt.report,
            chatTurns = attempt.chatTurns.dropLast(1) + attempt.chatTurns.last().copy(completedAt = event.at)).transition()
        is StageEvent.WorkerAccepted -> attempt.workerAccepted(event)
        is StageEvent.PlannerDecided -> attempt.plannerDecided(event)
        StageEvent.UserAnswered, StageEvent.EventFired -> attempt.replied().transition(StageEffect.Persist, StageEffect.RunWorker)
        is StageEvent.AcceptanceRecorded -> attempt.copy(acceptanceRecord = event.record).transition(StageEffect.Persist)
        is StageEvent.VerificationDecided -> attempt.verify(event)
        is StageEvent.Captured -> attempt.copy(resultCommit = event.commit, phase = AttemptPhase.INTEGRATING).transition(StageEffect.Persist, StageEffect.RunMerge)
        is StageEvent.MergeStarted -> attempt.transition(if (event.integrated && !attempt.mergeProgress.unresolved) StageEffect.Finish else StageEffect.RunMerge)
        is StageEvent.ConflictRequested -> attempt.requestConflict(event)
        StageEvent.ConflictStarted -> attempt.merging(MergeProgress.Running).transition(StageEffect.Persist)
        StageEvent.ConflictTurnEnded -> attempt.merging(MergeProgress.AwaitingVerdict).transition(StageEffect.Persist, StageEffect.RunMergeVerifier)
        is StageEvent.MergeFinished -> attempt.merging(if (event.merged) MergeProgress.Settled else MergeProgress.Rejected)
            .transition(StageEffect.Persist, if (event.merged) StageEffect.Finish else StageEffect.RunMerge)
        StageEvent.Completed -> attempt.copy(phase = AttemptPhase.COMPLETE, error = null).transition(StageEffect.Persist, StageEffect.Finish)
        is StageEvent.TransportFailed -> (if (event.workerFailed) attempt.copy(phase = AttemptPhase.FAILED) else attempt)
            .failed(event.issue, event.retry).let { it.transition(StageEffect.Persist, StageEffect.Block(it.error!!), StageEffect.Yield) }
        is StageEvent.Interrupted -> attempt.interrupted(event)
    }
}

private fun StageAttempt.transition(vararg effects: StageEffect) = StageTransition(toState(), effects.toList())

private fun StageAttempt.reconciled(event: StageEvent.Reconciled): StageTransition = when (val resume = resumption(event.journalUnsettled)) {
    is StageResumption.UnknownOutcome -> transition(StageEffect.Block(PlanningIssue(IssueKind.UNCERTAIN,
        "Нет подтверждения результата команды: ${resume.tool}. Проверьте её последствия перед повтором.", requiresUser = true)), StageEffect.Yield)
    is StageResumption.Interrupted -> copy(interrupted = false).transition(StageEffect.Persist)
    is StageResumption.Runnable, is StageResumption.Settled -> transition()
}

private fun StageAttempt.requestTurn(event: StageEvent.TurnRequested): StageTransition {
    val review = acceptanceRecord
    val problem = review?.takeIf { repairRetries > 0 && !it.permitsProgress }?.automaticRepairProblem(null)
    if (problem != null) {
        val issue = PlanningIssue(IssueKind.VERIFICATION, problem + "\n\n" + review.summary(), requiresUser = true, retryBlocked = true)
        return copy(error = issue).transition(StageEffect.Persist, StageEffect.Block(issue), StageEffect.Yield)
    }
    return if (event.coordinatorAvailable && (event.coordinationRecorded || coordinationOwed))
        handedToPlanner().transition(StageEffect.Persist, StageEffect.Coordinate)
    else transition(StageEffect.PrepareWorker)
}

private fun StageAttempt.admit(admitted: StageAttempt): StageTransition {
    require(admitted.id == id && admitted.sessionId == sessionId && admitted.turnIndex == turnIndex &&
        admitted.path == path && admitted.assignment == assignment) { "Допуск изменил идентичность задания" }
    return admitted.transition(StageEffect.Persist)
}

private fun StageAttempt.workerAccepted(event: StageEvent.WorkerAccepted): StageTransition {
    val next = (if (event.coordinatorAvailable) handedToPlanner() else this).copy(verificationSnapshot = event.snapshot)
    // Without a coordinator the next verification checkpoint owns the save, as in legacy plans.
    return if (event.coordinatorAvailable) next.transition(StageEffect.Persist) else next.transition()
}

private fun StageAttempt.plannerDecided(event: StageEvent.PlannerDecided): StageTransition {
    val decision = event.decision ?: return copy(phase = AttemptPhase.VERIFYING).transition(StageEffect.Persist, StageEffect.RunVerifier)
    val next = if (event.restored) awaiting(StageWaiting.of(decision.action, decision.requestId))
        .copy(report = decision.report, turnIndex = turnIndex + 1,
            phase = if (decision.action == StageTurnAction.VERIFY) AttemptPhase.VERIFYING else AttemptPhase.EXECUTING, error = null)
    else coordinationSettled().copy(report = decision.report, turnIndex = turnIndex + 1).let {
        if (decision.action == StageTurnAction.VERIFY) it.copy(phase = AttemptPhase.VERIFYING)
        else it.awaiting(StageWaiting.of(decision.action, decision.requestId)).copy(phase = AttemptPhase.EXECUTING, error = null)
    }
    return next.transition(StageEffect.Persist, when (decision.action) {
        StageTurnAction.VERIFY -> StageEffect.RunVerifier
        StageTurnAction.CONTINUE -> StageEffect.RunWorker
        StageTurnAction.WAIT -> StageEffect.AskUser
        StageTurnAction.WAIT_EVENT -> StageEffect.WaitForEvent
    })
}

private fun StageAttempt.failed(issue: PlanningIssue, inputs: StageRetryInputs): StageAttempt {
    val retry = PlanningRetryPolicy.decide(issue, transportRetries, inputs.limit, inputs.now, inputs.jitter)
    return copy(transportRetries = (retry as? RetryDecision.Again)?.retries ?: transportRetries, error = retry.applyTo(issue))
}

private fun StageAttempt.verify(event: StageEvent.VerificationDecided): StageTransition {
    val verdict = event.verdict
    verdict.issue?.let { issue ->
        val next = failed(issue, event.retry)
        return next.transition(StageEffect.Persist, StageEffect.Block(next.error!!), StageEffect.Yield)
    }
    if (verdict.passed) return transition(StageEffect.RecordVerification, StageEffect.Capture)
    val retry = PlanningRetryPolicy.canRetry(repairRetries, event.retry.limit)
    val next = if (retry) copy(phase = AttemptPhase.FAILED, repairRetries = PlanningRetryPolicy.nextRetry(repairRetries),
        error = PlanningIssue(IssueKind.VERIFICATION, verdict.note))
    else copy(error = PlanningIssue(IssueKind.VERIFICATION, verdict.note, requiresUser = true))
    val effects = listOf(StageEffect.RecordVerification, StageEffect.Persist) +
        (if (retry) emptyList() else listOf(StageEffect.Block(next.error!!))) + StageEffect.Yield
    return StageTransition(next.toState(), effects)
}

private fun StageAttempt.requestConflict(event: StageEvent.ConflictRequested): StageTransition {
    val effects = mutableListOf<StageEffect>()
    var next = this
    if (mergeProgress.needsResolver) {
        if (!PlanningRetryPolicy.canRetry(mergeRetries, event.retryLimit)) return transition(StageEffect.Yield)
        if (mergeRetries > 0) effects += StageEffect.Delay(PlanningRetryPolicy.delayMillis(mergeRetries))
        next = copy(mergeRetries = PlanningRetryPolicy.nextRetry(mergeRetries), mergeAssignment = mergeAssignment ?: assignment,
            mergePath = event.path, activity = "Агент разрешает конфликт объединения").merging(MergeProgress.Admitted)
        effects += StageEffect.Persist
    }
    effects += if (next.mergeProgress.needsTurn) StageEffect.RunConflictAgent else StageEffect.RunMergeVerifier
    return StageTransition(next.toState(), effects)
}

private fun StageAttempt.interrupted(event: StageEvent.Interrupted): StageTransition {
    val live = event.live
    if (id != live.id || phase == AttemptPhase.COMPLETE) return transition()
    val latest = if (phase == AttemptPhase.EXECUTING && !awaitingPlanner && turnIndex == live.turnIndex && chatTurns == live.chatTurns)
        copy(report = live.report, steps = live.steps, engineSessionId = live.engineSessionId,
            pendingTool = live.pendingTool, pendingToolExternal = live.pendingToolExternal) else this
    return latest.copy(interrupted = true,
        activity = (if (event.waiting) "Этап приостановлен" else "Выполнение остановлено") + latest.pendingTool.takeIf { it.isNotBlank() }
            ?.let { ". Прервана команда: $it. Перед продолжением проверь её фактические последствия." }.orEmpty(),
        steps = latest.steps.map { step -> if (!step.running) step else step.copy(running = false,
            ok = if (step.kind in setOf(CodingStepKind.TOOL, CodingStepKind.EXEC)) false else step.ok,
            toolPhase = if (step.kind in setOf(CodingStepKind.TOOL, CodingStepKind.EXEC))
                if (latest.pendingToolExternal) ToolPhase.UNKNOWN else ToolPhase.CANCELLED else step.toolPhase,
            systemEvent = step.systemEvent?.copy(phase = CompactionPhase.CANCELLED)) },
        chatTurns = latest.chatTurns.map { if (it.completedAt == 0L) it.copy(completedAt = event.at) else it }).transition(StageEffect.Persist)
}
