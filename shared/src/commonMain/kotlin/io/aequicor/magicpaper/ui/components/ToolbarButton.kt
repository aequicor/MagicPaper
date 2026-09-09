package io.aequicor.magicpaper.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import io.aequicor.magicpaper.designsystem.PaperToolbarIcon
import io.aequicor.magicpaper.designsystem.PaperToolbarButton

internal typealias ToolbarIcon = PaperToolbarIcon

@Composable
internal fun ToolbarButton(icon: ToolbarIcon, label: String, size: Dp, selected: Boolean = false, onClick: () -> Unit) =
    PaperToolbarButton(icon, label, size, selected, onClick)
