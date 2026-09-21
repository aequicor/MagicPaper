package io.aequicor.magicpaper.domain.planning

import io.aequicor.magicpaper.domain.OrchestrationInputStatus
import io.aequicor.magicpaper.domain.OrchestrationState
import io.aequicor.magicpaper.domain.UserRequestStatus
import io.aequicor.magicpaper.machine.Branch
import io.aequicor.magicpaper.machine.EffectId
import io.aequicor.magicpaper.machine.InputId
import io.aequicor.magicpaper.machine.InputSpec
import io.aequicor.magicpaper.machine.PhaseId
import io.aequicor.magicpaper.machine.StateSpace
import io.aequicor.magicpaper.machine.acceptance

/**
 * The state space of [OrchestrationMachine], declared so it can be read without running anything.
 *
 * The state is a store — inputs, questions, work pauses, session commands, message events — with no phase
 * and no fence: there is no "not loaded" and no unconfirmed persistence, and nearly every event is taken
 * whatever the store holds. What differs between stores is what an event *asks for* and *produces*, which
 * the harness derives from the reducer, and two acceptances that need a record to exist. A position is
 * therefore read from the records that make an event either meaningful or refused:
 *
 *  - [ANSWERED]: a question was answered and the answer's message event was never recorded. It is the
 *    only place `AnswerEventsRecorded` is accepted, because that event must match one the store still owes;
 *  - [ASKING]: a question is open. `QuestionAnswered` is accepted here and at [ANSWERED] — an answered
 *    question takes the same input again — and refused everywhere else, where there is no question to answer;
 *  - [PROCESSING]: an input was claimed and its outcome is not recorded. This is the store's one unresolved
 *    outcome, and what `unknown(state)` reports: a restart claims it again, running its work a second time
 *    unless someone settles it first;
 *  - [QUEUED]: an input waits to be claimed;
 *  - [IDLE]: none of the above. It may hold settled inputs, answered and recorded questions, work pauses and
 *    session commands, none of which decides what an event does.
 *
 * What the declaration cannot express, and leaves to `OrchestrationMachineTest`: identity — an input whose id
 * exists with another payload is refused, a question named by an answer must exist and belong to the plan —
 * and the validity of an answer (an unknown option, a question that cannot be skipped, a custom text where
 * none is allowed); which of several records an event names, since a store holding several is one position,
 * named by the most pressing; the effects of an event, which depend on records the position does not name
 * (a queued input beside an open question still yields a claim, which the position [ASKING] does not show).
 *
 * The events are split into intents and facts by [OrchestrationIntent] and [OrchestrationFact]; the branch
 * declared here for each input is the family it belongs to.
 */
object OrchestrationSpace : StateSpace<OrchestrationState, OrchestrationEvent, OrchestrationMachine.Effect> {
    val IDLE = PhaseId("idle")
    val QUEUED = PhaseId("queued")
    val PROCESSING = PhaseId("processing")
    val ASKING = PhaseId("asking")
    val ANSWERED = PhaseId("answered-unrecorded")

    val RESTORE = InputId("Restore")
    val INPUT_SUBMITTED = InputId("InputSubmitted")
    val INPUT_ENQUEUED = InputId("InputEnqueued")
    val UNSAVED_INPUTS_RECOVERED = InputId("UnsavedInputsRecovered")
    val SCHEDULED_RUNS_OBSERVED = InputId("ScheduledRunsObserved")
    val INPUT_CLAIM_REQUESTED = InputId("InputClaimRequested")
    val INPUT_WITHDRAWN = InputId("InputWithdrawn")
    val INPUT_RETRIED = InputId("InputRetried")
    val INPUT_STATUS_RECORDED = InputId("InputStatusRecorded")
    val INPUT_DECISION_RECORDED = InputId("InputDecisionRecorded")
    val LEGACY_REQUEST_IMPORTED = InputId("LegacyRequestImported")
    val PLAN_SELECTED = InputId("PlanSelected")
    val WORK_PAUSED = InputId("WorkPaused")
    val WORK_PAUSE_NEEDS_USER = InputId("WorkPauseNeedsUser")
    val WORK_PAUSE_FINISHED = InputId("WorkPauseFinished")
    val PROPOSAL_CONFIRMED = InputId("ProposalConfirmed")
    val PLAN_RESUMED = InputId("PlanResumed")
    val QUESTION_REGISTERED = InputId("QuestionRegistered")
    val QUESTION_ANSWERED = InputId("QuestionAnswered")
    val QUESTION_RESOLVED = InputId("QuestionResolved")
    val REQUIREMENTS_QUEUED = InputId("RequirementsQueued")
    val QUESTIONS_IMPORTED = InputId("QuestionsImported")
    val ANSWER_EVENTS_RECORDED = InputId("AnswerEventsRecorded")
    val STAGE_NUMBERS_REQUESTED = InputId("StageNumbersRequested")
    val SESSION_COMMAND_REGISTERED = InputId("SessionCommandRegistered")
    val SESSION_COMMAND_REJECTED = InputId("SessionCommandRejected")
    val SESSION_COMMAND_APPLIED = InputId("SessionCommandApplied")
    val SESSION_COMMAND_DISCARDED = InputId("SessionCommandDiscarded")

