package io.aequicor.magicpaper.domain

import io.aequicor.magicpaper.data.planning.PlanningStore
import io.aequicor.magicpaper.util.Id
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Application lifetime, independent of session/model turns. The inbox is the durable delivery boundary. */
class MessageScheduler(
    private val store: PlanningStore,
    private val scope: CoroutineScope,
    private val dispatch: suspend (Plan, ScheduledMessage) -> Boolean,
    private val now: () -> Long = Id::now,
    private val beforeTick: suspend () -> Unit = {},
    private val hasReceipt: suspend (Plan, ScheduledMessage) -> Boolean = { _, _ -> false },
) {
    private val lock = Mutex()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var runner: Job? = null

    fun bootstrap() {
        if (runner != null) return
        runner = scope.launch {
            launch { store.plans.collect { wake.trySend(Unit) } }
            while (isActive) {
                try { tick() } catch (e: CancellationException) { throw e }
                catch (_: Exception) { /* Persistence recovery belongs to the owning orchestration service. */ }
                val deadline = store.plans.value.flatMap { it.scheduledMessages }.filter { it.status == ScheduledMessageStatus.WAITING }
                    .mapNotNull { it.trigger.at ?: it.trigger.deadline }.filter { it > now() }.minOrNull()
                // Bounded clock recheck also catches wall-clock jumps and waking from machine sleep.
                val delay = deadline?.let { (it - now()).coerceIn(1, 60_000) } ?: 60_000
                withTimeoutOrNull(delay) { wake.receive() }
            }
        }
    }

    suspend fun apply(planId: String, commands: List<ScheduleCommand>, origin: String, author: String,
        questionIds: Set<String>, waitingTask: String? = null): Plan = lock.withLock {
        val p = store.planFor(planId) ?: error("План не найден")
        if (commands.indices.all { "$origin-schedule-$it" in p.scheduleReceipts }) return@withLock p
        val problem = deliveryProblem(p, commands, origin)
        require(problem == null) { problem!! }
        store.update(planId) { it.applyScheduleCommands(commands, origin, author, questionIds, now(), waitingTask) }
    }

    /** Check the same durable inbox boundary as apply, without saving rules or delivering messages. */
    suspend fun validationProblem(planId: String, commands: List<ScheduleCommand>, origin: String, author: String,
        questionIds: Set<String>, waitingTask: String? = null): String? = lock.withLock {
        val p = store.planFor(planId) ?: error("План не найден")
        deliveryProblem(p, commands, origin)?.let { return@withLock it }
        runCatching { p.applyScheduleCommands(commands, origin, author, questionIds, now(), waitingTask) }
            .exceptionOrNull()?.let { it.message ?: "Некорректное правило будущего сообщения" }
    }

    private suspend fun deliveryProblem(p: Plan, commands: List<ScheduleCommand>, origin: String): String? {
        commands.forEachIndexed { index, command ->
            if ("$origin-schedule-$index" in p.scheduleReceipts || command.operation == ScheduleOperation.CREATE) return@forEachIndexed
            p.scheduledMessages.firstOrNull { it.id == command.ruleId }?.let {
                if (hasReceipt(p, it)) return "Сообщение уже поставлено в очередь; изменять его правило нельзя"
            }
        }
        return null
    }

    suspend fun tick() = lock.withLock {
        if (store.failure.value != null) return@withLock
        beforeTick()
        for (id in store.plans.value.map { it.id }) {
            var p = store.planFor(id) ?: continue
            if (p.advanceScheduledMessages(now()) != p) p = store.update(id) { it.advanceScheduledMessages(now()) }
            if (p.intent != ExecutionIntent.RUN) continue
            for (rule in p.scheduledMessages.filter { it.status == ScheduledMessageStatus.READY }) {
                val latest = store.planFor(id) ?: break
                if (latest.intent != ExecutionIntent.RUN) break
                try {
                    if (dispatch(latest, rule)) store.update(id) { current -> current.copy(scheduledMessages = current.scheduledMessages.map {
                        if (it.id == rule.id && it.status == ScheduledMessageStatus.READY) it.copy(status = ScheduledMessageStatus.QUEUED) else it
                    }) }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    if (store.failure.value != null) throw e
                    val committed = hasReceipt(store.planFor(id) ?: throw e, rule)
                    store.update(id) { current -> current.copy(scheduledMessages = current.scheduledMessages.map {
                        if (it.id == rule.id) it.copy(status = if (committed) ScheduledMessageStatus.QUEUED else ScheduledMessageStatus.ERROR,
                            reason = if (committed) it.reason else e.message ?: "Ошибка доставки") else it
                    }) }
                }
            }
        }
    }
}

