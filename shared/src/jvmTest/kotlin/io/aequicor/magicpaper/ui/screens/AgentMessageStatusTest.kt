package io.aequicor.magicpaper.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.use
import io.aequicor.magicpaper.domain.CodingDraft
import io.aequicor.magicpaper.domain.CodingEvent
import io.aequicor.magicpaper.domain.CodingRunRecorder
import io.aequicor.magicpaper.domain.CodingStep
import io.aequicor.magicpaper.domain.CodingStepKind
import io.aequicor.magicpaper.ui.theme.MagicPaperTheme
import java.io.File
import kotlin.test.*

class AgentMessageStatusTest {
    @Test fun summaryOnlyHasNoDisclosureAndFullThinkingKeepsGrowingAcrossTools() {
        val recorder = CodingRunRecorder()
        val state = mutableStateOf(CodingDraft(active = true))
        var height = 0
        ImageComposeScene(680, 480) {
            MagicPaperTheme { Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).onSizeChanged { height = it.height }) {
                AgentMessageStatus(state.value, expanded = true, onToggle = {})
            } }
        }.use { scene ->
            var frame = 0L
            fun render() { repeat(40) { scene.render(++frame * 32_000_000L).close(); Thread.sleep(25) } }
            render()
            val emptyHeight = height
            recorder.apply(CodingEvent.ThinkingDelta("**Running final verification**", "s", summary = true))
            state.value = recorder.draft(true)
            render()
            assertEquals(emptyHeight, height, "Короткая сводка не создаёт блок размышлений")
            assertEquals("Running final verification", currentThinkingSummary(state.value.reasoningSummary))

            recorder.apply(CodingEvent.ThinkingDelta("First reasoning section explains why the state must be preserved.", "r1"))
            state.value = recorder.draft(true)
            render()
            val firstHeight = height
            assertTrue(firstHeight > emptyHeight)
            recorder.apply(CodingEvent.ToolStarted("read", "state", "t"))
            recorder.apply(CodingEvent.ToolFinished("read", false, "t"))
            recorder.apply(CodingEvent.ThinkingDelta("Second reasoning section uses the result of the read.\n\nThird section checks the alternative and the final decision.", "r2"))
            state.value = recorder.draft(true)
            render()
            assertTrue(height > firstHeight, "Блок накапливает все фрагменты, включая текст до инструмента")
            val output = File("build/reports/agent-reasoning").apply { mkdirs() }
            File(output, "full-thinking.png").writeBytes(scene.render(++frame * 32_000_000L).use { it.encodeToData()!!.use { data -> data.bytes } })

            state.value = CodingDraft(steps = listOf(CodingStep(CodingStepKind.SUMMARY, "Running final verification")), active = true)
            render()
            assertEquals(emptyHeight, height)
            File(output, "summary-only.png").writeBytes(scene.render(++frame * 32_000_000L).use { it.encodeToData()!!.use { data -> data.bytes } })
        }
    }

    @Test fun clickExpandsAndLiveThinkingUpdatesWithoutCollapsing() {
        val draft = mutableStateOf(CodingDraft(thinking = "**Проверяю интерфейс**\n\nПроверяю расположение кнопки.", active = true))
        val expanded = mutableStateOf(false)
        var height = 0
        ImageComposeScene(390, 320) {
            MagicPaperTheme { Column(Modifier.fillMaxWidth().onSizeChanged { height = it.height }) {
                AgentMessageStatus(draft.value, expanded.value) { expanded.value = !expanded.value }
            } }
        }.use { scene ->
            var frame = 0L
            var toggleY = 0f
            fun render() { repeat(20) { scene.render(++frame * 32_000_000L).close(); Thread.sleep(25) } }
            fun toggle() {
                scene.sendPointerEvent(PointerEventType.Press, Offset(100f, toggleY))
                scene.sendPointerEvent(PointerEventType.Release, Offset(100f, toggleY))
                render()
            }
            render()
            val collapsedHeight = height
            // The activity summary is separate from the disclosure below it.
            scene.sendPointerEvent(PointerEventType.Press, Offset(100f, 12f))
            scene.sendPointerEvent(PointerEventType.Release, Offset(100f, 12f))
            render()
            assertFalse(expanded.value, "Краткий статус не раскрывает размышления")
            toggleY = collapsedHeight - 12f
            toggle()
            assertTrue(expanded.value)
            assertTrue(height > collapsedHeight)
            val firstHeight = height
            draft.value = draft.value.copy(thinking = "**Проверяю интерфейс**\n\nПроверяю расположение кнопки.\n\n**Проверяю обработчик клика**\n\nСопоставляю его с текущим состоянием панели.")
            render()
            assertTrue(expanded.value)
            assertTrue(height > firstHeight)
            val output = File("build/reports/agent-status").apply { mkdirs() }
            File(output, "expanded.png").writeBytes(scene.render(++frame * 32_000_000L).use { it.encodeToData()!!.use { data -> data.bytes } })
            toggle()
            assertFalse(expanded.value)
            assertTrue(height < firstHeight)
        }
    }

    @Test fun runningCommandExpandsBeforeOutputAndStaysExpandedUntilCompletion() {
        val recorder = CodingRunRecorder()
        recorder.apply(CodingEvent.ToolStarted("command",
            "/bin/zsh -lc './gradlew :shared:jvmTest --tests io.aequicor.magicpaper.domain.PlanningExecutionServiceTest'",
            callId = "command-1", isExec = true))
        val step = mutableStateOf(recorder.timeline().single())
        var height = 0
        ImageComposeScene(390, 480) {
            MagicPaperTheme { Column(Modifier.fillMaxWidth().onSizeChanged { height = it.height }) {
                CodingStepRow(step.value, live = true)
            } }
        }.use { scene ->
            var frame = 0L
            fun render() { repeat(20) { scene.render(++frame * 32_000_000L).close(); Thread.sleep(25) } }
            fun snapshot(name: String) {
                val output = File("build/reports/agent-status").apply { mkdirs() }
                File(output, name).writeBytes(scene.render(++frame * 32_000_000L).use {
                    it.encodeToData()!!.use { data -> data.bytes }
                })
            }
            render()
            val collapsedHeight = height
            scene.sendPointerEvent(PointerEventType.Press, Offset(100f, 16f))
            scene.sendPointerEvent(PointerEventType.Release, Offset(100f, 16f))
            render()
            assertTrue(height > collapsedHeight, "Команду можно раскрыть до первого вывода")
            snapshot("command-running.png")
            val commandHeight = height

            recorder.apply(CodingEvent.ToolProgress("command", "command-1", "Проверки выполняются…"))
            step.value = recorder.timeline().single()
            render()
            assertTrue(height > commandHeight, "Вывод добавляется к раскрытой команде")
            snapshot("command-output.png")
            val runningHeight = height

            recorder.apply(CodingEvent.ToolFinished("command", false, "command-1", "BUILD SUCCESSFUL"))
            step.value = recorder.timeline().single()
            render()
            assertTrue(height < runningHeight, "Подпись о выполнении исчезает после завершения")
            assertTrue(height > collapsedHeight, "Завершённая команда остаётся раскрытой")
            snapshot("command-completed.png")
        }
    }
}
