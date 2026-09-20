package io.aequicor.magicpaper.ui

import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.domain.planning.OrchestrationEvent
import io.aequicor.magicpaper.domain.planning.reduce
import kotlin.test.*

class PlanningSessionProjectionTest {
    private val parent = CodingSession("parent", "project", "Planner", 1, planningMode = true)
    private val worker = CodingSession("worker", "project", "Worker", 1,
        parentSessionId = parent.id, planId = "plan", stageId = "stage")
    private val plan = Plan("plan", "project", "Goal", parentSessionId = parent.id)
    private fun snapshot(state: OrchestrationState = OrchestrationState(parent.id, parent.projectId),
        plans: List<Plan> = listOf(plan), drafts: Map<String, CodingDraft> = emptyMap(),
        running: Set<String> = emptySet()) = PlanningSessionSnapshot(CodingPlanningState(mapOf(parent.id to state), plans, drafts), running)

    @Test fun eventReplayProjectsFailureRetryAndCompletionWithoutServices() {
        val events = listOf(
            OrchestrationEvent.InputSubmitted(OrchestrationInput("input", "Task", 1)),
            OrchestrationEvent.InputStatusRecorded("input", OrchestrationInputStatus.FAILED),
            OrchestrationEvent.InputRetried("input"),
            OrchestrationEvent.InputStatusRecorded("input", OrchestrationInputStatus.DONE),
        )
        fun replay(): List<Pair<Boolean, Boolean>> {
            var state = OrchestrationState(parent.id, parent.projectId)
            var ui = CodingSessionUi(parent)
            return events.map { event ->
                state = reduce(state, event).state
                ui = projectPlanningSession(ui, snapshot(state))
                ui.failedRequest to ui.interruptedRequest
            }
        }
        assertEquals(listOf(false to false, true to true, false to false, false to false), replay())
        assertEquals(replay(), replay())
    }

    @Test fun planSelectionHonorsWorkerIdentityAndRejectsAnotherParentsActivePlan() {
        val complete = plan.copy(id = "complete", phase = ExecutionPhase.COMPLETE)
        val foreign = plan.copy(id = "foreign", parentSessionId = "another-parent")
        val state = OrchestrationState(parent.id, parent.projectId, activePlanId = foreign.id)
        val inputs = snapshot(state, listOf(complete, foreign, plan))
        assertSame(plan, projectPlanningSession(CodingSessionUi(parent), inputs).plan)
        assertSame(complete, projectPlanningSession(CodingSessionUi(worker.copy(planId = complete.id)), inputs).plan)
        assertSame(complete, projectPlanningSession(CodingSessionUi(parent),
            inputs.copy(planning = inputs.planning.copy(states = mapOf(parent.id to state.copy(activePlanId = complete.id))))).plan)
    }

    @Test fun questionsAreScopedToTheirPlanAndStageAndClearAfterAnswer() {
        val question = OrchestrationQuestion("q", plan.id, "Choose", listOf(PlanningQuestion("choice", "Choice")),
            worker.id, stageIds = listOf(worker.stageId!!))
        val state = reduce(OrchestrationState(parent.id, parent.projectId), OrchestrationEvent.QuestionRegistered(question)).state
        val inputs = snapshot(state)
        assertTrue(projectPlanningSession(CodingSessionUi(parent), inputs).awaitingUser)
        val waiting = projectPlanningSession(CodingSessionUi(worker), inputs)
        assertTrue(waiting.awaitingUser)
        assertFalse(projectPlanningSession(CodingSessionUi(worker.copy(stageId = "other")), inputs).awaitingUser)
        val answered = state.copy(questions = listOf(question.copy(status = UserRequestStatus.ANSWERED)))
        assertFalse(projectPlanningSession(waiting, snapshot(answered)).awaitingUser)
        val otherPlan = state.copy(questions = listOf(question.copy(planId = "other-plan")))
        assertFalse(projectPlanningSession(waiting, snapshot(otherPlan)).awaitingUser)
    }

