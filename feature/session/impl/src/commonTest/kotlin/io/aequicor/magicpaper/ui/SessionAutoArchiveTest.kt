package io.aequicor.magicpaper.ui

import io.aequicor.magicpaper.domain.*
import kotlin.test.*

class SessionAutoArchiveTest {
    @Test fun readyAppearanceDoesNotArchivePendingQueuedOrUncertainWork() {
        val idle = CodingSessionUi(CodingSession("s", "p", "Task", 1))
        assertTrue(idle.readyForArchive)
        val request = CodingRunCheckpoint("r", "Input")
        val guarded = listOf(
            idle.copy(running = true), idle.copy(interruptedRequest = true), idle.copy(failedRequest = true),
            idle.copy(session = idle.session.copy(pendingRun = request)),
            idle.copy(session = idle.session.copy(queuedPrompts = listOf(request))),
            idle.copy(session = idle.session.copy(observedState = SessionObservedState.UNKNOWN)),
            idle.copy(session = idle.session.copy(observedState = SessionObservedState.STOPPING)),
            idle.copy(session = idle.session.copy(archived = true)),
            idle.copy(session = idle.session.copy(sessionKind = SessionKind.IMMUNITY)),
        )
        assertTrue(guarded.none { it.readyForArchive })
    }

    @Test fun rootKeepsChildAttentionVisibleUntilTheEntireGroupIsReady() {
        val root = CodingSessionUi(CodingSession("root", "p", "Root", 1, organismId = "o"))
        val child = CodingSessionUi(CodingSession("child", "p", "Child", 1, parentSessionId = "root", organismId = "o"),
            messages = listOf(CodingMessage("answer", CodingRole.AGENT, "Done", createdAt = 2)))
        val ui = CodingUi(sessions = listOf(root, child), organisms = mapOf("o" to SessionOrganism("o", "p", "root", createdAt = 1)))
        assertEquals(CodingSessionStatus.NEEDS_TESTING, child.status)
        assertFalse(ui.readyForArchive(root))
        assertTrue(ui.copy(sessions = listOf(root, child.copy(session = child.session.copy(manuallyVerifiedResponseId = "answer")))).readyForArchive(root))
    }
}
