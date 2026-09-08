package io.aequicor.magicpaper.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.ui.CodingSessionUi

@Composable
internal fun OrchestrationStatus(
    session: CodingSessionUi, service: OrchestrationService, onOpenSession: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val states by service.states.collectAsState()
    val plans by service.store.plans.collectAsState()
    val sessions by service.sessions.collectAsState()
    val drafts by service.drafts.collectAsState()
    val persistenceErrors by service.persistenceErrors.collectAsState()
    val unsavedInputs by service.unsavedInputs.collectAsState()
    val state = states[session.session.id]
    val plan = plans.firstOrNull { it.id == state?.activePlanId }
        ?: plans.filter { it.parentSessionId == session.session.id }.maxByOrNull { it.updatedAt }
    val questions = state?.openQuestions().orEmpty()
    val children = sessions.filter { it.parentSessionId == session.session.id }
    var expanded by rememberSaveable(session.session.id) { mutableStateOf(false) }
    var archive by rememberSaveable(session.session.id) { mutableStateOf(false) }
    val previousStages = plan?.runHistory?.lastOrNull()?.milestones?.filter { it.completed }?.map { it.id }.orEmpty().toSet()
    val stages = plan?.selectedMilestones.orEmpty().filter { it.id !in previousStages }
    val done = stages.count { it.completed }
    val blockers = plan?.blockingIssues(session.messages).orEmpty()
    val active = plan?.selectedMilestones.orEmpty().filter { plan?.isStageWorking(it) == true }
    val phase = when {
        session.session.id in persistenceErrors -> "Ошибка сохранения · выполнение остановлено"
        plan == null -> "Готов обсудить задачу"
        questions.isNotEmpty() -> "Нужен ваш ответ"
        plan.proposal != null -> "Предложена доработка · нужно подтверждение"
        blockers.isNotEmpty() -> "Нужно устранить блокировку"
        plan.phase == ExecutionPhase.COMPLETE -> "Работа завершена · можно задать вопрос или запросить доработку"
        plan.intent == ExecutionIntent.PAUSE -> "Пауза"
        plan.intent == ExecutionIntent.STOP && plan.confirmedRevision != null -> "Остановлено"
        drafts[session.session.id]?.active == true -> "Оркестратор обрабатывает сообщение"
        plan.confirmedRevision == null -> if (plan.milestones.isEmpty()) "Уточнение задачи" else "План готов · нужно подтверждение"
        plan.phase == ExecutionPhase.VERIFYING -> "Итоговая проверка"
        plan.phase == ExecutionPhase.APPLYING -> "Перенос результата в проект"
        active.isNotEmpty() -> "Выполнение этапов"
        else -> "Ожидание следующего этапа"
    }
    Surface(modifier.semantics { contentDescription = "Состояние оркестратора" },
        shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${session.session.subtitle()} · ${session.session.name}", Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Text(if (expanded) "Свернуть ▴" else "Подробнее ▾")
                }
            }
            Text(phase, style = MaterialTheme.typography.bodyMedium)
            persistenceErrors[session.session.id]?.let { message ->
                Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                unsavedInputs[session.session.id].orEmpty().forEach { input ->
                    Text("Не сохранено: ${input.text}", style = MaterialTheme.typography.bodySmall)
                }
                TextButton({ service.recoverOrchestration(session.session.id) }) { Text("Проверить хранилище и восстановить очередь") }
            }
            if (stages.isNotEmpty()) Text("Текущий запуск: $done/${stages.size} этапов", style = MaterialTheme.typography.labelMedium)
            questions.take(2).forEach { q -> Text("Ответ для: ${q.scopeLabel}", color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodySmall) }
            if (questions.size > 2) Text("Ещё ожидают ответа: ${questions.size - 2}", style = MaterialTheme.typography.bodySmall)
            if (active.isNotEmpty()) Text("Сейчас: ${active.take(2).joinToString { it.stageLabel() }}" +
                if (active.size > 2) " и ещё ${active.size - 2}" else "", style = MaterialTheme.typography.bodySmall)
            if (blockers.isNotEmpty()) Text(blockers.joinToString("\n") { it.title },
                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            if (expanded) Column(Modifier.heightIn(max = 270.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SessionActions(session.session, service, onOpenSession, allowArchive = false)
                questions.forEach { q -> Text("Ожидается ответ: ${q.scopeLabel}", style = MaterialTheme.typography.bodySmall) }
                state?.inputs?.filter { it.status in listOf(OrchestrationInputStatus.QUEUED, OrchestrationInputStatus.PROCESSING,
                    OrchestrationInputStatus.FAILED, OrchestrationInputStatus.CANCELLED) }?.forEach { input ->
                    Text("${input.status.inputLabel()}: ${input.text}", style = MaterialTheme.typography.bodySmall)
                    if (input.status in listOf(OrchestrationInputStatus.FAILED, OrchestrationInputStatus.CANCELLED)) {
                        if (input.error.isNotBlank()) Text(input.error, color = MaterialTheme.colorScheme.error)
                        TextButton({ service.retryInput(session.session.id, input.id) }) { Text("Повторить обработку") }
                    }
                }
                Text("Сессии", style = MaterialTheme.typography.labelLarge)
                children.filter { !it.archived }.forEach { child ->
                    val childPlan = plans.firstOrNull { it.id == child.planId }
                    val stage = childPlan?.milestones?.firstOrNull { it.id == child.stageId }
                    SessionActions(child, service, onOpenSession, allowArchive = stage?.completed == true)
                    Text(when {
                        questions.any { child.id == it.sourceSessionId || child.stageId in it.stageIds } -> "Ждёт вашего ответа"
                        stage?.waitingForAnswer() == true -> "Ждёт вашего ответа"
                        stage?.completed == true -> "Завершено"
                        stage != null && childPlan.isStageWorking(stage) -> "Работает"
                        else -> "Ожидает задания"
                    }, style = MaterialTheme.typography.labelSmall)
                }
                if (children.any { it.archived }) {
                    TextButton({ archive = !archive }) { Text("Архив сессий (${children.count { it.archived }}) ${if (archive) "▴" else "▾"}") }
                    if (archive) children.filter { it.archived }.forEach { SessionActions(it, service, onOpenSession) }
                }
                plan?.deliveries?.takeLast(5)?.forEach { delivery ->
                    val target = children.firstOrNull { it.stageId == delivery.targetStageId && it.planId == plan.id }
                    Text("${delivery.state.deliveryLabel()} → ${target?.name ?: plan.milestones.firstOrNull { it.id == delivery.targetStageId }?.title.orEmpty()}",
                        style = MaterialTheme.typography.bodySmall)
                }
                Text("Следующий шаг: " + when {
                    questions.isNotEmpty() -> "ответить в карточке уточнения под диалогом"
                    plan?.proposal != null -> "проверить предложение доработки"
                    blockers.isNotEmpty() -> "исправить причину блокировки"
                    plan?.phase == ExecutionPhase.COMPLETE -> "обсудить результат или описать доработку"
                    plan?.confirmedRevision == null -> "уточнить и подтвердить план"
                    else -> "дождаться результатов исполнителей"
                }, style = MaterialTheme.typography.bodySmall)
                if (plan != null && plan.phase != ExecutionPhase.COMPLETE && plan.confirmedRevision != null) {
                    Row {
                        TextButton({ service.control(plan.id, if (plan.intent == ExecutionIntent.RUN) "pause" else "resume") }) {
                            Text(if (plan.intent == ExecutionIntent.RUN) "Пауза" else "Продолжить")
                        }
                        TextButton({ service.control(plan.id, "stop") }) { Text("Остановить") }
                    }
                }
            }
        }
    }
}

