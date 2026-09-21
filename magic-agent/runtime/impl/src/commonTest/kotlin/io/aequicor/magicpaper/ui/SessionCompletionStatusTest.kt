package io.aequicor.magicpaper.ui

import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.data.coding.JsonCodingProjectRepository
import io.aequicor.magicpaper.data.storage.InMemoryKeyValueStore
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.*

class SessionCompletionStatusTest {
    private val session = CodingSession("s", "p", "Task", 1)
    private val reply = CodingMessage("a", CodingRole.AGENT, "Done", createdAt = 2)

    @Test fun unreadThenManualReviewThenReady() {
        val completed = CodingSessionUi(session, listOf(reply), unread = true)
        assertEquals(CodingSessionStatus.UNREAD, completed.status)
        assertEquals(CodingSessionStatus.NEEDS_TESTING, completed.copy(unread = false).status)
        val checked = completed.copy(session = session.copy(manuallyVerifiedResponseId = reply.id), unread = false)
        assertEquals(CodingSessionStatus.IDLE, checked.status)
        assertEquals(CodingSessionStatus.NEEDS_TESTING, checked.copy(messages = listOf(reply, reply.copy(id = "next"))).status)
        assertEquals(CodingSessionStatus.UNREAD, checked.copy(unread = true).status)
    }

    @Test fun runningFailuresAndUnansweredRequestsAreNotCompletedResults() {
        val completed = CodingSessionUi(session, listOf(reply), unread = true)
        assertEquals(CodingSessionStatus.WORKING, completed.copy(running = true).status)
        assertEquals(CodingSessionStatus.BLOCKED, completed.copy(failedRequest = true).status)
        assertNull(completed.copy(messages = listOf(reply.copy(failed = true))).completedResponseId)
        assertNull(completed.copy(messages = listOf(reply, CodingMessage("u", CodingRole.USER, "Next", createdAt = 3))).completedResponseId)
        assertNull(completed.copy(session = session.copy(archived = true)).completedResponseId)
        assertEquals(CodingSessionStatus.IDLE, CodingSessionUi(session).status)
    }

    @Test fun systemNoticesDoNotInvalidateVerificationAndProjectsShowPendingResults() {
        val checked = CodingSessionUi(session.copy(manuallyVerifiedResponseId = reply.id),
            listOf(reply, reply.copy(id = "notice", systemNotice = true)))
        assertTrue(checked.manuallyVerified)
        assertEquals(CodingSessionStatus.IDLE, checked.status)
        assertEquals(CodingSessionStatus.NEEDS_TESTING,
            CodingUi(sessions = listOf(CodingSessionUi(session, listOf(reply)))).statusOf("p"))
        assertEquals(CodingSessionStatus.WORKING,
            aggregateCodingStatus(listOf(CodingSessionStatus.WORKING, CodingSessionStatus.UNREAD)))
    }

    @Test fun verificationSurvivesRepositoryReopen() = runTest {
        val storage = InMemoryKeyValueStore()
        val repo = JsonCodingProjectRepository(storage, Json)
        repo.save(CodingProject("p", "Project", "/fixture", 1))
        repo.saveSession(session)
        repo.saveMessages("p", "s", listOf(reply))
        repo.updateSession("p", "s") { it.copy(manuallyVerifiedResponseId = reply.id) }
        val reopened = JsonCodingProjectRepository(storage, Json)
        val restored = CodingSessionUi(reopened.sessions("p").single(), reopened.messages("p", "s"))
        assertTrue(restored.manuallyVerified)
        assertEquals(CodingSessionStatus.IDLE, restored.status)
    }
}
