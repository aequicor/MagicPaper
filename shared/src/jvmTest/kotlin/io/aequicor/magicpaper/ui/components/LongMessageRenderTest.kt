package io.aequicor.magicpaper.ui.components
import androidx.compose.foundation.lazy.LazyListState
import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.ui.screens.MessagesList
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

    @Test fun hugeMessagesExpandIntoTheChatListAndCollapseWithoutADialog() {
        val source = "## Проверка длинного ответа\n\n" + "**Текст сообщения** со [ссылкой](https://example.com). ".repeat(12000) + "\n\nКонец полного сообщения"
        runBlocking { ChatMarkdownDocuments.load(source, cache = true) }
        val list = LazyListState()
        val session = ChatSession("long", "long", 0, 0, listOf(ChatMessage("message", ChatRole.AGENT, source, 0)))
        val scene = onUi { ImageComposeScene(760, 680) {
            MagicPaperTheme { Surface { MessagesList(session, false, listState = list) } }
        } }
        try {
            var frame = 0L
            fun render() { repeat(15) { onUi { scene.render(++frame * 32_000_000L).close() }; Thread.sleep(5) } }
            fun click(label: String) = onUi {
                val button = scene.nodes().single { it.config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } == true }
                scene.sendPointerEvent(PointerEventType.Press, button.boundsInRoot.center)
                scene.sendPointerEvent(PointerEventType.Release, button.boundsInRoot.center)
            }
            render()
            assertEquals(1, list.layoutInfo.totalItemsCount)
            assertTrue(list.layoutInfo.visibleItemsInfo.single().size <= 400)
            assertTrue(onUi { scene.texts().sumOf { it.length } } < 8000)
            onUi { scene.snapshot("preview") }
            click("Читать далее")
            render()
            assertTrue(list.layoutInfo.totalItemsCount > 300, "Fragments must belong to the outer chat list")
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
                    val node = scene.nodes().single { it.config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } == true }
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
                assertEquals(2, list.layoutInfo.totalItemsCount)
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
            assertEquals(2, list.layoutInfo.totalItemsCount)
            assertTrue(onUi { "Читать далее" in scene.texts() }, "Restore the opened tool, including its preview")
        } finally { onUi { scene.close() } }
    }

    @Test fun oversizedCodeListTableAndPlainParagraphHaveBoundedLazyLayout() {
        val examples = linkedMapOf(
            "code" to "```kotlin\n" + (1..5000).joinToString("\n") { "println(\"Строка $it\")" } + "\n```",
            "list" to (1..5000).joinToString("\n") { "$it. Элемент **$it** со [ссылкой](https://example.com)" },
            "table" to "| Название | Значение |\n| :--- | ---: |\n" + (1..2000).joinToString("\n") { "| Строка $it | **$it** |" },
            "paragraph" to "Длинный абзац **с форматированием**. ".repeat(15000),
        )
        for ((name, source) in examples) {
            val document = runBlocking { ChatMarkdownDocuments.load(source, cache = false) }
            ImageComposeScene(760, 680) {
                MagicPaperTheme { Surface { MarkdownDocumentBody(document, document.blocks, Modifier.fillMaxSize().padding(16.dp), lazy = true) } }
            }.use { scene ->
                var frame = 0L
                fun render() { repeat(8) { scene.render(++frame * 32_000_000L).close(); Thread.sleep(5) } }
                render()
                assertTrue(scene.texts().isNotEmpty(), name)
                assertTrue(scene.texts().sumOf { it.length } < 12000, "$name must only lay out visible fragments")
                scene.snapshot(name)
                repeat(3) {
                    scene.sendPointerEvent(PointerEventType.Scroll, Offset(300f, 400f), scrollDelta = Offset(0f, 6f))
                    render()
                    assertTrue(scene.texts().sumOf { it.length } < 12000, "$name became eager while scrolling")
                }
            }
        }
    }

    @Test fun megabytePlainTextPreviewStaysBounded() {
        ImageComposeScene(760, 680) {
            MagicPaperTheme { Column { ChatPlainText("output 😀 ".repeat(120000)) } }
        }.use { scene ->
            repeat(8) { scene.render(it * 32_000_000L).close(); Thread.sleep(5) }
            assertTrue(scene.texts().all { it.length <= MESSAGE_PREVIEW_CHARS })
            assertTrue("Читать далее" in scene.texts())
        }
    }
}