/** Runs in the same durable write as the source transition, never from a lossy StateFlow observer. */
internal fun Plan.checkpointMessageEvents(previous: Plan?, now: Long, generate: () -> String = Id::uuid): Plan {
    if (parentSessionId.isBlank() || runId.isBlank()) return this
    val events = messageEvents.toMutableList()
    fun emit(key: String, kind: MessageEventKind, text: String, taskId: String? = null, attempt: String? = null, turn: Int? = null, at: Long = now) {
        val source = "$runId:$key"
        if (events.any { it.sourceKey == source }) return
        events += MessageEvent(uniqueSchedulingId(events.map { it.id }.toSet(), generate), source, id, runId, kind, at, text, taskId,
            attemptId = attempt, turnIndex = turn)
    }
    // The runtime report is a durable handoff even if the app exits before the coordinator hook runs.
    selectedMilestones.forEach { task -> task.attempts.lastOrNull()?.takeIf { it.coordinationPending == true }?.let { attempt ->
        val reply = stageReplyOrNull(attempt.report)
        if (reply != null) emit("handoff:${attempt.id}-turn-${attempt.turnIndex}", when (reply.kind) {
            StageReplyKind.RESULT -> MessageEventKind.RESULT_RETURNED
            StageReplyKind.QUESTION -> MessageEventKind.QUESTION_RETURNED
            StageReplyKind.BLOCKED -> MessageEventKind.BLOCKED_RETURNED
            StageReplyKind.WAIT -> MessageEventKind.WAIT_REQUESTED
        }, reply.text, task.id, attempt.id, attempt.turnIndex,
            attempt.chatTurns.getOrNull(attempt.turnIndex)?.completedAt?.takeIf { it > 0 } ?: now)
    } }
    coordination.filter { it.runId == runId }.forEach { record ->
        emit("handoff:${record.id}", when (record.reply.kind) {
            StageReplyKind.RESULT -> MessageEventKind.RESULT_RETURNED
            StageReplyKind.QUESTION -> MessageEventKind.QUESTION_RETURNED
            StageReplyKind.BLOCKED -> MessageEventKind.BLOCKED_RETURNED
            StageReplyKind.WAIT -> MessageEventKind.WAIT_REQUESTED
        }, record.reply.text, record.stageId, record.attemptId, record.turnIndex, record.createdAt)
    }
    selectedMilestones.forEach { task ->
        val inherited = runHistory.any { snapshot -> snapshot.milestones.any { it.id == task.id && it.status == MilestoneStatus.DONE && it.attempts == task.attempts } }
        if (task.status == MilestoneStatus.DONE && !inherited)
            emit("success:${task.id}", MessageEventKind.TASK_SUCCEEDED, task.report, task.id, at = task.updatedAt.takeIf { it > 0 } ?: now)
        val error = task.attempts.lastOrNull()?.error
        val oldError = previous?.milestones?.firstOrNull { it.id == task.id }?.attempts?.lastOrNull()?.error
        if (error?.requiresUser == true && error != oldError)
            emit("error:${task.id}:${revision + 1}", MessageEventKind.INTERVENTION_REQUIRED, error.message, task.id,
                task.attempts.lastOrNull()?.id)
    }
    if (phase == ExecutionPhase.COMPLETE) emit("complete", MessageEventKind.RUN_COMPLETED, finalAttempt?.report.orEmpty())
    if (issue?.requiresUser == true && issue != previous?.issue)
        emit("error:${revision + 1}", MessageEventKind.INTERVENTION_REQUIRED, issue.message)
    return if (events == messageEvents) this else copy(messageEvents = events)
}
