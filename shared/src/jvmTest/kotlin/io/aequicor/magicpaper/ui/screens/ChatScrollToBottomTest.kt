package io.aequicor.magicpaper.ui.screens

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.ui.CodingSessionUi
import io.aequicor.magicpaper.ui.theme.MagicPaperTheme
import java.awt.EventQueue
import java.io.File
import kotlin.test.*

/** Exercise the actual button, list and composer in both chat screens. */
@OptIn(ExperimentalComposeUiApi::class)
class ChatScrollToBottomTest {
    private companion object {
        fun <T> onUi(block: () -> T): T {
            if (EventQueue.isDispatchThread()) return block()
            var result: Result<T>? = null
            EventQueue.invokeAndWait { result = runCatching(block) }
            return result!!.getOrThrow()
        }
    }

    private class Chat(val coding: Boolean, long: Boolean = true) : AutoCloseable {
        val lines = mutableStateOf(if (long) 70 else 1)
        private var frame = 0L
        private val scene = onUi { ImageComposeScene(if (coding) 760 else 420, 700) {
            MagicPaperTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    val answer = (1..lines.value).joinToString("\n\n") { "Строка ответа $it" }
                    if (coding) {
                        CodingChat(CodingProject("p", "Проект", "/project", 0),
                            // A long answer now has a bounded preview, so scrolling needs history.
                            CodingSessionUi(CodingSession("s", "p", "Диалог", 0), messages = List(if (long) 12 else 0) {
                                CodingMessage("earlier-$it", CodingRole.USER, "Предыдущее сообщение $it", createdAt = 0)
                            } + listOf(
                                CodingMessage("request", CodingRole.USER, "Проверь проект", createdAt = 0),
                                CodingMessage("answer", CodingRole.AGENT, answer, createdAt = 1))),
                            false, true, { _, _ -> }, {}, { _, _ -> })
                    } else {
                        MessagesList(ChatSession("s", "Диалог", 0, 0, messages = List(if (long) 12 else 0) {
                            ChatMessage("earlier-$it", ChatRole.USER, "Предыдущее сообщение $it", 0)
                        } + listOf(
                            ChatMessage("request", ChatRole.USER, "Проверь проект", 0),
                            ChatMessage("answer", ChatRole.AGENT, answer, 1))), false)
                    }
                }
            }
        } }

        init { render() }
        fun render() { repeat(24) { onUi { scene.render(++frame * 32_000_000L).close() }; Thread.sleep(10) } }
        private fun nodes(): List<SemanticsNode> {
            fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
            return scene.semanticsOwners.flatMap { walk(it.unmergedRootSemanticsNode) }
        }
        fun button() = onUi { nodes().singleOrNull {
            it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("К концу чата") == true
        } }
        fun atEnd() = onUi {
            val range = nodes().mapNotNull { it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) }.first()
            range.value() == range.maxValue()
        }
        fun readEarlier() {
            onUi { scene.sendPointerEvent(PointerEventType.Scroll, Offset(100f, 250f), scrollDelta = Offset(0f, -12f)) }
            render()
        }
        fun clickArrow() {
            val point = assertNotNull(button()).boundsInRoot.center
            onUi {
                scene.sendPointerEvent(PointerEventType.Press, point)
                scene.sendPointerEvent(PointerEventType.Release, point)
            }
            render()
        }
        fun append() { onUi { lines.value += 8 }; render() }
        fun snapshot() = onUi {
            val directory = File("build/reports/chat-scroll-to-bottom").apply { mkdirs() }
            scene.render(++frame * 32_000_000L).use { rendered ->
                rendered.encodeToData()!!.use { data ->
                    File(directory, if (coding) "coding.png" else "chat.png").writeBytes(data.bytes)
                }
            }
        }
        override fun close() = onUi { scene.close() }
    }

    @Test fun arrowReturnsToTheBottomOfLongAnswersAndResumesFollowingInBothScreens() {
        for (coding in listOf(false, true)) Chat(coding).use { chat ->
            if (!chat.atEnd()) chat.clickArrow()
            assertTrue(chat.atEnd(), "The arrow reaches the end from the initial position")
            assertNull(chat.button(), "The arrow is hidden at the end")
            chat.readEarlier()
            assertFalse(chat.atEnd())
            assertNotNull(chat.button(), "Reading earlier output exposes the arrow")
            chat.append()
            assertFalse(chat.atEnd(), "New output must not interrupt reading")
            chat.snapshot()
            chat.clickArrow()
            assertTrue(chat.atEnd(), "Jump to the bottom of the answer, including the composer inset")
            assertNull(chat.button())
            chat.append()
            assertTrue(chat.atEnd(), "Clicking the arrow resumes following new output")
            assertNull(chat.button())
        }
    }

    @Test fun shortChatsDoNotShowAnArrow() {
        for (coding in listOf(false, true)) Chat(coding, long = false).use { chat ->
            assertTrue(chat.atEnd())
            assertNull(chat.button())
        }
    }
}