private fun Milestone.waitingForAnswer() = attempts.lastOrNull()?.waitingForUser != null

@Composable
private fun SessionActions(session: CodingSession, service: OrchestrationService, onOpen: (String) -> Unit, allowArchive: Boolean = true) {
    var menu by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf(false) }
    var name by remember(session.name) { mutableStateOf(session.name) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        TextButton({ onOpen(session.id) }, Modifier.weight(1f)) {
            Column { Text(session.name); Text(session.subtitle(), style = MaterialTheme.typography.labelSmall) }
        }
        Box {
            TextButton({ menu = true }) { Text("⋯") }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem(text = { Text("Переименовать") }, onClick = { menu = false; rename = true })
                if (session.archived) DropdownMenuItem(text = { Text("Восстановить") }, onClick = { menu = false; service.restoreSession(session.id) })
                else if (allowArchive) DropdownMenuItem(text = { Text("В архив") }, onClick = { menu = false; service.archiveSession(session.id) })
            }
        }
    }
    if (rename) Dialog(onDismissRequest = { rename = false }) {
        Surface(shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Название сессии", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(name, { name = it }, singleLine = true)
                Row {
                    TextButton({ rename = false }) { Text("Отмена") }
                    Button({ service.renameSession(session.id, name); rename = false }, enabled = name.isNotBlank()) { Text("Сохранить") }
                }
            }
        }
    }
}

