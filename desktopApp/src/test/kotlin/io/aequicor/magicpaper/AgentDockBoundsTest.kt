package io.aequicor.magicpaper

import androidx.compose.ui.unit.dp
import io.aequicor.magicpaper.designsystem.PaperAgentDockCollapsedWidth
import io.aequicor.magicpaper.designsystem.PaperAgentDockExpandedHeight
import io.aequicor.magicpaper.designsystem.PaperAgentDockExpandedWidth
import java.awt.Rectangle
import kotlin.math.roundToInt
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

    /**
     * The morph must arrive: a progress that never reaches 1 leaves the window at the size it
     * started from while the content already lays out the other state.
     */
    @Test fun theMorphReachesItsTargetOnTime() {
        assertEquals(0.0, boundsProgress(0L))
        assertEquals(0.5, boundsProgress(DOCK_MORPH_NANOS / 2), 1e-9)
        assertEquals(1.0, boundsProgress(DOCK_MORPH_NANOS))
        assertEquals(1.0, boundsProgress(DOCK_MORPH_NANOS * 3), "past its end the morph stays on its target")
        assertTrue(DOCK_MORPH_NANOS in 100_000_000L..400_000_000L, "a morph is felt, not waited for")
    }

    /**
     * Window units are dp at every display scale, so the dock's footprint is its dp size as is.
     * Multiplying by the display scale doubled the dock on every Retina display.
     */
    @Test fun theFootprintIsItsDpSizeOnTheEdgeItHugs() {
        val usable = Rectangle(0, 25, 1512, 920)
        val start = dockBounds(usable, 288.dp, 180.5.dp, DockEdge.START, 0.5f)
        assertEquals(Rectangle(0, 25 + ((920 - 181) * 0.5f).roundToInt(), 288, 181), start)
        val end = dockBounds(usable, 288.dp, 180.dp, DockEdge.END, 0f)
        assertEquals(1512 - 288, end.x, "docked to the end edge, the panel ends on it")
        assertEquals(25, end.y)
        val huge = dockBounds(usable, 4000.dp, 4000.dp, DockEdge.START, 1f)
        assertEquals(Rectangle(usable), huge, "a card larger than the display is clamped to it")
    }

    /**
     * The panel opens under the pointer that hovered the compact list, and retracting must not
     * leave the list under a pointer that has already left the panel: at every place on the
     * edge, the open panel covers the list it grew from.
     */
    @Test fun theOpenPanelCoversTheListItGrewFrom() {
        val usable = Rectangle(1512, 0, 1920, 1050)
        for (edge in DockEdge.values()) for (step in 0..10) {
            val offset = step / 10f
            val compact = dockBounds(usable, PaperAgentDockCollapsedWidth, 210.dp, edge, offset)
            val open = dockBounds(usable, PaperAgentDockExpandedWidth, PaperAgentDockExpandedHeight, edge, offset)
            assertTrue(open.contains(compact), "$edge at $offset: $open does not cover $compact")
        }
    }
}
