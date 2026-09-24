package io.aequicor.magicpaper

import java.awt.Rectangle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The expand/collapse morph is the dock's only animation of its window, so its interpolation
 * must be exact at the ends and monotone between them: a wrong blend shows as a jump or a
 * backwards step while the panel grows.
 */
class AgentDockBoundsTest {
    private val panel = object {
        fun interpolate(from: Rectangle, to: Rectangle, t: Double) = interpolateRect(from, to, t)
        fun ease(t: Double) = easeOut(t)
    }

    @Test fun interpolationStartsAndEndsExactlyOnItsRectangles() {
        val from = Rectangle(0, 100, 42, 88)
        val to = Rectangle(0, 40, 448, 476)
        assertEquals(from, panel.interpolate(from, to, 0.0))
        assertEquals(to, panel.interpolate(from, to, 1.0))
        // Out-of-range parameters clamp instead of extrapolating past the target.
        assertEquals(from, panel.interpolate(from, to, -1.0))
        assertEquals(to, panel.interpolate(from, to, 2.0))
    }

    @Test fun interpolationMovesMonotonicallyTowardsTheTarget() {
        val from = Rectangle(0, 100, 42, 88)
        val to = Rectangle(0, 40, 448, 476)
        var previous = from
        var t = 0.0
        while (t <= 1.0) {
            val current = panel.interpolate(from, to, panel.ease(t))
            assertTrue(current.width >= previous.width, "width must not shrink: $previous -> $current")
            assertTrue(current.height >= previous.height, "height must not shrink: $previous -> $current")
            assertTrue(current.y <= previous.y, "y must not move down: $previous -> $current")
            previous = current
            t += 0.1
        }
        assertEquals(to, previous)
    }

    @Test fun easeOutDeceleratesIntoTheTarget() {
        val first = panel.ease(0.2) - panel.ease(0.0)
        val last = panel.ease(1.0) - panel.ease(0.8)
        assertTrue(first > last, "an ease-out covers more ground early: $first vs $last")
        assertEquals(0.0, panel.ease(0.0))
        assertEquals(1.0, panel.ease(1.0))
    }
}
