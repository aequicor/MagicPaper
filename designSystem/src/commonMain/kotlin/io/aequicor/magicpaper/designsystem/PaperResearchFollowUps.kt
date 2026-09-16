package io.aequicor.magicpaper.designsystem

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

/** Borderless continuation actions share the answer's leading edge and retain Paper feedback. */
@Composable
public fun PaperResearchFollowUps(questions: List<String>, onSelect: (String) -> Unit,
    modifier: Modifier = Modifier, enabled: Boolean = true) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        questions.forEach { question ->
            PaperAction({ onSelect(question) }, Modifier.fillMaxWidth().semantics {
                contentDescription = "Задать вопрос: $question"
            }, enabled = enabled, contentPadding = PaddingValues(vertical = 4.dp)) {
                PaperText(question, Modifier.fillMaxWidth(), role = PaperTextRole.CHROME,
                    textAlign = TextAlign.Start,
                    color = if (enabled) LocalPaperColors.current.action else LocalPaperColors.current.secondaryText)
            }
        }
    }
}

@Preview(name = "Next questions", group = "Research follow-ups", widthDp = 640, heightDp = 240)
@Preview(name = "Narrow large text", group = "Research follow-ups", widthDp = 320, heightDp = 900, fontScale = 2f)
@Composable
internal fun PaperResearchFollowUpsPreview() = PaperTheme {
    PaperSurface {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            PaperText("Начните с небольшой задачи и проверьте результат на практике.")
            PaperResearchFollowUps(listOf("Как выбрать первый проект?", "Сравнить Kotlin и Compose на практическом примере",
                "Написать статью: от первого экрана до Android-приложения"), {})
        }
    }
}