    override val phases = listOf(IDLE, QUEUED, PROCESSING, ASKING, ANSWERED)

    override val inputs = listOf(
        InputSpec(RESTORE, Branch.FACT),
        InputSpec(INPUT_SUBMITTED, Branch.INTENT),
        InputSpec(INPUT_ENQUEUED, Branch.FACT),
        InputSpec(UNSAVED_INPUTS_RECOVERED, Branch.FACT),
        InputSpec(SCHEDULED_RUNS_OBSERVED, Branch.FACT),
        InputSpec(INPUT_CLAIM_REQUESTED, Branch.INTENT),
        InputSpec(INPUT_WITHDRAWN, Branch.INTENT),
        InputSpec(INPUT_RETRIED, Branch.INTENT),
        InputSpec(INPUT_STATUS_RECORDED, Branch.FACT),
        InputSpec(INPUT_DECISION_RECORDED, Branch.FACT),
        InputSpec(LEGACY_REQUEST_IMPORTED, Branch.FACT),
        InputSpec(PLAN_SELECTED, Branch.INTENT),
        InputSpec(WORK_PAUSED, Branch.INTENT),
        InputSpec(WORK_PAUSE_NEEDS_USER, Branch.FACT),
        InputSpec(WORK_PAUSE_FINISHED, Branch.FACT),
        InputSpec(PROPOSAL_CONFIRMED, Branch.INTENT),
        InputSpec(PLAN_RESUMED, Branch.FACT),
        InputSpec(QUESTION_REGISTERED, Branch.FACT),
        InputSpec(QUESTION_ANSWERED, Branch.INTENT),
        InputSpec(QUESTION_RESOLVED, Branch.FACT),
        InputSpec(REQUIREMENTS_QUEUED, Branch.FACT),
        InputSpec(QUESTIONS_IMPORTED, Branch.FACT),
        InputSpec(ANSWER_EVENTS_RECORDED, Branch.FACT),
        InputSpec(STAGE_NUMBERS_REQUESTED, Branch.INTENT),
        InputSpec(SESSION_COMMAND_REGISTERED, Branch.INTENT),
        InputSpec(SESSION_COMMAND_REJECTED, Branch.FACT),
        InputSpec(SESSION_COMMAND_APPLIED, Branch.FACT),
        InputSpec(SESSION_COMMAND_DISCARDED, Branch.FACT),
    )

    override val effects = listOf(EffectId("ProcessInput"), EffectId("PublishAnswer"), EffectId("Reject"))

    // Rows follow `phases`. An event is accepted at every position except the two that need a record: a
    // question to answer, and an answered question whose event was never recorded. Written by naming the
    // refusals, so a row reads as what it refuses rather than as twenty-eight flags.
    private fun refusing(vararg refused: InputId) = inputs.map { if (it.id in refused) '0' else '1' }.joinToString("")

    override val accepts = acceptance(phases, inputs, listOf(
        /* idle               */ refusing(QUESTION_ANSWERED, ANSWER_EVENTS_RECORDED),
        /* queued             */ refusing(QUESTION_ANSWERED, ANSWER_EVENTS_RECORDED),
        /* processing         */ refusing(QUESTION_ANSWERED, ANSWER_EVENTS_RECORDED),
        /* asking             */ refusing(ANSWER_EVENTS_RECORDED),
        /* answered-unrecorded */ refusing(),
    ))

