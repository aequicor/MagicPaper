package io.aequicor.magicpaper.domain

import io.aequicor.magicpaper.domain.planning.OrchestrationEvent
import io.aequicor.magicpaper.domain.planning.OrchestrationFact
import io.aequicor.magicpaper.domain.planning.OrchestrationIntent
import io.aequicor.magicpaper.domain.planning.OrchestrationMachine
import io.aequicor.magicpaper.domain.planning.OrchestrationSpace
import io.aequicor.magicpaper.domain.planning.pendingAnswerEvents
import io.aequicor.magicpaper.machine.Branch
import io.aequicor.magicpaper.machine.verifyStateSpace
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The representatives of [OrchestrationSpace], kept here rather than in the api so a shipped binary carries
 * no fixtures.
 *
 * Every one is built by running the machine from an empty store. `OrchestrationState` keeps a public
 * constructor, so the empty store is the one thing built directly.
 */
class OrchestrationSpaceTest {
    private val plan = Plan("plan", "project", "Goal", runId = "run", parentSessionId = "parent", intent = ExecutionIntent.RUN)
    private val input = OrchestrationInput("input", "Request", 10)
    private val answerInput = OrchestrationInput("answer-input", "Answer text", 11, answers = listOf(PlanningAnswer("field", text = "answer")))
    private val decision = UserTurnDecision(UserTurnIntent.entries.first(), "reply", replyTo = "question")
    private val pause = OrchestrationPause("plan", listOf("stage"))
    private val question = OrchestrationQuestion("question", "plan", "Text", listOf(PlanningQuestion("field", "Value", QuestionKind.TEXT)), "session")
    private val command = SessionCommand("command", SessionCommandKind.entries.first(), "session", "plan")
    private val messageEvent = MessageEvent("event", "source", "plan", "run", MessageEventKind.entries.first(), 1, "text")
    private fun step(state: OrchestrationState, event: OrchestrationEvent) = OrchestrationMachine.step(state, event).state

    private val idle = OrchestrationState("parent", "project")
    private val queued = step(idle, OrchestrationEvent.InputSubmitted(input))
    private val processing = step(queued, OrchestrationEvent.InputClaimRequested(emptyList()))
    private val asking = step(idle, OrchestrationEvent.QuestionRegistered(question))
    private val answered = step(asking, OrchestrationEvent.QuestionAnswered(plan, answerInput, decision, 5))

    private val representatives = mapOf(
        OrchestrationSpace.RESTORE to OrchestrationEvent.Restore,
        OrchestrationSpace.INPUT_SUBMITTED to OrchestrationEvent.InputSubmitted(input),
        OrchestrationSpace.INPUT_ENQUEUED to OrchestrationEvent.InputEnqueued(input),
        OrchestrationSpace.UNSAVED_INPUTS_RECOVERED to OrchestrationEvent.UnsavedInputsRecovered(listOf(input)),
        OrchestrationSpace.SCHEDULED_RUNS_OBSERVED to OrchestrationEvent.ScheduledRunsObserved(listOf(plan)),
        OrchestrationSpace.INPUT_CLAIM_REQUESTED to OrchestrationEvent.InputClaimRequested(listOf(plan)),
        OrchestrationSpace.INPUT_WITHDRAWN to OrchestrationEvent.InputWithdrawn("input"),
        OrchestrationSpace.INPUT_RETRIED to OrchestrationEvent.InputRetried("input", "clarification", true),
        OrchestrationSpace.INPUT_STATUS_RECORDED to OrchestrationEvent.InputStatusRecorded("input", OrchestrationInputStatus.DONE, "error"),
        OrchestrationSpace.INPUT_DECISION_RECORDED to OrchestrationEvent.InputDecisionRecorded("input", decision),
        OrchestrationSpace.LEGACY_REQUEST_IMPORTED to OrchestrationEvent.LegacyRequestImported(plan),
        OrchestrationSpace.PLAN_SELECTED to OrchestrationEvent.PlanSelected("plan"),
        OrchestrationSpace.WORK_PAUSED to OrchestrationEvent.WorkPaused("plan", pause, true),
        OrchestrationSpace.WORK_PAUSE_NEEDS_USER to OrchestrationEvent.WorkPauseNeedsUser("plan"),
        OrchestrationSpace.WORK_PAUSE_FINISHED to OrchestrationEvent.WorkPauseFinished(plan, "plan"),
        OrchestrationSpace.PROPOSAL_CONFIRMED to OrchestrationEvent.ProposalConfirmed("plan", "proposal"),
        OrchestrationSpace.PLAN_RESUMED to OrchestrationEvent.PlanResumed(plan),
        OrchestrationSpace.QUESTION_REGISTERED to OrchestrationEvent.QuestionRegistered(question),
        OrchestrationSpace.QUESTION_ANSWERED to OrchestrationEvent.QuestionAnswered(plan, answerInput, decision, 5),
        OrchestrationSpace.QUESTION_RESOLVED to OrchestrationEvent.QuestionResolved("question", "pause", pause),
        OrchestrationSpace.REQUIREMENTS_QUEUED to OrchestrationEvent.RequirementsQueued(input, pause),
        OrchestrationSpace.QUESTIONS_IMPORTED to OrchestrationEvent.QuestionsImported(plan, listOf(CodingMessage("message", CodingRole.AGENT, "text", createdAt = 1))),
        // The one event the store still owes: taken from the owed answer itself, so it matches exactly.
        OrchestrationSpace.ANSWER_EVENTS_RECORDED to OrchestrationEvent.AnswerEventsRecorded(answered.pendingAnswerEvents(0).map { it.copy(id = "answer-event") }),
        OrchestrationSpace.STAGE_NUMBERS_REQUESTED to OrchestrationEvent.StageNumbersRequested("plan", listOf("one", "two")),
        OrchestrationSpace.SESSION_COMMAND_REGISTERED to OrchestrationEvent.SessionCommandRegistered(command),
        OrchestrationSpace.SESSION_COMMAND_REJECTED to OrchestrationEvent.SessionCommandRejected("command", "error"),
        OrchestrationSpace.SESSION_COMMAND_APPLIED to OrchestrationEvent.SessionCommandApplied("command"),
        OrchestrationSpace.SESSION_COMMAND_DISCARDED to OrchestrationEvent.SessionCommandDiscarded("command"),
    )

