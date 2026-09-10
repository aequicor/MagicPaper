package io.aequicor.magicpaper.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.use
import io.aequicor.magicpaper.designsystem.LocalPaperColors
import io.aequicor.magicpaper.designsystem.PaperTheme
import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.ui.CodingSessionUi
import java.io.File
import kotlin.test.*

@OptIn(ExperimentalComposeUiApi::class)
class CodingBlockedStatusRenderTest {
    @Test fun unknownWorkerShowsItsBlockerBesideTheComposerAndYieldsToRealQuestions() {
        val project = CodingProject("project", "MagicPaper", "/project", 0)
        val worker = CodingSession("worker", project.id, "Миниатюры в поле ввода", 0,
            parentSessionId = "parent", planId = "plan", stageId = "stage", role = CodingSessionRole.WORKER,
            observedState = SessionObservedState.UNKNOWN)
        val completed = Plan("plan", project.id, "Добавить миниатюры", parentSessionId = "parent",
            phase = ExecutionPhase.COMPLETE,
            milestones = listOf(Milestone("stage", worker.name, status = MilestoneStatus.DONE)))
        val blocked = CodingSessionUi(worker, plan = completed, messages = listOf(
            CodingMessage("result", CodingRole.AGENT, "Готово. Изменения сохранены, проверки прошли.", createdAt = 1)))
        val banner = "Выполнение остановлено. Результат запуска не подтверждён."
        for (width in listOf(430, 1000)) {
            val current = mutableStateOf(blocked)
            ImageComposeScene(width, 680) {
                PaperTheme {
                    Box(Modifier.fillMaxSize().background(LocalPaperColors.current.canvas)) {
                        CodingChat(project, current.value, false, true, { _, _ -> }, {}, { _, _ -> })
                    }
                }
            }.use { scene ->
                var frame = 0L
                fun render() { repeat(12) { scene.render(++frame * 32_000_000L).close(); Thread.sleep(10) } }
                fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
                fun nodes() = scene.semanticsOwners.flatMap { walk(it.unmergedRootSemanticsNode) }
                fun text(label: String) = nodes().firstOrNull {
                    it.config.getOrNull(SemanticsProperties.Text).orEmpty().any { value -> value.text == label }
                }
                fun assertVisible(node: SemanticsNode, label: String) {
                    val bounds = node.boundsInRoot
                    assertTrue(bounds.width > 0 && bounds.height > 0, "$label has no visible bounds at $width")
                    assertTrue(bounds.left >= 0 && bounds.right <= width && bounds.top >= 0 && bounds.bottom <= 680,
                        "$label is clipped at $width: $bounds")
                }
                render()
                for (label in listOf(banner, "Что нужно сделать?", "Отправить"))
                    assertVisible(assertNotNull(text(label), "$label missing at $width"), label)
                val input = nodes().first { it.config.getOrNull(SemanticsActions.SetText) != null }
                assertVisible(input, "Composer")
                assertTrue(assertNotNull(text(banner)).boundsInRoot.bottom < input.boundsInRoot.top,
                    "Blocker must not cover the composer at $width")
                val directory = File("build/reports/coding-blocked-status").apply { mkdirs() }
                File(directory, "blocked-$width.png").writeBytes(scene.render(++frame * 32_000_000L).use {
                    it.encodeToData()!!.use { data -> data.bytes }
                })

                current.value = blocked.copy(session = worker.copy(observedState = SessionObservedState.COMPLETED))
                render()
                assertNull(text(banner), "Ready worker retains the blocked banner at $width")
                assertVisible(assertNotNull(text("Что нужно сделать?")), "Ready composer")

                val question = "Как продолжить проверку?"
                current.value = blocked.copy(interactions = listOf(UserInteractionRequest("question", project.id,
                    worker.id, InteractionKind.RUNTIME, listOf(PlanningQuestion("decision", question, QuestionKind.TEXT)))))
                render()
                assertNull(text(banner), "Questionnaire must replace the blocked banner at $width")
                assertVisible(assertNotNull(text("1/1 · $question"), "Questionnaire missing at $width"), question)
                assertNull(text("Что нужно сделать?"), "Ordinary composer must yield to the questionnaire at $width")
            }
        }
    }
}