    override fun label(state: OrchestrationState): PhaseId = when {
        // What each position accepts, most demanding first: only an owed answer event takes
        // `AnswerEventsRecorded`, only an open question takes a fresh answer, and a claimed input outranks
        // a waiting one because it is the one whose outcome is not yet known.
        state.pendingAnswerEvents(at = 0).isNotEmpty() -> ANSWERED
        state.questions.any { it.status == UserRequestStatus.OPEN } -> ASKING
        state.inputs.any { it.status == OrchestrationInputStatus.PROCESSING } -> PROCESSING
        state.inputs.any { it.status == OrchestrationInputStatus.QUEUED } -> QUEUED
        else -> IDLE
    }

    override fun name(input: OrchestrationEvent): InputId = when (input) {
        OrchestrationEvent.Restore -> RESTORE
        is OrchestrationEvent.InputSubmitted -> INPUT_SUBMITTED
        is OrchestrationEvent.InputEnqueued -> INPUT_ENQUEUED
        is OrchestrationEvent.UnsavedInputsRecovered -> UNSAVED_INPUTS_RECOVERED
        is OrchestrationEvent.ScheduledRunsObserved -> SCHEDULED_RUNS_OBSERVED
        is OrchestrationEvent.InputClaimRequested -> INPUT_CLAIM_REQUESTED
        is OrchestrationEvent.InputWithdrawn -> INPUT_WITHDRAWN
        is OrchestrationEvent.InputRetried -> INPUT_RETRIED
        is OrchestrationEvent.InputStatusRecorded -> INPUT_STATUS_RECORDED
        is OrchestrationEvent.InputDecisionRecorded -> INPUT_DECISION_RECORDED
        is OrchestrationEvent.LegacyRequestImported -> LEGACY_REQUEST_IMPORTED
        is OrchestrationEvent.PlanSelected -> PLAN_SELECTED
        is OrchestrationEvent.WorkPaused -> WORK_PAUSED
        is OrchestrationEvent.WorkPauseNeedsUser -> WORK_PAUSE_NEEDS_USER
        is OrchestrationEvent.WorkPauseFinished -> WORK_PAUSE_FINISHED
        is OrchestrationEvent.ProposalConfirmed -> PROPOSAL_CONFIRMED
        is OrchestrationEvent.PlanResumed -> PLAN_RESUMED
        is OrchestrationEvent.QuestionRegistered -> QUESTION_REGISTERED
        is OrchestrationEvent.QuestionAnswered -> QUESTION_ANSWERED
        is OrchestrationEvent.QuestionResolved -> QUESTION_RESOLVED
        is OrchestrationEvent.RequirementsQueued -> REQUIREMENTS_QUEUED
        is OrchestrationEvent.QuestionsImported -> QUESTIONS_IMPORTED
        is OrchestrationEvent.AnswerEventsRecorded -> ANSWER_EVENTS_RECORDED
        is OrchestrationEvent.StageNumbersRequested -> STAGE_NUMBERS_REQUESTED
        is OrchestrationEvent.SessionCommandRegistered -> SESSION_COMMAND_REGISTERED
        is OrchestrationEvent.SessionCommandRejected -> SESSION_COMMAND_REJECTED
        is OrchestrationEvent.SessionCommandApplied -> SESSION_COMMAND_APPLIED
        is OrchestrationEvent.SessionCommandDiscarded -> SESSION_COMMAND_DISCARDED
    }

    override fun name(effect: OrchestrationMachine.Effect): EffectId = when (effect) {
        is OrchestrationMachine.Effect.Reject -> EffectId("Reject")
        is OrchestrationMachine.Effect.Emit -> when (effect.effect) {
            is OrchestrationEffect.ProcessInput -> EffectId("ProcessInput")
            is OrchestrationEffect.PublishAnswer -> EffectId("PublishAnswer")
        }
    }

    override fun unknown(state: OrchestrationState) = state.inputs.any { it.status == OrchestrationInputStatus.PROCESSING }

    override fun rejected(effect: OrchestrationMachine.Effect) = effect is OrchestrationMachine.Effect.Reject
}
