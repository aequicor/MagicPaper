package io.aequicor.magicpaper.designsystem

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import androidx.compose.ui.unit.dp

class PaperStateCatalogTest {
    @Test
    fun editableControlsCoverRequiredAccessibleStates() {
        assertEquals(
            setOf(PaperControlState.NORMAL, PaperControlState.HOVER, PaperControlState.PRESSED, PaperControlState.FOCUSED, PaperControlState.DISABLED, PaperControlState.SELECTED, PaperControlState.ERROR),
            PaperStateCatalog.statesFor("field"),
        )
    }

    @Test
    fun everyInteractiveInventoryItemHasKeyboardReachableStates() {
        listOf("button", "switch", "choice", "menu", "dialog").forEach { component ->
            val states = PaperStateCatalog.statesFor(component)
            assertTrue(PaperControlState.FOCUSED in states, "$component must expose focus")
            assertTrue(PaperControlState.DISABLED in states, "$component must expose disabled")
        }
    }

    @Test
    fun catalogueDeclaresTheCompleteStateMatrixForEveryPrimitive() {
        assertEquals(PaperStateCatalog.actionable, PaperStateCatalog.statesFor("button"))
        listOf("switch", "choice", "menu", "dialog").forEach {
            assertEquals(PaperStateCatalog.interactive, PaperStateCatalog.statesFor(it), it)
        }
        listOf("text", "surface", "tooltip", "progress", "divider", "list", "scroll").forEach {
            assertEquals(setOf(PaperControlState.NORMAL), PaperStateCatalog.statesFor(it), it)
        }
    }

    @Test
    fun desktopPoliciesKeepCompactButDistinctDensity() {
        val mac = PaperDensity(28.dp, 28.dp, 30.dp, 16.dp)
        val windows = PaperDensity(32.dp, 32.dp, 32.dp, 16.dp)
        assertTrue(mac.controlHeight < windows.controlHeight)
        assertTrue(PaperPlatformPolicy.Fallback.density.controlHeight >= 40.dp)
    }

    @Test
    fun focusRestorerSkipsDisposedOpenerAndUsesLiveFallback() {
        val restorer = PaperFocusRestorer()
        var primaryRequests = 0
        var fallbackRequests = 0
        restorer.register("primary") { primaryRequests++; false }
        restorer.register("fallback") { fallbackRequests++; true }
        assertTrue(restorer.restore())
        assertEquals(0, primaryRequests, "Newest live anchor wins")
        assertEquals(1, fallbackRequests)

        restorer.unregister("fallback")
        assertEquals(false, restorer.restore())
        assertEquals(1, primaryRequests, "Disposed fallback must not be focused")
    }
}
