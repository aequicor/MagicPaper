package io.aequicor.magicpaper.ui.screens

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.ui.CodingSessionUi
import io.aequicor.magicpaper.ui.theme.MagicPaperTheme
import java.awt.EventQueue
import kotlin.test.*

/** Count actual executed composable bodies through the compiler's tracing hooks. */
@OptIn(InternalComposeTracingApi::class)
class ActiveCodingChatRenderTest {
    private companion object {
        fun <T> onUi(block: () -> T): T {
            if (EventQueue.isDispatchThread()) return block()
            var result: Result<T>? = null
            EventQueue.invokeAndWait { result = runCatching(block) }
            return result!!.getOrThrow()
        }
    }

    private class Tracer : CompositionTracer {
        val counts = mutableMapOf<String, Int>()
        override fun isTraceInProgress() = true
        override fun traceEventStart(key: Int, dirty1: Int, dirty2: Int, info: String) {
            if ("io.aequicor.magicpaper" in info) counts[info] = counts.getOrDefault(info, 0) + 1
        }
        override fun traceEventEnd() = Unit
        fun calls(name: String) = counts.filterKeys { ".$name (" in it }.values.sum()
    }

    @Test fun streamingDraftDoesNotRecomposeSavedRowsOrComposer() {
        val tracer = Tracer()
        Composer.setTracer(tracer)
        try {
            val saved = CodingMessage("saved", CodingRole.AGENT, "", createdAt = 0,
                steps = List(1000) { CodingStep(CodingStepKind.TOOL, "⚒ read · file-$it.kt", callId = "saved-$it") })
            val tool = CodingStep(CodingStepKind.TOOL, "⚒ bash · checks", callId = "live", running = true)
            val value = mutableStateOf(CodingSessionUi(CodingSession("session", "project", "Task", 0),
                messages = listOf(saved), running = true, draft = CodingDraft(steps = listOf(tool), active = true)))
            val list = LazyListState(Int.MAX_VALUE)
            val scene = onUi { ImageComposeScene(760, 700) {
                MagicPaperTheme { CodingChat(CodingProject("project", "Project", "/project", 0), value.value,
                    true, true, { _, _ -> }, {}, { _, _ -> }, listState = list) }
            } }
            try {
                var frame = 0L
                fun render() { repeat(8) { onUi { scene.render(++frame * 32_000_000L).close() }; Thread.sleep(5) } }
                render()
                assertTrue(tracer.calls("CodingMessageBubble") > 0, "Tracing must see real saved rows")
                assertTrue(tracer.calls("CodingComposer") > 0)
                tracer.counts.clear()
                var thought = "Анализ\n" + "Проверяю файлы. ".repeat(8000)
                repeat(20) {
                    thought += "Фрагмент $it. "
                    value.value = value.value.copy(draft = CodingDraft(
                        steps = listOf(tool, CodingStep(CodingStepKind.THINKING, thought)), active = true, thinking = thought))
                    render()
                }
                assertTrue(tracer.calls("CodingChat") >= 20, "Exercise live updates of the containing chat")
                assertEquals(0, tracer.calls("CodingMessageBubble"), "The live draft invalidated saved history")
                assertEquals(0, tracer.calls("CodingComposer"), "The live draft invalidated composer buttons")
                assertTrue(list.layoutInfo.visibleItemsInfo.size < 30, "Only the viewport should be composed")
            } finally { onUi { scene.close() } }
        } finally { Composer.setTracer(null) }
    }
}
