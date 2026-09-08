package io.aequicor.magicpaper.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.aequicor.magicpaper.domain.RequestPin
import io.aequicor.magicpaper.domain.RequestPinGroup
import io.aequicor.magicpaper.ui.components.*
import io.aequicor.magicpaper.ui.theme.MagicPaperTheme
import java.awt.EventQueue
import java.io.File
import kotlin.test.*

@OptIn(ExperimentalComposeUiApi::class)
class RequestPinsBrowserTest {
    private companion object {
        fun <T> onUi(block: () -> T): T {
            if (EventQueue.isDispatchThread()) return block()
            var result: Result<T>? = null
            EventQueue.invokeAndWait { result = runCatching(block) }
            return result!!.getOrThrow()
        }
        fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
        fun text(node: SemanticsNode) = node.config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString(" ") { it.text }
        fun description(node: SemanticsNode) = node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().joinToString(" ")
        fun pin(id: String) = RequestPin(id, "Сообщение $id", "Пользователь")
    }

    private class Chat(val width: Int = 420, fontScale: Float = 1f, messageCount: Int = 10) : AutoCloseable {
        val list = LazyListState()
        val session = mutableStateOf("first")
        val groups = mutableStateOf(if (messageCount > 10) listOf(
            RequestPinGroup(pin("m0"), (1 until messageCount).map { pin("m$it") })
        ) else listOf(
            RequestPinGroup(pin("m0"), listOf(pin("m2"), pin("m4"))),
            RequestPinGroup(pin("m6"), listOf(pin("m8"))),
        ))
        lateinit var scroll: ChatScrollState
        private var frame = 0L
        private val scene = onUi { ImageComposeScene(width, 640) {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                MagicPaperTheme {
                    scroll = stickToBottom(list, session.value)
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                        LazyColumn(state = list, modifier = Modifier.fillMaxSize().chatScrollInput(scroll),
                            contentPadding = PaddingValues(16.dp)) {
                            items(messageCount, key = { "m$it" }) { index ->
                                ChatScrollItem(scroll, "m$index") {
                                    Text("Исходное сообщение m$index", Modifier.fillMaxWidth().height(200.dp))
                                }
                            }
                            item { Spacer(Modifier.height(700.dp)) }
                        }
                        RequestPinsOverlay(groups.value, (0 until messageCount).associate { "m$it" to it }, list, scroll,
                            Modifier.align(Alignment.TopEnd))
                    }
                }
            }
        } }
        init { render() }
        fun render(frames: Int = 24) {
            repeat(frames) { onUi { scene.render(++frame * 32_000_000L).close() }; Thread.sleep(10) }
        }
        fun nodes() = onUi { scene.semanticsOwners.flatMap { walk(it.unmergedRootSemanticsNode) } }
        fun hasText(label: String) = nodes().any { text(it) == label }
        fun indicator() = nodes().single { description(it).startsWith("Закреплённые сообщения:") }
        fun click(node: SemanticsNode) {
            onUi {
                val point = node.boundsInRoot.center
                scene.sendPointerEvent(PointerEventType.Press, point)
                scene.sendPointerEvent(PointerEventType.Release, point)
            }
            render()
        }
        // Dialog content follows the underlying panel, which can contain the same summary.
        fun clickText(label: String) = click(nodes().last { text(it) == label })
        fun snapshot(name: String) = onUi {
            val file = File("build/reports/request-pins/$name.png").apply { parentFile.mkdirs() }
            scene.render(++frame * 32_000_000L).use { image ->
                image.encodeToData()!!.use { file.writeBytes(it.bytes) }
            }
        }
        override fun close() = onUi { scene.close() }
    }

    @Test fun indicatorOpensAndClosesListWithoutNavigatingThenSelectionRevealsSource() = Chat().use { chat ->
        assertTrue(description(chat.indicator()).contains(": 5."))
        assertTrue(chat.indicator().boundsInRoot.height >= 48f)
        val before = chat.list.firstVisibleItemIndex to chat.list.firstVisibleItemScrollOffset
        chat.snapshot("indicator")
        chat.click(chat.indicator())
        assertTrue(chat.hasText("Закреплённые сообщения"))
        assertTrue(chat.hasText("Всего: 5"))
        assertEquals(before, chat.list.firstVisibleItemIndex to chat.list.firstVisibleItemScrollOffset)
        assertNull(chat.scroll.highlightedKey)
        chat.snapshot("pins-list")
        chat.clickText("Закрыть")
        assertFalse(chat.hasText("Закреплённые сообщения"))
        assertEquals(before, chat.list.firstVisibleItemIndex to chat.list.firstVisibleItemScrollOffset)
        chat.click(chat.indicator())
        chat.clickText("Сообщение m6")
        assertFalse(chat.hasText("Закреплённые сообщения"))
        assertEquals("m6", chat.scroll.highlightedKey)
        assertTrue(chat.list.layoutInfo.visibleItemsInfo.any { it.key == "m6" && it.offset >= 0 })
    }

    @Test fun buttonRemainsAtStartAndEmptyPinsOrSessionSwitchDismissTheList() = Chat().use { chat ->
        onUi { chat.list.dispatchRawDelta(-10000f) }
        chat.render()
        assertNull(chat.scroll.requestPinsBounds)
        chat.click(chat.indicator())
        assertTrue(chat.hasText("Сообщение m0"))
        chat.clickText("Сообщение m2")
        assertEquals("m2", chat.scroll.highlightedKey)
        chat.click(chat.indicator())
        chat.session.value = "second"
        chat.render()
        assertFalse(chat.hasText("Закреплённые сообщения"), "An open list must not leak into another chat")
        chat.click(chat.indicator())
        chat.groups.value = emptyList()
        chat.render()
        assertFalse(chat.hasText("Закреплённые сообщения"))
        assertTrue(chat.nodes().none { description(it).startsWith("Закреплённые сообщения:") })
        assertNull(chat.scroll.requestPinsBounds)
    }

    @Test fun narrowScreenWithLargeFontKeepsButtonAndDialogActionsInsideViewport() = Chat(320, 1.5f).use { chat ->
        val button = chat.indicator().boundsInRoot
        assertTrue(button.left >= 0 && button.right <= 320)
        chat.snapshot("indicator-narrow")
        chat.click(chat.indicator())
        assertTrue(chat.hasText("Всего: 5"))
        for (label in listOf("Закреплённые сообщения", "Закрыть", "Сообщение m8")) {
            val bounds = chat.nodes().first { text(it) == label }.boundsInRoot
            assertTrue(bounds.left >= 0 && bounds.right <= 320 && bounds.top >= 0 && bounds.bottom <= 640,
                "$label must fit the viewport: $bounds")
        }
        chat.snapshot("pins-list-narrow")
        chat.clickText("Закрыть")
        assertFalse(chat.hasText("Закреплённые сообщения"))
    }

    @Test fun countAndListExcludeMissingSourcesAndKeepRequestOrderWithoutDuplicates() {
        val groups = listOf(RequestPinGroup(pin("a"), listOf(pin("b"), pin("missing"))),
            RequestPinGroup(pin("c"), listOf(pin("b"))))
        val entries = requestPinEntries(groups, setOf("a", "b", "c"))
        assertEquals(listOf("a", "b", "c"), entries.map { it.pin.messageId })
        assertEquals(listOf(true, false, true), entries.map { it.isRequest })
        assertTrue(requestPinEntries(groups, emptySet()).isEmpty())
    }

    @Test fun manyPinsUseABoundedListAndKeepTheCurrentClarificationReachable() = Chat(320, 1.5f, 101).use { chat ->
        assertTrue(description(chat.indicator()).contains(": 101."))
        assertTrue(chat.indicator().boundsInRoot.right <= 320)
        chat.snapshot("indicator-many")
        chat.click(chat.indicator())
        assertTrue(chat.hasText("Всего: 101"))
        assertTrue(chat.hasText("Сообщение m100"))
        assertTrue(chat.nodes().count { text(it).startsWith("Сообщение m") } < 20,
            "The browser must compose only the visible part of a long history")
        chat.snapshot("pins-list-many")
        chat.clickText("Сообщение m100")
        assertEquals("m100", chat.scroll.highlightedKey)
        assertFalse(chat.hasText("Закреплённые сообщения"))
    }
}
