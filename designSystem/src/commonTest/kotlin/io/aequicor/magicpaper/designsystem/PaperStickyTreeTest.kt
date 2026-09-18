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
            paperTreePins(entries, listOf(PaperTreeVisibleEntry("child", -10)), heights))
    }

    @Test fun siblingPushesOnlyItsPredecessorWhileAncestorsStay() {
        assertEquals(listOf(PaperTreePin("project", 0), PaperTreePin("task", 30), PaperTreePin("parent", 50)),
            paperTreePins(entries, listOf(PaperTreeVisibleEntry("child", -10), PaperTreeVisibleEntry("sibling", 90)), heights))
    }

    @Test fun chatPushesTheWholeStackAndClearsItAtTheBoundary() {
        assertEquals(listOf(PaperTreePin("project", -20), PaperTreePin("task", 10), PaperTreePin("parent", 60)),
            paperTreePins(entries, listOf(PaperTreeVisibleEntry("child", -10), PaperTreeVisibleEntry("chat", 100)), heights))
        assertTrue(paperTreePins(entries, listOf(PaperTreeVisibleEntry("chat", -1), PaperTreeVisibleEntry("project-again", 40)), heights).isEmpty())
    }

    @Test fun visibleDescendantsPinAsTheyReachTheirAncestorStack() {
        assertEquals(listOf(PaperTreePin("project", 0), PaperTreePin("task", 30), PaperTreePin("parent", 80)),
            paperTreePins(entries, listOf(PaperTreeVisibleEntry("project", -10), PaperTreeVisibleEntry("task", 20), PaperTreeVisibleEntry("parent", 70)), heights))
        assertTrue(paperTreePins(entries, listOf(PaperTreeVisibleEntry("project", 0), PaperTreeVisibleEntry("task", 30)), heights).isEmpty())
    }

    @Test fun headerDoesNotOwnSiblingRowsOutsideItsDeclaredBranch() {
        val peers = listOf(
            PaperStickyTreeEntry("project", header = true),
            PaperStickyTreeEntry("selected", listOf("project"), header = true),
            PaperStickyTreeEntry("ordinary-1", listOf("project")),
            PaperStickyTreeEntry("ordinary-2", listOf("project")),
        )

        assertEquals(
            listOf(PaperTreePin("project", 0)),
            paperTreePins(
                peers,
                listOf(PaperTreeVisibleEntry("ordinary-2", -8)),
                mapOf("project" to 30, "selected" to 40),
            ),
        )
    }

    @Test fun restoredScrollCanMeasureAncestorsNotPreviouslyVisible() {
        assertEquals(listOf("project", "task", "parent"),
            paperTreePins(entries, listOf(PaperTreeVisibleEntry("child", -10)), emptyMap()).map { it.key })
    }

    @Test fun deeperTreesNeverPinMoreThanFourHeaders() {
        val deep = entries.map { if (it.key == "child") it.copy(header = true) else it } +
            PaperStickyTreeEntry("deep", listOf("project", "task", "parent", "child"), true)
        assertEquals(
            listOf("project", "parent", "child", "deep"),
            paperTreePins(deep, listOf(PaperTreeVisibleEntry("deep", -1)), heights).map { it.key },
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
            paperTreePins(rows, listOf(PaperTreeVisibleEntry("other-chat", -10)), sizes))
        assertEquals(listOf(PaperTreePin("selected-chat", 0), PaperTreePin("project", 40), PaperTreePin("unread", 70)),
            paperTreePins(rows, listOf(PaperTreeVisibleEntry("ready", -10)), sizes))
        assertTrue(paperTreePins(rows, listOf(PaperTreeVisibleEntry("selected-chat", 0)), sizes).isEmpty())
        assertTrue(paperTreePins(rows.map { it.copy(retainAfterBranch = false) },
            listOf(PaperTreeVisibleEntry("other-chat", -10)), sizes).isEmpty(), "Changing selection releases the old chat")
    }

    @Test fun selectedSessionKeepsItsSlotWhenManyUnreadSessionsHavePassed() {
        val rows = listOf(PaperStickyTreeEntry("project", header = true),
            PaperStickyTreeEntry("selected", listOf("project"), header = true, retainAfterBranch = true)) +
            (1..6).map { n -> PaperStickyTreeEntry("unread-$n",
                listOf("project", "selected") + (1 until n).map { "unread-$it" }, header = true) }
        val sizes = rows.associate { it.key to 30 }
        assertEquals(listOf("project", "selected", "unread-5", "unread-6"),
            paperTreePins(rows, listOf(PaperTreeVisibleEntry("unread-6", -10)), sizes).map { it.key })
    }

    @Test fun selectedRowPinsBeforeItCanHideUnderItsProjectHeader() {
        val rows = listOf(PaperStickyTreeEntry("project", header = true),
            PaperStickyTreeEntry("selected", listOf("project"), header = true, retainAfterBranch = true),
            PaperStickyTreeEntry("ordinary", listOf("project", "selected")))
        assertEquals(listOf(PaperTreePin("project", 0), PaperTreePin("selected", 30)),
            paperTreePins(rows, listOf(PaperTreeVisibleEntry("selected", 0), PaperTreeVisibleEntry("ordinary", 40)),
                mapOf("project" to 30, "selected" to 40)))
    }

    @Test fun laterHeadersReachTheStackBeforeTheirNaturalRowLeavesTheViewport() {
        val rows = listOf(PaperStickyTreeEntry("project", header = true)) + (1..6).map { n ->
            PaperStickyTreeEntry("status-$n", listOf("project") + (1 until n).map { "status-$it" }, header = true)
        }
        val pins = paperTreePins(rows, listOf(
            PaperTreeVisibleEntry("status-1", -10), PaperTreeVisibleEntry("status-2", 20),
            PaperTreeVisibleEntry("status-3", 50), PaperTreeVisibleEntry("status-4", 80),
            PaperTreeVisibleEntry("status-5", 110)), rows.associate { it.key to 30 })
        assertEquals(listOf(PaperTreePin("project", 0), PaperTreePin("status-2", 30),
            PaperTreePin("status-3", 60), PaperTreePin("status-4", 90)), pins)
    }

    @Test fun removedRowsInPreviousLayoutCannotPinUnrelatedEntriesAtTheSameIndex() {
        val collapsed = listOf(PaperStickyTreeEntry("project", header = true),
            PaperStickyTreeEntry("next-project", header = true),
            PaperStickyTreeEntry("next-session", listOf("next-project")))
        val oldLayout = listOf(PaperTreeVisibleEntry("parent", -10), PaperTreeVisibleEntry("child", 30))
        assertTrue(paperTreePins(collapsed, oldLayout, heights).isEmpty())
    }

    @Test fun selectionKeepsChronologicalOrderBetweenEarlierAndLaterStatusHeaders() {
        val rows = listOf(PaperStickyTreeEntry("project", header = true),
            PaperStickyTreeEntry("working", listOf("project"), header = true),
            PaperStickyTreeEntry("selected", listOf("project", "working"), header = true, retainAfterBranch = true),
            PaperStickyTreeEntry("waiting", listOf("project", "working", "selected"), header = true),
            PaperStickyTreeEntry("ordinary", listOf("project", "working", "selected", "waiting")))
        assertEquals(listOf(PaperTreePin("project", 0), PaperTreePin("working", 30),
            PaperTreePin("selected", 60), PaperTreePin("waiting", 90)),
            paperTreePins(rows, listOf(PaperTreeVisibleEntry("ordinary", -10)), rows.associate { it.key to 30 }))
    }

    @Test fun nextProjectSticksBelowRetainedChatBeforeBeingCoveredByIt() {
        val rows = listOf(PaperStickyTreeEntry("selected", header = true, retainAfterBranch = true),
            PaperStickyTreeEntry("project", header = true), PaperStickyTreeEntry("ordinary", listOf("project")))
        assertEquals(listOf(PaperTreePin("selected", 0), PaperTreePin("project", 40)),
            paperTreePins(rows, listOf(PaperTreeVisibleEntry("selected", -10), PaperTreeVisibleEntry("project", 30),
                PaperTreeVisibleEntry("ordinary", 60)), mapOf("selected" to 40, "project" to 30)))
    }

    @Test fun cappedAncestorsBeforeTheCurrentHeadersDoNotPushThemOutAsAnotherBranch() {
        val rows = listOf(PaperStickyTreeEntry("project", header = true)) + (1..6).map { n ->
            PaperStickyTreeEntry("status-$n", listOf("project") + (1 until n).map { "status-$it" }, header = true)
        }
        val sizes = rows.associate { it.key to if (it.key == "project") 60 else 10 }
        val visible = (1..6).map { PaperTreeVisibleEntry("status-$it", (it - 2) * 10) }
        assertEquals(listOf(PaperTreePin("project", 0), PaperTreePin("status-4", 60),
            PaperTreePin("status-5", 70), PaperTreePin("status-6", 80)), paperTreePins(rows, visible, sizes))
    }
}