    @Test fun planningDraftClearsWhileOrdinaryDraftAndLocalRunSurvive() {
        val draft = CodingDraft(active = true, thinking = "Latest output")
        val ordinary = CodingSessionUi(parent.copy(planningMode = false), draft = draft)
        val inputs = snapshot(plans = emptyList(), running = setOf(parent.id))
        val projected = projectPlanningSession(ordinary, inputs)
        assertSame(draft, projected.draft)
        assertTrue(projected.running)
        val planner = projectPlanningSession(CodingSessionUi(parent, draft = draft), inputs.copy(runningSessions = emptySet()))
        assertFalse(planner.draft.active)
        assertFalse(planner.running)
        assertSame(planner, projectPlanningSession(planner, inputs.copy(runningSessions = emptySet())))
    }

    @Test fun liveDraftAndWorkerCheckpointBothContributeToRunning() {
        val attempt = StageAttempt("attempt", worker.id, StageAssignment("profile", "model"), phase = AttemptPhase.EXECUTING)
        val executing = plan.copy(intent = ExecutionIntent.RUN, confirmedRevision = 1,
            milestones = listOf(Milestone("stage", "Work", status = MilestoneStatus.ACTIVE, attempts = listOf(attempt))))
        assertTrue(projectPlanningSession(CodingSessionUi(worker), snapshot(plans = listOf(executing))).running)
        val draft = CodingDraft(active = true)
        val live = projectPlanningSession(CodingSessionUi(parent), snapshot(drafts = mapOf(parent.id to draft)))
        assertSame(draft, live.draft)
        assertTrue(live.running)
        assertFalse(projectPlanningSession(live, snapshot()).running)
    }

    @Test fun completedHistoryReadPreservesNewDraftAndConcurrentSessionButRemovesDeletedProject() {
        val draft = CodingDraft(active = true, thinking = "Arrived while reading history")
        val latest = CodingSessionUi(parent, draft = draft)
        val added = CodingSessionUi(worker)
        val deleted = CodingSessionUi(parent.copy(id = "deleted", projectId = "deleted-project"))
        val reply = CodingMessage("reply", CodingRole.AGENT, "Saved output", createdAt = 2)
        val stored = parent.copy(name = "Renamed")
        val merged = mergeStoredCodingSessions(listOf(latest, added, deleted), listOf(stored), setOf(parent.projectId),
            mapOf(parent.id to listOf(reply)))
        assertEquals(listOf(parent.id, worker.id), merged.map { it.session.id })
        assertSame(draft, merged.first().draft)
        assertEquals(listOf(reply), merged.first().messages)
        assertEquals(stored, merged.first().session)
        assertSame(added, merged.last())
        val unchanged = mergeStoredCodingSessions(merged, listOf(stored), setOf(parent.projectId), emptyMap())
        assertTrue(merged.indices.all { merged[it] === unchanged[it] })
    }

    @Test fun onePublishedSnapshotFeedsSessionTranscriptAndPlanningCards() {
        val draft = CodingDraft(active = true, thinking = "Live planner output")
        val error = "Unable to save"
        val input = OrchestrationInput("input", "Task", 1)
        val inputs = snapshot(drafts = mapOf(parent.id to draft)).let {
            it.copy(planning = it.planning.copy(sessions = listOf(parent),
                persistenceErrors = mapOf(parent.id to error), unsavedInputs = mapOf(parent.id to listOf(input))))
        }
        val projected = projectCodingPlanning(CodingUi(sessions = listOf(CodingSessionUi(parent))), inputs)
        val transcript = projectPlanningTranscript(projected.sessions.single(), projected)
        assertSame(inputs.planning, projected.planning)
        assertSame(plan, projected.sessions.single().plan)
        assertEquals(draft, transcript.draft)
        assertTrue(transcript.running)
        assertEquals(error, projected.planning.persistenceErrors[parent.id])
        assertEquals(listOf(input), projected.planning.unsavedInputs[parent.id])
        assertSame(projected, projectCodingPlanning(projected, inputs))

        val cleared = projectCodingPlanning(projected, inputs.copy(planning = CodingPlanningState()))
        val idleTranscript = projectPlanningTranscript(cleared.sessions.single(), cleared)
        assertNull(idleTranscript.plan)
        assertFalse(idleTranscript.running)
        assertFalse(idleTranscript.draft.active)
        assertTrue(cleared.planning.persistenceErrors.isEmpty())
    }
}
