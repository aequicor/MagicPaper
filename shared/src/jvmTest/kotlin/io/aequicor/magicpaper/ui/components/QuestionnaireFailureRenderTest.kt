package io.aequicor.magicpaper.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.use
import io.aequicor.magicpaper.designsystem.PaperTheme
import io.aequicor.magicpaper.domain.InteractionKind
import io.aequicor.magicpaper.domain.PlanningAnswer
import io.aequicor.magicpaper.domain.PlanningQuestion
import io.aequicor.magicpaper.domain.QuestionKind
import io.aequicor.magicpaper.domain.QuestionOption
import io.aequicor.magicpaper.domain.QuestionnaireDraft
import io.aequicor.magicpaper.domain.UserInteractionRequest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalComposeUiApi::class)
class QuestionnaireFailureRenderTest {
    @Test fun rejectedConfirmationKeepsErrorAndRetryVisibleWhileLongDetailsScroll() {
        val output = File("build/reports/questionnaire-failure").apply { mkdirs() }
        for ((width, scale) in listOf(390 to 1f, 1000 to 1f, 390 to 2f)) {
            val question = PlanningQuestion("decision", "Выполнение остановлено. Как продолжить?", QuestionKind.SINGLE,
                listOf(QuestionOption("retry", "Повторить запуск"), QuestionOption("leave", "Оставить остановленной")))
            var request by mutableStateOf(UserInteractionRequest("recovery", "project", "worker", InteractionKind.RECOVER_PLAN,
                listOf(question), details = "Предыдущая попытка завершилась без подтверждённого результата. ".repeat(300)))
            val reviewed = QuestionnaireDraft(listOf(PlanningAnswer("decision", listOf("retry"))), reviewing = true)
            var draft by mutableStateOf(reviewed)
            val sent = mutableListOf<List<PlanningAnswer>>()
            var frame = 0L
            ImageComposeScene(width, 640, density = Density(1f, scale)) {
                PaperTheme { UserInteractionDock(request, draft, { draft = it }, {
                    sent += it
                    request = request.copy(submitting = true, error = null)
                }, Modifier.fillMaxWidth().heightIn(max = 620.dp)) }
            }.use { scene ->
                fun draw() = repeat(6) { scene.render(++frame * 16_000_000L).close() }
                draw()
                scene.node("questionnaire.confirm").config[SemanticsActions.OnClick].action!!.invoke()
                draw()
                assertEquals(listOf(reviewed.answers), sent)
                assertNotNull(scene.node("questionnaire.confirm").config.getOrNull(SemanticsProperties.Disabled))

                val failure = "Сначала подтвердите остановку предыдущего запуска"
                request = request.copy(submitting = false, error = failure)
                draw()
                val error = scene.node("questionnaire.error")
                val errorBounds = error.boundsInRoot
                val confirmBounds = scene.node("questionnaire.confirm").boundsInRoot
                assertEquals(failure, error.config[SemanticsProperties.Error])
                assertEquals(LiveRegionMode.Polite, error.config[SemanticsProperties.LiveRegion])
                assertTrue(errorBounds.height > 0f && errorBounds.top >= 0f && errorBounds.bottom <= confirmBounds.top,
                    "Failure must be visible above confirmation: $errorBounds, $confirmBounds")
                assertTrue(errorBounds.left >= 0f && errorBounds.right <= width.toFloat())
                assertTrue(confirmBounds.bottom <= 620f)
                assertFalse(scene.node("questionnaire.confirm").config.contains(SemanticsProperties.Disabled))
                assertEquals(reviewed, draft, "Failed submission must preserve the reviewed answer")
                scene.assertActionLabelFits("questionnaire.return", "Вернуться")
                scene.assertActionLabelFits("questionnaire.confirm", "Подтвердить")

                val scroll = scene.nodes().first { it.config.getOrNull(SemanticsActions.ScrollBy) != null }
                scroll.config[SemanticsActions.ScrollBy].action!!.invoke(0f, 1500f)
                draw()
                assertEquals(errorBounds, scene.node("questionnaire.error").boundsInRoot,
                    "Scrolling the previous failure details must not hide the new error")
                assertEquals(confirmBounds, scene.node("questionnaire.confirm").boundsInRoot)
                scene.render(++frame * 16_000_000L).use { image ->
                    File(output, "retry-error-$width-$scale.png").writeBytes(image.encodeToData()!!.use { it.bytes })
                }
                scene.node("questionnaire.confirm").config[SemanticsActions.OnClick].action!!.invoke()
                draw()
                assertEquals(listOf(reviewed.answers, reviewed.answers), sent)
            }
        }
    }

