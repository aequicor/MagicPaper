package io.aequicor.magicpaper.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** Copy-only native file drag. Clicking/keyboard activation reveals it instead; never executes it. */
@Composable
public fun PaperFileTransfer(label: String, path: String, onReveal: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    PaperButton(label, onReveal, modifier = modifier.paperFileDrag(path, enabled), enabled = enabled, kind = PaperButtonKind.SECONDARY)
}

internal expect fun Modifier.paperFileDrag(path: String, enabled: Boolean): Modifier
