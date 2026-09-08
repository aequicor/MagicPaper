package io.aequicor.magicpaper.domain

import io.aequicor.magicpaper.util.Id
import kotlinx.serialization.Serializable
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

@Serializable enum class MessageEventKind {
    RESULT_RETURNED, QUESTION_RETURNED, BLOCKED_RETURNED, WAIT_REQUESTED,
    TASK_SUCCEEDED, RUN_COMPLETED, INTERVENTION_REQUIRED, QUESTION_ANSWERED,
}
@Serializable enum class MessageTriggerKind { AT_TIME, EVENT }
@Serializable data class MessageTrigger(
    val kind: MessageTriggerKind,
    val at: Long? = null,
    val event: MessageEventKind? = null,
    val taskId: String? = null,
    val questionId: String? = null,
    val attemptId: String? = null,
    val turnIndex: Int? = null,
    val deadline: Long? = null,
    val afterMillis: Long? = null,
    val timeoutMillis: Long? = null,
)
@Serializable enum class ScheduledMessageStatus { WAITING, READY, QUEUED, CANCELLED, ERROR }
@Serializable enum class ScheduleOperation { CREATE, UPDATE, CANCEL }
@Serializable data class ScheduleCommand(
    val operation: ScheduleOperation = ScheduleOperation.CREATE,
    val ruleId: String = "",
    val trigger: MessageTrigger? = null,
    /** null addresses the owning orchestrator; a task is never selected by its title. */
    val targetTaskId: String? = null,
    val text: String = "",
    /** Only the worker currently returning control may release its execution slot this way. */
    val waitTaskId: String? = null,
)
@Serializable data class ScheduledMessage(
    val id: String, val planId: String, val runId: String, val authorSessionId: String,
    val trigger: MessageTrigger, val targetSessionId: String, val targetTaskId: String?,
    val text: String, val createdAt: Long,
    val afterEventIds: Set<String> = emptySet(),
    val waitTaskId: String? = null,
    val status: ScheduledMessageStatus = ScheduledMessageStatus.WAITING,
    val deliveryId: String,
    val eventId: String? = null,
    val firedAt: Long? = null,
    val timeout: Boolean = false,
    val payload: String = "",
    val reason: String = "",
)
@Serializable data class MessageEvent(
    val id: String, val sourceKey: String, val planId: String, val runId: String,
    val kind: MessageEventKind, val at: Long, val text: String,
    val taskId: String? = null, val questionId: String? = null,
    val attemptId: String? = null, val turnIndex: Int? = null,
)

internal fun uniqueSchedulingId(used: Set<String>, generate: () -> String = Id::uuid): String {
    repeat(100) { val id = generate(); if (id.isNotBlank() && id !in used) return id }
    error("Не удалось выдать уникальный идентификатор")
}

internal fun Plan.taskSessionId(taskId: String): String = milestones.first { it.id == taskId }
    .attempts.firstOrNull()?.sessionId ?: "plan-$id-stage-$taskId"

internal fun MessageTrigger.normalized(now: Long): MessageTrigger {
    fun deadlineAfter(duration: Long): Long {
        require(duration >= 0 && now >= 0 && duration <= Long.MAX_VALUE - now) { "Недопустимый интервал ожидания" }
        return now + duration
    }
    require(afterMillis == null || (kind == MessageTriggerKind.AT_TIME && at == null)) { "Укажите at или afterMillis, не оба" }
    require(timeoutMillis == null || (kind == MessageTriggerKind.EVENT && deadline == null)) { "Укажите deadline или timeoutMillis, не оба" }
    return copy(at = afterMillis?.let(::deadlineAfter) ?: at, deadline = timeoutMillis?.let(::deadlineAfter) ?: deadline,
        afterMillis = null, timeoutMillis = null)
}

