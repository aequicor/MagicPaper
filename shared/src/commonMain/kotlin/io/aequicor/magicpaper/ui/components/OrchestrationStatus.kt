package io.aequicor.magicpaper.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.ui.CodingSessionUi
import io.aequicor.magicpaper.designsystem.LocalPaperColors
import io.aequicor.magicpaper.designsystem.PaperAction
import io.aequicor.magicpaper.designsystem.PaperDialog
import io.aequicor.magicpaper.designsystem.PaperDivider
import io.aequicor.magicpaper.designsystem.PaperField
import io.aequicor.magicpaper.designsystem.PaperMenuHost
import io.aequicor.magicpaper.designsystem.PaperRichMenuAction
import io.aequicor.magicpaper.designsystem.PaperStatusPanel
import io.aequicor.magicpaper.designsystem.PaperText
import io.aequicor.magicpaper.designsystem.PaperTextRole

@Composable
internal fun OrchestrationStatus(
    session: CodingSessionUi, service: OrchestrationService, onOpenSession: (String) -> Unit,
    modifier: Modifier = Modifier,
    scrolled: Boolean = false,
) {
    val openQuestionnaire = LocalOpenQuestionnaire.current
    val states by service.states.collectAsState()
    val plans by service.store.plans.collectAsState()
    val sessions by service.sessions.collectAsState()
    val drafts by service.drafts.collectAsState()
    val persistenceErrors by service.persistenceErrors.collectAsState()
    val unsavedInputs by service.unsavedInputs.collectAsState()
    val state = states[session.session.id]
    val plan = plans.firstOrNull { it.id == state?.activePlanId }
        ?: plans.filter { it.parentSessionId == session.session.id }.maxByOrNull { it.updatedAt }
    val questions = state?.openQuestions().orEmpty().filter { q -> session.interactions.any { it.sourceId == q.id } }
    val children = sessions.filter { it.parentSessionId == session.session.id }
    var expanded by rememberSaveable(session.session.id) { mutableStateOf(false) }
    var archive by rememberSaveable(session.session.id) { mutableStateOf(false) }
    val previousStages = plan?.runHistory?.lastOrNull()?.milestones?.filter { it.completed }?.map { it.id }.orEmpty().toSet()
    val stages = plan?.selectedMilestones.orEmpty().filter { it.id !in previousStages }
    val pausedStages = plan?.let { state?.pausedStages(it) }.orEmpty()
    val done = stages.count { it.completed }
    val blockers = plan?.blockingIssues(session.messages).orEmpty()
    val active = plan?.selectedMilestones.orEmpty().filter { plan?.isStageWorking(it) == true && it.id !in pausedStages }
    val failedInput = state?.inputs?.lastOrNull()?.takeIf { it.status == OrchestrationInputStatus.FAILED }
    val phase = when {
        pausedStages.isNotEmpty() -> "Ожидание ответа пользователя · приостановлено этапов: ${pausedStages.size}"
        session.interactions.isNotEmpty() -> "Ждём вашего ответа"
        session.session.id in persistenceErrors -> "Ошибка сохранения · выполнение остановлено"
        failedInput != null -> "Ошибка обработки сообщения"
        plan == null -> "Готов обсудить задачу"
        plan.proposalReadyForConfirmation -> "Предложение доработки сохранено"
        blockers.isNotEmpty() -> "Выполнение остановлено"
        plan.phase == ExecutionPhase.COMPLETE -> "Работа завершена · можно задать вопрос или запросить доработку"
        plan.intent == ExecutionIntent.PAUSE -> "Пауза"
        plan.intent == ExecutionIntent.STOP && plan.confirmedRevision != null -> "Остановлено"
        drafts[session.session.id]?.active == true -> "Сообщение обрабатывается"
        plan.confirmedRevision == null -> if (plan.milestones.isEmpty()) "Уточнение задачи" else "План сохранён"
        plan.phase == ExecutionPhase.VERIFYING -> "Итоговая проверка"
        plan.phase == ExecutionPhase.APPLYING -> "Перенос результата в проект"
        active.isNotEmpty() -> "Выполнение этапов" + if (plan.proposal != null) " · есть предложение доработки" else ""
        plan.scheduledMessages.any { it.status == ScheduledMessageStatus.WAITING } || plan.selectedMilestones.any { it.attempts.lastOrNull()?.waitingForEvent != null } -> "Ожидание события или времени"
        else -> "Ожидание следующего этапа"
    }
    PaperStatusPanel(modifier.fillMaxWidth().padding(horizontal = 8.dp).semantics {
        contentDescription = "Состояние сессии"
    }, scrolled = scrolled) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            PaperText("${session.session.subtitle()} · ${session.session.name}", role = PaperTextRole.TITLE,
                fontWeight = FontWeight.SemiBold)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    PaperText(phase)
                    plan?.finalAttempt?.acceptanceRecord?.let { record ->
                        PaperText(when (record.status) {
                            AcceptanceStatus.ACCEPTED -> "Обязательные критерии приняты"
                            AcceptanceStatus.ACCEPTED_WITH_SKIPS -> "Завершено с пропуском проверок по вашему решению"
                            AcceptanceStatus.PARTIAL -> "Приёмка частичная: часть проверок не выполнена"
                            AcceptanceStatus.BLOCKED -> "Приёмка заблокирована"
                            AcceptanceStatus.FAILED -> "Приёмка не пройдена"
                            AcceptanceStatus.STALE -> "Результаты проверки устарели"
                            AcceptanceStatus.UNKNOWN -> "Приёмка не подтверждена"
                        }, role = PaperTextRole.LABEL)
                    }
                    if (session.interactions.isNotEmpty()) PaperText("Обращений: ${session.interactions.size}", role = PaperTextRole.LABEL)
                    if (stages.isNotEmpty()) PaperText("Текущий запуск: $done/${stages.size} этапов", role = PaperTextRole.LABEL)
                    questions.take(2).forEach { q -> PaperText("Ответ для: ${q.scopeLabel}", color = LocalPaperColors.current.action,
                        role = PaperTextRole.LABEL) }
                    if (questions.size > 2) PaperText("Ещё ожидают ответа: ${questions.size - 2}", role = PaperTextRole.LABEL)
                    if (active.isNotEmpty()) PaperText("Сейчас: ${active.take(2).joinToString { it.stageLabel() }}" +
                        if (active.size > 2) " и ещё ${active.size - 2}" else "", role = PaperTextRole.LABEL)
                    if (blockers.isNotEmpty()) PaperText(blockers.joinToString("\n") { it.title },
                        color = LocalPaperColors.current.error, role = PaperTextRole.LABEL)
                }
                PaperAction({ expanded = !expanded }) {
                    PaperText(if (expanded) "Свернуть ▴" else "Подробнее ▾", role = PaperTextRole.LABEL,
                        color = LocalPaperColors.current.action)
                }
            }
            if (plan?.proposal != null) PlanningProposalCard(plan, questions.isNotEmpty()) { proposalId ->
                openQuestionnaire(InteractionKind.CONFIRM_PLAN, plan.id)
            }
            if (expanded) Column(Modifier.heightIn(max = 270.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SessionActions(session.session, service, onOpenSession, allowArchive = false)
                questions.forEach { q -> PaperText("Ожидается ответ: ${q.scopeLabel}", role = PaperTextRole.LABEL) }
                state?.inputs?.filter { it.status in listOf(OrchestrationInputStatus.QUEUED, OrchestrationInputStatus.PROCESSING,
                    OrchestrationInputStatus.FAILED, OrchestrationInputStatus.CANCELLED) }?.forEach { input ->
                    PaperText("${input.status.inputLabel()}: ${input.text}", role = PaperTextRole.LABEL)
                    if (input.status == OrchestrationInputStatus.QUEUED && input.scheduledRuleId == null) {
                        PaperAction({ service.cancelQueuedInput(session.session.id, input.id) }) {
                            PaperText("Отменить отправку", role = PaperTextRole.LABEL)
                        }
                    }
                    if (input.status in listOf(OrchestrationInputStatus.FAILED, OrchestrationInputStatus.CANCELLED)) {
                        if (input.error.isNotBlank()) PaperText(input.error, color = LocalPaperColors.current.error)
                        PaperAction({ openQuestionnaire(InteractionKind.RECOVER_INPUT, input.id) }) {
                            PaperText("Повторить обработку", role = PaperTextRole.LABEL)
                        }
                    }
                }
                if (plan != null) ScheduledMessages(plan,
                    onCancel = { service.cancelScheduledMessage(plan.id, it) },
                    onEdit = { id, request -> service.send(session.session, "Измени правило $id: $request") })
                PaperText("Сессии", role = PaperTextRole.TITLE)
                children.filter { !it.archived }.forEach { child ->
                    val childPlan = plans.firstOrNull { it.id == child.planId }
                    val stage = childPlan?.milestones?.firstOrNull { it.id == child.stageId }
                    SessionActions(child, service, onOpenSession, allowArchive = stage?.completed == true)
                    PaperText(when {
                        questions.any { child.id == it.sourceSessionId || child.stageId in it.stageIds } -> "Ждёт вашего ответа"
                        stage?.id in pausedStages || stage?.waitingForAnswer() == true -> "Ждёт вашего ответа"
                        stage?.attempts?.lastOrNull()?.waitingForEvent != null -> childPlan.eventWaitLabel(stage.id)
                        stage?.completed == true -> "Завершено"
                        stage != null && childPlan.isStageWorking(stage) -> "Работает"
                        else -> "Ожидает задания"
                    }, role = PaperTextRole.LABEL)
                }
                if (children.any { it.archived }) {
                    PaperAction({ archive = !archive }) {
                        PaperText("Архив сессий (${children.count { it.archived }}) ${if (archive) "▴" else "▾"}", role = PaperTextRole.LABEL)
                    }
                    if (archive) children.filter { it.archived }.forEach { SessionActions(it, service, onOpenSession) }
                }
                plan?.deliveries?.takeLast(5)?.forEach { delivery ->
                    val target = children.firstOrNull { it.stageId == delivery.targetStageId && it.planId == plan.id }
                    PaperText("${delivery.state.deliveryLabel()} → ${target?.name ?: plan.milestones.firstOrNull { it.id == delivery.targetStageId }?.title.orEmpty()}",
                        role = PaperTextRole.LABEL)
                }
                PaperText("Следующий шаг: " + when {
                    failedInput != null -> "повторить обработку сообщения"
                    questions.isNotEmpty() -> "ответить в карточке уточнения под диалогом"
                    plan?.proposalReadyForConfirmation == true -> "проверить и подтвердить предложение доработки"
                    blockers.isNotEmpty() -> "исправить причину блокировки"
                    plan?.phase == ExecutionPhase.COMPLETE -> "обсудить результат или описать доработку"
                    plan?.confirmedRevision == null -> "уточнить и подтвердить план"
                    else -> "дождаться результатов дочерних сессий"
                }, role = PaperTextRole.LABEL)
                if (plan != null && plan.phase != ExecutionPhase.COMPLETE && plan.confirmedRevision != null) {
                    Row {
                        PaperAction({ if (plan.intent != ExecutionIntent.RUN && plan.blockingIssues(session.messages).isNotEmpty()) openQuestionnaire(InteractionKind.RECOVER_PLAN, plan.id) else service.control(plan.id, if (plan.intent == ExecutionIntent.RUN) "pause" else "resume") }) {
                            PaperText(if (plan.intent == ExecutionIntent.RUN) "Пауза" else "Продолжить", role = PaperTextRole.LABEL)
                        }
                        PaperAction({ service.control(plan.id, "stop") }) {
                            PaperText("Остановить", role = PaperTextRole.LABEL)
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun OrchestrationInputFailure(input: OrchestrationInput, onRetry: () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        PaperText(planningFailureMessage(input.error.ifBlank { "Не удалось обработать сообщение." }),
            role = PaperTextRole.LABEL, color = LocalPaperColors.current.error)
        PaperAction(onRetry) { PaperText("Повторить обработку", role = PaperTextRole.LABEL) }
    }
}

private fun Milestone.waitingForAnswer() = attempts.lastOrNull()?.waitingForUser != null

@Composable
private fun SessionActions(session: CodingSession, service: OrchestrationService, onOpen: (String) -> Unit, allowArchive: Boolean = true) {
    var menu by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf(false) }
    var name by remember(session.name) { mutableStateOf(session.name) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        PaperAction({ onOpen(session.id) }, Modifier.weight(1f)) {
            Column { PaperText(session.name); PaperText(session.subtitle(), role = PaperTextRole.LABEL) }
        }
        Box {
            PaperAction({ menu = true }) { PaperText("⋯", role = PaperTextRole.LABEL) }
            PaperMenuHost(menu, { menu = false }) {
                PaperRichMenuAction(text = { PaperText("Переименовать") }, onClick = { menu = false; rename = true })
                if (session.archived) PaperRichMenuAction(text = { PaperText("Восстановить") }, onClick = { menu = false; service.restoreSession(session.id) })
                else if (allowArchive) PaperRichMenuAction(text = { PaperText("В архив") }, onClick = { menu = false; service.archiveSession(session.id) })
            }
        }
    }
    if (rename) PaperDialog("Название сессии", { rename = false }, confirmLabel = "Сохранить",
        onConfirm = { service.renameSession(session.id, name); rename = false }, confirmEnabled = name.isNotBlank(),
        dismissLabel = "Отмена") {
        PaperField(name, { name = it }, label = "Название сессии")
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
                    PaperText("↓", Modifier.padding(start = 8.dp), role = PaperTextRole.LABEL,
                        color = LocalPaperColors.current.secondaryText)
                    AddressLink(route.target, "Кому", onOpen, Modifier.fillMaxWidth())
                }
            } else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AddressLink(route.source, "От", onOpen, Modifier.weight(1f))
                PaperText("→", Modifier.padding(top = 30.dp), role = PaperTextRole.LABEL,
                    color = LocalPaperColors.current.secondaryText)
                AddressLink(route.target, "Кому", onOpen, Modifier.weight(1f))
            }
        }
        if (route.hops.size > 2) PaperText("Маршрут: ${route.hops.joinToString(" → ") { it.name }}", role = PaperTextRole.LABEL)
        else route.via?.takeIf { it.sessionId != route.source.sessionId }?.let {
            PaperText("Через родителя: ${it.name}", role = PaperTextRole.LABEL)
        }
        if (route.stageLabel.isNotBlank()) PaperText(route.stageLabel, role = PaperTextRole.LABEL)
        val contextState = route.sessionDeliveryState?.let { state -> when (state) {
            SessionDeliveryState.ACCEPTED -> "Принято к доставке"
            SessionDeliveryState.DELIVERED -> "Доставлено"
            SessionDeliveryState.PROCESSED -> "Обработано"
            SessionDeliveryState.CANCELLED -> "Отменено"
        } }
        PaperText(route.kind + (contextState?.let { " · $it" } ?: delivery?.let { " · ${it.state.deliveryLabel()}" } ?: ""), role = PaperTextRole.LABEL)
        message.handoff?.let { HandoffDetails(it) }
        PaperDivider(Modifier.padding(vertical = 4.dp))
    }
}

