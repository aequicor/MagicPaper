package io.aequicor.magicpaper.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
    Surface(modifier.testTag("questionnaire"), shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Text(if (draft.reviewing) "Проверьте ответы" else "${index + 1}/${questions.size} · ${question.title}",
                    Modifier.weight(1f).testTag("questionnaire.title"), style = MaterialTheme.typography.titleMedium,
                    maxLines = 4, overflow = TextOverflow.Ellipsis, onTextLayout = { titleOverflow = it.hasVisualOverflow })
                if (!draft.reviewing) {
                    TextButton({ onDraft(draft.copy(index = index - 1)) }, enabled = index > 0 && !busy,
                        modifier = Modifier.testTag("questionnaire.back"), contentPadding = PaddingValues(horizontal = 8.dp)) { Text("Назад") }
                    if (!answer.skipped && answer.isComplete(question)) {
                        TextButton({ change(answer, advance = true) }, enabled = !busy,
                            modifier = Modifier.testTag("questionnaire.next"), contentPadding = PaddingValues(horizontal = 8.dp)) { Text("Далее") }
                    } else TextButton({ change(PlanningAnswer(question.id, skipped = true), advance = true) }, enabled = question.canSkip && !busy,
                        modifier = Modifier.testTag("questionnaire.skip"), contentPadding = PaddingValues(horizontal = 8.dp)) { Text("Пропустить") }
                }
            }
            if (context.isNotBlank() || queuedCount > 0) Text(
                listOfNotNull(context.takeIf { it.isNotBlank() }, "В очереди: $queuedCount".takeIf { queuedCount > 0 }).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            key(if (draft.reviewing) "review" else question.id) {
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (details.isNotBlank()) SelectionContainer { Text(details, style = MaterialTheme.typography.bodyMedium) }
                    if (draft.reviewing) {
                        questions.forEach { q ->
                            Text(interactionAnswerText(listOf(q), answers, redactSecrets = true), style = MaterialTheme.typography.bodyMedium)
                        }
                    } else {
                        if (titleOverflow) Text(question.title, style = MaterialTheme.typography.bodyMedium)
                        if (question.kind == QuestionKind.MULTIPLE) Text("Можно выбрать несколько вариантов", style = MaterialTheme.typography.labelSmall)
                        question.options.forEach { option ->
                            val selected = option.id in answer.selected
                            Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).background(if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
                                MaterialTheme.shapes.small).selectable(selected, enabled = !busy && option.enabled,
                                role = if (question.kind == QuestionKind.MULTIPLE) Role.Checkbox else Role.RadioButton,
                                onClick = {
                                    val chosen = if (question.kind == QuestionKind.MULTIPLE) {
                                        if (selected) answer.selected - option.id else answer.selected + option.id
                                    } else listOf(option.id)
                                    change(answer.copy(selected = chosen, skipped = false),
                                        advance = question.kind != QuestionKind.MULTIPLE && answer.text.isBlank())
                                }).testTag("questionnaire.option.${option.id}").padding(horizontal = 8.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                if (question.kind == QuestionKind.MULTIPLE) Checkbox(selected, null, enabled = !busy && option.enabled)
                                else RadioButton(selected, null, enabled = !busy && option.enabled)
                                Spacer(Modifier.width(8.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(option.label, style = MaterialTheme.typography.bodyLarge)
                                    if (option.description.isNotBlank()) Text(option.description, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                        if (question.allowCustomInput) OutlinedTextField(answer.text, { change(answer.copy(text = it, skipped = false)) },
                            placeholder = { Text("Свой вариант") }, enabled = !busy,
                            modifier = Modifier.fillMaxWidth().testTag("questionnaire.custom"), minLines = 1, maxLines = 4,
                            visualTransformation = if (question.secret) PasswordVisualTransformation() else VisualTransformation.None)
                    }
                    if (error != null) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
            if (draft.reviewing) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    TextButton({ onDraft(draft.copy(index = 0, reviewing = false)) }, enabled = !busy,
                        modifier = Modifier.testTag("questionnaire.return")) { Text("Вернуться") }
                    Button({ onSubmit(answers) }, enabled = !busy && questions.all { q -> answers.first { it.questionId == q.id }.isComplete(q) },
                        modifier = Modifier.testTag("questionnaire.confirm")) { Text(if (busy) "Отправляем…" else "Подтвердить") }
            }
        }
    }
}