internal fun MessageTrigger.validate(plan: Plan, questionIds: Set<String>) {
    when (kind) {
        MessageTriggerKind.AT_TIME -> require(at != null && at >= 0 && event == null && deadline == null &&
            taskId == null && questionId == null && attemptId == null && turnIndex == null) { "Для времени укажите только at (Unix milliseconds)" }
        MessageTriggerKind.EVENT -> {
            require(at == null && event != null && (deadline == null || deadline >= 0)) { "Укажите событие и необязательный deadline" }
            when (event) {
                MessageEventKind.RUN_COMPLETED -> require(taskId == null && questionId == null) { "Завершение относится ко всему запуску" }
                MessageEventKind.QUESTION_ANSWERED -> require(questionId in questionIds && taskId == null) { "Неизвестный вопрос" }
                MessageEventKind.INTERVENTION_REQUIRED -> require(questionId == null && (taskId == null || plan.selectedMilestones.any { it.id == taskId })) { "Неизвестная задача" }
                else -> require(questionId == null && plan.selectedMilestones.any { it.id == taskId }) { "Неизвестная задача события" }
            }
            if (attemptId != null) require(plan.milestones.firstOrNull { it.id == taskId }?.attempts?.any { it.id == attemptId } == true) { "Попытка не относится к задаче" }
            if (turnIndex != null) require(attemptId != null && turnIndex >= 0) { "Номер хода требует ID попытки" }
            if (event in setOf(MessageEventKind.RUN_COMPLETED, MessageEventKind.QUESTION_ANSWERED, MessageEventKind.TASK_SUCCEEDED))
                require(attemptId == null && turnIndex == null) { "Это событие не относится к отдельному ходу" }
        }
    }
}

/** Executed under the PlanningStore lock: command receipts and IDs commit before any delivery. */
internal fun Plan.applyScheduleCommands(
    commands: List<ScheduleCommand>, origin: String, author: String, questions: Set<String>,
    now: Long, waitingTask: String? = null, generate: () -> String = Id::uuid,
): Plan {
    var p = copy(scheduleQuestionIds = scheduleQuestionIds + questions)
    commands.forEachIndexed { index, command ->
        val key = "$origin-schedule-$index"
        if (key in p.scheduleReceipts) return@forEachIndexed
        require(command.operation != ScheduleOperation.CREATE || command.ruleId.isBlank()) { "ID нового правила выдаёт приложение" }
        val old = p.scheduledMessages.firstOrNull { it.id == command.ruleId }
        if (command.operation != ScheduleOperation.CREATE) require(old != null && old.status in setOf(ScheduledMessageStatus.WAITING, ScheduledMessageStatus.READY, ScheduledMessageStatus.ERROR)) { "Правило не найдено или сообщение уже поставлено в очередь" }
        val next = if (command.operation == ScheduleOperation.CANCEL) old!!.copy(status = ScheduledMessageStatus.CANCELLED, reason = "Отменено") else {
            require(p.confirmedRevision != null && p.runId.isNotBlank()) { "Сначала подтвердите запуск плана" }
            require(p.phase != ExecutionPhase.COMPLETE) { "Этот запуск уже завершён" }
            val trigger = command.trigger?.normalized(now) ?: error("Нет условия запуска")
            trigger.validate(p, questions)
            require(command.text.isNotBlank()) { "Нет текста будущего сообщения" }
            require(command.targetTaskId == null || p.selectedMilestones.any { it.id == command.targetTaskId }) { "Адресат не относится к плану" }
            require(command.waitTaskId == null || (command.waitTaskId == waitingTask && p.milestones.any { it.id == waitingTask && it.attempts.isNotEmpty() })) { "Ожидание может назначить только возвращающий управление исполнитель" }
            val used = p.scheduledMessages.flatMap { listOf(it.id, it.deliveryId) }.toSet()
            val id = old?.id ?: uniqueSchedulingId(used, generate)
            val deliveryId = old?.deliveryId ?: uniqueSchedulingId(used + id, generate)
            ScheduledMessage(id, p.id, p.runId, author, trigger,
                command.targetTaskId?.let { p.taskSessionId(it) } ?: p.parentSessionId,
                command.targetTaskId, command.text.trim(), now, p.messageEvents.map { it.id }.toSet(),
                command.waitTaskId ?: old?.waitTaskId, deliveryId = deliveryId).also { next ->
                    val source = p.scheduledMessages.firstOrNull { it.deliveryId == origin && it.runId == p.runId }
                    if (source != null && next.targetTaskId == null) {
                        require(source.trigger.kind != MessageTriggerKind.AT_TIME || next.trigger.kind != MessageTriggerKind.AT_TIME) {
                            "Доставка по времени не может назначать оркестратору следующий таймер: это цикл сообщений. Обработай поручение сейчас через INSTRUCT, REFINE или DISCUSS; для ожидания укажи новое событие."
                        }
                        require(p.messageEvents.none { it.at <= now && it.matches(next) }) {
                            "Событие уже произошло. Обработай его сейчас, не создавай новое сообщение самому себе по тому же событию."
                        }
                    }
                }
        }
        p = p.copy(scheduledMessages = p.scheduledMessages.filterNot { it.id == next.id } + next,
            scheduleReceipts = p.scheduleReceipts + (key to next.id))
        if (next.waitTaskId != null && command.operation != ScheduleOperation.CANCEL) p = p.copy(milestones = p.milestones.map { task ->
            if (task.id != next.waitTaskId) task else task.copy(attempts = task.attempts.mapIndexed { i, a ->
                if (i != task.attempts.lastIndex) a else a.copy(waitingForEvent = next.id)
            })
        })
    }
    return p
}

