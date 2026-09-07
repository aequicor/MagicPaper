package io.aequicor.magicpaper.ui.components

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
    val message = history.lastOrNull { it.planning?.questions?.isNotEmpty() == true } ?: return
    val block = message.planning ?: return
    if (history.any { it.planning?.replyTo == message.id } || plans.none { it.id == block.planId }) return
    key(session.id, message.id) {
        PlanningQuestionWizard(block.questions, busy, modifier) { answers ->
            val text = block.questions.joinToString("\n\n") { question ->
                val answer = answers.first { it.questionId == question.id }
                "${question.title}\n" + (question.options.filter { it.id in answer.selected }.map { it.label } +
                    listOf(answer.text).filter { it.isNotBlank() }).joinToString("; ")
            }
            service.send(session, text, answers, message.id)
        }
    }
}

@Composable
internal fun PlanningQuestionWizard(
    questions: List<PlanningQuestion>,
    busy: Boolean,
    modifier: Modifier = Modifier,
    onSubmit: (List<PlanningAnswer>) -> Unit,
) {
    if (questions.isEmpty()) return
    val serializer = ListSerializer(PlanningAnswer.serializer())
    val saver = Saver<List<PlanningAnswer>, String>(
        save = { Json.encodeToString(serializer, it) }, restore = { Json.decodeFromString(serializer, it) })
    var answers by rememberSaveable(questions, stateSaver = saver) { mutableStateOf(questions.map { PlanningAnswer(it.id) }) }
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
