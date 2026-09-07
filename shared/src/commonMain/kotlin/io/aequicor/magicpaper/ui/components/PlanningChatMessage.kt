package io.aequicor.magicpaper.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.plugins.builtin.StageDetailsDialog

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun PlanningChatMessage(message: CodingMessage, session: CodingSession, history: List<CodingMessage>, service: PlanningChatService, onOpenSession: (String) -> Unit) {
    val block = message.planning ?: return
    val plans by service.store.plans.collectAsState()
    val live by service.execution.live.collectAsState()
    val drafts by service.drafts.collectAsState()
    val source = plans.firstOrNull { it.id == block.planId } ?: return
    if (block.questions.isNotEmpty()) {
        val answered = history.any { it.planning?.replyTo == message.id }
        Text(if (answered) "Ответы на уточнения отправлены" else "Уточнения — в карточке над полем ввода",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (!block.graph) return
    fun preview(a: StageAttempt) = live[a.id]?.takeIf { it.updatedAt > a.updatedAt } ?: a
    val plan = source.copy(milestones = source.milestones.map { it.copy(attempts = it.attempts.map(::preview)) }, finalAttempt = source.finalAttempt?.let(::preview))
    var selected by rememberSaveable(message.id) { mutableStateOf<String?>(null) }
    var graphOpen by rememberSaveable(message.id) { mutableStateOf(false) }
    var original by rememberSaveable(message.id) { mutableStateOf(false) }
    val initial = plan.versions.firstOrNull { it.revision == plan.confirmedRevision }
    val display = if (original && initial != null) plan.copy(tree = initial.tree, milestones = initial.milestones, finalAttempt = null) else plan
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(display.goal, style = MaterialTheme.typography.bodyLarge)
        display.selectedMilestones.forEachIndexed { index, stage ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("${index + 1}. ${stage.title}", style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold))
                if (stage.description.isNotBlank()) ChatMarkdown(stage.description)
                if (stage.acceptance.isNotBlank()) Text("Критерии готовности: ${stage.acceptance}", style = MaterialTheme.typography.bodyLarge)
                if (stage.dependsOn.isNotEmpty()) Text("После: " + stage.dependsOn.joinToString { id ->
                    display.milestones.firstOrNull { it.id == id }?.title ?: id
                }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                stage.assignment?.let { assignment ->
                    Text("Исполнитель: ${assignment.displayName.ifBlank { assignment.modelId }} · ${assignment.effort.shortLabel}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (plan.confirmedRevision != null) {
            Text("${plan.doneCount}/${plan.selectedMilestones.size} этапов · ${when {
                plan.phase == ExecutionPhase.COMPLETE -> "Готово"
                plan.intent == ExecutionIntent.PAUSE -> "Пауза"
                plan.issue != null -> "Нужно внимание"
                plan.intent == ExecutionIntent.STOP -> "Остановлено"
                else -> "Выполняется"
            }}", style = MaterialTheme.typography.bodySmall)
            LinearProgressIndicator(progress = { plan.progress }, modifier = Modifier.fillMaxWidth())
        } else Text("Для уточнения напишите сообщение в этом чате.", style = MaterialTheme.typography.bodySmall)
        plan.issue?.let { Text(it.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { graphOpen = true }) { Text("Схема плана") }
            if (plan.confirmedRevision == null) {
                Button(onClick = { service.confirm(plan.id) }, enabled = plan.wizardStep != PlanningStep.CLARIFY && plan.selectedMilestones.isNotEmpty() && DecisionCompiler.compile(plan).valid && drafts[session.id]?.active != true) { Text("Подтвердить") }
            } else {
                if (plan.phase != ExecutionPhase.COMPLETE) {
                    OutlinedButton(onClick = { service.control(plan.id, if (plan.intent == ExecutionIntent.RUN) "pause" else "resume") }) { Text(if (plan.intent == ExecutionIntent.RUN) "Пауза" else "Продолжить") }
                    TextButton(onClick = { service.control(plan.id, "stop") }, enabled = plan.intent != ExecutionIntent.STOP) { Text("Остановить") }
                    if (plan.issue != null) TextButton(onClick = { service.control(plan.id, "retry") }) { Text("Повторить") }
                }
                if (initial != null) TextButton(onClick = { original = !original }) { Text(if (original) "Актуальный план" else "Подтверждённая версия") }
            }
        }
    }
    if (graphOpen) {
        Dialog(onDismissRequest = { graphOpen = false; selected = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.padding(16.dp).widthIn(max = 1100.dp).fillMaxWidth().fillMaxHeight(0.85f),
                shape = MaterialTheme.shapes.large) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Схема плана", style = MaterialTheme.typography.titleMedium)
                    DecisionGraph(display, selected, { selected = it }, Modifier.fillMaxWidth().weight(1f), fitInitially = true,
                        onChooseOption = if (original || drafts[session.id]?.active == true) null else { choiceId, optionId -> service.chooseOption(plan.id, plan.revision, choiceId, optionId) })
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { graphOpen = false; selected = null }) { Text("Закрыть") }
                    }
                }
            }
        }
    }
    if (selected != null) {
        val projected = planningGraphProjection(display)
        projected.tree.firstOrNull { it.id == selected }?.let { node ->
            StageDetailsDialog(projected, node, { selected = null }) {
                val stage = plan.milestones.firstOrNull { it.id == (node.stageId ?: node.id) }
                if (stage != null && plan.confirmedRevision != null) Button(onClick = {
                    selected = null
                    graphOpen = false
                    onOpenSession(stage.attempts.firstOrNull()?.sessionId ?: "plan-${plan.id}-stage-${stage.id}")
                }) { Text("Открыть сессию") }
            }
        }
    }
}