internal fun MessageEvent.matches(rule: ScheduledMessage): Boolean {
    val t = rule.trigger
    val stateEvent = kind in setOf(MessageEventKind.TASK_SUCCEEDED, MessageEventKind.RUN_COMPLETED, MessageEventKind.QUESTION_ANSWERED)
    return planId == rule.planId && runId == rule.runId && kind == t.event && taskId == t.taskId && questionId == t.questionId &&
        (t.attemptId == null || attemptId == t.attemptId) && (t.turnIndex == null || turnIndex == t.turnIndex) &&
        (stateEvent || id !in rule.afterEventIds)
}

/** Pure transition; no model, runtime, UI, or timer is involved. */
internal fun Plan.advanceScheduledMessages(now: Long): Plan {
    val staleDeliveries = deliveries.filter { it.sourceRunId != null && it.sourceRunId != runId && it.state == DeliveryState.QUEUED }.map { it.id }.toSet()
    return copy(deliveries = deliveries.map { if (it.id in staleDeliveries) it.copy(state = DeliveryState.CANCELLED) else it }, scheduledMessages = scheduledMessages.map { rule ->
        if (rule.deliveryId in staleDeliveries) return@map rule.copy(status = ScheduledMessageStatus.CANCELLED, reason = "Запуск изменился до доставки")
        if (rule.status in setOf(ScheduledMessageStatus.QUEUED, ScheduledMessageStatus.CANCELLED)) return@map rule
        val missing = rule.runId != runId || rule.trigger.questionId?.let { it !in scheduleQuestionIds } == true || rule.trigger.taskId?.let { id -> selectedMilestones.none { it.id == id } } == true ||
            rule.targetTaskId?.let { id -> selectedMilestones.none { it.id == id } } == true
        if (missing) return@map rule.copy(status = ScheduledMessageStatus.CANCELLED, reason = "Задача удалена или запуск изменился")
        if (rule.status != ScheduledMessageStatus.WAITING) return@map rule
        val completedAt = messageEvents.firstOrNull { it.runId == runId && it.kind == MessageEventKind.RUN_COMPLETED }?.at
        val event = messageEvents.filter { it.matches(rule) && it.at <= now && (completedAt == null || it.at <= completedAt) && (rule.trigger.deadline == null || it.at <= rule.trigger.deadline) }
            .minWithOrNull(compareBy<MessageEvent> { it.at }.thenBy { it.id })
        if (completedAt != null && event == null && (rule.trigger.at ?: rule.trigger.deadline ?: Long.MAX_VALUE) > completedAt)
            return@map rule.copy(status = ScheduledMessageStatus.CANCELLED, reason = "Запуск завершён до срока ожидания")
        val timeDue = rule.trigger.kind == MessageTriggerKind.AT_TIME && rule.trigger.at!! <= now
        val timeout = rule.trigger.deadline?.let { it <= now && event == null } == true
        if (event != null || timeDue || timeout) {
            val reason = when { timeout -> "Истёк срок ожидания: ${rule.trigger.describe(this)}"
                event != null -> "Событие: ${event.kind.label()}"
                else -> "Наступило указанное время" }
            rule.copy(status = ScheduledMessageStatus.READY, eventId = event?.id, firedAt = event?.at ?: now, timeout = timeout,
                reason = reason, payload = "$reason\n${rule.text}" + (event?.text?.takeIf { it.isNotBlank() }?.let { "\nРезультат события:\n$it" } ?: "") +
                    if (timeout) "\nОпредели дальнейшее действие. Не опрашивай исполнителя повторно только ради статуса." else "")
        } else if (phase == ExecutionPhase.COMPLETE) rule.copy(status = ScheduledMessageStatus.CANCELLED, reason = "Запуск завершён; условие не наступило") else rule
    })
}

