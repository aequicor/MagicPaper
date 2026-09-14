package io.aequicor.magicpaper.ui.components
import androidx.compose.foundation.lazy.LazyListState
import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.ui.screens.MessagesList
import java.awt.EventQueue


import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.dp
import androidx.compose.ui.use
import io.aequicor.magicpaper.ui.theme.MagicPaperTheme
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.*

@OptIn(ExperimentalComposeUiApi::class)
class ChatLongMessageRenderTest {
    private fun ImageComposeScene.nodes(): List<SemanticsNode> {
        fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
        return semanticsOwners.flatMap { walk(it.unmergedRootSemanticsNode) }
    }
    private fun ImageComposeScene.texts() = nodes().flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }.map { it.text }
    private fun ImageComposeScene.snapshot(name: String) {
        val dir = File("build/reports/long-messages").apply { mkdirs() }
        File(dir, "$name.png").writeBytes(render(5_000_000_000).use { it.encodeToData()!!.use { data -> data.bytes } })
    }

    private fun <T> onUi(block: () -> T): T {
        if (EventQueue.isDispatchThread()) return block()
        var result: Result<T>? = null
        EventQueue.invokeAndWait { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    @Test fun hugeMessagesExpandIntoTheChatListAndCollapseWithoutADialog() {
        val source = "## Проверка длинного ответа\n\n" + "**Текст сообщения** со [ссылкой](https://example.com). ".repeat(12000) + "\n\nКонец полного сообщения"
        val list = LazyListState()
        val session = ChatSession("long", "long", 0, 0, listOf(ChatMessage("message", ChatRole.AGENT, source, 0)))
        val scene = onUi { ImageComposeScene(760, 680) {
            MagicPaperTheme { Surface { MessagesList(session, false, listState = list) } }
        } }
        try {
            var frame = 0L
            fun render() { repeat(15) { onUi { scene.render(++frame * 32_000_000L).close() }; Thread.sleep(5) } }
            fun awaitRender(description: String, condition: () -> Boolean) {
                val deadline = System.nanoTime() + 10_000_000_000L
                while (!onUi(condition) && System.nanoTime() < deadline) render()
                assertTrue(onUi(condition), description)
            }
            fun click(label: String) {
                awaitRender("Visible action: $label") { label in scene.texts() }
                onUi {
                    val button = scene.nodes().single { it.config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } == true }
                    scene.sendPointerEvent(PointerEventType.Press, button.boundsInRoot.center)
                    scene.sendPointerEvent(PointerEventType.Release, button.boundsInRoot.center)
                }
            }
            render()
            assertEquals(1, list.layoutInfo.totalItemsCount)
            assertTrue(list.layoutInfo.visibleItemsInfo.single().size <= 400)
            assertTrue(onUi { scene.texts().sumOf { it.length } } < 8000)
            onUi { scene.snapshot("preview") }
            click("Читать далее")
            render()
            awaitRender("Parsed fragments must belong to the outer chat list") { list.layoutInfo.totalItemsCount > 300 }
            assertEquals(0, list.firstVisibleItemIndex, "Expansion must preserve the beginning instead of following the tail")
            assertEquals(1, scene.semanticsOwners.size, "No modal window")
            assertTrue(onUi { scene.texts().sumOf { it.length } } < 16000, "Only visible text is composed")
            onUi { scene.snapshot("inline-expanded") }
            repeat(4) {
                onUi { list.dispatchRawDelta(500f) }
                render()
                assertTrue(onUi { scene.texts().sumOf { it.length } } < 16000)
            }
            onUi { list.requestScrollToItem(list.layoutInfo.totalItemsCount - 1) }
            render()
            assertTrue(onUi { scene.texts().any { "Конец полного сообщения" in it } })
            click("Свернуть")
            render()
            assertEquals(1, list.layoutInfo.totalItemsCount)
            assertTrue(onUi { "Читать далее" in scene.texts() })
        } finally { onUi { scene.close() } }
    }










}
