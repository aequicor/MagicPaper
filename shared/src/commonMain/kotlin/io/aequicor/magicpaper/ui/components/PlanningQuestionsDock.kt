package io.aequicor.magicpaper.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.aequicor.magicpaper.domain.*
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

@Composable
internal fun PlanningQuestionsDock(
    session: CodingSession,
    history: List<CodingMessage>,
    service: PlanningChatService,
    busy: Boolean,
    modifier: Modifier = Modifier,
) {
    val plans by service.store.plans.collectAsState()
    val states by service.states.collectAsState()
    val sessions by service.sessions.collectAsState()
    val answered = history.filter { it.planning?.closesRequest != false }.mapNotNull { it.planning?.replyTo }.toSet()
    val pending = history.filter { it.planning?.questions?.isNotEmpty() == true && it.id !in answered &&
        it.planning.requestStatus == UserRequestStatus.OPEN && plans.any { p -> p.id == it.planning.planId } }
    if (pending.isEmpty()) return
    var selectedId by rememberSaveable(session.id) { mutableStateOf<String?>(null) }
    val message = pending.firstOrNull { it.id == selectedId } ?: pending.first()
    val block = message.planning!!
    val request = states[session.id]?.questions?.firstOrNull { it.id == message.id }
    val source = sessions.firstOrNull { it.id == (request?.sourceSessionId ?: block.sourceSessionId) }
    val scope = request?.scopeLabel ?: block.scopeLabel.ifBlank { "Для всего плана" }
    val submit: (List<PlanningAnswer>) -> Unit = { answers ->
        val text = block.questions.mapNotNull { question ->
            answers.firstOrNull { it.questionId == question.id }?.let { answer ->
                "${question.title}\n" + (question.options.filter { it.id in answer.selected }.map { it.label } +
                    listOf(answer.text).filter { it.isNotBlank() }).joinToString("; ")
            }
        }.joinToString("\n\n")
        service.send(session, text, answers, message.id)
    }
    Surface(modifier, shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.secondaryContainer) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Нужен ваш ответ · $scope", style = MaterialTheme.typography.titleSmall)
            Text("Запросил: ${source?.name ?: "Оркестратор"}" + (source?.subtitle()?.let { " · $it" } ?: ""),
                style = MaterialTheme.typography.bodySmall)
            val affected = request?.stageIds ?: block.affectedStageIds.ifEmpty { listOfNotNull(block.sourceStageId) }
            val recipients = sessions.filter { it.parentSessionId == session.id && it.planId == block.planId && it.stageId in affected }
            Text(when {
                request?.forPlanning == true -> "Ответ получит оркестратор для уточнения плана"
                affected.isEmpty() -> "Ответ получит оркестратор и передаст исполнителям незавершённых этапов"
                recipients.isNotEmpty() -> "Ответ получит оркестратор и передаст: ${recipients.joinToString { it.name }}"
                else -> "Ответ получит оркестратор для указанных этапов"
            }, style = MaterialTheme.typography.labelSmall)
            if (pending.size > 1) Row(Modifier.horizontalScroll(rememberScrollState())) {
                pending.forEach { candidate ->
                    TextButton({ selectedId = candidate.id }) {
                        Text((if (candidate.id == message.id) "● " else "") + candidate.planning!!.scopeLabel.ifBlank { "Вопрос к плану" })
                    }
                }
            }
            if (request?.partialAnswers?.isEmpty() == true && request.partialMessages.isNotEmpty())
                Text("Сохранённый ответ: ${request.partialMessages.values.joinToString("; ")}", style = MaterialTheme.typography.bodySmall)
            key(message.id) {
                PlanningQuestionWizard(block.questions, false, Modifier.fillMaxWidth().weight(1f, fill = false),
                    initialAnswers = request?.partialAnswers.orEmpty(), onSavePartial = submit, onSubmit = submit)
            }
        }
    }
}

@Composable
internal fun PlanningQuestionWizard(
    questions: List<PlanningQuestion>,
    busy: Boolean,
    modifier: Modifier = Modifier,
    initialAnswers: List<PlanningAnswer> = emptyList(),
    onSavePartial: ((List<PlanningAnswer>) -> Unit)? = null,
    onSubmit: (List<PlanningAnswer>) -> Unit,
) {
    if (questions.isEmpty()) return
    val serializer = ListSerializer(PlanningAnswer.serializer())
    val saver = Saver<List<PlanningAnswer>, String>(
        save = { Json.encodeToString(serializer, it) }, restore = { Json.decodeFromString(serializer, it) })
    var answers by rememberSaveable(questions, stateSaver = saver) { mutableStateOf(questions.map { q -> initialAnswers.firstOrNull { it.questionId == q.id } ?: PlanningAnswer(q.id) }) }
    var index by rememberSaveable(questions) { mutableStateOf(0) }
    val question = questions[index.coerceIn(questions.indices)]
    val answer = answers.first { it.questionId == question.id }
    fun update(value: PlanningAnswer) { answers = answers.map { if (it.questionId == value.questionId) value else it } }
    fun PlanningAnswer.complete() = selected.isNotEmpty() || text.isNotBlank()
    Surface(modifier, shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 2.dp) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Уточнение ${index + 1} из ${questions.size}", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            key(question.id) {
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(question.title, style = MaterialTheme.typography.bodyLarge)
                    if (question.kind == QuestionKind.MULTIPLE) Text("Можно выбрать несколько вариантов",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    question.options.forEach { option ->
                        val selected = option.id in answer.selected
                        Row(Modifier.fillMaxWidth()
                            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent,
                                MaterialTheme.shapes.small)
                            .clickable(enabled = !busy, role = if (question.kind == QuestionKind.SINGLE) Role.RadioButton else Role.Checkbox) {
                                update(answer.copy(selected = if (question.kind == QuestionKind.SINGLE) listOf(option.id)
                                    else if (selected) answer.selected - option.id else answer.selected + option.id))
                            }.padding(horizontal = 6.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            if (question.kind == QuestionKind.SINGLE) RadioButton(selected, onClick = null, modifier = Modifier.size(24.dp), enabled = !busy)
                            else Checkbox(selected, onCheckedChange = null, modifier = Modifier.size(24.dp), enabled = !busy)
                            Spacer(Modifier.width(6.dp))
                            Text(option.label, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                    OutlinedTextField(answer.text, { update(answer.copy(text = it)) },
                        placeholder = { Text(if (question.kind == QuestionKind.TEXT) "Ваш ответ" else "Свой ответ или комментарий",
                            style = MaterialTheme.typography.bodyMedium) },
                        textStyle = MaterialTheme.typography.bodyLarge,
                        enabled = !busy, modifier = Modifier.fillMaxWidth(), minLines = 1, maxLines = 3)
                }
            }
            if (onSavePartial != null && answers.any { it.complete() } && !answers.all { it.complete() })
                TextButton({ onSavePartial(answers.filter { it.complete() }) }, enabled = !busy) { Text("Сохранить часть ответов") }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton({ index-- }, enabled = index > 0 && !busy, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("Назад") }
                Button(onClick = { if (index < questions.lastIndex) index++ else onSubmit(answers) },
                    enabled = !busy && answer.complete() && (index < questions.lastIndex || answers.all { it.complete() }),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
                    Text(if (index < questions.lastIndex) "Далее" else "Отправить ответы")
                }
            }
        }
    }
}
