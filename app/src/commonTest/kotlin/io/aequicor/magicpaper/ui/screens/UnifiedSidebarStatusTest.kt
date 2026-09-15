package io.aequicor.magicpaper.ui.screens

import io.aequicor.magicpaper.domain.CodingSessionStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UnifiedSidebarStatusTest {
    @Test
    fun greenIdleStateHasNoSubtitle() {
        assertNull(CodingSessionStatus.IDLE.sidebarSubtitle)
    }

    @Test
    fun everyNonGreenStateUsesItsStatusLabelAsSubtitle() {
        CodingSessionStatus.entries
            .filterNot { it == CodingSessionStatus.IDLE }
            .forEach { status -> assertEquals(status.label, status.sidebarSubtitle, status.name) }
    }

    @Test
    fun newSessionsAreOrderedByCreationUntilTheirStatusChanges() {
        var now = 100L
        val tracker = SessionRecencyTracker { now }

        val older = tracker.observe("older", CodingSessionStatus.IDLE, createdAt = 10)
        val newer = tracker.observe("newer", CodingSessionStatus.IDLE, createdAt = 20)
        assertTrue(newer > older)

        now = 30
        val changed = tracker.observe("older", CodingSessionStatus.WORKING, createdAt = 10)
        assertTrue(changed > newer)
        assertEquals(changed, tracker.observe("older", CodingSessionStatus.WORKING, createdAt = 10))

        now = 40
        assertEquals(now, tracker.observe("older", CodingSessionStatus.WAITING, createdAt = 10))
    }

    @Test
    fun temporarilyMissingSessionKeepsItsActivityTimeWhenItReturns() {
        var now = 30L
        val tracker = SessionRecencyTracker { now }
        tracker.observe("session", CodingSessionStatus.IDLE, createdAt = 10)
        val workingAt = tracker.observe("session", CodingSessionStatus.WORKING, createdAt = 10)

        // Repository refreshes can briefly omit a session while interruption is persisted.
        now = 40
        assertEquals(workingAt, tracker.observe("session", CodingSessionStatus.WORKING, createdAt = 10))
        assertEquals(now, tracker.observe("session", CodingSessionStatus.IDLE, createdAt = 10))
    }
}
