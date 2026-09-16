package io.aequicor.magicpaper.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.use
import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.ui.theme.MagicPaperTheme
import java.io.File
import kotlin.test.*

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
class ComputerUsePanelTest {
    @Test fun backgroundPanelOffersStopButNeverCapturesDesktopAndShowsPermissionRecovery() {
        for (failure in listOf(false, true)) for (scale in listOf(1f, 1.5f)) {
            var stopped = 0
            var settings = 0
            ImageComposeScene(390, 600, density = Density(1f, scale)) {
                MagicPaperTheme { Surface { renderComputerUsePanel(
                    ComputerUseState("s", applicationAccess = ComputerAccess.CONTROL, busy = !failure, error = failure,
                        detail = if (failure) "Разрешите запись экрана в системных настройках, затем отправьте новый запрос." else "Приложение в фоне"),
                    "s", true, { error("Must not grant") }, { stopped++ }, { error("Must not capture desktop") }, { settings++ },
                ) } }
            }.use { scene ->
                fun render() { repeat(6) { scene.render(it * 32_000_000L).close() } }
                fun texts(): List<SemanticsNode> {
                    fun walk(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::walk)
                    return scene.semanticsOwners.flatMap { walk(it.unmergedRootSemanticsNode) }
                }
                fun label(text: String) = texts().filter { it.config.getOrNull(SemanticsProperties.Text)?.any { s -> s.text == text } == true }
                fun click(text: String) {
                    val point = label(text).single().boundsInRoot.center
                    scene.sendPointerEvent(PointerEventType.Press, point); scene.sendPointerEvent(PointerEventType.Release, point); render()
                }
                render()
                assertEquals(0, stopped)
                assertTrue(label("Снимок").isEmpty())
                click("Отключить")
                assertEquals(1, stopped)
                if (failure) { click("Системные настройки"); assertEquals(1, settings) }
                else assertTrue(label("Системные настройки").isEmpty())
                val output = File("build/reports/computer-use").apply { mkdirs() }
                scene.render(800_000_000L).use { File(output, "application-$failure-$scale.png").writeBytes(it.encodeToData()!!.use { data -> data.bytes }) }
            }
        }
    }

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
