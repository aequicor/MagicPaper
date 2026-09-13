package io.aequicor.magicpaper.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import io.aequicor.magicpaper.designsystem.*
import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.ui.CodingSessionUi
import io.aequicor.magicpaper.ui.components.*
import java.io.File
import kotlin.test.*

@OptIn(ExperimentalComposeUiApi::class)
class UsageUiTest {
    private var frame = 0L
    private fun tick(): Long { frame += 100_000_000; return frame }
    private fun SemanticsNode.hasText(value: String): Boolean = text() == value || children.any { it.hasText(value) }
    private fun ImageComposeScene.nodes(): List<SemanticsNode> {
        fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
        return semanticsOwners.flatMap { walk(it.unmergedRootSemanticsNode) }
    }
    private fun SemanticsNode.text() = config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }.orEmpty()
    private fun ImageComposeScene.label(value: String) = nodes().first { it.config.getOrNull(SemanticsProperties.ContentDescription)?.any { name -> name.startsWith(value) } == true }
    private fun ImageComposeScene.click(node: SemanticsNode) {
        assertEquals(true, node.config.getOrNull(SemanticsActions.OnClick)?.action?.invoke())
        repeat(5) { render(tick()).close() }
    }
    private fun ImageComposeScene.snapshot(name: String) {
        val file = File("build/reports/usage/$name.png"); file.parentFile.mkdirs()
        render(tick()).use { image -> file.writeBytes(checkNotNull(image.encodeToData()).bytes) }
    }

    @Test fun menuSupportsScopeAndPeriodSwitchesOnNarrowAndWideScreens() {
        val now = io.aequicor.magicpaper.util.Id.now()
        val archive = UsageArchive(records = listOf(
            UsageRecord("a", now, UsageScope.chat("a"), provider = "Fixture", model = "Model A", tokens = TokenUsage(100, 20, 50, 10), cost = UsageCost(.003)),
            UsageRecord("b", now - 3 * 86400000, UsageScope.chat("b"), provider = "Fixture", model = "Model B", tokens = TokenUsage(200, 30))))
        for (width in listOf(390, 1000)) ImageComposeScene(width, 700) {
            PaperTheme { PaperPanel(Modifier.fillMaxSize()) { Row { UsageMenu(archive, "chat:a") } } }
        }.use { scene ->
            repeat(5) { scene.render(tick()).close() }
            scene.click(scene.label("Расходы"))
            assertTrue(scene.nodes().any { it.text() == "Model B" || it.text().contains("Model B") })
            assertTrue(scene.nodes().any { it.text() == "Известная стоимость" })
            scene.snapshot("menu-$width")
            scene.click(scene.nodes().first { it.hasText("Текущий чат") && it.config.getOrNull(SemanticsActions.OnClick) != null })
            assertFalse(scene.nodes().any { it.text().contains("Model B") })
            scene.click(scene.nodes().first { it.hasText("Всё приложение") && it.config.getOrNull(SemanticsActions.OnClick) != null })
            scene.click(scene.nodes().first { it.hasText("Сегодня") && it.config.getOrNull(SemanticsActions.OnClick) != null })
            assertFalse(scene.nodes().any { it.text().contains("Model B") })
        }
    }


}
