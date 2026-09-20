package io.aequicor.magicpaper.domain

import io.aequicor.magicpaper.domain.planning.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.*

class OrchestrationMachineTest {
    private val initial = OrchestrationState("parent", "project")
    private val plan = Plan("plan", "project", "Goal", runId = "run", parentSessionId = "parent", intent = ExecutionIntent.RUN)
    private fun input(id: String) = OrchestrationInput(id, "Request $id", 10)
    private fun OrchestrationState.on(event: OrchestrationEvent) = reduce(this, event)
    private fun OrchestrationState.apply(vararg events: OrchestrationEvent): OrchestrationState =
        events.fold(this) { state, event -> reduce(state, event).state }

    @Test fun replayIsDeterministicAndTheDurableShapeRemainsCompatible() {
        val events = listOf(OrchestrationEvent.InputSubmitted(input("a")), OrchestrationEvent.InputSubmitted(input("b")),
            OrchestrationEvent.InputWithdrawn("b"), OrchestrationEvent.InputClaimRequested(listOf(plan)),
            OrchestrationEvent.InputDecisionRecorded("a", UserTurnDecision(UserTurnIntent.DISCUSS, "reply")),
            OrchestrationEvent.InputStatusRecorded("a", OrchestrationInputStatus.DONE),
            OrchestrationEvent.StageNumbersRequested(plan.id, listOf("one", "two")))
        fun replay() = events.fold(listOf(OrchestrationTransition(initial))) { states, event ->
            states + reduce(states.last().state, event)
        }
        val trace = replay()
        assertEquals(trace, replay())
        val serializer = Json { encodeDefaults = true }
        val encoded = serializer.encodeToString(trace.last().state)
        val decoded = serializer.decodeFromString<OrchestrationState>(encoded)
        assertEquals(encoded, serializer.encodeToString(decoded.on(OrchestrationEvent.Restore).state))
        assertEquals(OrchestrationInputStatus.WITHDRAWN, decoded.inputs.last().status)
    }

    @Test fun replayedSubmissionRetainsItsOriginalPayloadAndProcessingState() {
        val forwarded = input("a").copy(sourceSessionId = "worker", sourceText = "original")
        val state = initial.apply(OrchestrationEvent.InputSubmitted(forwarded), OrchestrationEvent.InputClaimRequested(emptyList()))
        assertEquals(state, state.on(OrchestrationEvent.InputSubmitted(forwarded.copy(text = "renamed worker: original", createdAt = 99))).state)
        assertFailsWith<IllegalArgumentException> {
            state.on(OrchestrationEvent.InputSubmitted(forwarded.copy(sourceText = "changed")))
        }
        assertFailsWith<IllegalArgumentException> {
            state.on(OrchestrationEvent.InputSubmitted(forwarded.copy(answers = listOf(PlanningAnswer("q", text = "new")))))
        }
        assertEquals(1, state.inputs.single().attempt)
    }

    @Test fun withdrawalAndClaimUseTheirEventOrderAndWithdrawalSurvivesEveryRecoveryPath() {
        val queued = initial.apply(OrchestrationEvent.InputSubmitted(input("a")), OrchestrationEvent.InputSubmitted(input("b")))
        val withdrawn = queued.apply(OrchestrationEvent.InputWithdrawn("a"))
        val recovered = withdrawn.apply(OrchestrationEvent.InputRetried("a", "more details", true),
            OrchestrationEvent.UnsavedInputsRecovered(listOf(input("a"))),
            OrchestrationEvent.LegacyRequestImported(plan.copy(requestId = "a", pendingRequest = "legacy")))
        assertEquals(withdrawn, recovered)
        assertEquals("b", recovered.on(OrchestrationEvent.InputClaimRequested(listOf(plan))).claimedInput?.id)
        val claimed = queued.on(OrchestrationEvent.InputClaimRequested(listOf(plan)))
        assertEquals("a", claimed.claimedInput?.id)
        assertEquals(claimed.state, claimed.state.on(OrchestrationEvent.InputWithdrawn("a")).state)
    }

