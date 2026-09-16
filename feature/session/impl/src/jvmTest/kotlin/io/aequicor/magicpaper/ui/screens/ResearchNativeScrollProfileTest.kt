package io.aequicor.magicpaper.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.unit.dp
import io.aequicor.magicpaper.designsystem.*
import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.ui.components.DefaultChatPresentation
import io.aequicor.magicpaper.ui.components.LocalChatPresentation
import io.aequicor.magicpaper.ui.window.LocalWindowScope
import java.awt.Component
import java.awt.Container
import java.awt.EventQueue
import java.awt.GraphicsEnvironment
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.ceil
import kotlin.test.Test
import kotlin.test.assertTrue
import org.jetbrains.skiko.ExperimentalSkikoApi
import org.jetbrains.skiko.GraphicsApi
import org.jetbrains.skiko.OS
import org.jetbrains.skiko.SkiaLayerAnalytics
import org.jetbrains.skiko.SkiaLayer

/** Opt-in isolated native window. No application services, user history, model calls, or network. */
@OptIn(ExperimentalComposeUiApi::class, ExperimentalSkikoApi::class)
class ResearchNativeScrollProfileTest {
    @Test fun nativeCompletedArticleWithAndWithoutAnimatedPaper() {
        if (System.getProperty("magicpaper.research.native") != "true") return
        assertTrue(!GraphicsEnvironment.isHeadless(), "Native profile needs a desktop display")
        val preview = researchPreviewState()
        val original = requireNotNull(preview.current)
        val answer = original.messages.last().copy(text = mixedArticle())
        val session = original.copy(messages = listOf(original.messages.first(), answer))
        val state = preview.copy(current = session,
            sessions = preview.sessions.map { if (it.id == session.id) session else it })
        val list = LazyListState()
        val animation = mutableStateOf(false)
        val frames = FrameAnalytics()
        val failure = AtomicReference<Throwable?>()
        val directory = File("build/reports/research-workspace/native-scroll-profile").apply { mkdirs() }
        val report = mutableListOf(
            "Isolated ComposeWindow, 1440×900 logical pixels, platform density, real native graphics backend.",
            "Full production research workspace, long completed mixed article, source footnotes, follow-up buttons and composer.",
            "Order: animation off / on / off; same content and initial position. Timings diagnostic, not machine-speed gates.",
            "Native render callbacks measure submission work and frame-start spacing, not display scan-out or GPU completion.",
        )
        val window = onUi {
            ComposeWindow(skiaLayerAnalytics = frames).apply {
                title = "MagicPaper — isolated scroll profile"
                setSize(1440, 900)
                setLocationRelativeTo(null)
                defaultCloseOperation = javax.swing.WindowConstants.DO_NOTHING_ON_CLOSE
                exceptionHandler = androidx.compose.ui.window.WindowExceptionHandler { failure.compareAndSet(null, it) }
                setContent {
                    CompositionLocalProvider(LocalWindowScope provides this,
                        LocalChatPresentation provides DefaultChatPresentation) {
                        PaperTheme {
                            Box(Modifier.fillMaxSize().paperTitleBarFrost(56.dp, blurContent = false)) {
                                PaperBackground(animation.value, Modifier.fillMaxSize())
                                ResearchWorkspaceContent(state, onForkQuestion = {}) {
                                    PaperResearchReading {
                                        MessagesList(session, busy = false, listState = list, modifier = Modifier.fillMaxSize(),
                                            onEdit = { _, _ -> Result.success(Unit) }, onDelete = { Result.success(Unit) },
                                            onFork = { Result.success("fixture-fork") }, onFollowUp = { _, _ -> },
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
                }
                isVisible = true
                toFront()
                requestFocus()
            }
        }
        fun checkFailure() { failure.get()?.let { throw AssertionError("Native fixture failed", it) } }
        fun waitUntil(timeoutMillis: Long, condition: () -> Boolean) {
            val deadline = System.nanoTime() + timeoutMillis * 1_000_000
            while (!condition() && System.nanoTime() < deadline) { checkFailure(); Thread.sleep(20) }
            checkFailure()
            assertTrue(condition(), "Native fixture did not reach its expected state within ${timeoutMillis}ms")
        }
        try {
            waitUntil(20_000) { onUi { list.layoutInfo.totalItemsCount >= 400 && window.isFocused } }
            onUi { list.requestScrollToItem(list.layoutInfo.totalItemsCount - 1) }
            Thread.sleep(1_000)
            report += onUi { "backend=${window.renderApi} scale=${window.graphicsConfiguration.defaultTransform.scaleX} focused=${window.isFocused}" }
            val end = onUi { list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset }
            // Compose's pinned version subscribes wheel input on its container, not the native canvas.
            val wheelTarget = onUi { descendants(window.contentPane).firstOrNull { it.mouseWheelListeners.isNotEmpty() }
                ?: error("No wheel listener in the isolated ComposeWindow") }
            val layer = onUi { descendants(window.contentPane).filterIsInstance<SkiaLayer>().single() }
            fun paperPixels(): List<Int> = onUi {
                requireNotNull(layer.screenshot()).use { bitmap ->
                    (120 until bitmap.height - 30 step 10).flatMap { y ->
                        (2..8).map { x -> bitmap.getColor(x, y) }
                    }
                }
            }
            report += "wheelTarget=${wheelTarget.javaClass.name} size=${onUi { wheelTarget.size }}"
            report += "initial end=$end totalItems=${onUi { list.layoutInfo.totalItemsCount }}"
            onUi {
                wheelTarget.dispatchEvent(MouseEvent(wheelTarget, MouseEvent.MOUSE_ENTERED, System.currentTimeMillis(),
                    0, wheelTarget.width / 2, wheelTarget.height / 2, 0, false))
                wheelTarget.dispatchEvent(MouseEvent(wheelTarget, MouseEvent.MOUSE_MOVED, System.currentTimeMillis(),
                    0, wheelTarget.width / 2, wheelTarget.height / 2, 0, false))
            }
            for ((label, enabled) in listOf("off-first" to false, "on" to true, "off-repeat" to false)) {
                onUi { animation.value = enabled; list.requestScrollToItem(end.first, end.second) }
                Thread.sleep(2_000)
                assertTrue(onUi { window.isFocused }, "Native paper eligibility requires the fixture to stay focused")
                val paperBefore = paperPixels()
                val idleStart = System.nanoTime()
                Thread.sleep(1_500)
                val idle = frames.snapshot(idleStart, System.nanoTime())
                val changedPaperPixels = paperBefore.zip(paperPixels()).count { (a, b) -> a != b }
                report += "$label idle nativeFrames=${idle.size}/1.5s"
                report += "$label changedPaperPixels=$changedPaperPixels/${paperBefore.size}"
                if (enabled) report += "animationObserved=${changedPaperPixels > 0} (native picture gutter changed; eligibility policy retained)"
                val start = onUi { list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset }
                val events = mutableListOf<Double>()
                val started = System.nanoTime()
                repeat(240) {
                    val eventStart = System.nanoTime()
                    onUi {
                        wheelTarget.dispatchEvent(MouseWheelEvent(wheelTarget, MouseWheelEvent.MOUSE_WHEEL,
                            System.currentTimeMillis(), 0, wheelTarget.width / 2, wheelTarget.height / 2,
                            0, false, MouseWheelEvent.WHEEL_UNIT_SCROLL, 3, -1))
                    }
                    events += (System.nanoTime() - eventStart) / 1_000_000.0
                    val remaining = 16_666_667L - (System.nanoTime() - eventStart)
                    if (remaining > 0) Thread.sleep(remaining / 1_000_000, (remaining % 1_000_000).toInt())
                    checkFailure()
                }
                val stopped = System.nanoTime()
                val nativeFrames = frames.snapshot(started, stopped)
                val finish = onUi { list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset }
                report += "$label position $start -> $finish nativeFrames=${nativeFrames.size} elapsedMs=${(stopped-started)/1_000_000.0}"
                report += "$label wheel event round-trip ${events.summary()}"
                report += "$label native render submission ${nativeFrames.map { (it.end-it.start)/1_000_000.0 }.summary()}"
                report += "$label frame-start gaps ${nativeFrames.zipWithNext { a,b -> (b.start-a.start)/1_000_000.0 }.summary()}"
                File(directory, "$label-frames.csv").writeText("startNanos,endNanos,renderMillis\n" +
                    nativeFrames.joinToString("\n") { "${it.start},${it.end},${(it.end-it.start)/1_000_000.0}" })
                File(directory, "timing.txt").writeText(report.joinToString("\n", postfix = "\n"))
                assertTrue(finish.first < start.first, "Native wheel events must scroll the completed article ($label); $start -> $finish")
                waitUntil(3_000) { onUi { !list.isScrollInProgress } }
            }
        } finally {
            File(directory, "timing.txt").writeText(report.joinToString("\n", postfix = "\n"))
            onUi { window.dispose() }
        }
    }

    private class FrameAnalytics : SkiaLayerAnalytics {
        private val frames = ConcurrentLinkedQueue<Frame>()
        override fun device(skikoVersion: String, os: OS, api: GraphicsApi, deviceName: String?) =
            object : SkiaLayerAnalytics.DeviceAnalytics {
                private var started = 0L
                override fun beforeFrameRender() { started = System.nanoTime() }
                override fun afterFrameRender() { frames += Frame(started, System.nanoTime()) }
            }
        fun snapshot(start: Long, end: Long) = frames.filter { it.start >= start && it.end <= end }.sortedBy { it.start }
    }
    private data class Frame(val start: Long, val end: Long)
    private fun descendants(component: Component): List<Component> = listOf(component) +
        if (component is Container) component.components.flatMap(::descendants) else emptyList()
    private fun List<Double>.summary(): String {
        if (isEmpty()) return "n=0"
        val sorted = sorted()
        fun percentile(p: Double) = sorted[(ceil(size * p).toInt()-1).coerceIn(indices)]
        return "n=$size p50=${percentile(.5)} p95=${percentile(.95)} max=${sorted.last()} ms"
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

}
