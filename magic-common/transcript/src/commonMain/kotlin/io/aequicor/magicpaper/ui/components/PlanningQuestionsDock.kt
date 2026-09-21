package io.aequicor.magicpaper.ui.components

import io.aequicor.magicpaper.designsystem.PaperScrollColumn

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import io.aequicor.magicpaper.designsystem.LocalPaperColors
import io.aequicor.magicpaper.designsystem.PaperAction
import io.aequicor.magicpaper.designsystem.PaperButton
import io.aequicor.magicpaper.designsystem.PaperButtonKind
import io.aequicor.magicpaper.designsystem.PaperPromptField
import io.aequicor.magicpaper.designsystem.PaperQuestionnaire
import io.aequicor.magicpaper.designsystem.PaperQuestionnaireChoice
import io.aequicor.magicpaper.designsystem.PaperText
import io.aequicor.magicpaper.designsystem.PaperTextRole
import io.aequicor.magicpaper.domain.*

/** One surface replaces the composer; multi-question flows keep a draft through their final review. */
@Composable
fun UserInteractionDock(
    request: UserInteractionRequest,
    draft: QuestionnaireDraft,
    onDraft: (QuestionnaireDraft) -> Unit,
    onSubmit: (List<PlanningAnswer>) -> Unit,
    modifier: Modifier = Modifier,
) {
    Questionnaire(request.questions, draft, onDraft, onSubmit, modifier,
        busy = request.submitting, error = request.error,
        decisionDetails = request.details.takeIf { request.kind == InteractionKind.APPROVAL }.orEmpty())
}

