package io.aequicor.magicpaper.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import io.aequicor.magicpaper.designsystem.PaperSurface
import io.aequicor.magicpaper.designsystem.PaperText
import io.aequicor.magicpaper.designsystem.PaperTheme
import io.aequicor.magicpaper.domain.*

@Preview(name = "Chat question", group = "Chat questionnaire", widthDp = 720, heightDp = 420)
@Preview(name = "Chat question narrow", group = "Chat questionnaire", widthDp = 390, heightDp = 480)
@Composable
internal fun ChatQuestionnairePreview() = ChatQuestionnaireFixture()

@Preview(name = "Chat question retry large", group = "Chat questionnaire", widthDp = 390, heightDp = 600, fontScale = 2f)
@Composable
internal fun ChatQuestionnaireRetryPreview() = ChatQuestionnaireFixture(error = "Не удалось отправить ответы. Повторите попытку.")

@Preview(name = "Chat question sending", group = "Chat questionnaire", widthDp = 390, heightDp = 480)
@Composable
internal fun ChatQuestionnaireSendingPreview() = ChatQuestionnaireFixture(busy = true)

@Composable
private fun ChatQuestionnaireFixture(error: String? = null, busy: Boolean = false) {
    val request = UserInteractionRequest("preview", "", "chat", InteractionKind.RUNTIME, listOf(
        PlanningQuestion("format", "Как представить результат исследования?", QuestionKind.SINGLE,
            options = listOf(QuestionOption("brief", "Кратко"), QuestionOption("detail", "С пояснениями")), canSkip = false)),
        submitting = busy, error = error)
    PaperTheme { PaperSurface {
        ChatInputSurface(request, QuestionnaireDraft(listOf(PlanningAnswer("format", listOf("detail")))), {}, {}) {
            PaperText("Сообщение")
        }
    } }
}
