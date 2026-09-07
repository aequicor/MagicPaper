package io.aequicor.magicpaper.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.use
import io.aequicor.magicpaper.domain.CodingDraft
import io.aequicor.magicpaper.ui.theme.MagicPaperTheme
import java.io.File
import kotlin.test.*

class AgentMessageStatusTest {
    @Test fun clickExpandsAndLiveThinkingUpdatesWithoutCollapsing() {
        val draft = mutableStateOf(CodingDraft(thinking = "**Проверяю интерфейс**\n\nПроверяю расположение кнопки.", active = true))
        val expanded = mutableStateOf(false)
        var height = 0
        ImageComposeScene(390, 320) {
            MagicPaperTheme { Column(Modifier.fillMaxWidth().onSizeChanged { height = it.height }) {
                AgentMessageStatus(draft.value, expanded.value) { expanded.value = !expanded.value }
            } }
        }.use { scene ->
            var frame = 0L
            fun render() { repeat(20) { scene.render(++frame * 32_000_000L).close(); Thread.sleep(25) } }
            fun toggle() {
                scene.sendPointerEvent(PointerEventType.Press, Offset(100f, 32f))
                scene.sendPointerEvent(PointerEventType.Release, Offset(100f, 32f))
                render()
            }
            render()
            val collapsedHeight = height
            toggle()
            assertTrue(expanded.value)
            assertTrue(height > collapsedHeight)
            val firstHeight = height
            draft.value = draft.value.copy(thinking = "**Проверяю интерфейс**\n\nПроверяю расположение кнопки.\n\nНашёл обработчик клика.\n\nСопоставляю его с текущим состоянием панели.")
            render()
            assertTrue(expanded.value)
            assertTrue(height > firstHeight)
            val output = File("build/reports/agent-status").apply { mkdirs() }
            File(output, "expanded.png").writeBytes(scene.render(++frame * 16_000_000L).use { it.encodeToData()!!.use { data -> data.bytes } })
            toggle()
            assertFalse(expanded.value)
            assertTrue(height < firstHeight)
        }
    }
}
