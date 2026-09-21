package io.aequicor.magicpaper.ui.screens

import androidx.compose.runtime.*
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import io.aequicor.magicpaper.designsystem.*
import io.aequicor.magicpaper.domain.*
import java.io.File
import kotlin.test.*

@OptIn(ExperimentalComposeUiApi::class)
class ChatQuestionnaireRenderTest {
    @Test fun explicitQuestionReplacesComposerAndRetainsAnswerAfterFailedDelivery() {
        for (scale in listOf(1f, 2f)) {
            val question = UserInteractionRequest("request", "", "chat", InteractionKind.RUNTIME, listOf(
                PlanningQuestion("format", "Как представить результат?", QuestionKind.SINGLE,
                    listOf(QuestionOption("brief", "Кратко"), QuestionOption("detail", "С пояснениями")), canSkip = false)))
            var request by mutableStateOf<UserInteractionRequest?>(question)
            var draft by mutableStateOf(QuestionnaireDraft(listOf(PlanningAnswer("format", listOf("detail")))))
            var sent = 0
            ImageComposeScene(390, 620, density = Density(1f, scale)) {
                PaperTheme { PaperSurface {
                    ChatInputSurface(request, draft, { draft = it }, { sent++; request = question.copy(submitting = true) }) {
                        PaperText("Написать сообщение")
                    }
                } }
            }.use { scene ->
                var frame = 0L
                fun draw() = repeat(8) { scene.render(++frame * 16_000_000L).close() }
                fun labels() = scene.nodes().flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }.map { it.text }
                draw()
                assertEquals(0, sent, "Restoring a selected answer never submits it")
                assertFalse("Написать сообщение" in labels())
                scene.node("questionnaire.option.detail").config[SemanticsActions.OnClick].action!!.invoke()
                draw()
                assertEquals(1, sent)
                assertTrue(scene.node("questionnaire.option.brief").config.contains(SemanticsProperties.Disabled))
                request = question.copy(error = "Не удалось отправить ответы. Повторите попытку.")
                draw()
                val bounds = scene.node("questionnaire.error").boundsInRoot
                assertTrue(bounds.left >= 0 && bounds.right <= 390 && bounds.bottom <= 620)
                assertEquals(listOf("detail"), draft.answers.single().selected)
                assertFalse(scene.node("questionnaire.option.detail").config.contains(SemanticsProperties.Disabled))
                val file = File("build/reports/chat-questionnaire/retry-390-$scale.png").apply { parentFile.mkdirs() }
                scene.render(++frame * 16_000_000L).use { image -> file.writeBytes(image.encodeToData()!!.use { it.bytes }) }
                request = null; draw()
                assertTrue("Написать сообщение" in labels())
            }
        }
    }
    private fun ImageComposeScene.node(tag: String) = nodes().first { it.config.getOrNull(SemanticsProperties.TestTag) == tag }
    private fun ImageComposeScene.nodes(): List<SemanticsNode> {
        fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
        return semanticsOwners.flatMap { walk(it.unmergedRootSemanticsNode) }
    }
}
