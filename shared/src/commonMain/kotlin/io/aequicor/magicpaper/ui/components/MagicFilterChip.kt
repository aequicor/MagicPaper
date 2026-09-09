package io.aequicor.magicpaper.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.aequicor.magicpaper.designsystem.PaperChoice

@Composable
internal fun MagicFilterChip(selected: Boolean, onClick: () -> Unit, label: @Composable () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) =
    PaperChoice(selected, onClick, modifier, enabled, label)
