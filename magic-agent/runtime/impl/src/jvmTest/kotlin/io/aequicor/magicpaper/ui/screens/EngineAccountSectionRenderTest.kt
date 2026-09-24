package io.aequicor.magicpaper.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.use
import io.aequicor.magicpaper.data.coding.backendCatalog
import io.aequicor.magicpaper.designsystem.PaperDivider
import io.aequicor.magicpaper.designsystem.PaperSurface
import io.aequicor.magicpaper.domain.CodingEngine
import io.aequicor.magicpaper.domain.RuntimePhase
import io.aequicor.magicpaper.domain.RuntimeStatus
import io.aequicor.magicpaper.ui.theme.MagicPaperTheme
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/** The Claude Code account beside the ChatGPT subscription; renders go to `build/reports/engine-account` for inspection. */
class EngineAccountSectionRenderTest {
    @Test fun accountStatesRenderAtNarrowAndDesktopWidths() {
        val claude = backendCatalog.descriptors.single { it.engine == CodingEngine.CLAUDE_CODE }
        val output = File("build/reports/engine-account").apply { mkdirs() }
        for (width in listOf(360, 720)) {
            ImageComposeScene(width, 760) {
                MagicPaperTheme {
                    PaperSurface {
                        Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            EngineAccountSection(claude, RuntimeStatus(RuntimePhase.READY, signedIn = false), false, {}, {}, {})
                            PaperDivider()
                            EngineAccountSection(claude, RuntimeStatus(RuntimePhase.READY, signedIn = false), true, {}, {}, {})
                            PaperDivider()
                            EngineAccountSection(claude, RuntimeStatus(RuntimePhase.READY, signedIn = true), false, {}, {}, {})
                        }
                    }
                }
            }.use { scene ->
                repeat(4) { scene.render(it * 16_000_000L).close() }
                val file = File(output, "account-$width.png")
                file.writeBytes(scene.render(80_000_000L).use { it.encodeToData()!!.use { data -> data.bytes } })
                assertTrue(file.length() > 0)
            }
        }
    }
}
