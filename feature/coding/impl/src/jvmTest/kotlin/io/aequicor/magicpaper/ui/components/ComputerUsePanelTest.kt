package io.aequicor.magicpaper.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.use
import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.ui.theme.MagicPaperTheme
import java.io.File
import kotlin.test.*

class ComputerUsePanelTest {
    @Test fun stopRemainsAvailableWhileComputerIsBusy() {
        var stopped = false
        var captured = false
        ImageComposeScene(390, 200) {
            MagicPaperTheme { Surface { ComputerUsePanel(
                ComputerUseState("s", ComputerAccess.CONTROL, busy = true, detail = "Снимок экрана · 1600 × 900"),
                "s", running = true, onEnable = { error("Must not enable implicitly") },
                onDisable = { stopped = true }, onPreview = { captured = true }, onSettings = {},
            ) } }
        }.use { scene ->
            fun render() { repeat(10) { scene.render(it * 32_000_000L).close(); Thread.sleep(15) } }
            render()
            assertFalse(stopped)
            scene.sendPointerEvent(PointerEventType.Press, Offset(335f, 28f))
            scene.sendPointerEvent(PointerEventType.Release, Offset(335f, 28f))
            render()
            assertTrue(stopped, "Stop must stay clickable during input/capture")
            assertFalse(captured)
            val output = File("build/reports/computer-use").apply { mkdirs() }
            File(output, "panel-narrow.png").writeBytes(scene.render(800_000_000L).use { it.encodeToData()!!.use { data -> data.bytes } })
        }
    }
}
