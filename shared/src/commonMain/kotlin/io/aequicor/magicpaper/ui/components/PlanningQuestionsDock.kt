package io.aequicor.magicpaper.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.aequicor.magicpaper.designsystem.LocalPaperColors
import io.aequicor.magicpaper.designsystem.PaperAction
import io.aequicor.magicpaper.designsystem.PaperButton
import io.aequicor.magicpaper.designsystem.PaperButtonKind
import io.aequicor.magicpaper.designsystem.PaperChoice
import io.aequicor.magicpaper.designsystem.PaperPromptField
import io.aequicor.magicpaper.designsystem.PaperQuestionnaire
import io.aequicor.magicpaper.designsystem.PaperText
import io.aequicor.magicpaper.designsystem.PaperTextRole
import io.aequicor.magicpaper.domain.*
import kotlinx.serialization.json.Json

/** One surface replaces the composer. All changes are a draft until the review is confirmed. */
@Composable
internal fun UserInteractionDock(
    request: UserInteractionRequest,
    draft: QuestionnaireDraft,
    onDraft: (QuestionnaireDraft) -> Unit,
    onSubmit: (List<PlanningAnswer>) -> Unit,
    modifier: Modifier = Modifier,
    queuedCount: Int = 0,
) {
    Questionnaire(request.questions, draft, onDraft, onSubmit, modifier,
        busy = request.submitting, context = request.context,
        details = request.details, error = request.error, queuedCount = queuedCount)
}

@Composable
internal fun PlanningQuestionWizard(
    questions: List<PlanningQuestion>, busy: Boolean, modifier: Modifier = Modifier,
    initialAnswers: List<PlanningAnswer> = emptyList(),
    onSubmit: (List<PlanningAnswer>) -> Unit,
) {
    val saver = Saver<QuestionnaireDraft, String>(
        save = { Json.encodeToString(QuestionnaireDraft.serializer(), it) },
        restore = { Json.decodeFromString(QuestionnaireDraft.serializer(), it) })
    var draft by rememberSaveable(questions, stateSaver = saver) { mutableStateOf(QuestionnaireDraft(initialAnswers)) }
    Questionnaire(questions, draft, { draft = it }, onSubmit, modifier, busy)
}

@Composable
private fun Questionnaire(
    questions: List<PlanningQuestion>, draft: QuestionnaireDraft, onDraft: (QuestionnaireDraft) -> Unit,
    onSubmit: (List<PlanningAnswer>) -> Unit, modifier: Modifier, busy: Boolean,
    context: String = "", details: String = "", error: String? = null, queuedCount: Int = 0,
) {
    if (questions.isEmpty()) return
    val index = draft.index.coerceIn(questions.indices)
    val question = questions[index]
    var titleOverflow by remember(question.id, question.title, draft.reviewing) { mutableStateOf(false) }
    val answers = questions.map { q -> draft.answers.firstOrNull { it.questionId == q.id } ?: PlanningAnswer(q.id) }
    val answer = answers[index]
    fun change(value: PlanningAnswer, advance: Boolean = false) {
        onDraft(draft.copy(answers = answers.map { if (it.questionId == value.questionId) value else it },
            index = if (advance && index < questions.lastIndex) index + 1 else index,
            reviewing = advance && index == questions.lastIndex))
    }
    PaperQuestionnaire(modifier.testTag("questionnaire")) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                PaperText(if (draft.reviewing) "Проверьте ответы" else "${index + 1}/${questions.size} · ${question.title}",
                    Modifier.weight(1f).testTag("questionnaire.title"), role = PaperTextRole.TITLE,
                    maxLines = 4, overflow = TextOverflow.Ellipsis, onTextLayout = { titleOverflow = it.hasVisualOverflow })
                if (!draft.reviewing) {
                    PaperAction({ onDraft(draft.copy(index = index - 1)) }, enabled = index > 0 && !busy,
                        modifier = Modifier.testTag("questionnaire.back")) { PaperText("Назад", role = PaperTextRole.LABEL) }
                    if (!answer.skipped && answer.isComplete(question)) {
                        PaperAction({ change(answer, advance = true) }, enabled = !busy,
                            modifier = Modifier.testTag("questionnaire.next")) { PaperText("Далее", role = PaperTextRole.LABEL) }
                    } else PaperAction({ change(PlanningAnswer(question.id, skipped = true), advance = true) }, enabled = question.canSkip && !busy,
                        modifier = Modifier.testTag("questionnaire.skip")) { PaperText("Пропустить", role = PaperTextRole.LABEL) }
                }
            }
            if (context.isNotBlank() || queuedCount > 0) PaperText(
                listOfNotNull(context.takeIf { it.isNotBlank() }, "В очереди: $queuedCount".takeIf { queuedCount > 0 }).joinToString(" · "),
                role = PaperTextRole.LABEL, color = LocalPaperColors.current.secondaryText)
            key(if (draft.reviewing) "review" else question.id) {
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (details.isNotBlank()) SelectionContainer { PaperText(details) }
                    if (draft.reviewing) {
                        questions.forEach { q ->
                            PaperText(interactionAnswerText(listOf(q), answers, redactSecrets = true))
                        }
                    } else {
                        if (titleOverflow) PaperText(question.title)
                        if (question.kind == QuestionKind.MULTIPLE) PaperText("Можно выбрать несколько вариантов", role = PaperTextRole.LABEL)
                        question.options.forEach { option ->
                            val selected = option.id in answer.selected
                            PaperChoice(selected = selected, enabled = !busy && option.enabled,
                                modifier = Modifier.fillMaxWidth().testTag("questionnaire.option.${option.id}"),
                                label = option.label, description = option.description.takeIf { it.isNotBlank() }, onSelect = {
                                    val chosen = if (question.kind == QuestionKind.MULTIPLE) {
                                        if (selected) answer.selected - option.id else answer.selected + option.id
                                    } else listOf(option.id)
                                    change(answer.copy(selected = chosen, skipped = false),
                                        advance = question.kind != QuestionKind.MULTIPLE && answer.text.isBlank())
                                })
                        }
                        if (question.allowCustomInput) PaperPromptField(answer.text, { change(answer.copy(text = it, skipped = false)) },
                            placeholder = "Свой вариант", enabled = !busy,
                            modifier = Modifier.fillMaxWidth().testTag("questionnaire.custom"), maxLines = 4,
                            visualTransformation = if (question.secret) PasswordVisualTransformation() else VisualTransformation.None)
                    }
                    if (error != null) PaperText(error, color = LocalPaperColors.current.error, role = PaperTextRole.LABEL)
                }
            }
            if (draft.reviewing) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    PaperAction({ onDraft(draft.copy(index = 0, reviewing = false)) }, enabled = !busy,
                        modifier = Modifier.testTag("questionnaire.return")) { PaperText("Вернуться", role = PaperTextRole.LABEL) }
                    PaperButton(if (busy) "Отправляем…" else "Подтвердить", { onSubmit(answers) }, enabled = !busy && questions.all { q -> answers.first { it.questionId == q.id }.isComplete(q) },
                        modifier = Modifier.testTag("questionnaire.confirm"), kind = PaperButtonKind.PRIMARY)
            }
        }
    }
}
