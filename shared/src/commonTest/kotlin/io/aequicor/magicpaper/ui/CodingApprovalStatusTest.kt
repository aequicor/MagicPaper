package io.aequicor.magicpaper.ui

import io.aequicor.magicpaper.domain.*
import kotlin.test.*

class CodingApprovalStatusTest {
    @Test fun pendingApprovalTakesPriorityOverRunningSessionAndProject() {
        val session = CodingSession("s", "p", "Session", 0)
        val request = CodingApproval("a", "s", "p", "Session", CodingApprovalKind.COMMAND, "reason", "command")
        val ui = CodingUi(sessions = listOf(CodingSessionUi(session, running = true),
            CodingSessionUi(session.copy(id = "other"), running = true)), approvals = listOf(request), interactions = listOf(approvalInteraction(request, listOf(session))))
        assertEquals(CodingSessionStatus.WAITING, ui.statusOf("p"))
        assertEquals(listOf(CodingSessionStatus.WAITING, CodingSessionStatus.WORKING), ui.sessionsOf("p").map { it.status })
        assertEquals(CodingSessionStatus.WORKING, ui.copy(approvals = emptyList(), interactions = emptyList()).sessionsOf("p").first().status)
        assertEquals(CodingSessionStatus.IDLE, ui.statusOf("unrelated"))
    }

    @Test fun backgroundMergeIsVisibleInWorkerAndProjectStatus() {
        val session = CodingSession("worker", "p", "Worker", 0)
        val request = CodingApproval("a", "worker-merge", "p", "Merge", CodingApprovalKind.FILE_CHANGE, "reason", "diff")
        val ui = CodingUi(sessions = listOf(CodingSessionUi(session)), approvals = listOf(request), interactions = listOf(approvalInteraction(request, listOf(session))))
        assertEquals(CodingSessionStatus.WAITING, ui.sessionsOf("p").single().status)
        assertEquals(CodingSessionStatus.WAITING, ui.copy(sessions = emptyList()).statusOf("p"))
    }
}