    @Test fun rejectedRetryCanReturnToChoicesAndConfirmLeavingStopped() {
        val output = File("build/reports/questionnaire-failure").apply { mkdirs() }
        for (scale in listOf(1f, 2f)) {
            val question = PlanningQuestion("decision", "Выполнение остановлено. Как продолжить?", QuestionKind.SINGLE,
                listOf(QuestionOption("retry", "Повторить запуск"), QuestionOption("leave", "Оставить остановленной")))
            var request by mutableStateOf(UserInteractionRequest("recovery", "project", "worker", InteractionKind.RECOVER_PLAN,
                listOf(question), details = "Исход предыдущей команды не подтверждён. ".repeat(300)))
            val retry = listOf(PlanningAnswer("decision", listOf("retry")))
            val leave = listOf(PlanningAnswer("decision", listOf("leave")))
            var draft by mutableStateOf(QuestionnaireDraft(retry, reviewing = true))
            val sent = mutableListOf<List<PlanningAnswer>>()
            var frame = 0L
            ImageComposeScene(390, 640, density = Density(1f, scale)) {
                PaperTheme { UserInteractionDock(request, draft, { draft = it }, {
                    sent += it
                    request = request.copy(submitting = true, error = null)
                }, Modifier.fillMaxWidth().heightIn(max = 620.dp)) }
            }.use { scene ->
                fun draw() = repeat(6) { scene.render(++frame * 16_000_000L).close() }
                draw()
                scene.node("questionnaire.confirm").config[SemanticsActions.OnClick].action!!.invoke()
                draw()
                val failure = "Не удалось подтвердить исход предыдущего запуска; повтор пока недоступен"
                request = request.copy(submitting = false, error = failure)
                draw()
                assertEquals(retry, draft.answers)
                assertEquals(failure, scene.node("questionnaire.error").config[SemanticsProperties.Error])
                assertFalse(scene.node("questionnaire.return").config.contains(SemanticsProperties.Disabled))
                scene.node("questionnaire.return").config[SemanticsActions.OnClick].action!!.invoke()
                draw()
                assertFalse(draft.reviewing)
                assertEquals(retry, draft.answers)
                val scroll = scene.nodes().first { it.config.getOrNull(SemanticsActions.ScrollBy) != null }
                scroll.config[SemanticsActions.ScrollBy].action!!.invoke(0f, 1_000_000f)
                draw()
                val option = scene.node("questionnaire.option.leave")
                assertFalse(option.config.contains(SemanticsProperties.Disabled))
                assertTrue(option.boundsInRoot.height > 0f && option.boundsInRoot.top >= 0f && option.boundsInRoot.bottom <= 620f,
                    "Leave must be reachable after a failed retry: ${option.boundsInRoot}")
                option.config[SemanticsActions.OnClick].action!!.invoke()
                draw()
                assertTrue(draft.reviewing)
                assertEquals(leave, draft.answers)
                assertEquals(listOf(retry), sent, "Changing the reviewed choice must not submit another action")
                assertFalse(scene.node("questionnaire.confirm").config.contains(SemanticsProperties.Disabled))
                scene.assertActionLabelFits("questionnaire.return", "Вернуться")
                scene.assertActionLabelFits("questionnaire.confirm", "Подтвердить")
                scene.render(++frame * 16_000_000L).use { image ->
                    File(output, "leave-after-retry-error-390-$scale.png").writeBytes(image.encodeToData()!!.use { it.bytes })
                }
                scene.node("questionnaire.confirm").config[SemanticsActions.OnClick].action!!.invoke()
                draw()
                assertEquals(listOf(retry, leave), sent)
                assertNotNull(scene.node("questionnaire.confirm").config.getOrNull(SemanticsProperties.Disabled))
            }
        }
    }

    private fun ImageComposeScene.node(tag: String) = nodes().first {
        it.config.getOrNull(SemanticsProperties.TestTag) == tag
    }
    private fun ImageComposeScene.assertActionLabelFits(tag: String, label: String) {
        fun descendants(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descendants)
        val layouts = mutableListOf<TextLayoutResult>()
        descendants(node(tag)).forEach { it.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts) }
        val labels = layouts.filter { it.layoutInput.text.text == label }
        assertTrue(labels.isNotEmpty(), "Missing measured label for $tag")
        labels.forEach { result ->
            assertFalse(result.hasVisualOverflow, "$tag must display its complete label")
            assertTrue((0 until result.lineCount).none { result.isLineEllipsized(it) }, "$tag must not ellipsize its label")
        }
    }
    private fun ImageComposeScene.nodes(): List<SemanticsNode> {
        fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
        return semanticsOwners.flatMap { walk(it.unmergedRootSemanticsNode) }
    }
}
