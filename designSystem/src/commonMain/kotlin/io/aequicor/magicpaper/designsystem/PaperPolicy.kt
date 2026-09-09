package io.aequicor.magicpaper.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** A platform name is exposed as data, never as an OS-specific API. */
public enum class PaperPlatform { MACOS, WINDOWS, ANDROID, WEB, LINUX, OTHER }

@Immutable
public data class PaperDensity(
    public val controlHeight: Dp,
    public val rowHeight: Dp,
    public val fieldHeight: Dp,
    public val iconSize: Dp,
)

@Immutable
public data class PaperPlatformPolicy(
    public val platform: PaperPlatform,
    public val density: PaperDensity,
    public val primaryModifierLabel: String,
    public val settingsShortcutLabel: String,
    public val supportsNativeMenuBar: Boolean,
) {
    public companion object {
        public val Fallback: PaperPlatformPolicy = PaperPlatformPolicy(
            platform = PaperPlatform.OTHER,
            density = PaperDensity(40.dp, 40.dp, 40.dp, 20.dp),
            primaryModifierLabel = "Ctrl",
            settingsShortcutLabel = "Ctrl+,",
            supportsNativeMenuBar = false,
        )
    }
}

public val LocalPaperPlatformPolicy = staticCompositionLocalOf { PaperPlatformPolicy.Fallback }

@Composable
public expect fun rememberPaperPlatformPolicy(): PaperPlatformPolicy

/** Public interaction state used consistently by every Paper control. */
public enum class PaperControlState { NORMAL, HOVER, PRESSED, FOCUSED, DISABLED, SELECTED, ERROR, BUSY }

public enum class PaperButtonKind { PRIMARY, SECONDARY, DESTRUCTIVE, QUIET }
public enum class PaperTextRole { DISPLAY, HEADLINE, TITLE, BODY, LABEL, CODE, CHROME }
public enum class PaperSurfaceKind { CANVAS, PANEL, RAISED, SELECTED, ERROR }
public enum class PaperProgressKind { LINEAR, CIRCULAR }

/**
 * Registry used by overlays to return focus to a live opener. A target that
 * disappeared while the overlay was open is skipped in favour of the most
 * recently registered live fallback.
 */
public class PaperFocusRestorer {
    private val targets = LinkedHashMap<String, () -> Boolean>()

    public fun register(key: String, requestFocus: () -> Boolean) {
        targets.remove(key)
        targets[key] = requestFocus
    }

    public fun unregister(key: String) {
        targets.remove(key)
    }

    /** Returns false only when no still-live target accepted focus. */
    public fun restore(): Boolean {
        val iterator = targets.entries.reversed().iterator()
        while (iterator.hasNext()) if (iterator.next().value()) return true
        return false
    }
}

/** The testable state contract for the public primitive inventory. */
public object PaperStateCatalog {
    public val interactive: Set<PaperControlState> = setOf(
        PaperControlState.NORMAL, PaperControlState.HOVER, PaperControlState.PRESSED,
        PaperControlState.FOCUSED, PaperControlState.DISABLED, PaperControlState.SELECTED,
    )
    public val editable: Set<PaperControlState> = interactive + PaperControlState.ERROR
    public val actionable: Set<PaperControlState> = interactive + PaperControlState.BUSY
    public fun statesFor(component: String): Set<PaperControlState> = when (component) {
        "field" -> editable
        "button" -> actionable
        "switch", "choice", "menu", "dialog" -> interactive
        "text", "surface", "tooltip", "progress", "divider", "list", "scroll" -> setOf(PaperControlState.NORMAL)
        else -> emptySet()
    }
}