@Composable private fun AddressLink(address: SessionAddress, label: String, onOpen: (String) -> Unit, modifier: Modifier = Modifier) {
    val body: @Composable () -> Unit = {
        Column(Modifier.heightIn(min = 48.dp).padding(8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            PaperText(label, role = PaperTextRole.LABEL, color = LocalPaperColors.current.secondaryText)
            PaperText(address.name, role = PaperTextRole.BODY, fontWeight = FontWeight.SemiBold,
                color = if (address.sessionId.isNotBlank()) LocalPaperColors.current.action else LocalPaperColors.current.text)
            if (address.subtitle.isNotBlank()) PaperText(address.subtitle, role = PaperTextRole.LABEL,
                color = LocalPaperColors.current.secondaryText)
            if (address.orchestratorName.isNotBlank()) PaperText(address.orchestratorName, role = PaperTextRole.LABEL,
                color = LocalPaperColors.current.secondaryText)
        }
    }
    if (address.sessionId.isNotBlank()) {
        PaperAction(onClick = { onOpen(address.sessionId) }, modifier = modifier, contentPadding = PaddingValues(0.dp)) {
            body()
        }
    } else {
        Box(modifier) { body() }
    }
}

@Composable
internal fun OrchestrationMessageInputStatus(message: CodingMessage, sessionId: String, service: OrchestrationService?) {
    if (message.inputStatus == null) return
    val states = service?.states?.collectAsState()?.value
    val input = states?.get(sessionId)?.inputs?.firstOrNull { it.id == message.id }
    val status = input?.status ?: message.inputStatus
    Column {
        PaperText(status.inputLabel(), role = PaperTextRole.LABEL)
        if (service != null && message.role == CodingRole.USER && status == OrchestrationInputStatus.QUEUED &&
            message.scheduledRuleId == null && input?.scheduledRuleId == null) {
            PaperAction({ service.cancelQueuedInput(sessionId, message.id) }) {
                PaperText("Отменить отправку", role = PaperTextRole.LABEL)
            }
        }
    }
}

internal fun DeliveryState.deliveryLabel(): String = when (this) {
    DeliveryState.QUEUED -> "В очереди"
    DeliveryState.DELIVERED -> "Доставлено в сессию"
    DeliveryState.ANSWERED -> "Обработано"
    DeliveryState.CANCELLED -> "Отменено"
}
internal fun OrchestrationInputStatus.inputLabel(): String = when (this) {
    OrchestrationInputStatus.QUEUED -> "Сообщение в очереди"
    OrchestrationInputStatus.PROCESSING -> "Сообщение обрабатывается"
    OrchestrationInputStatus.DONE -> "Сообщение обработано"
    OrchestrationInputStatus.FAILED -> "Нужна повторная обработка"
    OrchestrationInputStatus.CANCELLED -> "Обработка остановлена"
    OrchestrationInputStatus.WITHDRAWN -> "Отправка отменена"
}
