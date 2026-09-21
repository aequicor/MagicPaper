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
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.use
import io.aequicor.magicpaper.designsystem.PaperTheme
import io.aequicor.magicpaper.domain.*
import java.io.File
import kotlin.test.*

@OptIn(ExperimentalComposeUiApi::class)
class QuestionnaireFailureRenderTest {
    @Test fun rejectedSingleQuestionSubmissionKeepsChoiceAndErrorVisibleWithoutReview() {
        val output = File("build/reports/questionnaire-failure").apply { mkdirs() }
        for ((width, scale) in listOf(390 to 1f, 1000 to 1f, 390 to 2f)) {
            val question = PlanningQuestion("decision", "Выполнение остановлено. Как продолжить?", QuestionKind.SINGLE,
                listOf(QuestionOption("retry", "Повторить запуск"), QuestionOption("leave", "Оставить остановленной")))
            var request by mutableStateOf(UserInteractionRequest("recovery", "project", "worker", InteractionKind.RECOVER_PLAN,
                listOf(question), context = "Новая сессия", details = "Предыдущая попытка завершилась без результата. ".repeat(100)))
            val retry = listOf(PlanningAnswer("decision", listOf("retry")))
            val leave = listOf(PlanningAnswer("decision", listOf("leave")))
            var draft by mutableStateOf(QuestionnaireDraft(retry, reviewing = true))
            val sent = mutableListOf<List<PlanningAnswer>>()
            var frame = 0L
            ImageComposeScene(width, 640, density = Density(1f, scale)) {
                PaperTheme { UserInteractionDock(request, draft, { draft = it }, {
                    sent += it
                    request = request.copy(submitting = true, error = null)
                }, Modifier.fillMaxWidth().heightIn(max = 620.dp)) }
            }.use { scene ->
                fun draw() = repeat(6) { scene.render(++frame * 16_000_000L).close() }
                fun click(tag: String) {
                    scene.node(tag).config[SemanticsActions.OnClick].action!!.invoke()
                    draw()
                }
                draw()
                assertTrue(scene.nodes().none { it.tag() in setOf("questionnaire.return", "questionnaire.confirm") })
                assertTrue(scene.nodes().flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }
                    .none { it.text.contains("Новая сессия") || it.text.contains("Предыдущая попытка") })

                click("questionnaire.option.retry")
                assertEquals(listOf(retry), sent)
                assertNotNull(scene.node("questionnaire.option.retry").config.getOrNull(SemanticsProperties.Disabled))

                val failure = "Сначала подтвердите остановку предыдущего запуска"
                request = request.copy(submitting = false, error = failure)
                draw()
                val error = scene.node("questionnaire.error")
                assertEquals(failure, error.config[SemanticsProperties.Error])
                assertEquals(LiveRegionMode.Polite, error.config[SemanticsProperties.LiveRegion])
                assertTrue(error.boundsInRoot.top >= 0f && error.boundsInRoot.bottom <= 620f)
                assertFalse(scene.node("questionnaire.option.leave").config.contains(SemanticsProperties.Disabled))

                scene.render(++frame * 16_000_000L).use { image ->
                    File(output, "direct-retry-error-$width-$scale.png")
                        .writeBytes(image.encodeToData()!!.use { it.bytes })
                }
                click("questionnaire.option.leave")
                assertEquals(listOf(retry, leave), sent)
                assertEquals(leave, draft.answers)
                assertFalse(draft.reviewing)
            }
        }
    }

    private fun SemanticsNode.tag() = config.getOrNull(SemanticsProperties.TestTag)
    private fun ImageComposeScene.node(tag: String) = nodes().first { it.tag() == tag }
    private fun ImageComposeScene.nodes(): List<SemanticsNode> {
        fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
        return semanticsOwners.flatMap { walk(it.unmergedRootSemanticsNode) }
    }
}
