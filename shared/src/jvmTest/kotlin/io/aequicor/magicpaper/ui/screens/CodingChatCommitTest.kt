package io.aequicor.magicpaper.ui.screens

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.ui.CodingSessionUi
import io.aequicor.magicpaper.ui.components.LocalHideSystemSteps
import io.aequicor.magicpaper.ui.theme.MagicPaperTheme
import java.awt.EventQueue
import java.io.File
import kotlin.test.*

/** Exercise the actual draft -> saved transition, including independently published snapshots. */
@OptIn(ExperimentalComposeUiApi::class)
class CodingChatCommitTest {
    private companion object {
        fun <T> onUi(block: () -> T): T {
            if (EventQueue.isDispatchThread()) return block()
            var result: Result<T>? = null
            EventQueue.invokeAndWait { result = runCatching(block) }
            return result!!.getOrThrow()
        }
    }

    private class Chat : AutoCloseable {
        val recorder = CodingRunRecorder().apply {
            repeat(60) {
                apply(CodingEvent.Notice("System step $it"))
                apply(CodingEvent.ToolStarted("read", "file-$it.kt", callId = "call-$it"))
                apply(CodingEvent.ToolFinished("read", false, callId = "call-$it", resultPreview = "File content $it"))
            }
        }
        val value = mutableStateOf(CodingSessionUi(CodingSession("session", "project", "Проверка", 0),
            running = true, draft = recorder.draft(true)))
        val list = LazyListState(Int.MAX_VALUE)
        val hidden = mutableStateOf(true)
        private var frame = 0L
        private val scene = onUi { ImageComposeScene(760, 700) {
            MagicPaperTheme { CompositionLocalProvider(LocalHideSystemSteps provides hidden.value) {
                CodingChat(CodingProject("project", "Project", "/project", 0), value.value,
                    value.value.running, true, { _, _ -> }, {}, { _, _ -> }, listState = list)
            } }
        } }
        init { render() }
        fun render() { repeat(16) { onUi { scene.render(++frame * 32_000_000L).close() }; Thread.sleep(5) } }
        fun readMiddle() { onUi { list.dispatchRawDelta(-900f) }; render(); assertTrue(list.canScrollForward) }
        fun publishSaved(clearDraft: Boolean) {
            onUi { value.value = value.value.copy(messages = listOf(recorder.message("response", 1)),
                running = !clearDraft, draft = if (clearDraft) CodingDraft() else value.value.draft) }
            render()
        }
        fun snapshot(name: String) = onUi {
            val dir = File("build/reports/chat-scroll-stability").apply { mkdirs() }
            val rendered = scene.render(++frame * 32_000_000L)
            val data = rendered.encodeToData()!!
            try { File(dir, "$name.png").writeBytes(data.bytes) } finally { data.close(); rendered.close() }
        }
        fun anchor() = list.layoutInfo.visibleItemsInfo.first { it.index == list.firstVisibleItemIndex }
            .let { it.key to it.offset }
        private fun nodes(): List<SemanticsNode> {
            fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
            return scene.semanticsOwners.flatMap { walk(it.unmergedRootSemanticsNode) }
        }
        fun textNode(text: String) = onUi { nodes().firstOrNull {
            it.config.getOrNull(SemanticsProperties.Text)?.any { it.text.contains(text) } == true
        } }
        fun expandVisibleCommand(): String {
            val node = onUi { nodes().first {
                it.boundsInRoot.top in 80f..350f &&
                    it.config.getOrNull(SemanticsProperties.Text)?.any { it.text.contains("file-") } == true
            } }
            val title = node.config.getOrNull(SemanticsProperties.Text)!!.first().text
            val number = Regex("file-(\\d+)").find(title)!!.groupValues[1]
            onUi {
                val point = node.boundsInRoot.center
                scene.sendPointerEvent(PointerEventType.Press, point)
                scene.sendPointerEvent(PointerEventType.Release, point)
            }
            render()
            return "File content $number".also { assertNotNull(textNode(it)) }
        }
        override fun close() = onUi { scene.close() }
    }

