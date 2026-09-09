package io.aequicor.magicpaper.ui

import io.aequicor.magicpaper.domain.*
import kotlin.test.Test
import kotlin.test.assertEquals

class CodingSessionStatusTest {
    @Test fun readinessBackendActivityAttentionAndQueueRemainDistinct() {
        val idle = CodingSessionUi(CodingSession("s", "p", "Session", 0))
        assertEquals(CodingSessionStatus.IDLE, idle.status)
        assertEquals(CodingSessionStatus.WORKING, idle.copy(draft = CodingDraft(active = true, awaitingModel = true)).status)
        assertEquals(CodingSessionStatus.WAITING, idle.copy(running = true, awaitingUser = true).status)
        assertEquals(CodingSessionStatus.WAITING, idle.copy(draft = CodingDraft(active = true, awaitingApproval = true)).status)
        assertEquals(CodingSessionStatus.BLOCKED, idle.copy(failedRequest = true).status)
        assertEquals(CodingSessionStatus.BLOCKED, idle.copy(draft = CodingDraft(failedMessage = "Ошибка")).status)
        assertEquals(CodingSessionStatus.WORKING, idle.copy(failedRequest = true, running = true).status)
        assertEquals(CodingSessionStatus.QUEUED, idle.copy(session = idle.session.copy(stageId = "stage")).status)
    }
}
