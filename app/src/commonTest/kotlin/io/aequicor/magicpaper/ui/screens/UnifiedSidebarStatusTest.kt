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
    fun allItemsAreOrderedByRecentActivityRegardlessOfTypeOrStatus() {
        val idle = UnifiedSidebarItem("idle", "Idle", 100, isCoding = true, codingStatus = CodingSessionStatus.IDLE)
        val working = UnifiedSidebarItem("working", "Working", 10, isCoding = true, codingStatus = CodingSessionStatus.WORKING)
        val chat = UnifiedSidebarItem("chat", "Chat", 50, isCoding = false)

        assertEquals(listOf("idle", "chat", "working"),
            listOf(working, idle, chat).sortedWith(unifiedSidebarItemComparator).map { it.id })
    }

    @Test
    fun chatInteractionSplitsSessionsFromTheSameProjectIntoSeparateGroups() {
        fun session(id: String, time: Long, project: String = "p") = UnifiedSidebarItem(
            id, id, time, isCoding = true, projectName = "Project", projectId = project,
        )
        val chat = UnifiedSidebarItem("chat", "Chat", 80, isCoding = false)

        val groups = groupUnifiedSidebarItems(listOf(
            session("newest", 100),
            session("newer", 90),
            chat,
            session("older", 70),
        ))

        assertEquals(listOf(listOf("newest", "newer"), listOf("chat"), listOf("older")),
            groups.map { group -> group.items.map { it.id } })
        assertTrue(groups.first().showsProjectHeader)
        assertTrue(!groups[1].showsProjectHeader)
        assertTrue(groups.last().showsProjectHeader)
    }

    @Test
    fun anotherProjectAlsoStartsANewGroup() {
        fun session(id: String, time: Long, project: String) = UnifiedSidebarItem(
            id, id, time, isCoding = true, projectName = project, projectId = project,
        )

        val groups = groupUnifiedSidebarItems(listOf(
            session("a1", 40, "a"),
            session("b1", 30, "b"),
            session("a2", 20, "a"),
        ))

        assertEquals(listOf("a", "b", "a"), groups.map { it.projectId })
        assertTrue(groups.all { it.showsProjectHeader })
    }

    @Test
    fun singleProjectSessionIncludesProjectInItsSubtitle() {
        val idle = UnifiedSidebarItem(
            "idle", "Idle", 10, isCoding = true, projectName = "MagicPaper",
            codingStatus = CodingSessionStatus.IDLE,
        )
        val working = idle.copy(codingStatus = CodingSessionStatus.WORKING)

        assertEquals("MagicPaper", idle.sidebarSubtitle(showProject = true))
        assertEquals("Работает · MagicPaper", working.sidebarSubtitle(showProject = true))
        assertEquals("Работает", working.sidebarSubtitle(showProject = false))
    }

    @Test
    fun filtersStatusAndSourceWithoutDroppingMatchingOrganismChildren() {
        val child = UnifiedSidebarItem("child", "Child", 9, true, projectId = "p",
            codingStatus = CodingSessionStatus.WORKING)
        val zygote = UnifiedSidebarItem("zygote", "Task", 10, true, projectId = "p",
            codingStatus = CodingSessionStatus.IDLE, children = listOf(child), isOrganism = true)
        val chat = UnifiedSidebarItem("chat", "Chat", 8, false)

        val working = filterUnifiedSidebarItems(listOf(zygote, chat), SidebarStatusFilter.WORKING, SidebarSourceFilter.All)
        assertEquals(listOf("zygote"), working.map { it.id })
        assertEquals(listOf("child"), working.single().children.map { it.id })
        assertEquals(listOf("chat"), filterUnifiedSidebarItems(listOf(zygote, chat), SidebarStatusFilter.ALL,
            SidebarSourceFilter.Chats).map { it.id })
    }

    @Test
    fun projectsAndImportantSessionsAreStickyCandidates() {
        assertTrue(UnifiedSidebarGroup("p", "p", "Project", listOf(
            UnifiedSidebarItem("one", "One", 1, true, projectId = "p"),
        )).showsProjectHeader)
        assertTrue(UnifiedSidebarItem("z", "Z", 1, true, isOrganism = true).needsStickyHeader())
        assertTrue(UnifiedSidebarItem("w", "W", 1, true,
            codingStatus = CodingSessionStatus.WORKING).needsStickyHeader())
        assertTrue(UnifiedSidebarItem("u", "U", 1, true, unread = true).needsStickyHeader())
    }
}
