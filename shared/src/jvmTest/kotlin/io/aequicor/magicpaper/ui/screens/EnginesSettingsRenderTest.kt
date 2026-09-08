package io.aequicor.magicpaper.ui.screens

import androidx.compose.material3.Surface
import androidx.compose.ui.*
import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.ui.ModelSettingsFixture
import io.aequicor.magicpaper.ui.theme.MagicPaperTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import java.io.File
import kotlin.test.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EnginesSettingsRenderTest {
    @Test fun engineControlsAndCreationDialogRenderAtNarrowAndDesktopWidths() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val vm = ModelSettingsFixture().prepare()
            val output = File("build/reports/engines").apply { mkdirs() }
            val state = vm.state.value.let { it.copy(openAiSubscription = it.openAiSubscription.copy(available = true), coding = it.coding.copy(engines = mapOf(
                CodingEngine.PI to RuntimeStatus(RuntimePhase.READY, "Зависимости установлены", "0.84.4"),
                CodingEngine.CODEX to RuntimeStatus(RuntimePhase.READY, "Codex app-server доступен")))) }
            for (width in listOf(390, 1000)) {
                ImageComposeScene(width, 1100) { MagicPaperTheme { Surface { EnginesSettings(vm, state) } } }.use { scene ->
                    repeat(4) { scene.render(it * 16_000_000L).close() }
                    scene.render(80_000_000L).use { image -> File(output, "settings-$width.png").writeBytes(image.encodeToData()!!.use { it.bytes }) }
                }
                ImageComposeScene(width, 650) { MagicPaperTheme { Surface { NewCodingSessionDialog(CodingEngine.CODEX, {}, {}) } } }.use { scene ->
                    repeat(4) { scene.render(it * 16_000_000L).close() }
                    scene.render(80_000_000L).use { image -> File(output, "new-session-$width.png").writeBytes(image.encodeToData()!!.use { it.bytes }) }
                }
            }
        } finally { Dispatchers.resetMain() }
    }
}
