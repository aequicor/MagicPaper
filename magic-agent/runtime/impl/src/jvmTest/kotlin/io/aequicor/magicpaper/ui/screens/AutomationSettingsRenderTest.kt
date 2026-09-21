package io.aequicor.magicpaper.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.*
import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.ui.theme.MagicPaperTheme
import java.io.File
import kotlin.test.*

@OptIn(ExperimentalComposeUiApi::class)
class AutomationSettingsRenderTest {
    @Test fun independentChoicesWrapStayAccessibleAndDoNotGrantOnRender() {
        val out = File("build/reports/automation-settings").apply { mkdirs() }
        for ((name, width, scale) in listOf(Triple("narrow", 390, 1f), Triple("wide", 900, 1f), Triple("large-text", 390, 1.5f),
            Triple("unavailable", 390, 1f), Triple("saving", 390, 1f))) {
            var changes = 0
            var settings by mutableStateOf(AppSettings())
            ImageComposeScene(width, 1300, density = Density(1f, scale)) {
                MagicPaperTheme { Surface {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                        AutomationSettings(settings, computerEnabled = name != "unavailable", applicationEnabled = name != "unavailable",
                            saving = name == "saving") { settings = it; changes++ }
                    }
                } }
            }.use { scene ->
                fun render() { repeat(6) { scene.render(it * 32_000_000L).close() } }
                fun nodes(): List<SemanticsNode> {
                    fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
                    return scene.semanticsOwners.flatMap { walk(it.unmergedRootSemanticsNode) }
                }
                fun choice(label: String) = nodes().single { label in it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() }
                fun click(label: String) {
                    val point = choice(label).boundsInRoot.center
                    assertTrue(point.x in 0f..width.toFloat() && point.y in 0f..1300f)
                    scene.sendPointerEvent(PointerEventType.Press, point)
                    scene.sendPointerEvent(PointerEventType.Release, point)
                    render()
                }
                render()
                assertEquals(0, changes)
                if (name !in listOf("unavailable", "saving")) {
                    click("Приложение в фоне: Управление")
                    assertEquals(ComputerAccess.CONTROL, settings.applicationAccess)
                    assertEquals(ComputerAccess.OFF, settings.computerAccess)
                    click("Весь компьютер: Просмотр")
                    assertEquals(ComputerAccess.CONTROL, settings.applicationAccess)
                    assertEquals(ComputerAccess.SCREEN, settings.computerAccess)
                    assertEquals(true, choice("Приложение в фоне: Управление").config[SemanticsProperties.Selected])
                    assertEquals(2, changes)
                } else {
                    assertTrue(choice("Приложение в фоне: Управление").config.contains(SemanticsProperties.Disabled))
                    click("Приложение в фоне: Управление")
                    assertEquals(0, changes)
                }
                scene.render(800_000_000L).use { image -> File(out, "$name.png").writeBytes(image.encodeToData()!!.use { it.bytes }) }
            }
        }
    }
}
