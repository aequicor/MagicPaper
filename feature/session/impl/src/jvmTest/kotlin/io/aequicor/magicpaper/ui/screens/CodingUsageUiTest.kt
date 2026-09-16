package io.aequicor.magicpaper.ui.screens

import io.aequicor.magicpaper.ui.theme.MagicPaperTheme
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
class CodingUsageUiTest {
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



    @Test fun contextAndSystemMessagesUseAgentLayoutAndKeepDraftOnResize() {
        for (width in listOf(390, 1000)) {
            val context = mutableStateOf<ContextUsageSnapshot?>(ContextUsageSnapshot("coding:s", "Fixture", 500, 1000, approximate = true))
            val compacting = mutableStateOf(false)
            val draft = CodingComposerDraft().also { it.text.value = "Сохранённый черновик" }
            ImageComposeScene(width, 800) {
                MagicPaperTheme { PaperPanel(Modifier.fillMaxSize()) {
                    Column {
                        Column(Modifier.weight(1f).padding(16.dp)) {
                            PaperSessionContextMessage("system", "Проверенные настройки сессии")
                            PaperSystemMessage { PaperText("Контекст автоматически сжат") }
                        }
                        CodingComposer(state = draft, enabled = true, busy = false, contextUsage = context.value,
                            contextCompacting = compacting.value,
                            controls = { PaperText("Fixture") }, onSend = { _, _ -> }, onAbort = {}, onPickAttachments = { _, _ -> })
                    }
                } }
            }.use { scene ->
                repeat(5) { scene.render(tick()).close() }
                val indicator = scene.label("Заполненность контекста")
                assertEquals("≈50%", indicator.config.getOrNull(SemanticsProperties.StateDescription))
                assertTrue(indicator.boundsInRoot.right <= width)
                val system = scene.nodes().first { it.text() == "Контекст автоматически сжат" }
                assertTrue(system.boundsInRoot.left < width / 2)
                scene.snapshot("chat-$width")
                assertFalse(scene.nodes().any { it.text() == "Проверенные настройки сессии" })
                scene.click(scene.nodes().first { it.hasText("Показать контекст") && it.config.getOrNull(SemanticsActions.OnClick) != null })
                assertTrue(scene.nodes().any { it.text() == "Проверенные настройки сессии" })
                scene.click(scene.nodes().first { it.hasText("Свернуть") && it.config.getOrNull(SemanticsActions.OnClick) != null })
                scene.click(indicator)
                assertTrue(scene.nodes().any { it.text().contains("Приблизительная оценка") })
                assertEquals("Сохранённый черновик", draft.text.value)
                context.value = null
                repeat(5) { scene.render(tick()).close() }
                assertEquals("—", scene.label("Заполненность контекста").config.getOrNull(SemanticsProperties.StateDescription))
                context.value = ContextUsageSnapshot("coding:s", "Fixture", 500, 1000, approximate = true)
                compacting.value = true
                repeat(5) { scene.render(tick()).close() }
                assertEquals("Сжатие контекста", scene.label("Заполненность контекста").config.getOrNull(SemanticsProperties.StateDescription))
                scene.snapshot("chat-compacting-$width")
            }
        }
    }
}