@Composable
internal fun OrchestrationMessageRoute(message: CodingMessage, service: OrchestrationService?, onOpen: (String) -> Unit) {
    val route = message.route ?: return
    val plans = service?.store?.plans?.collectAsState()?.value.orEmpty()
    val delivery = plans.flatMap { it.deliveries }.firstOrNull { it.id == route.deliveryId }
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            if (maxWidth < 480.dp * LocalDensity.current.fontScale) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    AddressLink(route.source, "От", onOpen, Modifier.fillMaxWidth())
                    Text("↓", Modifier.padding(start = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    AddressLink(route.target, "Кому", onOpen, Modifier.fillMaxWidth())
                }
            } else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AddressLink(route.source, "От", onOpen, Modifier.weight(1f))
                Text("→", Modifier.padding(top = 30.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                AddressLink(route.target, "Кому", onOpen, Modifier.weight(1f))
            }
        }
        route.via?.takeIf { it.sessionId != route.source.sessionId }?.let {
            Text("Через оркестратора: ${it.name}", style = MaterialTheme.typography.labelSmall)
        }
        if (route.stageLabel.isNotBlank()) Text(route.stageLabel, style = MaterialTheme.typography.labelMedium)
        Text(route.kind + (delivery?.let { " · ${it.state.deliveryLabel()}" } ?: ""), style = MaterialTheme.typography.labelSmall)
        HorizontalDivider(Modifier.padding(vertical = 4.dp))
    }
}

@Composable private fun AddressLink(address: SessionAddress, label: String, onOpen: (String) -> Unit, modifier: Modifier = Modifier) {
    val link = if (address.sessionId.isNotBlank()) Modifier.clip(MaterialTheme.shapes.small)
        .clickable(role = Role.Button) { onOpen(address.sessionId) } else Modifier
    Column(modifier.then(link).heightIn(min = 48.dp).padding(8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(address.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
            color = if (address.sessionId.isNotBlank()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
        if (address.subtitle.isNotBlank()) Text(address.subtitle, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (address.orchestratorName.isNotBlank()) Text(address.orchestratorName, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

internal fun DeliveryState.deliveryLabel(): String = when (this) {
    DeliveryState.QUEUED -> "В очереди"
    DeliveryState.DELIVERED -> "Передано исполнителю"
    DeliveryState.ANSWERED -> "Обработано"
}
internal fun OrchestrationInputStatus.inputLabel(): String = when (this) {
    OrchestrationInputStatus.QUEUED -> "Сообщение в очереди"
    OrchestrationInputStatus.PROCESSING -> "Оркестратор обрабатывает сообщение"
    OrchestrationInputStatus.DONE -> "Сообщение обработано"
    OrchestrationInputStatus.FAILED -> "Нужна повторная обработка"
    OrchestrationInputStatus.CANCELLED -> "Обработка остановлена"
}
