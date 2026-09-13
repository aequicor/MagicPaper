package io.aequicor.magicpaper.ui.components

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import io.aequicor.magicpaper.domain.*

object DefaultCodingPresentation : CodingPresentation {
    @Composable override fun ComputerUsePanel(state: ComputerUseState, sessionId: String, running: Boolean, onEnable: (ComputerAccess) -> Unit, onDisable: () -> Unit, onPreview: () -> Unit, onSettings: () -> Unit) = renderComputerUsePanel(state, sessionId, running, onEnable, onDisable, onPreview, onSettings)
}
