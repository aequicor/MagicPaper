package io.aequicor.magicpaper.ui.components

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import io.aequicor.magicpaper.domain.*

/** Feature-owned Paper content. Defaults live in wrappers to keep the Compose JS ABI stable. */
interface ModelPresentation {
    @Composable fun EffortControl(
    capability: ReasoningCapability,
    selection: EffortSelection,
    onSelect: (EffortSelection) -> Unit,
    modifier: Modifier,
)
    @Composable fun ModelSettingsButton(
    profile: LlmProfile?,
    selection: ModelSelection?,
    onChoose: () -> Unit,
    onEffort: (EffortSelection) -> Unit,
    onParameters: () -> Unit,
    modifier: Modifier,
)
    @Composable fun FavoriteModelPicker(
    profiles: List<LlmProfile>,
    selection: ModelSelection?,
    onSelect: (ModelSelection) -> Unit,
    onDismiss: () -> Unit,
    title: String,
    footer: @Composable () -> Unit,
)
}

val LocalModelPresentation = staticCompositionLocalOf<ModelPresentation> { error("ModelPresentation is not installed") }

@Composable
fun EffortControl(
    capability: ReasoningCapability,
    selection: EffortSelection,
    onSelect: (EffortSelection) -> Unit,
    modifier: Modifier = Modifier,
) = LocalModelPresentation.current.EffortControl(capability, selection, onSelect, modifier)

@Composable
fun ModelSettingsButton(
    profile: LlmProfile?,
    selection: ModelSelection?,
    onChoose: () -> Unit,
    onEffort: (EffortSelection) -> Unit,
    onParameters: () -> Unit,
    modifier: Modifier = Modifier,
) = LocalModelPresentation.current.ModelSettingsButton(profile, selection, onChoose, onEffort, onParameters, modifier)

@Composable
fun FavoriteModelPicker(
    profiles: List<LlmProfile>,
    selection: ModelSelection?,
    onSelect: (ModelSelection) -> Unit,
    onDismiss: () -> Unit,
    title: String = "Выбрать модель",
    footer: @Composable () -> Unit = {},
) = LocalModelPresentation.current.FavoriteModelPicker(profiles, selection, onSelect, onDismiss, title, footer)
