package io.aequicor.magicpaper.ui

import io.aequicor.magicpaper.domain.*
import kotlin.test.Test
import kotlin.test.assertEquals

class CodingSessionStatusTest {
    @Test fun readinessBackendActivityAttentionAndQueueRemainDistinct() {
        val idle = CodingSessionUi(CodingSession("s", "p", "Session", 0))
        assertEquals(CodingSessionStatus.IDLE, idle.status)
        assertEquals(CodingSessionStatus.WORKING, idle.copy(draft = CodingDraft(active = true, awaitingModel = true)).status)
        assertEquals(CodingSessionStatus.WORKING, idle.copy(running = true, awaitingUser = true).status)
        assertEquals(CodingSessionStatus.WORKING, idle.copy(draft = CodingDraft(active = true, awaitingApproval = true)).status)
        assertEquals(CodingSessionStatus.BLOCKED, idle.copy(failedRequest = true).status)
        assertEquals(CodingSessionStatus.BLOCKED, idle.copy(draft = CodingDraft(failedMessage = "Ошибка")).status)
        assertEquals(CodingSessionStatus.WORKING, idle.copy(failedRequest = true, running = true).status)
        assertEquals(CodingSessionStatus.QUEUED, idle.copy(session = idle.session.copy(stageId = "stage")).status)
    }

    @Test fun completedStageAndSuccessMessageDoNotClearAnUncertainOrQuarantinedRun() {
        val worker = CodingSession("worker", "project", "Stage", 0,
            parentSessionId = "parent", planId = "plan", stageId = "stage")
        val plan = Plan("plan", worker.projectId, "Goal", parentSessionId = "parent",
            phase = ExecutionPhase.COMPLETE,
            milestones = listOf(Milestone("stage", "Stage", status = MilestoneStatus.DONE)))
        val completed = CodingSessionUi(worker,
            messages = listOf(CodingMessage("result", CodingRole.AGENT, "Работа завершена, проверки прошли.", createdAt = 1)),
            plan = plan, interactions = emptyList())
        assertEquals(CodingSessionStatus.IDLE, completed.status)
        for (session in listOf(
            worker.copy(observedState = SessionObservedState.UNKNOWN),
            worker.copy(observedState = SessionObservedState.COMPLETED, desiredState = SessionDesiredState.QUARANTINE),
        )) {
            assertEquals(CodingSessionStatus.BLOCKED, completed.copy(session = session).status)
        }
    }
}
