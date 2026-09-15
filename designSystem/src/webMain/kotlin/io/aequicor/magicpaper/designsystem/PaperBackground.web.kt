package io.aequicor.magicpaper.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember

// Browser battery and hardware information is not reliably available across JS/Wasm browsers.
// Keep the static paper without scheduling animation or battery polling.
@Composable
internal actual fun rememberPaperEnvironment(active: Boolean): State<PaperEnvironment> =
    remember { mutableStateOf(PaperEnvironment()) }

@Composable
internal actual fun rememberPaperRenderer(): PaperRenderer? = null
