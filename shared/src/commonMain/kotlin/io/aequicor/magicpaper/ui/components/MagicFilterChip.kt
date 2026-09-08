package io.aequicor.magicpaper.ui.components

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver

/** Keep feedback in the rounded container, including the area behind the label and its padding. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MagicFilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    val focused by interaction.collectIsFocusedAsState()
    val alpha = when {
        !enabled -> 0f
        pressed -> .14f
        focused -> .10f
        hovered -> .08f
        else -> 0f
    }
    val ink = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha)
    // Material still owns selection, keyboard input, disabled state and the hit area.
    // Suppress only its separate ripple; the same interaction source colors the whole surface.
    CompositionLocalProvider(LocalRippleConfiguration provides null) {
        FilterChip(
            selected = selected,
            onClick = onClick,
            label = label,
            modifier = modifier,
            enabled = enabled,
            interactionSource = interaction,
            colors = FilterChipDefaults.filterChipColors(
                containerColor = if (alpha == 0f) Color.Transparent else ink,
                selectedContainerColor = ink.compositeOver(MaterialTheme.colorScheme.secondaryContainer),
            ),
        )
    }
}