    @Test fun scheduledInputsRequireTheExactRunningPlanAndExpiredRunsNeverBecomeHumanMessages() {
        val scheduled = input("scheduled").copy(scheduledRuleId = "rule", sourcePlanId = plan.id, sourceRunId = plan.runId)
        val state = initial.copy(inputs = listOf(scheduled, input("human")))
        assertEquals("human", state.on(OrchestrationEvent.InputClaimRequested(listOf(plan.copy(intent = ExecutionIntent.STOP)))).claimedInput?.id)
        assertEquals("human", state.on(OrchestrationEvent.InputClaimRequested(listOf(plan.copy(runId = "new")))).claimedInput?.id)
        assertEquals("scheduled", state.on(OrchestrationEvent.InputClaimRequested(listOf(plan))).claimedInput?.id)
        val expired = state.apply(OrchestrationEvent.ScheduledRunsObserved(listOf(plan.copy(runId = "new"))))
        assertEquals(OrchestrationInputStatus.CANCELLED, expired.inputs.first().status)
        val retried = expired.apply(OrchestrationEvent.InputRetried(scheduled.id))
        assertEquals("human", retried.on(OrchestrationEvent.InputClaimRequested(listOf(plan.copy(runId = "new")))).claimedInput?.id)
    }

    @Test fun interruptedProcessingResumesInOrderWithoutLosingTheSavedDecision() {
        val decision = UserTurnDecision(UserTurnIntent.ANSWER, replyTo = "q")
        val state = initial.copy(inputs = listOf(input("a").copy(status = OrchestrationInputStatus.PROCESSING, attempt = 4, decision = decision), input("b")))
        val resumed = state.on(OrchestrationEvent.InputClaimRequested(emptyList())).claimedInput!!
        assertEquals("a", resumed.id)
        assertEquals(5, resumed.attempt)
        assertEquals(decision, resumed.decision)
        val failed = state.apply(OrchestrationEvent.InputStatusRecorded("a", OrchestrationInputStatus.FAILED, "failure"))
        val retried = failed.apply(OrchestrationEvent.InputRetried("a", "correction", clearResumeAfter = true)).inputs.first()
        assertNull(retried.decision)
        assertFalse(retried.resumeAfter)
        assertTrue(retried.text.endsWith("correction"))
    }

    @Test fun partialAnswersStayOpenAndACompleteAnswerHasOneDurableSchedulingEvent() {
        val question = OrchestrationQuestion("q", plan.id, "Questions", listOf(PlanningQuestion("one", "First"), PlanningQuestion("two", "Second")), "parent")
        val state = initial.apply(OrchestrationEvent.QuestionRegistered(question))
        val first = input("first").copy(answers = listOf(PlanningAnswer("one", text = "answer one")), replyTo = "q")
        val decision = UserTurnDecision(UserTurnIntent.ANSWER, replyTo = "q")
        val partial = state.on(OrchestrationEvent.QuestionAnswered(plan, first, decision, 11))
        assertFalse(partial.answer!!.complete)
        assertEquals(UserRequestStatus.OPEN, partial.state.questions.single().status)
        assertTrue(partial.state.pendingAnswerEvents(12).isEmpty())
        val second = input("second").copy(answers = listOf(PlanningAnswer("two", text = "answer two")), replyTo = "q")
        val complete = partial.state.on(OrchestrationEvent.QuestionAnswered(plan, second, decision, 20))
        assertTrue(complete.answer!!.complete)
        assertEquals(2, complete.answer!!.combined.size)
        assertTrue(complete.state.questions.single().resolutionPending)
        val event = complete.state.pendingAnswerEvents(99).single().copy(id = "event")
        assertEquals(20L, event.at)
        assertEquals(plan.runId, event.runId)
        assertEquals("question:q:second", event.sourceKey)
        val recorded = complete.state.apply(OrchestrationEvent.AnswerEventsRecorded(listOf(event)))
        assertTrue(recorded.pendingAnswerEvents(99).isEmpty())
        assertEquals(recorded, recorded.on(OrchestrationEvent.AnswerEventsRecorded(listOf(event))).state)
        assertTrue(recorded.on(OrchestrationEvent.QuestionAnswered(plan, first, decision, 30)).effects.isEmpty())
        assertFailsWith<IllegalArgumentException> { recorded.on(OrchestrationEvent.AnswerEventsRecorded(listOf(event.copy(id = "changed")))) }
    }

