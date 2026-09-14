package io.aequicor.magicpaper.ui.components

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import io.aequicor.magicpaper.domain.*

/** Feature-owned Paper content. */
interface CodingPresentation {
    @Composable fun ComputerUsePanel(
    state: ComputerUseState,
    sessionId: String,
    running: Boolean,
    onEnable: (ComputerAccess) -> Unit,
    onDisable: () -> Unit,
    onPreview: () -> Unit,
    onSettings: () -> Unit,
)
}

val LocalCodingPresentation = staticCompositionLocalOf<CodingPresentation> { error("CodingPresentation is not installed") }

@Composable
fun ComputerUsePanel(
    state: ComputerUseState,
    sessionId: String,
    running: Boolean,
    onEnable: (ComputerAccess) -> Unit,
    onDisable: () -> Unit,
    onPreview: () -> Unit,
    onSettings: () -> Unit,
) = LocalCodingPresentation.current.ComputerUsePanel(state, sessionId, running, onEnable, onDisable, onPreview, onSettings)
