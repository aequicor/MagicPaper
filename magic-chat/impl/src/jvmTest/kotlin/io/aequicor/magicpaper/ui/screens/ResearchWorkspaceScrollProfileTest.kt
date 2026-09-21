package io.aequicor.magicpaper.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.use
import io.aequicor.magicpaper.designsystem.*
import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.ui.components.DefaultChatPresentation
import io.aequicor.magicpaper.ui.components.LocalChatPresentation
import java.awt.EventQueue
import java.io.File
import java.time.Duration
import jdk.jfr.Recording
import kotlinx.coroutines.runBlocking
import kotlin.math.ceil
import kotlin.test.Test
import kotlin.test.assertTrue

/** Real research components with isolated fixtures; timing is diagnostic, never a machine-speed gate. */
@OptIn(ExperimentalComposeUiApi::class)
class ResearchWorkspaceScrollProfileTest {
    @Test fun completedMixedArticleInTheFullWorkspace() {
        val preview = researchPreviewState()
        val root = requireNotNull(preview.current)
        val answer = root.messages.last().copy(text = mixedArticle())
        val session = root.copy(messages = listOf(root.messages.first(), answer))
        val state = preview.copy(current = session, sessions = preview.sessions.map { if (it.id == session.id) session else it })
        val list = LazyListState()
        val scene = onUi { ImageComposeScene(2880, 1800, density = Density(2f)) {
            PaperTheme {
                CompositionLocalProvider(LocalChatPresentation provides DefaultChatPresentation) {
                    Box(Modifier.fillMaxSize().paperTitleBarFrost(56.dp, blurContent = false)) {
                        // Exercise the real static paper/background route. Animated shader/GPU
                        // acceptance needs a foreground native window, not this raster scene.
                        PaperBackground(false, Modifier.fillMaxSize())
                        ResearchWorkspaceContent(state, onForkQuestion = {}) {
                            PaperResearchReading {
                                MessagesList(session, busy = false, listState = list, modifier = Modifier.fillMaxSize(),
                                    onEdit = { _, _ -> Result.success(Unit) },
                                    onDelete = { Result.success(Unit) },
                                    onFork = { Result.success("fixture-fork") },
                                    onFollowUp = { _, _ -> },
                                    footer = {
                                        Composer(enabled = true, session = session,
                                            profiles = listOf(LlmProfile("preview", "GPT-5.5", baseUrl = "https://preview.invalid",
                                                modelId = "gpt-5.5", effort = EffortSelection.of(ReasoningEffort.HIGH))),
                                            activeProfileId = "preview", onSend = { _, _ -> }, onOpenSwitcher = {},
                                            contextUsage = ContextUsageSnapshot("chat:research", "gpt-5.5", 30_720, 128_000),
                                            onPickAttachments = { _, _ -> })
                                    })
                            }
                        }
                    }
                }
            }
        } }
        val directory = File("build/reports/research-workspace/scroll-profile").apply { mkdirs() }
        var frame = 0L
        fun render() = onUi { scene.render(++frame * FRAME_NANOS).close() }
        fun settleWheel() {
            val deadline = System.nanoTime() + 2_000_000_000L
            do {
                render()
                Thread.sleep(10)
            } while (onUi { list.isScrollInProgress } && System.nanoTime() < deadline)
            repeat(16) { render(); Thread.sleep(10) }
        }
        fun capture(name: String) = onUi {
            File(directory, "$name.png").writeBytes(scene.render(++frame * FRAME_NANOS).use {
                it.encodeToData()!!.use { data -> data.bytes }
            })
        }
        val report = mutableListOf(
            "Headless Compose/AWT raster scene; physical 2880×1800, 1440×900 dp, density=2, fontScale=1.",
            "Production ResearchWorkspaceContent + MessagesList + MessageHistoryActions + Composer + static PaperBackground.",
            "Completed synthetic article: paragraphs, headings, ordered/unordered lists, block quotes, tables; real source footnotes and follow-ups.",
            "Not native display FPS; animated paper/GPU composition is not exercised. No user history, services, filesystem source reads or network requests.",
        )
        val parsed = runBlocking { parsePaperMarkdown(answer.text) }
        report += "AST top-level node counts: ${parsed.node.children.groupingBy { it.type.toString() }.eachCount()}"
        report += "AST top-level whitespace-only nodes: ${parsed.node.children.count { answer.text.substring(it.startOffset, it.endOffset).isBlank() }}"
        try {
            val deadline = System.nanoTime() + 15_000_000_000L
            while (onUi { list.layoutInfo.totalItemsCount < 400 } && System.nanoTime() < deadline) {
                render()
                Thread.sleep(5)
            }
            assertTrue(onUi { list.layoutInfo.totalItemsCount >= 400 }, "The complete article must be parsed into lazy fragments")
            repeat(30) { render(); Thread.sleep(5) }
            val end = onUi { list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset }
            val initialText = onUi { scene.texts() }
            for (label in listOf("Вопросы", "Источники", "Отправить", "Источники ответа", answer.followUps.first())) {
                assertTrue(label in initialText, "The full-workspace fixture must include $label")
            }
            capture("completed-tail")
            Recording().use { recording ->
                recording.enable("jdk.ExecutionSample").withPeriod(Duration.ofMillis(2))
                recording.enable("jdk.ObjectAllocationSample")
                recording.start()
                for (pass in listOf("cold", "warm")) {
                    if (pass == "warm") {
                        settleWheel()
                        onUi { list.requestScrollToItem(end.first, end.second) }
                        repeat(24) { render(); Thread.sleep(10) }
                    }
                    val start = onUi { list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset }
                    val samples = (1..WHEEL_FRAMES).map {
                        val before = onUi { list.firstVisibleItemIndex }
                        val started = System.nanoTime()
                        onUi {
                            scene.sendPointerEvent(PointerEventType.Scroll, Offset(1350f, 800f), scrollDelta = Offset(0f, -8f))
                            scene.render(++frame * FRAME_NANOS).close()
                        }
                        Sample((System.nanoTime() - started) / 1_000_000.0,
                            onUi { list.firstVisibleItemIndex }, before)
                    }
                    val finish = onUi { list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset }
                    assertTrue(finish.first < start.first, "$pass wheel input must move through the article")
                    report += "$pass start=$start end=$finish distinctFirstItems=${samples.map { it.index }.distinct().size}"
                    report += "$pass all ${samples.map { it.millis }.summary()}"
                    report += "$pass crossing-item ${samples.filter { it.index != it.previousIndex }.map { it.millis }.summary()}"
                    report += "$pass within-item ${samples.filter { it.index == it.previousIndex }.map { it.millis }.summary()}"
                    File(directory, "$pass-frames.csv").writeText("frame,milliseconds,previousFirstItem,firstItem\n" +
                        samples.mapIndexed { index, sample -> "${index + 1},${sample.millis},${sample.previousIndex},${sample.index}" }.joinToString("\n"))
                    capture("$pass-mixed-content")
                }
                recording.stop()
                recording.dump(File(directory, "completed-workspace-scroll.jfr").toPath())
            }
            File(directory, "timing.txt").writeText(report.joinToString("\n", postfix = "\n"))
        } finally { onUi { scene.close() } }
    }

