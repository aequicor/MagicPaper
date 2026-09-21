package io.aequicor.magicpaper.ui.screens

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.aequicor.magicpaper.designsystem.*
import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.ui.components.DefaultChatPresentation
import io.aequicor.magicpaper.ui.components.LocalChatPresentation
import java.awt.EventQueue
import java.io.File
import kotlin.test.*

/** Real wheel events during streamed output, with the same selectable fragments as production. */
@OptIn(ExperimentalComposeUiApi::class)
class ResearchScrollRegressionTest {
    private fun <T> onUi(block: () -> T): T {
        if (EventQueue.isDispatchThread()) return block()
        var result: Result<T>? = null
        EventQueue.invokeAndWait { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    @Test fun completedArticleScrollAtRetinaScaleWithWindowChrome() {
        val answer = (1..120).joinToString("\n\n") {
            "## Раздел $it\n\n" + "Текст статьи с **выделением**, [ссылкой](https://example.com) и подробностями. ".repeat(6)
        }
        val session = ChatSession("article", "Статья", 0, 1,
            messages = listOf(ChatMessage("article-answer", ChatRole.AGENT, answer, 1)))
        val reports = mutableListOf<String>()
        for (frost in listOf(false, true)) {
            val list = LazyListState()
            val scene = onUi { ImageComposeScene(2880, 1800, density = Density(2f)) {
                PaperTheme { PaperResearchReading {
                    CompositionLocalProvider(LocalChatPresentation provides DefaultChatPresentation) {
                        Box(Modifier.fillMaxSize().paperTitleBarFrost(56.dp, blurContent = frost)) {
                            MessagesList(session, false, listState = list)
                        }
                    }
                } }
            } }
            var frame = 0L
            fun render() = onUi { scene.render(++frame * 16_666_667L).close() }
            try {
                val deadline = System.nanoTime() + 10_000_000_000L
                while (onUi { list.layoutInfo.totalItemsCount < 200 } && System.nanoTime() < deadline) {
                    render(); Thread.sleep(5)
                }
                assertTrue(onUi { list.layoutInfo.totalItemsCount > 200 })
                repeat(20) { render(); Thread.sleep(5) }
                val end = onUi { list.firstVisibleItemIndex }
                val durations = (1..90).map {
                    val started = System.nanoTime()
                    onUi {
                        scene.sendPointerEvent(PointerEventType.Scroll, Offset(1000f, 700f), scrollDelta = Offset(0f, -3f))
                        scene.render(++frame * 16_666_667L).close()
                    }
                    (System.nanoTime() - started) / 1_000_000.0
                }.sorted()
                assertTrue(onUi { list.firstVisibleItemIndex < end })
                reports += "frost=$frost p50=${durations[45]} p95=${durations[85]} max=${durations.last()} ms"
            } finally { onUi { scene.close() } }
        }
        File("build/reports/research-workspace/article-scroll-timing.txt").apply { parentFile.mkdirs() }
            .writeText("Headless Compose/AWT, 1440×900 dp, density 2, completed article, 90 wheel frames.\n" +
                reports.joinToString("\n") + "\nRegression workload, not native display FPS.\n")
    }
    @Test fun scrollingLongResearchWhileStreamingKeepsEarlierContentReachable() {
        val list = LazyListState()
        val draft = mutableStateOf(CodingDraft(active = true, steps = listOf(
            CodingStep(CodingStepKind.TOOL, "Чтение материалов", tool = "read", running = true, id = "read"))))
        val answer = (1..120).joinToString("\n\n") {
            "## Раздел $it\n\nИсследование с **важным выводом**, [источником](https://example.com) и подробностями. ".repeat(2)
        }
        val session = ChatSession("scroll", "Исследование", 0, 1,
            messages = listOf(ChatMessage("user", ChatRole.USER, "Исследуй тему", 0),
                ChatMessage("answer", ChatRole.AGENT, answer, 1)),
            pendingRun = CodingRunCheckpoint("follow-up", "Продолжай", responseId = "live"))
        val scene = onUi { ImageComposeScene(860, 720) {
            PaperTheme { PaperResearchReading {
                CompositionLocalProvider(LocalChatPresentation provides DefaultChatPresentation) {
                    MessagesList(session, true, draft = draft.value, listState = list)
                }
            } }
        } }
        var frame = 0L
        fun render() = onUi { scene.render(++frame * 16_666_667L).close() }
        try {
            val deadline = System.nanoTime() + 10_000_000_000L
            while (onUi { list.layoutInfo.totalItemsCount < 200 } && System.nanoTime() < deadline) {
                render(); Thread.sleep(5)
            }
            assertTrue(onUi { list.layoutInfo.totalItemsCount > 200 })
            repeat(24) { render(); Thread.sleep(5) }
            val end = onUi { list.firstVisibleItemIndex }
            val durations = (1..120).map { index ->
                val started = System.nanoTime()
                onUi {
                    if (index % 12 == 0) draft.value = draft.value.copy(steps = draft.value.steps.take(1) +
                        CodingStep(CodingStepKind.ANSWER, "Поступающий ответ ".repeat(index), id = "text"))
                    scene.sendPointerEvent(PointerEventType.Scroll, Offset(400f, 300f), scrollDelta = Offset(0f, -1.5f))
                    scene.render(++frame * 16_666_667L).close()
                }
                (System.nanoTime() - started) / 1_000_000.0
            }.sorted()
            assertTrue(onUi { list.firstVisibleItemIndex < end }, "Streaming must not pull the reader back to the end")
            assertTrue(durations.last() < 1000, "No one-second UI stall in the bounded wheel replay")
            val report = File("build/reports/research-workspace/scroll-timing.txt").apply { parentFile.mkdirs() }
            report.writeText("Headless Compose/AWT, 860×720, 120 wheel frames during streaming.\n" +
                "p50=${durations[60]} ms\np95=${durations[114]} ms\nmax=${durations.last()} ms\n" +
                "This is a regression workload, not native display FPS.\n")
        } finally { onUi { scene.close() } }
    }
}
