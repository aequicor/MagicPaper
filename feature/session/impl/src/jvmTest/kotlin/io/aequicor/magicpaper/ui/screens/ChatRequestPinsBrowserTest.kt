package io.aequicor.magicpaper.ui.screens

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import io.aequicor.magicpaper.designsystem.PaperResearchReading
import io.aequicor.magicpaper.designsystem.PaperTheme
import io.aequicor.magicpaper.domain.*
import java.awt.EventQueue
import kotlin.test.*

/** Research uses ordinary scrolling messages; coding keeps its separate pin browser. */
@OptIn(ExperimentalComposeUiApi::class)
class ChatRequestPinsBrowserTest {
    private fun <T> onUi(block: () -> T): T {
        if (EventQueue.isDispatchThread()) return block()
        var result: Result<T>? = null
        EventQueue.invokeAndWait { result = runCatching(block) }
        return result!!.getOrThrow()
    }
    @Test fun userMessagesScrollAwayWithoutPinsOrStickyCopies() {
        for (scale in listOf(1f, 2f)) {
            val list = LazyListState()
            val scene = onUi { ImageComposeScene(620, 700) {
                CompositionLocalProvider(LocalDensity provides Density(1f, scale),
                    io.aequicor.magicpaper.ui.components.LocalChatPresentation provides io.aequicor.magicpaper.ui.components.DefaultChatPresentation) {
                    PaperTheme { PaperResearchReading {
                        MessagesList(ChatSession("research", "Исследование", 0, 0, messages = listOf(
                            ChatMessage("request", ChatRole.USER, "Обычный вопрос без закрепления", 0),
                            ChatMessage("answer", ChatRole.AGENT,
                                (1..80).joinToString("\n\n") { "Параграф ответа $it, который продолжается ниже." }, 1))),
                            false, listState = list)
                    } }
                }
            } }
            var frame = 0L
            fun render() { repeat(24) { onUi { scene.render(++frame * 32_000_000L).close() }; Thread.sleep(5) } }
            fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
            fun nodes() = scene.semanticsOwners.flatMap { walk(it.unmergedRootSemanticsNode) }
            fun questions() = nodes().filter { it.config.getOrNull(SemanticsProperties.Text).orEmpty()
                .any { text -> text.text == "Обычный вопрос без закрепления" } }
            try {
                render()
                // Enter reader-controlled scrolling before asking the lazy list to
                // locate the first message; initial automatic end-following is async.
                onUi { scene.sendPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Scroll,
                    androidx.compose.ui.geometry.Offset(300f, 250f), scrollDelta = androidx.compose.ui.geometry.Offset(0f, -2f)) }
                render()
                onUi { list.requestScrollToItem(0) }; render()
                onUi {
                    assertEquals(1, questions().size, "scale=$scale, anchor=${list.firstVisibleItemIndex}:${list.firstVisibleItemScrollOffset}, items=${list.layoutInfo.totalItemsCount}, text=${nodes().flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }.take(4)}")
                    assertFalse(nodes().any { node -> node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
                        .any { it.contains("закреп", ignoreCase = true) } })
                }
                onUi { list.requestScrollToItem(25) }; render()
                onUi { assertTrue(questions().isEmpty(), "The original question must scroll out of view") }
            } finally { onUi { scene.close() } }
        }
    }
}
