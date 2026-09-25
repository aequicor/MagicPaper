package io.aequicor.magicpaper

import java.awt.Dimension
import java.awt.Point
import java.awt.Rectangle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AgentDockBoundsTest {
    private val screen = Rectangle(1512, 30, 1920, 1040)

    @Test fun compactPanelCanLiveAnywhereInTheUsableDisplay() {
        for (x in 0..10) for (y in 0..10) {
            val bounds = compactOverlayBounds(screen, OverlayPlacement(x / 10f, y / 10f))
            assertTrue(screen.contains(bounds), "Panel escaped the screen at $x,$y: $bounds")
        }
        val center = compactOverlayBounds(screen, OverlayPlacement(.5f, .5f))
        assertTrue(center.x > screen.x && center.x + center.width < screen.x + screen.width)
        assertTrue(center.y > screen.y && center.y + center.height < screen.y + screen.height)
    }

    @Test fun expansionAlwaysContainsTheCompactCardEvenAtCorners() {
        for (x in 0..10) for (y in 0..10) {
            val compact = compactOverlayBounds(screen, OverlayPlacement(x / 10f, y / 10f))
            val expanded = expandedOverlayBounds(screen, compact)
            assertTrue(screen.contains(expanded), "Expanded card escaped the screen: $expanded")
            assertTrue(expanded.contains(compact), "Card moved away from its anchor: $compact -> $expanded")
            for (frame in 0..10) {
                val transition = interpolateRect(compact, expanded, easeOut(frame / 10.0))
                assertTrue(transition.contains(compact),
                    "Hover target fell outside the window during the morph: $compact -> $transition")
            }
        }
    }

    @Test fun draggingWritesAFreePositionThatRestoresWithinOneWindowUnit() {
        val placed = compactOverlayBounds(screen, OverlayPlacement(.37f, .71f))
        val restored = compactOverlayBounds(screen, placementAt(screen, placed.x, placed.y))
        assertTrue(kotlin.math.abs(placed.x - restored.x) <= 1)
        assertTrue(kotlin.math.abs(placed.y - restored.y) <= 1)
        assertEquals(placed.size, restored.size, "Dragging does not change the compact footprint")
    }

    @Test fun crossingASecondDisplayKeepsTheGrabPointUnderThePointer() {
        val left = Rectangle(0, 25, 1000, 800)
        val right = Rectangle(1000, 0, 1400, 900)
        val travel = dragTravelArea(listOf(left, right), left)
        val grab = Point(88, 26)
        val footprint = Dimension(304, 152)
        val before = draggedOrigin(Point(995, 260), grab, footprint, travel)
        val after = draggedOrigin(Point(1005, 260), grab, footprint, travel)
        assertEquals(10, after.x - before.x, "Crossing the seam must not snap to the second screen's edge")
        assertEquals(before.y, after.y)
    }

    @Test fun smallScreensClampTheExpandedFootprintWithoutLosingTheCard() {
        val small = Rectangle(0, 24, 380, 330)
        val compact = compactOverlayBounds(small, OverlayPlacement(1f, 1f))
        val expanded = expandedOverlayBounds(small, compact)
        assertEquals(small, expanded)
        assertTrue(expanded.contains(compact))
    }

    @Test fun morphIsMonotoneAndEndsOnTheExactTarget() {
        val from = compactOverlayBounds(screen, OverlayPlacement(.9f, .8f))
        val to = expandedOverlayBounds(screen, from)
        assertEquals(from, interpolateRect(from, to, 0.0))
        assertEquals(to, interpolateRect(from, to, 1.0))
        var previous = from
        for (step in 1..10) {
            val current = interpolateRect(from, to, easeOut(step / 10.0))
            assertTrue(current.width >= previous.width)
            assertTrue(current.height >= previous.height)
            assertTrue(current.x <= previous.x)
            assertTrue(current.y <= previous.y)
            previous = current
        }
        assertEquals(to, previous)
        assertEquals(1.0, boundsProgress(DOCK_MORPH_NANOS))
        assertTrue(DOCK_MORPH_NANOS in 180_000_000L..300_000_000L)
    }
}
