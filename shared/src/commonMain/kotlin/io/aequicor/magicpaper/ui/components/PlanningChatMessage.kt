package io.aequicor.magicpaper.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.plugins.builtin.StageDetailsDialog
import io.aequicor.magicpaper.designsystem.*

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun PlanningChatMessage(message: CodingMessage, session: CodingSession, history: List<CodingMessage>, service: PlanningChatService, onOpenSession: (String) -> Unit) {
    val openQuestionnaire = LocalOpenQuestionnaire.current
    val block = message.planning ?: return
    val plans by service.store.plans.collectAsState()
    val live by service.execution.live.collectAsState()
    val drafts by service.drafts.collectAsState()
    val source = plans.firstOrNull { it.id == block.planId } ?: return
    if (block.questions.isNotEmpty()) {
        val answered = block.requestStatus == UserRequestStatus.ANSWERED || history.any { it.planning?.replyTo == message.id && it.planning.closesRequest }
        PaperText(if (answered) "Ответы на уточнения отправлены" else "Ответьте в опроснике под диалогом",
            role = PaperTextRole.LABEL, color = LocalPaperColors.current.secondaryText)
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
        SelectionContainer { PaperText(display.goal) }
        display.selectedMilestones.forEach { stage ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SelectionContainer { PaperText(stage.stageLabel(), fontWeight = FontWeight.SemiBold) }
                if (stage.description.isNotBlank()) ChatMarkdown(stage.description)
                if (stage.acceptance.isNotBlank()) SelectionContainer { PaperText("Критерии готовности: ${stage.acceptance}") }
                stage.acceptanceCriteria.forEach { criterion ->
                    PaperText("${if (criterion.required) "Обязательно" else "Необязательно"}: ${criterion.description} · ${criterion.environment.label()}", role = PaperTextRole.LABEL)
                }
                if (stage.dependsOn.isNotEmpty()) PaperText("После: " + stage.dependsOn.joinToString { id ->
                    display.milestones.firstOrNull { it.id == id }?.title ?: id
                }, role = PaperTextRole.LABEL, color = LocalPaperColors.current.secondaryText)
                stage.assignment?.let { assignment ->
                    PaperText("Исполнитель: ${assignment.displayName.ifBlank { assignment.modelId }} · ${assignment.effort.shortLabel}",
                        role = PaperTextRole.LABEL, color = LocalPaperColors.current.secondaryText)
                }
            }
        }
        if (plan.proposal != null) {
            PaperDivider()
            val state by service.states.collectAsState()
            PlanningProposalDetails(plan, state[plan.parentSessionId]?.openQuestions(plan.id).orEmpty().isNotEmpty()) {
                openQuestionnaire(InteractionKind.CONFIRM_PLAN, plan.id)
            }
        }
        if (plan.confirmedRevision != null) {
            PaperText("${plan.doneCount}/${plan.selectedMilestones.size} этапов · ${when {
                plan.phase == ExecutionPhase.COMPLETE -> "Готово"
                plan.intent == ExecutionIntent.PAUSE -> "Пауза"
                plan.issue != null -> "Нужно внимание"
                plan.intent == ExecutionIntent.STOP -> "Остановлено"
                else -> "Выполняется"
            }}", role = PaperTextRole.LABEL)
            PaperProgress(progress = plan.progress, modifier = Modifier.fillMaxWidth())
        } else PaperText("Для уточнения напишите сообщение в этом чате.", role = PaperTextRole.LABEL)
        plan.issue?.let { PaperText(it.message, role = PaperTextRole.LABEL, color = LocalPaperColors.current.error) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PaperButton("Схема плана", { graphOpen = true }, kind = PaperButtonKind.SECONDARY)
            if (plan.confirmedRevision == null) {
                PaperButton("Подтвердить", { openQuestionnaire(InteractionKind.CONFIRM_PLAN, plan.id) }, enabled = plan.wizardStep != PlanningStep.CLARIFY && plan.selectedMilestones.isNotEmpty() && DecisionCompiler.compile(plan).valid && drafts[session.id]?.active != true)
            } else {
                if (plan.phase != ExecutionPhase.COMPLETE) {
                    PaperButton(if (plan.intent == ExecutionIntent.RUN) "Пауза" else "Продолжить", { if (plan.intent != ExecutionIntent.RUN && plan.blockingIssues(history).isNotEmpty()) openQuestionnaire(InteractionKind.RECOVER_PLAN, plan.id) else service.control(plan.id, if (plan.intent == ExecutionIntent.RUN) "pause" else "resume") }, kind = PaperButtonKind.SECONDARY)
                    PaperAction({ service.control(plan.id, "stop") }, enabled = plan.intent != ExecutionIntent.STOP) { PaperText("Остановить", role = PaperTextRole.LABEL) }
                    if (plan.issue != null) PaperAction({ openQuestionnaire(InteractionKind.RECOVER_PLAN, plan.id) }) { PaperText(if (plan.canExtendAfterFinalVerification) "Доработать план" else "Повторить", role = PaperTextRole.LABEL) }
                }
                if (initial != null) PaperAction({ original = !original }) { PaperText(if (original) "Актуальный план" else "Подтверждённая версия", role = PaperTextRole.LABEL) }
            }
        }
    }
    if (graphOpen) {
        PaperModal(onDismissRequest = { graphOpen = false; selected = null },
            title = { PaperText("Схема плана", role = PaperTextRole.TITLE) },
            text = {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    DecisionGraph(display, selected, { selected = it }, Modifier.fillMaxWidth().weight(1f), fitInitially = true,
                        onChooseOption = if (original || drafts[session.id]?.active == true) null else { choiceId, optionId -> service.chooseOption(plan.id, plan.revision, choiceId, optionId) })
                }
            }, confirmButton = { PaperAction({ graphOpen = false; selected = null }) { PaperText("Закрыть", role = PaperTextRole.LABEL) } })
    }
    if (selected != null) {
        val projected = planningGraphProjection(display)
        projected.tree.firstOrNull { it.id == selected }?.let { node ->
            StageDetailsDialog(projected, node, { selected = null }) {
                val stage = plan.milestones.firstOrNull { it.id == (node.stageId ?: node.id) }
                if (stage != null && plan.confirmedRevision != null) PaperButton("Открыть сессию", {
                    selected = null
                    graphOpen = false
                    onOpenSession(stage.attempts.firstOrNull()?.sessionId ?: "plan-${plan.id}-stage-${stage.id}")
                })
            }
        }
    }
}
