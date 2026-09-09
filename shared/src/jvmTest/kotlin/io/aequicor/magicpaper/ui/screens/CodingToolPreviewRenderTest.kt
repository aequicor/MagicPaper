package io.aequicor.magicpaper.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import io.aequicor.magicpaper.domain.tools.ToolPhase
import io.aequicor.magicpaper.domain.CodingStep
import io.aequicor.magicpaper.domain.CodingStepKind
import io.aequicor.magicpaper.ui.theme.MagicPaperTheme
import kotlin.test.*

@OptIn(ExperimentalComposeUiApi::class)
class CodingToolPreviewRenderTest {
    private class Card(initial: CodingStep) : AutoCloseable {
        val step = mutableStateOf(initial)
        var height = 0
        private var frame = 0L
        val scene = ImageComposeScene(680, 600) {
            MagicPaperTheme {
                Column(Modifier.fillMaxWidth().onSizeChanged { height = it.height }) {
                    CodingStepRow(step.value, live = true)
                }
            }
        }

        fun renderFrame() { scene.render(++frame * 32_000_000L).close(); Thread.sleep(10) }
        fun render() { repeat(10) { renderFrame() } }
        fun texts(): List<String> {
            fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
            return scene.semanticsOwners.flatMap { walk(it.unmergedRootSemanticsNode) }
                .flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }.map { it.text }
        }
        fun toggle() {
            scene.sendPointerEvent(PointerEventType.Press, Offset(120f, 18f))
            scene.sendPointerEvent(PointerEventType.Release, Offset(120f, 18f))
            render()
        }
        override fun close() = scene.close()
    }

    @Test fun waitingAndCancellationRemainVisibleInTheExistingToolCard() {
        Card(CodingStep(CodingStepKind.TOOL, "Вопросы пользователю · Формат результата", tool = "questionnaire",
            callId = "stable", running = true, toolPhase = ToolPhase.WAITING)).use { card ->
            card.render()
            fun labels() = card.scene.semanticsOwners.flatMap { owner ->
                fun all(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::all)
                all(owner.rootSemanticsNode)
            }.flatMap { it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() }
            assertTrue("Ожидается ответ пользователя" in labels(), labels().toString())
            val before = card.height
            card.step.value = card.step.value.copy(running = false, ok = false, toolPhase = ToolPhase.CANCELLED)
            card.render()
            assertTrue("Вызов отменён" in labels(), labels().toString())
            assertTrue(card.height <= before, "Completion must not expand the card")
            val output = java.io.File("build/reports/agent-tools").apply { mkdirs() }
            card.scene.render().encodeToData()?.bytes?.let { java.io.File(output, "cancelled-tool.png").writeBytes(it) }
        }
    }

    @Test fun megabyteToolPayloadsStayOutOfCollapsedTextLayoutDuringUpdates() {
        for (tool in listOf("bash", "read", "write")) {
            val payload = "large file payload; ".repeat(60_000)
            val title = "⚒ $tool · " + payload
            val initial = CodingStep(if (tool == "bash") CodingStepKind.EXEC else CodingStepKind.TOOL,
                title, tool = tool, callId = tool, running = true, result = payload)
            Card(initial).use { card ->
                card.render()
                val height = card.height
                val texts = card.texts()
                assertTrue(texts.any { it.startsWith("⚒ $tool · ") }, "The tool preview must render")
                assertTrue(texts.all { it.length <= 513 }, "Collapsed Text must never receive the full payload")
                repeat(3) { chunk ->
                    card.step.value = card.step.value.copy(result = payload + " output $chunk")
                    card.render()
                    assertEquals(texts, card.texts(), "Hidden output must not change the rendered text")
                    assertEquals(height, card.height, "Hidden output must not resize the card")
                }
                card.step.value = card.step.value.copy(running = false, ok = false)
                card.render()
                assertTrue(card.texts().all { it.length <= 513 }, "Completion must retain the bounded preview")
            }
        }
    }

    @Test fun disclosureRestoresFullCommandAndLatestOutput() {
        val title = "⚒ bash · cat <<'EOF'\n" + (1..12).joinToString("\n") { "command line $it" }
        val output = "first output\nsecond output"
        Card(CodingStep(CodingStepKind.EXEC, title, callId = "command", running = true, result = output)).use { card ->
            card.render()
            val collapsed = card.texts()
            assertFalse(title in collapsed)
            assertFalse(output in collapsed)
            card.toggle()
            assertTrue(title in card.texts(), "Opening must show the original command without truncation")
            assertTrue(output in card.texts())
            val completed = output + "\nCommand completed"
            card.step.value = card.step.value.copy(result = completed, running = false)
            card.render()
            assertTrue(title in card.texts(), "Completion must preserve disclosure state")
            assertTrue(completed in card.texts())
            card.toggle()
            assertFalse(title in card.texts())
            assertFalse(completed in card.texts())
            card.toggle()
            assertTrue(completed in card.texts(), "Reopening must show the latest full output")
        }
    }

    @Test fun completionShrinksTheRunningLabelOverSeveralFrames() {
        for (ok in listOf(true, false)) {
            Card(CodingStep(CodingStepKind.EXEC, "⚒ command · ./gradlew check",
                callId = "command", running = true)).use { card ->
                card.render()
                val runningHeight = card.height
                card.step.value = card.step.value.copy(running = false, ok = ok)
                val heights = List(12) { card.renderFrame(); card.height }
                val completedHeight = heights.last()
                assertTrue(completedHeight < runningHeight)
                assertTrue(heights.any { it in (completedHeight + 1) until runningHeight },
                    "Completion must shrink smoothly, not jump from $runningHeight to $completedHeight: $heights")
                assertTrue(heights.zipWithNext().all { (before, after) -> after <= before },
                    "Completion must not bounce or briefly expand: $heights")
                assertFalse(card.texts().any { it.startsWith("Выполняется") })
            }
        }
    }
}
