package io.aequicor.magicpaper.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.use
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.ui.CodingSessionUi
import io.aequicor.magicpaper.ui.theme.MagicPaperTheme
import java.io.File
import kotlin.test.*

@OptIn(ExperimentalComposeUiApi::class)
class LargeCodingChatRenderTest {
    private fun steps(count: Int) = List(count) { index ->
        CodingStep(CodingStepKind.TOOL, "⚒ read · src/file-$index.kt", callId = "call-$index", result = "file content\n".repeat(100))
    }
    private fun session(id: String, count: Int, live: Boolean = false): CodingSessionUi {
        val run = steps(count)
        val request = CodingMessage("request-$id", CodingRole.USER, "Проверь проект", createdAt = 0)
        return CodingSessionUi(CodingSession(id, "p", id, 0),
            messages = if (live) listOf(request) else listOf(request,
                CodingMessage("response-$id", CodingRole.AGENT, "", steps = run, createdAt = 1)),
            draft = if (live) CodingDraft(run, active = true) else CodingDraft(), running = live)
    }
    private fun ImageComposeScene.texts(): List<String> {
        fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
        return semanticsOwners.flatMap { walk(it.unmergedRootSemanticsNode) }
            .flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }.map { it.text }
    }

    private fun ImageComposeScene.snapshot(name: String, frame: Long) {
        val directory = File("build/reports/large-coding-chat").apply { mkdirs() }
        File(directory, "$name.png").writeBytes(render(frame).use { image ->
            image.encodeToData()!!.use { it.bytes }
        })
    }

    private inner class Chat(initial: CodingSessionUi, defaultList: Boolean = false) : AutoCloseable {
        val session = mutableStateOf(initial)
        val list = LazyListState(Int.MAX_VALUE)
        var frame = 0L
        val scene = ImageComposeScene(760, 700) {
            MagicPaperTheme {
                val project = CodingProject("p", "Project", "/project", 0)
                if (defaultList) CodingChat(project, session.value, session.value.running, true, { _, _ -> }, {}, { _, _ -> })
                else CodingChat(project, session.value, session.value.running, true, { _, _ -> }, {}, { _, _ -> }, listState = list)
            }
        }
        fun render() { repeat(12) { scene.render(++frame * 32_000_000L).close(); Thread.sleep(5) } }
        fun assertBounded() {
            val tools = scene.texts().filter { it.startsWith("⚒ read") }
            assertTrue(tools.isNotEmpty(), "Visible tool steps must render")
            assertTrue(tools.size < 40, "Offscreen steps were composed: ${tools.size}")
        }
        override fun close() = scene.close()
    }

    @Test fun aThousandStepsInOneSavedMessageStayLazyWhileScrolling() = Chat(session("saved", 1000)).use { chat ->
        chat.render()
        assertEquals(1002, chat.list.layoutInfo.totalItemsCount)
        assertFalse(chat.list.canScrollForward)
        chat.assertBounded()
        chat.scene.snapshot("saved-tail", ++chat.frame * 32_000_000L)
        repeat(5) {
            chat.list.dispatchRawDelta(-500f)
            chat.render()
            chat.assertBounded()
        }
        assertTrue(chat.list.canScrollForward)
        chat.scene.snapshot("saved-scrolled", ++chat.frame * 32_000_000L)
    }

    @Test fun largeLiveRunFollowsOutputWithoutMovingTheReaderAndCommitsAtBottom() = Chat(session("live", 1000, true)).use { chat ->
        chat.render()
        chat.assertBounded()
        chat.session.value = session("live", 1001, true)
        chat.render()
        assertFalse(chat.list.canScrollForward)
        chat.list.dispatchRawDelta(-500f)
        chat.render()
        val index = chat.list.firstVisibleItemIndex
        val offset = chat.list.firstVisibleItemScrollOffset
        chat.session.value = session("live", 1002, true)
        chat.render()
        assertEquals(index, chat.list.firstVisibleItemIndex)
        assertEquals(offset, chat.list.firstVisibleItemScrollOffset)
        chat.assertBounded()
        chat.list.dispatchRawDelta(10000f)
        chat.render()
        chat.session.value = session("live", 1002)
        chat.render()
        assertFalse(chat.list.canScrollForward, "Committing the draft must keep the latest output visible")
        chat.assertBounded()
    }

    @Test fun switchingLargeSessionsImmediatelyShowsOnlyTheirLatestSteps() = Chat(session("first", 1000), defaultList = true).use { chat ->
        chat.render()
        chat.assertBounded()
        assertTrue(chat.scene.texts().any { "file-999.kt" in it })
        chat.session.value = session("second", 1500)
        chat.render()
        chat.assertBounded()
        assertTrue(chat.scene.texts().any { "file-1499.kt" in it })
        assertFalse(chat.scene.texts().any { "file-999.kt" in it })
    }

    @Test fun expandingAThousandThinkingParagraphsOnlyComposesTheVisibleTail() {
        val thinking = (1..1000).joinToString("\n\n") { "Thought $it: проверяю расположение элементов и обработку событий." }
        val expanded = mutableStateOf(false)
        ImageComposeScene(680, 500) {
            MagicPaperTheme {
                Column(Modifier.fillMaxWidth()) {
                    AgentMessageStatus(CodingDraft(thinking = thinking, active = true), expanded.value) { expanded.value = !expanded.value }
                }
            }
        }.use { scene ->
            var frame = 0L
            fun render() { scene.render(++frame * 32_000_000L).close(); Thread.sleep(10) }
            repeat(6) { render() }
            assertFalse(scene.texts().any { it.startsWith("Thought 1:") })
            fun descendants(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descendants)
            val disclosure = scene.semanticsOwners.flatMap { descendants(it.unmergedRootSemanticsNode) }
                .first { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Развернуть размышления") == true }
            scene.sendPointerEvent(PointerEventType.Press, disclosure.boundsInRoot.center)
            scene.sendPointerEvent(PointerEventType.Release, disclosure.boundsInRoot.center)
            repeat(100) { render() }
            assertTrue(expanded.value, "Click the real thinking disclosure")
            val paragraphs = scene.texts().filter { it.startsWith("Thought ") && ": проверяю" in it }
            assertTrue(paragraphs.any { it.startsWith("Thought 1000:") }, "Opening must show the latest thought")
            assertTrue(paragraphs.any { it.startsWith("Thought 999:") }, "The scrollable body must render, independently of the one-line status")
            assertTrue(paragraphs.size < 20, "Hidden thinking paragraphs were composed: ${paragraphs.size}")
            scene.snapshot("thinking-expanded", ++frame * 32_000_000L)
        }
    }
}
