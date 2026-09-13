package io.aequicor.magicpaper.ui.components

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import io.aequicor.magicpaper.domain.*

object DefaultModelPresentation : ModelPresentation {
    @Composable override fun EffortControl(capability: ReasoningCapability, selection: EffortSelection, onSelect: (EffortSelection) -> Unit, modifier: Modifier) = renderEffortControl(capability, selection, onSelect, modifier)
    @Composable override fun ModelSettingsButton(profile: LlmProfile?, selection: ModelSelection?, onChoose: () -> Unit, onEffort: (EffortSelection) -> Unit, onParameters: () -> Unit, modifier: Modifier) = renderModelSettingsButton(profile, selection, onChoose, onEffort, onParameters, modifier)
    @Composable override fun FavoriteModelPicker(profiles: List<LlmProfile>, selection: ModelSelection?, onSelect: (ModelSelection) -> Unit, onDismiss: () -> Unit, title: String, footer: @Composable () -> Unit) = renderFavoriteModelPicker(profiles, selection, onSelect, onDismiss, title, footer)
}
