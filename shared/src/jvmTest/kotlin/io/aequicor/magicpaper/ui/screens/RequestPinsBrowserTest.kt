package io.aequicor.magicpaper.ui.screens

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.ui.CodingSessionUi
import io.aequicor.magicpaper.ui.components.requestPinEntries
import io.aequicor.magicpaper.ui.components.requestPinNumbers
import io.aequicor.magicpaper.ui.theme.MagicPaperTheme
import java.awt.EventQueue
import java.io.File
import kotlin.test.*

/** Real chat screens: the marker belongs to a saved LazyColumn message, never the sticky panel. */
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
        fun pin(id: String) = RequestPin(id, "Сводка $id", "Пользователь")
        fun body(index: Int) = if (index % 2 == 0) "Исходное сообщение m$index\nПроверяем закрепление в ленте чата."
            else "Ответ агента m$index"
        fun isIndicator(node: SemanticsNode) = description(node).startsWith("Закреплённое сообщение №")
    }

    private class Chat(val coding: Boolean, val width: Int = 420, fontScale: Float = 1f, val messageCount: Int = 10) : AutoCloseable {
        val session = mutableStateOf("first")
        val tailCount = mutableIntStateOf(0)
        val groups = mutableStateOf(if (messageCount > 10) listOf(
            RequestPinGroup(pin("m0"), (2 until messageCount step 2).map { pin("m$it") })
        ) else listOf(
            RequestPinGroup(pin("m0"), listOf(pin("m2"))),
            RequestPinGroup(pin("m6"), listOf(pin("m8"))),
        ))
        private var frame = 0L
        private val scene = onUi { ImageComposeScene(width, 780) {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                MagicPaperTheme {
                    Surface(color = MaterialTheme.colorScheme.background) {
                        if (coding) {
                            CodingChat(CodingProject("p", "Проект", "/project", 0),
                                CodingSessionUi(CodingSession(session.value, "p", "Диалог", 0),
                                    messages = List(messageCount + tailCount.intValue) { index ->
                                        CodingMessage("m$index", if (index < messageCount && index % 2 == 0) CodingRole.USER else CodingRole.AGENT,
                                            body(index), createdAt = index.toLong())
                                    }), false, true, { _, _ -> }, {}, { _, _ -> }, pins = groups.value)
                        } else {
                            MessagesList(ChatSession(session.value, "Диалог", 0, 0,
                                messages = List(messageCount + tailCount.intValue) { index ->
                                    ChatMessage("m$index", if (index < messageCount && index % 2 == 0) ChatRole.USER else ChatRole.AGENT,
                                        body(index), index.toLong())
                                }), false, pins = groups.value)
                        }
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
        fun indicator(number: Int) = nodes().single { description(it) == "Закреплённое сообщение №$number. Открыть список" }
        fun history() = nodes().first { it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) != null }
        fun position() = onUi { history().config[SemanticsProperties.VerticalScrollAxisRange].value() }
        fun scrollTo(index: Int) {
            onUi { assertTrue(history().config[SemanticsActions.ScrollToIndex].action!!.invoke(index + if (coding) 1 else 0)) }
            render()
        }
        fun click(node: SemanticsNode) {
            onUi {
                val point = node.boundsInRoot.center
                scene.sendPointerEvent(PointerEventType.Press, point)
                scene.sendPointerEvent(PointerEventType.Release, point)
            }
            render()
        }
        fun clickText(label: String) = click(nodes().last { text(it) == label })
        fun snapshot(name: String) = onUi {
            val file = File("build/reports/request-pins/$name-${if (coding) "coding" else "chat"}.png").apply { parentFile.mkdirs() }
            scene.render(++frame * 32_000_000L).use { image -> image.encodeToData()!!.use { file.writeBytes(it.bytes) } }
        }
        override fun close() = onUi { scene.close() }
    }

    @Test fun messageButtonOpensItsOwnPinAndSelectingAnotherPinRevealsTheSourceInBothChats() {
        for (coding in listOf(false, true)) Chat(coding).use { chat ->
            val marker = chat.indicator(4)
            assertTrue(marker.boundsInRoot.height >= 48f)
            assertTrue(walk(chat.history()).any { it.id == marker.id }, "The button must be inside LazyColumn")
            val source = chat.nodes().single { text(it) == body(8) }
            assertTrue(marker.boundsInRoot.top >= source.boundsInRoot.bottom, "The marker belongs below its message text")
            chat.snapshot("message-indicator")
            val before = chat.position()
            chat.click(marker)
            assertTrue(chat.hasText("Закреплённые сообщения"))
            assertTrue(chat.hasText("Всего: 4"))
            assertTrue(chat.nodes().any { it.config.getOrNull(SemanticsProperties.Selected) == true &&
                walk(it).any { child -> text(child) == "Сводка m8" } }, "Select the message whose button was clicked")
            assertEquals(before, chat.position())
            chat.clickText("Закрыть")
            assertEquals(before, chat.position())
            chat.click(chat.indicator(4))
            chat.clickText("Сводка m0")
            assertFalse(chat.hasText("Закреплённые сообщения"))
            assertTrue(chat.hasText(body(0)))
            assertTrue(chat.indicator(1).boundsInRoot.top >= 0)
        }
    }

    @Test fun markersScrollAwayWithMessagesAndUnpinnedMessagesHaveNoButton() {
        for (coding in listOf(false, true)) Chat(coding).use { chat ->
            chat.scrollTo(4)
            val source = chat.nodes().single { text(it) == body(4) }.boundsInRoot
            val next = chat.nodes().single { text(it) == body(5) }.boundsInRoot
            assertTrue(chat.nodes().filter(::isIndicator).none { it.boundsInRoot.top in source.top..next.top },
                "An unpinned user message must not show a marker")
            chat.tailCount.intValue = 12
            chat.render()
            chat.scrollTo(21)
            assertTrue(chat.hasText("Сводка m8"), "The sticky clarification remains visible")
            assertTrue(chat.nodes().none(::isIndicator), "No button may remain on the sticky panel or outside the list")
            chat.clickText("Сводка m8")
            assertNotNull(chat.indicator(4), "Navigating back restores the marker on the source")
        }
    }

    @Test fun switchingSessionsOrRemovingTheSelectedPinClosesTheBrowser() {
        for (coding in listOf(false, true)) Chat(coding).use { chat ->
            chat.click(chat.indicator(4))
            chat.session.value = "second"
            chat.render()
            assertFalse(chat.hasText("Закреплённые сообщения"))
            chat.click(chat.indicator(4))
            chat.groups.value = chat.groups.value.dropLast(1)
            chat.render()
            assertFalse(chat.hasText("Закреплённые сообщения"), "A removed source cannot leave an obsolete browser open")
            chat.groups.value = emptyList()
            chat.render()
            assertTrue(chat.nodes().none(::isIndicator))
        }
    }

    @Test fun manyPinsFitOnNarrowScreensWithLargeTextAndKeepTheListLazy() {
        for (coding in listOf(false, true)) Chat(coding, 360, 1.4f, 202).use { chat ->
            val marker = chat.indicator(101)
            val bounds = marker.boundsInRoot
            assertTrue(bounds.left >= 0 && bounds.right <= 360 && bounds.top >= 0 && bounds.bottom <= 780)
            chat.snapshot("message-indicator-narrow")
            chat.click(marker)
            assertTrue(chat.hasText("Всего: 101"))
            assertTrue(chat.hasText("Сводка m200"))
            assertTrue(chat.nodes().count { text(it).startsWith("Сводка m") } < 20)
            chat.snapshot("message-pins-list-narrow")
            chat.clickText("Закрыть")
            assertFalse(chat.hasText("Закреплённые сообщения"))
        }
    }

    @Test fun numbersAndListExcludeMissingSourcesAndKeepRequestOrderWithoutDuplicates() {
        val groups = listOf(RequestPinGroup(pin("a"), listOf(pin("b"), pin("missing"))),
            RequestPinGroup(pin("c"), listOf(pin("b"))))
        val entries = requestPinEntries(groups, setOf("a", "b", "c"))
        assertEquals(listOf("a", "b", "c"), entries.map { it.pin.messageId })
        assertEquals(listOf(true, false, true), entries.map { it.isRequest })
        assertEquals(mapOf("a" to 1, "b" to 2, "c" to 3), requestPinNumbers(groups, setOf("a", "b", "c")))
        assertTrue(requestPinEntries(groups, emptySet()).isEmpty())
    }
}
