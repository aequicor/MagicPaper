package io.aequicor.magicpaper.ui.screens

import io.aequicor.magicpaper.domain.CodingSessionStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UnifiedSidebarStatusTest {
    @Test
    fun greenIdleStateHasNoSubtitle() {
        assertNull(CodingSessionStatus.IDLE.sidebarSubtitle)
    }

    @Test
    fun everyNonGreenStateHasACompactSubtitle() {
        CodingSessionStatus.entries
            .filterNot { it == CodingSessionStatus.IDLE }
            .forEach { status ->
                val subtitle = status.sidebarSubtitle.orEmpty()
                assertTrue(subtitle.isNotBlank(), status.name)
                assertTrue(subtitle.split(Regex("\\s+")).size <= 2, "$status: $subtitle")
            }
        assertEquals("Ждёт ответа", CodingSessionStatus.WAITING.sidebarSubtitle)
        assertEquals("Нужна проверка", CodingSessionStatus.NEEDS_TESTING.sidebarSubtitle)
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

    @Test
    fun restoredTrackerUsesPersistedStatusTransitionTime() {
        val tracker = SessionRecencyTracker { 100L }

        assertEquals(
            70L,
            tracker.observe(
                "session",
                CodingSessionStatus.WAITING,
                createdAt = 10,
                persistedStatus = CodingSessionStatus.WAITING,
                persistedStatusChangedAt = 70,
            ),
        )
    }

    @Test
    fun trackerAcceptsStatusTimePersistedAfterItsFirstObservation() {
        val tracker = SessionRecencyTracker { 100L }
        assertEquals(10L, tracker.observe("session", CodingSessionStatus.WORKING, createdAt = 10))

        assertEquals(
            90L,
            tracker.observe(
                "session",
                CodingSessionStatus.WORKING,
                createdAt = 10,
                persistedStatus = CodingSessionStatus.WORKING,
                persistedStatusChangedAt = 90,
            ),
        )
    }

    @Test
    fun workingAndWaitingSessionsArePinnedAheadOfNewerInactiveSessions() {
        val idle = UnifiedSidebarItem("idle", "Idle", 100, isCoding = true, codingStatus = CodingSessionStatus.IDLE)
        val working = UnifiedSidebarItem("working", "Working", 10, isCoding = true, codingStatus = CodingSessionStatus.WORKING)
        val waiting = UnifiedSidebarItem("waiting", "Waiting", 20, isCoding = true, codingStatus = CodingSessionStatus.WAITING)

        assertEquals(listOf("waiting", "working", "idle"),
            listOf(idle, working, waiting).sortedWith(unifiedSidebarItemComparator).map { it.id })
        assertTrue(CodingSessionStatus.WORKING.pinnedInSidebar)
        assertTrue(CodingSessionStatus.WAITING.pinnedInSidebar)
        assertFalse(CodingSessionStatus.CONFIRMATION.pinnedInSidebar)
    }
}