@Composable
fun PlanningQuestionWizard(
    questions: List<PlanningQuestion>, busy: Boolean, modifier: Modifier = Modifier,
    initialAnswers: List<PlanningAnswer> = emptyList(),
    onSubmit: (List<PlanningAnswer>) -> Unit,
) {
    // Secret answers belong to the durable questionnaire draft, never to navigation presentation.
    var draft by remember(questions) { mutableStateOf(QuestionnaireDraft(initialAnswers)) }
    Questionnaire(questions, draft, { draft = it }, onSubmit, modifier, busy)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Questionnaire(
    questions: List<PlanningQuestion>, draft: QuestionnaireDraft, onDraft: (QuestionnaireDraft) -> Unit,
    onSubmit: (List<PlanningAnswer>) -> Unit, modifier: Modifier, busy: Boolean,
    error: String? = null, decisionDetails: String = "",
) {
    if (questions.isEmpty()) return
    val index = draft.index.coerceIn(questions.indices)
    val question = questions[index]
    val reviewing = draft.reviewing && questions.size > 1
    val answers = questions.map { q -> draft.answers.firstOrNull { it.questionId == q.id } ?: PlanningAnswer(q.id) }
    val answer = answers[index]
    val answerComplete = answer.isComplete(question)
    fun change(value: PlanningAnswer, advance: Boolean = false) {
        val changed = answers.map { if (it.questionId == value.questionId) value else it }
        when {
            !advance -> onDraft(draft.copy(answers = changed, reviewing = false))
            index < questions.lastIndex -> onDraft(draft.copy(answers = changed, index = index + 1, reviewing = false))
            questions.size == 1 -> {
                onDraft(draft.copy(answers = changed, index = 0, reviewing = false))
                onSubmit(changed)
            }
            else -> onDraft(draft.copy(answers = changed, reviewing = true))
        }
    }
    PaperQuestionnaire(modifier.testTag("questionnaire")) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (reviewing) {
                PaperText("Проверьте ответы", Modifier.testTag("questionnaire.title"), role = PaperTextRole.TITLE)
            } else if (questions.size > 1) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    PaperText("Вопрос ${index + 1} из ${questions.size}",
                        Modifier.weight(1f), role = PaperTextRole.LABEL, color = LocalPaperColors.current.secondaryText)
                    if (index > 0) PaperAction({ onDraft(draft.copy(index = index - 1)) }, enabled = !busy,
                        modifier = Modifier.testTag("questionnaire.back")) { PaperText("Назад", role = PaperTextRole.LABEL) }
                    if (!answer.skipped && answerComplete) {
                        PaperAction({ change(answer, advance = true) }, enabled = !busy,
                            modifier = Modifier.testTag("questionnaire.next")) {
                            PaperText(if (index == questions.lastIndex) "Готово" else "Далее", role = PaperTextRole.LABEL)
                        }
                    } else PaperAction({ change(PlanningAnswer(question.id, skipped = true), advance = true) }, enabled = question.canSkip && !busy,
                        modifier = Modifier.testTag("questionnaire.skip")) { PaperText("Пропустить", role = PaperTextRole.LABEL) }
                }
            } else if (question.kind != QuestionKind.SINGLE || (question.canSkip && !answerComplete)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    if (answerComplete) PaperAction({ change(answer, advance = true) }, enabled = !busy,
                        modifier = Modifier.testTag("questionnaire.next")) { PaperText("Готово", role = PaperTextRole.LABEL) }
                    else if (question.canSkip) PaperAction({ change(PlanningAnswer(question.id, skipped = true), advance = true) }, enabled = !busy,
                        modifier = Modifier.testTag("questionnaire.skip")) { PaperText("Пропустить", role = PaperTextRole.LABEL) }
                    else PaperAction({}, enabled = false, modifier = Modifier.testTag("questionnaire.next")) {
                        PaperText("Готово", role = PaperTextRole.LABEL)
                    }
                }
            }
            key(if (reviewing) "review" else question.id) {
                PaperScrollColumn(Modifier.weight(1f, fill = false),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (reviewing) {
                        questions.forEach { q ->
                            PaperText(interactionAnswerText(listOf(q), answers, redactSecrets = true))
                        }
                    } else {
                        PaperText(question.title, Modifier.fillMaxWidth().testTag("questionnaire.title"))
                        if (decisionDetails.isNotBlank()) SelectionContainer {
                            PaperText(decisionDetails, color = LocalPaperColors.current.secondaryText)
                        }
                        if (question.kind == QuestionKind.MULTIPLE) PaperText("Можно выбрать несколько",
                            role = PaperTextRole.LABEL, color = LocalPaperColors.current.secondaryText)
                        question.options.forEach { option ->
                            val selected = option.id in answer.selected
                            PaperQuestionnaireChoice(selected = selected, multiple = question.kind == QuestionKind.MULTIPLE,
                                enabled = !busy && option.enabled,
                                modifier = Modifier.fillMaxWidth().testTag("questionnaire.option.${option.id}"),
                                label = option.label, description = option.description.takeIf { it.isNotBlank() }, onSelect = {
                                    val chosen = if (question.kind == QuestionKind.MULTIPLE) {
                                        if (selected) answer.selected - option.id else answer.selected + option.id
                                    } else listOf(option.id)
                                    change(answer.copy(selected = chosen,
                                        text = if (question.kind == QuestionKind.SINGLE) "" else answer.text,
                                        skipped = false), advance = question.kind == QuestionKind.SINGLE)
                                })
                        }
                        if (question.allowCustomInput) PaperPromptField(answer.text, { value ->
                            change(answer.copy(selected = if (question.kind == QuestionKind.SINGLE) emptyList() else answer.selected,
                                text = value, skipped = false))
                        },
                            placeholder = "Введите ответ и нажмите Enter", label = "Свой вариант", enabled = !busy,
                            modifier = Modifier.fillMaxWidth().testTag("questionnaire.custom"), singleLine = true,
                            onSubmit = { if (answer.text.isNotBlank()) change(answer, advance = true) },
                            visualTransformation = if (question.secret) PasswordVisualTransformation() else VisualTransformation.None)
                    }
                }
            }
            // A rejected submission remains visible without replacing the user's draft.
            if (error != null) PaperText(error,
                modifier = Modifier.fillMaxWidth().testTag("questionnaire.error").semantics {
                    this.error(error)
                    liveRegion = LiveRegionMode.Polite
                }, color = LocalPaperColors.current.error, role = PaperTextRole.LABEL)
            if (reviewing) FlowRow(Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    PaperAction({ onDraft(draft.copy(index = 0, reviewing = false)) }, enabled = !busy,
                        modifier = Modifier.testTag("questionnaire.return")) { PaperText("Вернуться", role = PaperTextRole.LABEL) }
                    PaperButton(if (busy) "Отправляем…" else "Подтвердить", { onSubmit(answers) }, enabled = !busy && questions.all { q -> answers.first { it.questionId == q.id }.isComplete(q) },
                        modifier = Modifier.testTag("questionnaire.confirm"), kind = PaperButtonKind.PRIMARY)
            }
        }
    }
}
