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
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
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
import java.awt.EventQueue
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.*

class RequestPinsRenderTest {
    // Desktop input and frame dispatch share the UI thread. Mixing render() on the test
    // thread with wheel animation on AWT can deadlock Compose's two frame-clock locks.
    private companion object {
        fun <T> onUi(block: () -> T): T {
            if (EventQueue.isDispatchThread()) return block()
            var result: Result<T>? = null
            EventQueue.invokeAndWait { result = runCatching(block) }
            return result!!.getOrThrow()
        }
    }

    private class Chat(val width: Int = 420) : AutoCloseable {
        val list = LazyListState()
        val tail = mutableStateOf(900)
        val session = mutableStateOf("first")
        val precedingHeight = mutableStateOf(220)
        val prefixCount = mutableStateOf(0)
        val groups = mutableStateOf(listOf(
            RequestPinGroup(RequestPin("m0", "Добавить закрепления запросов во все чаты", "Пользователь"),
                listOf(RequestPin("m2", "Суммаризацию выполняет общая модель по умолчанию", "Пользователь"),
                    RequestPin("m4", "Показывать только ближайшее уточнение и индикатор его позиции", "Пользователь"))),
            RequestPinGroup(RequestPin("m6", "Проверить работу интерфейса на узком экране", "Оркестратор 1"),
                listOf(RequestPin("m8", "Увеличить шрифт и проверить переход к исходнику", "Пользователь"))),
        ))
        val indices get() = (0..9).associate { "m$it" to it + prefixCount.value }
        val tops = mutableMapOf<String, Float>()
        var panelHeight = 0
        lateinit var scroll: ChatScrollState
        private var frame = 0L
        private val scene = onUi { ImageComposeScene(width, 640) {
            MagicPaperTheme {
                scroll = stickToBottom(list, session.value)
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    LazyColumn(state = list, modifier = Modifier.fillMaxSize().chatScrollInput(scroll)
                        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }.requestPinsShade(scroll),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(prefixCount.value, key = { "prefix$it" }) { Text("Новый шаг", Modifier.height(90.dp)) }
                        items(10, key = { "m$it" }) { index ->
                            ChatScrollItem(scroll, "m$index") {
                                Box(Modifier.fillMaxWidth().height(if (index == 7) precedingHeight.value.dp else 220.dp)
                                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                    .onGloballyPositioned { tops["m$index"] = it.positionInRoot().y }) {
                                    Text("Исходное сообщение m$index", Modifier.padding(12.dp))
                                }
                            }
                        }
                        item(key = "draft") { Text("Поток ответа", Modifier.fillMaxWidth().height(tail.value.dp)) }
                    }
                    RequestPinsOverlay(groups.value, indices, list, scroll,
                        Modifier.align(Alignment.TopEnd).onSizeChanged { panelHeight = it.height })
                }
            }
        } }

        init { render() }
        fun render(frames: Int = 16) { repeat(frames) { onUi { scene.render(++frame * 32_000_000L).close() }; Thread.sleep(10) } }
        fun click(y: Float, settle: Boolean = true) {
            onUi {
                scene.sendPointerEvent(PointerEventType.Press, Offset(width - 100f, y))
                scene.sendPointerEvent(PointerEventType.Release, Offset(width - 100f, y))
            }
            if (settle) render(24)
        }
        fun wheel(delta: Float) {
            onUi { scene.sendPointerEvent(PointerEventType.Scroll, Offset(width - 100f, 450f), scrollDelta = Offset(0f, delta)) }
            render()
        }
        fun snapshot(name: String): File = onUi {
            val dir = File("build/reports/request-pins").apply { mkdirs() }
            val rendered = scene.render(++frame * 32_000_000L)
            val data = rendered.encodeToData()!!
            val file = File(dir, "$name.png")
            try { file.writeBytes(data.bytes) } finally { data.close(); rendered.close() }
            file
        }
        fun selected(): VisibleRequestPins? = visibleRequestPins(groups.value) { id ->
            val index = indices.getValue(id)
            list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }?.let {
                it.offset < list.layoutInfo.viewportStartOffset
            } ?: (index < list.firstVisibleItemIndex)
        }
        override fun close() = onUi { scene.close() }
    }

    @Test fun widePanelIsRightAlignedAndOnlyMessagesNearItFade() = Chat(width = 1040).use { chat ->
        chat.click(chat.panelHeight - 22f)
        chat.render()
        val bounds = assertNotNull(chat.scroll.requestPinsBounds)
        assertEquals(360f, bounds.left)
        assertEquals(1040f, bounds.right)
        val pixels = ImageIO.read(chat.snapshot("wide-right-shade"))
        val y = chat.panelHeight
        val unshaded = pixels.getRGB(30, y + 2)
        assertEquals(unshaded, pixels.getRGB(30, y + 18), "Uncovered messages on the left stay unchanged")
        assertEquals(unshaded, pixels.getRGB(990, y + 18), "Below the fade the message is fully visible")
        assertNotEquals(unshaded, pixels.getRGB(990, y + 2), "Content fades as it goes under the pin")
        assertNotEquals(pixels.getRGB(990, y + 2), pixels.getRGB(990, y + 10), "The edge must be gradual")
        chat.list.dispatchRawDelta(-10000f)
        chat.render()
        assertNull(chat.scroll.requestPinsBounds, "Removing the pin must also remove its shade")
    }

    @Test fun readerWheelInterruptsNavigationDuringStreaming() = Chat().use { chat ->
        chat.click(chat.panelHeight - 22f, settle = false)
        chat.render(5)
        assertTrue(chat.scroll.navigating)
        chat.wheel(-2f)
        assertFalse(chat.scroll.navigating)
        val index = chat.list.firstVisibleItemIndex
        val offset = chat.list.firstVisibleItemScrollOffset
        chat.tail.value += 300
        chat.render()
        assertEquals(index, chat.list.firstVisibleItemIndex)
        assertEquals(offset, chat.list.firstVisibleItemScrollOffset)
    }

    @Test fun navigationSettlesOnSourceWhileStreamingChangesHeightsAndRowIndices() = Chat().use { chat ->
        chat.tail.value += 100
        chat.click(chat.panelHeight - 22f, settle = false)
        repeat(10) { frame ->
            chat.tail.value += 80
            chat.precedingHeight.value += 35
            if (frame == 2 || frame == 5) chat.prefixCount.value++
            chat.render(1)
        }
        chat.render()
        val source = chat.list.layoutInfo.visibleItemsInfo.singleOrNull { it.key == "m8" }
        assertNotNull(source, "Navigate by source identity while rows are added")
        assertTrue(chat.tops.getValue("m8") in chat.panelHeight.toFloat()..(chat.panelHeight + 40f),
            "The source must settle just below the pin, not below a growing preceding message: ${chat.tops["m8"]}")
        val top = chat.tops.getValue("m8")
        chat.tail.value += 500
        chat.render()
        assertEquals(top, chat.tops.getValue("m8"), "Bottom following must stay paused")
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