    private data class Sample(val millis: Double, val index: Int, val previousIndex: Int)

    private fun List<Double>.summary(): String {
        if (isEmpty()) return "n=0"
        val sorted = sorted()
        fun percentile(value: Double) = sorted[(ceil(sorted.size * value).toInt() - 1).coerceIn(sorted.indices)]
        return "n=$size p50=${percentile(.50)} p95=${percentile(.95)} max=${sorted.last()} ms"
    }

    private fun ImageComposeScene.texts(): Set<String> {
        fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
        return semanticsOwners.flatMap { walk(it.unmergedRootSemanticsNode) }
            .flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty().map { text -> text.text } }.toSet()
    }

    private fun <T> onUi(block: () -> T): T {
        if (EventQueue.isDispatchThread()) return block()
        var result: Result<T>? = null
        EventQueue.invokeAndWait { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    private fun mixedArticle(): String = (1..72).joinToString("\n\n") { section ->
        """
            ## Раздел $section. Как организовать изучение материалов

            Сопоставьте **наблюдение и объяснение**, затем выберите небольшой следующий шаг. ${"Подробный разбор помогает проверить выводы на практике и сохранить контекст исследования. ".repeat(4)}[Первичный источник](https://example.invalid/research/$section).

            1. Запишите исходный вопрос и проверьте, что именно в нём пока неясно.
            2. Сравните два объяснения и отделите наблюдения от предположений.
            3. Выберите действие, результат которого можно оценить самостоятельно.

            - Краткая заметка сохраняет основную мысль.
            - Ссылка на источник позволяет проверить детали.
            - Следующий вопрос уточняет оставшуюся неопределённость.

            > Проверка небольшого предположения часто полезнее ещё одной общей рекомендации.

            | Подход | Когда применять | Проверка результата |
            | --- | --- | --- |
            | Краткий пересказ | Нужно понять основную мысль документа | Можно объяснить вывод своими словами |
            | Сравнение | Источники описывают разные варианты | Видны критерии и ограничения каждого варианта |
            | Практическое действие | Есть конкретная проверяемая задача | Получен наблюдаемый результат |
            | Подробная статья | Нужна связная картина нескольких источников | Выводы опираются на доступные материалы |

            Уточняющий вопрос связывает этот шаг с дальнейшей работой. ${"Сохраните важные детали и вернитесь к ним после первой проверки. ".repeat(3)}
        """.trimIndent()
    }

    private companion object {
        const val FRAME_NANOS = 16_666_667L
        const val WHEEL_FRAMES = 150
    }
}
