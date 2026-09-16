package io.aequicor.magicpaper.navigation

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import io.aequicor.magicpaper.designsystem.*
import io.aequicor.magicpaper.ui.components.rememberPaperInlineMessageParts
import java.awt.EventQueue
import java.io.File
import java.lang.management.ManagementFactory
import kotlin.test.*

/** Includes the real visit registry: feature-only transcript previews cannot catch its hot path. */
@OptIn(ExperimentalComposeUiApi::class)
class VisitPresentationScrollTest {
    @Test fun completedMarkdownRemainsLazyWhileVisitSavesAreCoalesced() {
        val article = (1..80).joinToString("\n\n") {
            "## Раздел $it\n\n" + "Текст с **выделением** и [ссылкой](https://example.invalid). ".repeat(4)
        }
        var saved: String? = null
        var writes = 0
        var list: androidx.compose.foundation.lazy.LazyListState? = null
        var parts: io.aequicor.magicpaper.ui.components.PaperInlineMessageParts? = null
        var compositions = 0
        val owner = VisitPresentationState(null) { saved = it; writes++ }
        val scene = onUi { ImageComposeScene(860, 720) {
            PaperTheme { owner.Content {
                val state = rememberLazyListState()
                val document = rememberPaperInlineMessageParts(article, markdown = true)
                SideEffect { list = state; parts = document }
                LazyColumn(Modifier.fillMaxSize(), state = state) {
                    items(document?.size ?: 0, key = { it }, contentType = { document!!.contentType(it) }) { index ->
                        // Legacy snapshots contain an editor draft per fragment, even if the
                        // editor was never opened. The root must also handle those saved values.
                        val draft = rememberSaveable { mutableStateOf(article) }
                        val expanded = rememberSaveable { mutableStateOf(false) }
                        SideEffect { compositions++; check(draft.value.isNotEmpty()); check(!expanded.value) }
                        document!!.Content(index)
                    }
                }
            } }
        } }
        var frame = 0L
        fun render() = onUi { scene.render(++frame * 16_666_667L).close() }
        try {
            val deadline = System.nanoTime() + 10_000_000_000L
            while (onUi { parts == null } && System.nanoTime() < deadline) { render(); Thread.sleep(5) }
            repeat(8) { render() }
            val parsed = onUi { requireNotNull(parts) }
            assertTrue(parsed.size >= 160)
            assertTrue(onUi { compositions < parsed.size / 2 }, "Only visible Markdown should compose")
            val initialWrites = onUi { writes }
            val bean = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
            val uiThread = onUi { Thread.currentThread().threadId() }
            val allocatedBefore = bean.getThreadAllocatedBytes(uiThread)
            val started = System.nanoTime()
            val timings = (1..100).map {
                val start = System.nanoTime()
                onUi {
                    scene.sendPointerEvent(PointerEventType.Scroll, Offset(400f, 300f), scrollDelta = Offset(0f, 4f))
                    scene.render(++frame * 16_666_667L).close()
                }
                (System.nanoTime() - start) / 1_000_000.0
            }.sorted()
            val elapsed = (System.nanoTime() - started) / 1_000_000
            val allocated = bean.getThreadAllocatedBytes(uiThread) - allocatedBefore
            val position = onUi { requireNotNull(list).firstVisibleItemIndex }
            val duringScroll = onUi { writes - initialWrites }
            onUi { owner.flush() }
            assertTrue(position > 15, "Wheel input must move across and dispose lazy fragments")
            assertSame(parsed, onUi { parts }, "Scrolling must retain the parsed Markdown document")
            assertNotNull(saved)
            val report = File("build/reports/presentation-scroll/timing.txt").apply { parentFile.mkdirs() }
            report.writeText("Real visit registry + lazy Paper Markdown, 100 wheel frames, headless 860×720.\n" +
                "position=$position writes=$duringScroll elapsedMs=$elapsed allocatedBytes=$allocated\n" +
                "p50=${timings[50]} p95=${timings[95]} max=${timings.last()} ms\n" +
                "Diagnostic timings, not native display FPS.\n")
            assertTrue(duringScroll <= elapsed / 200 + 3, "Saves must be coalesced, not per frame/item: $duringScroll in ${elapsed}ms")
        } finally { onUi { scene.close() } }
    }

    private fun <T> onUi(block: () -> T): T {
        if (EventQueue.isDispatchThread()) return block()
        var result: Result<T>? = null
        EventQueue.invokeAndWait { result = runCatching(block) }
        return result!!.getOrThrow()
    }
}
