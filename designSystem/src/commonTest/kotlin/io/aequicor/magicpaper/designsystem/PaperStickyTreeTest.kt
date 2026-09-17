package io.aequicor.magicpaper.designsystem

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PaperStickyTreeTest {
    private val entries = listOf(
        PaperStickyTreeEntry("project", header = true),
        PaperStickyTreeEntry("task", listOf("project"), true),
        PaperStickyTreeEntry("parent", listOf("project", "task"), true),
        PaperStickyTreeEntry("child", listOf("project", "task", "parent")),
        PaperStickyTreeEntry("sibling", listOf("project", "task"), true),
        PaperStickyTreeEntry("chat"),
        PaperStickyTreeEntry("project-again", header = true),
    )
    private val heights = mapOf("project" to 30, "task" to 50, "parent" to 40, "sibling" to 40)

    @Test fun threeHeadersKeepTheirOwnHeightAndOrder() {
        assertEquals(listOf(PaperTreePin("project", 0), PaperTreePin("task", 30), PaperTreePin("parent", 80)),
            paperTreePins(entries, listOf(PaperTreeVisibleEntry(3, -10)), heights))
    }

    @Test fun siblingPushesOnlyItsPredecessorWhileAncestorsStay() {
        assertEquals(listOf(PaperTreePin("project", 0), PaperTreePin("task", 30), PaperTreePin("parent", 50)),
            paperTreePins(entries, listOf(PaperTreeVisibleEntry(3, -10), PaperTreeVisibleEntry(4, 90)), heights))
    }

    @Test fun chatPushesTheWholeStackAndClearsItAtTheBoundary() {
        assertEquals(listOf(PaperTreePin("project", -20), PaperTreePin("task", 10), PaperTreePin("parent", 60)),
            paperTreePins(entries, listOf(PaperTreeVisibleEntry(3, -10), PaperTreeVisibleEntry(5, 100)), heights))
        assertTrue(paperTreePins(entries, listOf(PaperTreeVisibleEntry(5, -1), PaperTreeVisibleEntry(6, 40)), heights).isEmpty())
    }

    @Test fun visibleDescendantsPinAsTheyReachTheirAncestorStack() {
        assertEquals(listOf(PaperTreePin("project", 0), PaperTreePin("task", 30), PaperTreePin("parent", 80)),
            paperTreePins(entries, listOf(PaperTreeVisibleEntry(0, -10), PaperTreeVisibleEntry(1, 20), PaperTreeVisibleEntry(2, 70)), heights))
        assertTrue(paperTreePins(entries, listOf(PaperTreeVisibleEntry(0, 0), PaperTreeVisibleEntry(1, 30)), heights).isEmpty())
    }

    @Test fun previousPeerHeaderRemainsPinnedAfterItsNaturalRowLeavesTheViewport() {
        val peers = listOf(
            PaperStickyTreeEntry("project", header = true),
            PaperStickyTreeEntry("selected", listOf("project"), header = true),
            PaperStickyTreeEntry("ordinary-1", listOf("project")),
            PaperStickyTreeEntry("ordinary-2", listOf("project")),
        )

        assertEquals(
            listOf(PaperTreePin("project", 0), PaperTreePin("selected", 30)),
            paperTreePins(
                peers,
                listOf(PaperTreeVisibleEntry(3, -8)),
                mapOf("project" to 30, "selected" to 40),
            ),
        )
    }

    @Test fun restoredScrollCanMeasureAncestorsNotPreviouslyVisible() {
        assertEquals(listOf("project", "task", "parent"),
            paperTreePins(entries, listOf(PaperTreeVisibleEntry(3, -10)), emptyMap()).map { it.key })
    }

    @Test fun deeperTreesNeverPinMoreThanFourHeaders() {
        val deep = entries + PaperStickyTreeEntry("deep", listOf("project", "task", "parent", "child"), true)
        assertEquals(
            listOf("project", "parent", "child", "deep"),
            paperTreePins(deep, listOf(PaperTreeVisibleEntry(7, -1)), heights).map { it.key },
        )
    }

    @Test fun selectedChatSurvivesIndependentRowsAndProjectBoundaries() {
        val rows = listOf(
            PaperStickyTreeEntry("selected-chat", header = true, retainAfterBranch = true),
            PaperStickyTreeEntry("other-chat"),
            PaperStickyTreeEntry("project", header = true),
            PaperStickyTreeEntry("unread", listOf("project"), header = true),
            PaperStickyTreeEntry("ready", listOf("project", "unread")),
        )
        val sizes = mapOf("selected-chat" to 40, "project" to 30, "unread" to 50)
        assertEquals(listOf(PaperTreePin("selected-chat", 0)),
            paperTreePins(rows, listOf(PaperTreeVisibleEntry(1, -10)), sizes))
        assertEquals(listOf(PaperTreePin("selected-chat", 0), PaperTreePin("project", 40), PaperTreePin("unread", 70)),
            paperTreePins(rows, listOf(PaperTreeVisibleEntry(4, -10)), sizes))
        assertTrue(paperTreePins(rows, listOf(PaperTreeVisibleEntry(0, 0)), sizes).isEmpty())
        assertTrue(paperTreePins(rows.map { it.copy(retainAfterBranch = false) },
            listOf(PaperTreeVisibleEntry(1, -10)), sizes).isEmpty(), "Changing selection releases the old chat")
    }

    @Test fun selectedSessionKeepsItsSlotWhenManyUnreadSessionsHavePassed() {
        val rows = listOf(PaperStickyTreeEntry("project", header = true),
            PaperStickyTreeEntry("selected", listOf("project"), header = true, retainAfterBranch = true)) +
            (1..6).map { n -> PaperStickyTreeEntry("unread-$n",
                listOf("project", "selected") + (1 until n).map { "unread-$it" }, header = true) }
        val sizes = rows.associate { it.key to 30 }
        assertEquals(listOf("selected", "project", "unread-5", "unread-6"),
            paperTreePins(rows, listOf(PaperTreeVisibleEntry(7, -10)), sizes).map { it.key })
    }

    @Test fun selectedRowPinsBeforeItCanHideUnderItsProjectHeader() {
        val rows = listOf(PaperStickyTreeEntry("project", header = true),
            PaperStickyTreeEntry("selected", listOf("project"), header = true, retainAfterBranch = true),
            PaperStickyTreeEntry("ordinary", listOf("project", "selected")))
        assertEquals(listOf(PaperTreePin("selected", 0), PaperTreePin("project", 40)),
            paperTreePins(rows, listOf(PaperTreeVisibleEntry(1, 0), PaperTreeVisibleEntry(2, 40)),
                mapOf("project" to 30, "selected" to 40)))
    }
}