internal fun MessageEventKind.label(): String = when (this) {
    MessageEventKind.RESULT_RETURNED -> "Исполнитель передал результат"
    MessageEventKind.QUESTION_RETURNED -> "Исполнитель передал вопрос"
    MessageEventKind.BLOCKED_RETURNED -> "Исполнитель сообщил о блокировке"
    MessageEventKind.WAIT_REQUESTED -> "Исполнитель запросил ожидание"
    MessageEventKind.TASK_SUCCEEDED -> "Этап завершён и проверен"
    MessageEventKind.RUN_COMPLETED -> "Запуск завершён"
    MessageEventKind.INTERVENTION_REQUIRED -> "Нужно вмешательство"
    MessageEventKind.QUESTION_ANSWERED -> "Получен полный ответ"
}
internal fun MessageTrigger.describe(plan: Plan): String = when (kind) {
    MessageTriggerKind.AT_TIME -> "По времени: ${scheduleTime(at!!)}"
    MessageTriggerKind.EVENT -> event!!.label() + taskId?.let { id -> " · ${plan.milestones.firstOrNull { it.id == id }?.stageLabel() ?: id}" }.orEmpty() +
        questionId?.let { " · вопрос $it" }.orEmpty() + deadline?.let { " · до ${scheduleTime(it)}" }.orEmpty()
}
internal fun scheduleTime(at: Long): String {
    val zone = TimeZone.currentSystemDefault()
    return "${kotlin.time.Instant.fromEpochMilliseconds(at).toLocalDateTime(zone)} (${zone.id})"
}

fun Plan.eventWaitLabel(taskId: String): String {
    val ruleId = milestones.firstOrNull { it.id == taskId }?.attempts?.lastOrNull()?.waitingForEvent ?: return ""
    val rule = scheduledMessages.firstOrNull { it.id == ruleId } ?: return "Ожидание требует уточнения"
    return when (rule.status) {
        ScheduledMessageStatus.WAITING -> "Ожидается: ${rule.trigger.describe(this)}"
        ScheduledMessageStatus.READY -> "Условие наступило; сообщение ждёт доставки"
        ScheduledMessageStatus.QUEUED -> "Ожидается решение оркестратора"
        ScheduledMessageStatus.CANCELLED -> "Ожидание отменено; нужно новое поручение"
        ScheduledMessageStatus.ERROR -> "Не удалось доставить сообщение: ${rule.reason}"
    }
}
internal fun ScheduledMessageStatus.label(): String = when (this) {
    ScheduledMessageStatus.WAITING -> "Ожидает условия"
    ScheduledMessageStatus.READY -> "Готово к доставке"
    ScheduledMessageStatus.QUEUED -> "Поставлено в очередь"
    ScheduledMessageStatus.CANCELLED -> "Отменено"
    ScheduledMessageStatus.ERROR -> "Ошибка доставки"
}
