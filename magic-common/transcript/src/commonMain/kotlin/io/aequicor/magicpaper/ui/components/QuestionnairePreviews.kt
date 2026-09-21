package io.aequicor.magicpaper.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.aequicor.magicpaper.designsystem.LocalPaperColors
import io.aequicor.magicpaper.designsystem.PaperTheme
import io.aequicor.magicpaper.domain.*

@Preview(name = "Single choice", group = "Questionnaire", widthDp = 640, heightDp = 300)
@Preview(name = "Single choice narrow", group = "Questionnaire", widthDp = 390, heightDp = 320)
@Composable
internal fun SingleChoiceQuestionnairePreview() = QuestionnairePreview(
    UserInteractionRequest("preview-single", "project", "session", InteractionKind.QUESTION, listOf(
        PlanningQuestion("format", "В каком формате подготовить результат?", QuestionKind.SINGLE,
            listOf(QuestionOption("brief", "Кратко", "Только решение и важные оговорки"),
                QuestionOption("details", "Подробно", "Решение, ход проверки и ограничения")),
            allowCustomInput = true, canSkip = false),
    )),
)

@Preview(name = "Multiple choice", group = "Questionnaire", widthDp = 640, heightDp = 390)
@Composable
internal fun MultipleChoiceQuestionnairePreview() = QuestionnairePreview(
    UserInteractionRequest("preview-multiple", "project", "session", InteractionKind.QUESTION, listOf(
        PlanningQuestion("checks", "Что проверить перед завершением?", QuestionKind.MULTIPLE,
            listOf(QuestionOption("tests", "Автотесты"), QuestionOption("layout", "Внешний вид"),
                QuestionOption("keyboard", "Работу с клавиатуры"))),
        PlanningQuestion("note", "Что ещё важно учесть?"),
    )),
)

@Preview(name = "Text answer large", group = "Questionnaire", widthDp = 390, heightDp = 360, fontScale = 1.5f)
@Composable
internal fun TextQuestionnairePreview() = QuestionnairePreview(
    UserInteractionRequest("preview-text", "project", "session", InteractionKind.QUESTION, listOf(
        PlanningQuestion("result", "Каким должен быть итоговый результат?", canSkip = false),
    )),
)

@Composable
private fun QuestionnairePreview(request: UserInteractionRequest) {
    var draft by remember(request.id) { mutableStateOf(QuestionnaireDraft()) }
    PaperTheme {
        Box(Modifier.fillMaxSize().background(LocalPaperColors.current.canvas).padding(8.dp)) {
            UserInteractionDock(request, draft, { draft = it }, {})
        }
    }
}
