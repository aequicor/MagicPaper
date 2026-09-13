package io.aequicor.magicpaper.ui.screens

import androidx.compose.material3.Text
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.*
import androidx.compose.ui.use
import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.ui.CodingSessionUi
import io.aequicor.magicpaper.ui.components.CodingComposerDraft
import io.aequicor.magicpaper.ui.theme.MagicPaperTheme
import java.io.File
import kotlin.test.*

@OptIn(ExperimentalComposeUiApi::class)
class ResearchModeRenderTest {
    private class Screen(val width: Int) : AutoCloseable {
        val mode = mutableStateOf(CodingInteractionMode.CODE)
        val busy = mutableStateOf(false)
        val waiting = mutableStateOf(false)
        val draft = CodingComposerDraft().apply {
            text.value = "Объясни этот код"
            attachments.value = listOf(Attachment.fromBytes("note.txt", "text/plain", "context".encodeToByteArray()))
        }
        private var frame = 0L
        private val scene = ImageComposeScene(width, 680) {
            MagicPaperTheme { Surface {
                val session = CodingSession("s", "p", "Разбор проекта", 1, engine = CodingEngine.PI,
                    planningMode = mode.value == CodingInteractionMode.PLANNING, researchMode = mode.value == CodingInteractionMode.RESEARCH)
                CodingChat(CodingProject("p", "Проект", "/fixture", 1), CodingSessionUi(session, messages = listOf(
                    CodingMessage("answer", CodingRole.AGENT, "Сохранённый ответ", createdAt = 1)), awaitingUser = waiting.value),
                    busy.value, true, { _, _ -> }, {}, { _, _ -> },
                    composerDraft = draft, modelChip = { Text("Тестовая модель") },
                    onInteractionMode = { mode.value = it }, modeSwitchEnabled = !waiting.value,
                    interactions = if (waiting.value) listOf(UserInteractionRequest("q", "p", "s", InteractionKind.RUNTIME,
                        listOf(PlanningQuestion("question", "Что уточнить?", QuestionKind.TEXT)))) else emptyList())
            } }
        }
        init { render() }
        fun render() { repeat(12) { scene.render(++frame * 32_000_000L).close(); Thread.sleep(10) } }
        private fun nodes(): List<SemanticsNode> {
            fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + if (node.config.isClearingSemantics) emptyList() else node.children.flatMap(::walk)
            return scene.semanticsOwners.flatMap { walk(it.unmergedRootSemanticsNode) }
        }
        fun text(label: String): SemanticsNode = nodes().first { it.config.getOrNull(SemanticsProperties.Text)?.any { t -> t.text == label } == true }
        fun hasText(label: String) = nodes().any { it.config.getOrNull(SemanticsProperties.Text)?.any { t -> t.text.contains(label) } == true }
        fun menu() { click(nodes().single { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Инструменты и параметры сессии") == true }) }
        fun click(node: SemanticsNode) {
            scene.sendPointerEvent(PointerEventType.Press, node.boundsInRoot.center)
            scene.sendPointerEvent(PointerEventType.Release, node.boundsInRoot.center)
            render()
        }
        fun snapshot(name: String) {
            val directory = File("build/reports/research-mode").apply { mkdirs() }
            File(directory, "$name-$width.png").writeBytes(scene.render(++frame * 32_000_000L).use { it.encodeToData()!!.use { data -> data.bytes } })
        }
        override fun close() = scene.close()
    }

    @Test fun changesModeAndKeepsDialogueDraftAttachmentAndModelAtBothWidths() {
        for (width in listOf(390, 1000)) Screen(width).use { s ->
            s.menu(); s.snapshot("menu"); s.click(s.text("Режим исследования"))
            assertEquals(CodingInteractionMode.RESEARCH, s.mode.value)
            assertTrue(s.hasText("Исследование · код защищён")); assertTrue(s.hasText("Сохранённый ответ"))
            assertTrue(s.hasText("Тестовая модель")); assertTrue(s.hasText("note.txt"))
            assertEquals("Объясни этот код", s.draft.text.value)
            s.snapshot("research")
            s.menu(); s.click(s.text("Обычный режим"))
            assertEquals(CodingInteractionMode.CODE, s.mode.value)
            s.menu(); s.click(s.text("Режим планирования"))
            s.menu(); s.click(s.text("Режим исследования"))
            assertEquals(CodingInteractionMode.PLANNING, s.mode.value)
        }
    }

    @Test fun busyAndQuestionnaireKeepResearchVisibleAndPreventSwitching() {
        for (width in listOf(390, 1000)) Screen(width).use { s ->
            s.mode.value = CodingInteractionMode.RESEARCH; s.busy.value = true; s.render()
            s.menu(); s.click(s.text("Обычный режим"))
            assertEquals(CodingInteractionMode.RESEARCH, s.mode.value)
            s.busy.value = false; s.waiting.value = true; s.render()
            assertTrue(s.hasText("Исследование · код защищён")); assertTrue(s.hasText("Что уточнить?"))
            assertEquals("Объясни этот код", s.draft.text.value); assertEquals(1, s.draft.attachments.value.size)
            s.snapshot("questionnaire")
        }
    }
}
