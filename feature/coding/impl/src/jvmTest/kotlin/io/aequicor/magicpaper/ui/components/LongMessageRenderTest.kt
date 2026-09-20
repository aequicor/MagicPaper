package io.aequicor.magicpaper.ui.components
import androidx.compose.foundation.lazy.LazyListState
import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.ui.screens.CodingChat
import io.aequicor.magicpaper.ui.CodingSessionUi
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
class LongMessageRenderTest {
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



    @Test fun codingAnswerAndMegabyteUserMessageExpandLazilyInTheOuterTimeline() {
        for (role in listOf(CodingRole.USER, CodingRole.AGENT)) {
            val source = "Строка сообщения 😀 ".repeat(60000) + "\n\nПоследняя строка"
            val message = if (role == CodingRole.USER) CodingMessage("message", role, source, createdAt = 0)
                else CodingMessage("message", role, "", createdAt = 0,
                    steps = listOf(CodingStep(CodingStepKind.ANSWER, source, id = "answer")))
            val state = androidx.compose.runtime.mutableStateOf(CodingSessionUi(CodingSession("test", "p", "Test", 0),
                messages = listOf(message)))
            val list = LazyListState()
            val scene = onUi { ImageComposeScene(760, 700) {
                MagicPaperTheme { CodingChat(CodingProject("p", "Project", "/project", 0), state.value,
                    false, true, { _, _ -> }, {}, { _, _ -> }, listState = list) }
            } }
            try {
                var frame = 0L
                fun render() { repeat(30) { onUi { scene.render(++frame * 32_000_000L).close() }; Thread.sleep(5) } }
                fun click(label: String) = onUi {
                    val node = scene.nodes().singleOrNull { it.config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } == true }
                        ?: error("Missing $label for $role; index=${list.firstVisibleItemIndex}; visible=${scene.texts().map { it.take(60) }}")
                    scene.sendPointerEvent(PointerEventType.Press, node.boundsInRoot.center)
                    scene.sendPointerEvent(PointerEventType.Release, node.boundsInRoot.center)
                }
                render()
                click("Читать далее")
                render()
                assertTrue(list.layoutInfo.totalItemsCount > 500, "$role must be split into lazy items")
                assertTrue(list.firstVisibleItemIndex < 3, "Async expansion must not jump to the bottom")
                assertTrue(onUi { scene.texts().sumOf { it.length } } < 16000)
                onUi { list.dispatchRawDelta(700f) }
                render()
                val index = list.firstVisibleItemIndex
                val offset = list.firstVisibleItemScrollOffset
                // An appended chunk must update the expanded document without resetting its reader.
                onUi {
                    val updated = if (role == CodingRole.USER) message.copy(text = source + "\nНовый хвост")
                        else message.copy(steps = listOf(message.steps.single().copy(title = source + "\nНовый хвост")))
                    state.value = state.value.copy(messages = listOf(updated))
                }
                render()
                assertEquals(index, list.firstVisibleItemIndex)
                assertEquals(offset, list.firstVisibleItemScrollOffset)
                onUi { list.requestScrollToItem(list.layoutInfo.totalItemsCount - 1) }
                render()
                assertTrue(onUi { scene.texts().any { "Новый хвост" in it } })
                click("Свернуть")
                render()
                assertEquals(if (role == CodingRole.AGENT) 3 else 2, list.layoutInfo.totalItemsCount,
                    "Header, collapsed message, and manual review for a completed agent response")
                assertTrue(onUi { "Читать далее" in scene.texts() })
            } finally { onUi { scene.close() } }
        }
    }

    @Test fun expandedToolOutputKeepsItsHeaderAndRestoresTheOpenPreview() {
        val step = CodingStep(CodingStepKind.EXEC, "read huge-output.txt", id = "tool", callId = "tool",
            result = "Строка вывода\n".repeat(20000) + "Конец вывода")
        val session = CodingSessionUi(CodingSession("tool", "p", "Tool", 0), messages = listOf(
            CodingMessage("message", CodingRole.AGENT, "", createdAt = 0, steps = listOf(step))))
        val list = LazyListState()
        val scene = onUi { ImageComposeScene(760, 700) {
            MagicPaperTheme { CodingChat(CodingProject("p", "Project", "/project", 0), session,
                false, true, { _, _ -> }, {}, { _, _ -> }, listState = list) }
        } }
        try {
            var frame = 0L
            fun render() { repeat(20) { onUi { scene.render(++frame * 32_000_000L).close() }; Thread.sleep(5) } }
            fun click(label: String) = onUi {
                val node = scene.nodes().single { it.config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } == true }
                scene.sendPointerEvent(PointerEventType.Press, node.boundsInRoot.center)
                scene.sendPointerEvent(PointerEventType.Release, node.boundsInRoot.center)
            }
            render()
            click("read huge-output.txt")
            render()
            click("Читать далее")
            render()
            assertTrue(list.layoutInfo.totalItemsCount > 500)
            assertTrue(onUi { "read huge-output.txt" in scene.texts() }, "Keep the command header")
            assertTrue(onUi { scene.texts().sumOf { it.length } } < 16000)
            onUi { list.requestScrollToItem(list.layoutInfo.totalItemsCount - 1) }
            render()
            assertTrue(onUi { scene.texts().any { "Конец вывода" in it } })
            click("Свернуть")
            render()
            assertEquals(3, list.layoutInfo.totalItemsCount, "Header, collapsed tool output, and manual review")
            assertTrue(onUi { "Читать далее" in scene.texts() }, "Restore the opened tool, including its preview")
        } finally { onUi { scene.close() } }
    }

    @Test fun collapsingFullToolLogKeepsVisibleHeaderAtItsReadingPosition() {
        val title = "read huge-output.txt"
        val step = CodingStep(CodingStepKind.EXEC, title, id = "tool", callId = "tool",
            result = "Log line\n".repeat(20000))
        val before = List(4) { CodingMessage("before-$it", CodingRole.USER, "Earlier message $it", createdAt = it.toLong()) }
        val after = List(12) { CodingMessage("after-$it", CodingRole.USER, "Later message $it\n".repeat(8), createdAt = 10L + it) }
        val session = CodingSessionUi(CodingSession("position", "p", "Position", 0), messages =
            before + CodingMessage("command", CodingRole.AGENT, "", createdAt = 5, steps = listOf(step)) + after)
        val list = LazyListState()
        val scene = onUi { ImageComposeScene(760, 700) {
            MagicPaperTheme { CodingChat(CodingProject("p", "Project", "/project", 0), session,
                false, true, { _, _ -> }, {}, { _, _ -> }, listState = list) }
        } }
        try {
            var frame = 0L
            fun render() { repeat(30) { onUi { scene.render(++frame * 32_000_000L).close() }; Thread.sleep(5) } }
            fun textNode(label: String) = scene.nodes().single {
                it.config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } == true
            }
            fun click(label: String) = onUi {
                val node = textNode(label)
                scene.sendPointerEvent(PointerEventType.Press, node.boundsInRoot.center)
                scene.sendPointerEvent(PointerEventType.Release, node.boundsInRoot.center)
            }
            render()
            onUi { list.requestScrollToItem(5, -80) }
            render()
            click(title)
            render()
            click("Читать далее")
            render()
            assertTrue(list.layoutInfo.totalItemsCount > 500)
            val top = onUi { textNode(title).boundsInRoot.top }
            assertTrue(top > 40f, "Regression needs a visible header below the viewport top")
            click(title)
            render()
            assertEquals(18, list.layoutInfo.totalItemsCount)
            val collapsedTop = onUi { textNode(title).boundsInRoot.top }
            assertTrue(kotlin.math.abs(collapsedTop - top) <= 1f,
                "Collapsing full log moved its header: $top -> $collapsedTop")
        } finally { onUi { scene.close() } }
    }




}