    /** The order that names a store holding several things, which one record per position cannot show. */
    @Test fun theMostDemandingRecordNamesThePositionOfAStoreHoldingSeveral() {
        assertEquals(OrchestrationSpace.ANSWERED, OrchestrationSpace.label(answered))
        // An answer owed outranks a question still open, which outranks a claimed input, which outranks a queued one.
        val answeredAndAsking = step(answered, OrchestrationEvent.QuestionRegistered(question.copy(id = "second")))
        assertEquals(OrchestrationSpace.ANSWERED, OrchestrationSpace.label(answeredAndAsking))
        val askingAndProcessing = step(processing, OrchestrationEvent.QuestionRegistered(question))
        assertEquals(OrchestrationSpace.ASKING, OrchestrationSpace.label(askingAndProcessing))
        val processingAndQueued = step(processing, OrchestrationEvent.InputSubmitted(input.copy(id = "other")))
        assertEquals(OrchestrationSpace.PROCESSING, OrchestrationSpace.label(processingAndQueued))
        // An input that settled, or an answer whose event was recorded, leaves nothing that decides an event.
        val settled = step(processing, OrchestrationEvent.InputStatusRecorded("input", OrchestrationInputStatus.DONE))
        assertEquals(OrchestrationSpace.IDLE, OrchestrationSpace.label(settled))
        val recorded = step(answered, OrchestrationEvent.AnswerEventsRecorded(answered.pendingAnswerEvents(0).map { it.copy(id = "recorded") }))
        assertEquals(OrchestrationSpace.IDLE, OrchestrationSpace.label(recorded))
    }

    /** `unknown(state)` holds for a claimed input wherever the position, which names the most demanding record, is another. */
    @Test fun unknownIsExactlyAClaimedInputWhoseOutcomeIsNotRecorded() {
        val states = mapOf(
            OrchestrationSpace.IDLE to idle, OrchestrationSpace.QUEUED to queued, OrchestrationSpace.PROCESSING to processing,
            OrchestrationSpace.ASKING to asking, OrchestrationSpace.ANSWERED to answered,
        )
        for ((position, state) in states) assertEquals(position == OrchestrationSpace.PROCESSING, OrchestrationSpace.unknown(state), position.name)
        // The position and the predicate part company here, and the KDoc says so.
        val askingAndProcessing = step(processing, OrchestrationEvent.QuestionRegistered(question))
        assertEquals(OrchestrationSpace.ASKING, OrchestrationSpace.label(askingAndProcessing))
        assertTrue(OrchestrationSpace.unknown(askingAndProcessing))
    }

    /** Only the two kinds of failure the reducer raises deliberately are shown as a refusal. */
    @Test fun anEventTheOrchestratorCannotTakeIsARefusalAndLeavesTheStoreAlone() {
        val noQuestion = OrchestrationMachine.step(idle, OrchestrationEvent.QuestionAnswered(plan, answerInput, decision, 5))
        assertTrue(noQuestion.effects.single() is OrchestrationMachine.Effect.Reject)
        assertEquals(idle, noQuestion.state)
        val changedSubmission = OrchestrationMachine.step(queued, OrchestrationEvent.InputSubmitted(input.copy(text = "changed")))
        assertTrue(changedSubmission.effects.single() is OrchestrationMachine.Effect.Reject)
        assertEquals(queued, changedSubmission.state)
    }

    /** The branch the space declares for each of the twenty-eight inputs is the family the event actually belongs to. */
    @Test fun eachInputsDeclaredBranchIsItsFamily() {
        assertEquals(OrchestrationSpace.inputs.map { it.id }.toSet(), representatives.keys)
        for ((id, event) in representatives) {
            assertEquals(if (event is OrchestrationIntent) Branch.INTENT else Branch.FACT, OrchestrationSpace.inputs.single { it.id == id }.branch, id.name)
            assertFalse(event is OrchestrationIntent && event is OrchestrationFact)
        }
        assertEquals(10, representatives.values.count { it is OrchestrationIntent })
        assertEquals(18, representatives.values.count { it is OrchestrationFact })
    }

    @Test fun declaredSpaceIsClosedAndMatchesEveryTransition() = verifyStateSpace(
        OrchestrationMachine,
        states = mapOf(
            OrchestrationSpace.IDLE to idle,
            OrchestrationSpace.QUEUED to queued,
            // Claimed and never settled: a restart runs it again unless someone records how it ended.
            OrchestrationSpace.PROCESSING to processing,
            OrchestrationSpace.ASKING to asking,
            OrchestrationSpace.ANSWERED to answered,
        ),
        inputs = representatives,
    )
}