    @Test fun savingPreservesTheVisibleStepAndItsOffset() = Chat().use { chat ->
        chat.readMiddle()
        val before = chat.anchor()
        chat.snapshot("before-save")
        chat.publishSaved(clearDraft = true)
        chat.snapshot("after-save")
        assertEquals(before, chat.anchor(), "Saving must preserve the same visible step and its pixel offset")
    }

    @Test fun savedSnapshotDoesNotDuplicateTheStillPublishedDraft() = Chat().use { chat ->
        chat.readMiddle()
        val before = chat.anchor()
        val count = chat.list.layoutInfo.totalItemsCount
        chat.publishSaved(clearDraft = false)
        assertTrue(chat.list.layoutInfo.totalItemsCount <= count + 1, "The same run appeared twice")
        assertEquals(before, chat.anchor())
        chat.publishSaved(clearDraft = true)
        assertEquals(before, chat.anchor())
    }

    @Test fun savingAtTheBottomKeepsFollowingOutput() = Chat().use { chat ->
        assertFalse(chat.list.canScrollForward)
        chat.publishSaved(clearDraft = false)
        assertFalse(chat.list.canScrollForward)
        chat.publishSaved(clearDraft = true)
        assertFalse(chat.list.canScrollForward)
    }

    @Test fun expandedCommandSurvivesSavingAndVisibilityChanges() = Chat().use { chat ->
        chat.readMiddle()
        val result = chat.expandVisibleCommand()
        val before = chat.anchor()
        chat.publishSaved(clearDraft = true)
        assertNotNull(chat.textNode(result), "Saving collapsed the expanded command")
        assertEquals(before, chat.anchor())
        chat.hidden.value = false
        chat.render()
        assertEquals(before, chat.anchor(), "Revealing preceding system rows changed the reader's anchor")
        assertNotNull(chat.textNode(result))
        chat.hidden.value = true
        chat.render()
        assertEquals(before, chat.anchor())
    }

    @Test fun toolAndTextUpdatesBelowTheReaderDoNotMoveTheAnchor() = Chat().use { chat ->
        chat.readMiddle()
        val before = chat.anchor()
        repeat(6) {
            chat.recorder.apply(CodingEvent.ToolStarted("bash", "check-$it", callId = "live-$it", isExec = true))
            chat.recorder.apply(CodingEvent.ToolProgress("bash", callId = "live-$it", resultPreview = "Output $it"))
            chat.recorder.apply(CodingEvent.ToolFinished("bash", false, callId = "live-$it"))
            chat.recorder.apply(CodingEvent.TextDelta("Answer fragment $it. "))
            chat.value.value = chat.value.value.copy(draft = chat.recorder.draft(true))
            chat.render()
            assertEquals(before, chat.anchor())
        }
        chat.recorder.apply(CodingEvent.Failed("Interrupted"))
        chat.value.value = chat.value.value.copy(draft = chat.recorder.draft(false))
        chat.publishSaved(clearDraft = true)
        assertEquals(before, chat.anchor(), "An error must not move a reader to the end")
    }

    @Test fun removingEarlierRowsWhileNewOutputArrivesDoesNotDisableFollowing() = listOf(12, 60).forEach { count -> Chat().use { chat ->
        chat.hidden.value = false
        chat.render()
        assertFalse(chat.list.canScrollForward)
        chat.hidden.value = true
        repeat(count) { chat.recorder.apply(CodingEvent.ToolStarted("read", "new-$it", callId = "new-$it")) }
        chat.value.value = chat.value.value.copy(draft = chat.recorder.draft(true))
        chat.render()
        assertFalse(chat.list.canScrollForward, "A changed item index is not a user scrolling backwards")
    } }

    @Test fun equalVisibleDraftKeepsItsLiveStateDuringThinkingUpdates() = Chat().use { chat ->
        chat.recorder.apply(CodingEvent.ToolStarted("read", "running", callId = "running"))
        chat.value.value = chat.value.value.copy(draft = chat.recorder.draft(true))
        chat.render()
        assertNotNull(chat.textNode("Выполняется действие…"))
        repeat(3) {
            chat.recorder.apply(CodingEvent.ThinkingDelta("More thought $it. "))
            chat.value.value = chat.value.value.copy(draft = chat.recorder.draft(true))
            chat.render()
            assertNotNull(chat.textNode("Выполняется действие…"), "Cached equal rows must still be recognized as live")
        }
    }
}
