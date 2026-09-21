package io.aequicor.magicpaper.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import io.aequicor.magicpaper.data.coding.backendCatalog
import io.aequicor.magicpaper.data.coding.backendCatalog
import io.aequicor.magicpaper.designsystem.*
import io.aequicor.magicpaper.domain.RuntimePhase
import io.aequicor.magicpaper.domain.RuntimeStatus

@Preview(name = "Native engines", group = "Native settings", widthDp = 1000, heightDp = 720)
@Preview(name = "Native engines narrow", group = "Native settings", widthDp = 390, heightDp = 900)
@Preview(name = "Native engines large text", group = "Native settings", widthDp = 390, heightDp = 1000, fontScale = 2f)
@Composable
internal fun NativeEnginesSettingsPreview() = PaperTheme {
    PaperSurface(Modifier.fillMaxSize()) {
        val engines = backendCatalog.descriptors
        PaperScrollColumn(contentPadding = PaddingValues(LocalPaperSpacing.current.lg), verticalArrangement = Arrangement.spacedBy(LocalPaperSpacing.current.md)) {
            NativeEngineChoices(engines, engines.first().engine) {}
            engines.forEachIndexed { index, engine ->
                EngineStatusCard(engine, RuntimeStatus(if (index == 0) RuntimePhase.UNKNOWN else RuntimePhase.READY),
                    preparing = false, canRemove = true, onPrepare = {}, onRemove = {})
            }
        }
    }
}
