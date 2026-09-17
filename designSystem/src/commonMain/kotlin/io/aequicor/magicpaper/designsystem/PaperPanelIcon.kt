package io.aequicor.magicpaper.designsystem

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.aequicor.magicpaper.designsystem.resources.Res
import io.aequicor.magicpaper.designsystem.resources.dock_to_left
import io.aequicor.magicpaper.designsystem.resources.dock_to_right
import org.jetbrains.compose.resources.painterResource

/** Physical edge of the panel, unchanged when it is collapsed. */
public enum class PaperPanelSide { LEFT, RIGHT }

/** Decorative Material Symbol; the enclosing Paper control provides the action label. */
@Composable
public fun PaperPanelIcon(side: PaperPanelSide, modifier: Modifier = Modifier) {
    // Google names the docked main area: dock_to_right draws the narrow panel on the left.
    val resource = if (side == PaperPanelSide.LEFT) Res.drawable.dock_to_right else Res.drawable.dock_to_left
    Icon(painterResource(resource), contentDescription = null, modifier = modifier.size(24.dp),
        tint = LocalPaperColors.current.action)
}
