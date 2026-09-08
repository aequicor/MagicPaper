package io.aequicor.magicpaper.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.aequicor.magicpaper.domain.RequestPin
import io.aequicor.magicpaper.domain.RequestPinGroup
import io.aequicor.magicpaper.ui.components.*
import io.aequicor.magicpaper.ui.theme.MagicPaperTheme
import java.io.File
import kotlin.math.abs
import kotlin.test.*

class RequestPinsRenderTest {
    private class Chat : AutoCloseable {
        val list = LazyListState()
        val tail = mutableStateOf(900)
        val session = mutableStateOf("first")
        val groups = mutableStateOf(listOf(
            RequestPinGroup(RequestPin("m0", "Добавить закрепления запросов во все чаты", "Пользователь"),
                listOf(RequestPin("m2", "Суммаризацию выполняет общая модель по умолчанию", "Пользователь"),
                    RequestPin("m4", "Показывать только ближайшее уточнение и индикатор его позиции", "Пользователь"))),
            RequestPinGroup(RequestPin("m6", "Проверить работу интерфейса на узком экране", "Оркестратор 1"),
                listOf(RequestPin("m8", "Увеличить шрифт и проверить переход к исходнику", "Пользователь"))),
        ))
        val indices = (0..9).associate { "m$it" to it }
        val tops = mutableMapOf<String, Float>()
        var panelHeight = 0
        lateinit var scroll: ChatScrollState
        private var frame = 0L
        private val scene = ImageComposeScene(420, 640) {
            MagicPaperTheme {
                scroll = stickToBottom(list, session.value)
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    LazyColumn(state = list, contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(10, key = { "m$it" }) { index ->
                            ChatScrollItem(scroll, "m$index") {
                                Box(Modifier.fillMaxWidth().height(220.dp)
                                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                    .onGloballyPositioned { tops["m$index"] = it.positionInRoot().y }) {
                                    Text("Исходное сообщение m$index", Modifier.padding(12.dp))
                                }
                            }
                        }
                        item(key = "draft") { Text("Поток ответа", Modifier.fillMaxWidth().height(tail.value.dp)) }
                    }
                    RequestPinsOverlay(groups.value, indices, list, scroll,
                        Modifier.align(Alignment.TopCenter).onSizeChanged { panelHeight = it.height })
                }
            }
        }

        init { render() }
        fun render() { repeat(16) { scene.render(++frame * 32_000_000L).close(); Thread.sleep(10) } }
        fun click(y: Float) {
            scene.sendPointerEvent(PointerEventType.Press, Offset(160f, y))
            scene.sendPointerEvent(PointerEventType.Release, Offset(160f, y))
            render()
        }
        fun snapshot(name: String) {
            val dir = File("build/reports/request-pins").apply { mkdirs() }
            val rendered = scene.render(++frame * 32_000_000L)
            val data = rendered.encodeToData()!!
            try { File(dir, "$name.png").writeBytes(data.bytes) } finally { data.close(); rendered.close() }
        }
        fun selected(): VisibleRequestPins? = visibleRequestPins(groups.value) { id ->
            val index = indices.getValue(id)
            list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }?.let {
                it.offset < list.layoutInfo.viewportStartOffset
            } ?: (index < list.firstVisibleItemIndex)
        }
        override fun close() = scene.close()
    }

    @Test fun clickingClarificationThenTitleRevealsEarlierGroupAndPausesStreaming() = Chat().use { chat ->
        assertFalse(chat.list.canScrollForward)
        assertEquals("m8", chat.selected()?.clarification?.messageId)
        chat.snapshot("two-blocks")
        chat.click(chat.panelHeight - 22f)
        assertEquals("m8", chat.scroll.highlightedKey)
        assertNull(chat.selected()?.clarification)
        assertEquals("m6", chat.selected()?.group?.request?.messageId)
        val before = chat.tops.getValue("m8")
        assertTrue(before >= chat.panelHeight, "Target must be visible below the remaining panel")
        chat.tail.value += 200
        chat.render()
        assertTrue(abs(before - chat.tops.getValue("m8")) <= 1, "Streaming must not undo pin navigation")
        chat.click(22f)
        assertEquals("m6", chat.scroll.highlightedKey)
        assertEquals("m0", chat.selected()?.group?.request?.messageId)
        assertEquals("m4", chat.selected()?.clarification?.messageId)
        assertTrue(chat.tops.getValue("m6") >= chat.panelHeight, "A taller previous group must not cover the target")
        chat.snapshot("previous-group")
        chat.click(chat.panelHeight - 22f)
        assertEquals("m4", chat.scroll.highlightedKey)
        assertEquals("m2", chat.selected()?.clarification?.messageId)
        assertTrue(chat.tops.getValue("m4") >= chat.panelHeight)
        chat.click(22f)
        assertNull(chat.selected(), "Opening the first request removes the overlay")
        chat.list.dispatchRawDelta(10000f)
        chat.render()
        assertEquals("m8", chat.selected()?.clarification?.messageId)
        assertFalse(chat.list.canScrollForward)
    }

    @Test fun longSourcePinsAsItsStartCrossesViewportAndSummaryUpdateDoesNotMoveReader() = Chat().use { chat ->
        chat.list.dispatchRawDelta(-10000f)
        chat.render()
        assertNull(chat.selected())
        chat.list.dispatchRawDelta(40f)
        chat.render()
        assertEquals("m0", chat.selected()?.group?.request?.messageId)
        assertEquals(0, chat.list.firstVisibleItemIndex, "Most of the original message is still onscreen")
        val offset = chat.list.firstVisibleItemScrollOffset
        chat.groups.value = chat.groups.value.mapIndexed { index, group ->
            if (index == 0) group.copy(request = group.request.copy(summary = "Краткий заголовок")) else group
        }
        chat.render()
        assertEquals(offset, chat.list.firstVisibleItemScrollOffset)
        chat.session.value = "second"
        chat.render()
        assertFalse(chat.list.canScrollForward, "Session switch retains existing open-at-bottom behavior")
    }

    @Test fun manyClarificationsStayWithinTwoBlocksOnNarrowScreenWithLargeFont() {
        val root = RequestPin("root", "Разработать систему закрепления сообщений пользователя во всех чатах", "Пользователь")
        val clarifications = (1..100).map { RequestPin("c$it", "Показывать ближайшее уточнение в одну или две строки и сохранять переход к исходному сообщению", "Пользователь") }
        var panelHeight = 0
        val count = mutableIntStateOf(100)
        val scene = ImageComposeScene(360, 620) {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1.5f)) {
                MagicPaperTheme {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                        RequestPinsPanel(VisibleRequestPins(RequestPinGroup(root, clarifications.take(count.intValue)), count.intValue - 1), {},
                            Modifier.onSizeChanged { panelHeight = it.height })
                    }
                }
            }
        }
        try {
            var frame = 0L
            fun render() { repeat(12) { scene.render(++frame * 32_000_000L).close(); Thread.sleep(10) } }
            render()
            val firstHeight = panelHeight
            assertTrue(panelHeight in 100..260, "A long group must not become a scrolling list: $panelHeight")
            val dir = File("build/reports/request-pins").apply { mkdirs() }
            val rendered = scene.render(++frame * 32_000_000L)
            val data = rendered.encodeToData()!!
            try { File(dir, "narrow-large-font.png").writeBytes(data.bytes) } finally { data.close(); rendered.close() }
            count.intValue = 2
            render()
            assertTrue(abs(panelHeight - firstHeight) <= 4, "Adding clarifications should not add rows")
        } finally { scene.close() }
    }
}
