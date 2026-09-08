package io.aequicor.magicpaper.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.use
import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.ui.theme.MagicPaperTheme
import java.io.File
import kotlin.test.*

@OptIn(ExperimentalComposeUiApi::class)
class PlanningQuestionWizardTest {
    private fun ImageComposeScene.nodes(): List<SemanticsNode> {
        fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
        return semanticsOwners.flatMap { walk(it.unmergedRootSemanticsNode) }
    }
    private fun ImageComposeScene.node(tag: String) = nodes().first { it.config.getOrNull(SemanticsProperties.TestTag) == tag }
    private var frame = 0L
    private fun ImageComposeScene.draw() { repeat(5) { render(++frame * 16_000_000L).close() } }
    private fun ImageComposeScene.click(tag: String) {
        val n = node(tag)
        assertNull(n.config.getOrNull(SemanticsProperties.Disabled), "$tag should be enabled")
        n.config[SemanticsActions.OnClick].action!!.invoke(); draw()
    }
    private fun ImageComposeScene.snapshot(name: String) {
        val dir = File("build/reports/questionnaire").apply { mkdirs() }
        File(dir, "$name.png").writeBytes(render(++frame * 16_000_000L).use { it.encodeToData()!!.use { d -> d.bytes } })
    }

    @Test fun preservesChoicesAndCommentsAndOnlySubmitsAfterFinalReviewAtBothWidths() {
        for (width in listOf(390, 1000)) {
            val questions = listOf(
                PlanningQuestion("format", "Формат результата?", QuestionKind.SINGLE, listOf(QuestionOption("pdf", "PDF"), QuestionOption("docx", "DOCX"))),
                PlanningQuestion("features", "Какие возможности нужны?", QuestionKind.MULTIPLE, listOf(QuestionOption("edit", "Редактирование"), QuestionOption("export", "Экспорт"))),
                PlanningQuestion("extra", "Дополнительные требования?"))
            var draft by mutableStateOf(QuestionnaireDraft())
            var busy by mutableStateOf(false)
            var submitted: List<PlanningAnswer>? = null
            val request = UserInteractionRequest("request", "p", "s", InteractionKind.QUESTION, questions, context = "Оркестратор · Этап 3")
            ImageComposeScene(width, 640) {
                MagicPaperTheme { UserInteractionDock(request.copy(submitting = busy), draft, { draft = it }, { submitted = it },
                    Modifier.fillMaxWidth().heightIn(max = 620.dp), queuedCount = 1) }
            }.use { scene ->
                scene.draw(); scene.snapshot("question-$width")
                assertTrue(scene.nodes().none { it.config.getOrNull(SemanticsProperties.TestTag) == "questionnaire.next" })
                assertEquals("1/3 · Формат результата?", scene.node("questionnaire.title").config[SemanticsProperties.Text].single().text)
                assertNotNull(scene.node("questionnaire.back").config.getOrNull(SemanticsProperties.Disabled))
                scene.click("questionnaire.option.pdf")
                assertEquals(1, draft.index); assertNull(submitted)
                scene.click("questionnaire.option.edit"); scene.click("questionnaire.option.export")
                assertTrue(scene.nodes().none { it.config.getOrNull(SemanticsProperties.TestTag) == "questionnaire.skip" })
                assertEquals(scene.node("questionnaire.back").boundsInRoot.top, scene.node("questionnaire.next").boundsInRoot.top)
                scene.click("questionnaire.back")
                assertEquals(listOf("pdf"), draft.answers.first().selected)
                scene.click("questionnaire.option.docx")
                assertEquals(listOf("edit", "export"), draft.answers.first { it.questionId == "features" }.selected)
                scene.node("questionnaire.custom").config[SemanticsActions.SetText].action!!.invoke(AnnotatedString("Ещё комментарий")); scene.draw()
                scene.click("questionnaire.next"); scene.click("questionnaire.skip")
                assertTrue(draft.reviewing); assertNull(submitted)
                scene.snapshot("review-$width")
                scene.click("questionnaire.return"); assertEquals(0, draft.index)
                scene.click("questionnaire.next"); scene.click("questionnaire.next"); scene.click("questionnaire.skip")
                busy = true; scene.draw()
                assertNotNull(scene.node("questionnaire.confirm").config.getOrNull(SemanticsProperties.Disabled))
                assertNull(submitted)
                busy = false; scene.draw(); scene.click("questionnaire.confirm")
                assertEquals(listOf(PlanningAnswer("format", listOf("docx")), PlanningAnswer("features", listOf("edit", "export"), "Ещё комментарий"),
                    PlanningAnswer("extra", skipped = true)), submitted)
            }
        }
    }

    @Test fun permissionNeedsExplicitChoiceAndReviewHasNoFreeTextOrSkip() {
        for (width in listOf(390, 1000)) {
            var draft by mutableStateOf(QuestionnaireDraft())
            var submitted = 0
            val q = PlanningQuestion("permission", "Разрешить выполнение команды?", QuestionKind.SINGLE,
                listOf(QuestionOption("yes", "Да"), QuestionOption("no", "Нет")), allowCustomInput = false, canSkip = false)
            val request = UserInteractionRequest("approval", "p", "s", InteractionKind.APPROVAL, listOf(q), details = "Причина: проверка сборки\n\n./gradlew :shared:jvmTest")
            ImageComposeScene(width, 480) {
                MagicPaperTheme { UserInteractionDock(request, draft, { draft = it }, { submitted++ }, Modifier.fillMaxWidth().heightIn(max = 460.dp)) }
            }.use { scene ->
                scene.draw(); scene.snapshot("permission-$width")
                assertTrue(scene.nodes().none { it.config.getOrNull(SemanticsProperties.TestTag) == "questionnaire.custom" })
                assertNotNull(scene.node("questionnaire.skip").config.getOrNull(SemanticsProperties.Disabled))
                scene.click("questionnaire.option.yes"); assertEquals(0, submitted)
                scene.click("questionnaire.return"); scene.click("questionnaire.option.no"); assertEquals(0, submitted)
                scene.click("questionnaire.confirm"); assertEquals(1, submitted)
            }
        }
    }

    @Test fun longFailureKeepsHeaderAndConfirmationWithinPanel() {
        for (width in listOf(390, 1000)) {
            val q = PlanningQuestion("recovery", "Выполнение остановлено. Как продолжить?", QuestionKind.SINGLE,
                listOf(QuestionOption("retry", "Исправить и проверить"), QuestionOption("leave", "Оставить остановленной")))
            val request = UserInteractionRequest("failure", "p", "s", InteractionKind.RECOVER_PLAN, listOf(q), details = "Причина ошибки. ".repeat(300))
            val draft = QuestionnaireDraft(listOf(PlanningAnswer("recovery", listOf("leave"))), reviewing = true)
            ImageComposeScene(width, 520) {
                MagicPaperTheme { UserInteractionDock(request, draft, {}, {}, Modifier.fillMaxWidth().heightIn(max = 500.dp)) }
            }.use { scene ->
                scene.draw(); scene.snapshot("failure-$width")
                for (tag in listOf("questionnaire.title", "questionnaire.return", "questionnaire.confirm")) {
                    val bounds = scene.node(tag).boundsInRoot
                    assertTrue(bounds.top >= 0 && bounds.bottom <= 500 && bounds.left >= 0 && bounds.right <= width, "$tag: $bounds")
                }
            }
        }
    }
}