    @Test fun refinementAnswersAreExplicitAndCannotTurnSilenceIntoApproval() {
        val question = OrchestrationQuestion("q", plan.id, "Refine?", listOf(PlanningQuestion("choice", "Refine?", QuestionKind.SINGLE,
            listOf(QuestionOption("yes", "Yes"), QuestionOption("no", "No")), allowCustomInput = false, canSkip = false)), "parent", refinementRequest = "change spec")
        val state = initial.apply(OrchestrationEvent.QuestionRegistered(question))
        val decision = UserTurnDecision(UserTurnIntent.ANSWER, replyTo = "q")
        assertFailsWith<IllegalArgumentException> { state.on(OrchestrationEvent.QuestionAnswered(plan, input("a"), decision, 20)) }
        assertFailsWith<IllegalArgumentException> { state.on(OrchestrationEvent.QuestionAnswered(plan,
            input("a").copy(answers = listOf(PlanningAnswer("choice", selected = listOf("invalid")))), decision, 20)) }
        val declined = state.on(OrchestrationEvent.QuestionAnswered(plan,
            input("a").copy(answers = listOf(PlanningAnswer("choice", selected = listOf("no")))), decision, 20))
        assertEquals(false, declined.answer!!.refinePlan)
        assertTrue(declined.answer!!.complete)
    }

    @Test fun failedRefinementPauseNeedsExplicitResumeAndProposalKeepsItsPause() {
        val paused = initial.apply(OrchestrationEvent.WorkPaused("input", OrchestrationPause(plan.id, listOf("one"))),
            OrchestrationEvent.WorkPaused("input", OrchestrationPause(plan.id, listOf("two")), mergeStages = true),
            OrchestrationEvent.WorkPauseNeedsUser("input"))
        assertEquals(listOf("one", "two"), paused.workPauses["input"]!!.stageIds)
        assertEquals(paused, paused.on(OrchestrationEvent.WorkPauseFinished(plan, "input")).state)
        val proposal = PlanProposal("proposal", plan.runId, emptyList(), emptyList(), emptyList(), emptyList(), "spec")
        val awaitingApproval = paused.apply(OrchestrationEvent.WorkPauseFinished(plan.copy(proposal = proposal), "input"))
        assertEquals("proposal", awaitingApproval.workPauses["input"]!!.proposalId)
        assertEquals(awaitingApproval, awaitingApproval.on(OrchestrationEvent.PlanResumed(plan.copy(proposal = proposal))).state)
        assertEquals(awaitingApproval, awaitingApproval.on(OrchestrationEvent.ProposalConfirmed(plan.id, "other")).state)
        assertTrue(awaitingApproval.on(OrchestrationEvent.ProposalConfirmed(plan.id, proposal.id)).state.workPauses.isEmpty())
        assertTrue(paused.on(OrchestrationEvent.PlanResumed(plan)).state.workPauses.isEmpty())
    }

    @Test fun stageNumbersRemainStableAcrossPlanReorderingAndLaterPlans() {
        val state = initial.apply(OrchestrationEvent.StageNumbersRequested("first", listOf("a", "b", "a")),
            OrchestrationEvent.StageNumbersRequested("first", listOf("b", "a", "c")),
            OrchestrationEvent.StageNumbersRequested("second", listOf("a")))
        assertEquals(mapOf("first:a" to 1, "first:b" to 2, "first:c" to 3, "second:a" to 4), state.stageNumbers)
        assertEquals(5, state.nextStageNumber)
    }

    @Test fun repeatedSessionCommandRegistrationCannotEraseItsOutcomeOrReplaceItsArguments() {
        val command = SessionCommand("command", SessionCommandKind.RENAME, "worker", plan.id, name = "first")
        val state = initial.apply(OrchestrationEvent.SessionCommandRegistered(command), OrchestrationEvent.SessionCommandApplied(command.id))
        assertEquals(state, state.on(OrchestrationEvent.SessionCommandRegistered(command)).state)
        assertEquals(state, state.on(OrchestrationEvent.SessionCommandRegistered(command.copy(name = "second"))).state)
        val rejected = initial.apply(OrchestrationEvent.SessionCommandRegistered(command), OrchestrationEvent.SessionCommandRejected(command.id, "denied"))
        assertEquals("denied", rejected.on(OrchestrationEvent.SessionCommandRegistered(command)).state.sessionCommands.single().error)
    }
}
