package io.aequicor.magicpaper.domain

import io.aequicor.magicpaper.data.planning.PlanningStore
import io.aequicor.magicpaper.data.planning.command
import io.aequicor.magicpaper.logging.AppLog
import io.aequicor.magicpaper.util.Id
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class ScheduleConflictCode { STALE_RUN, ALREADY_DELIVERED }
class ScheduleConflict(val code: ScheduleConflictCode, val runId: String, val ruleId: String?, message: String) : IllegalArgumentException(message)

/** Application lifetime, independent of session/model turns. The inbox is the durable delivery boundary. */
class MessageScheduler(
    private val store: PlanningStore,
    private val scope: CoroutineScope,
    private val dispatch: suspend (Plan, ScheduledMessage) -> Boolean,
    private val onFailure: (String) -> Unit,
    private val now: () -> Long = Id::now,
    private val beforeTick: suspend () -> Unit = {},
    private val hasReceipt: suspend (Plan, ScheduledMessage) -> Boolean = { _, _ -> false },
) {
    private val lock = Mutex()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var runner: Job? = null

    suspend fun pauseForReset() {
        runner?.cancelAndJoin()
        runner = null
    }

    fun bootstrap() {
        if (runner != null) return
        runner = scope.launch {
            launch { store.plans.collect { wake.trySend(Unit) } }
            while (isActive) {
                try { tick() } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    reportFailure(e, "tick.failed")
                }
                val deadline = store.plans.value.flatMap { it.scheduledMessages }.filter { it.status == ScheduledMessageStatus.WAITING }
                    .mapNotNull { it.trigger.at ?: it.trigger.deadline }.filter { it > now() }.minOrNull()
                // Bounded clock recheck also catches wall-clock jumps and waking from machine sleep.
                val delay = deadline?.let { (it - now()).coerceIn(1, 60_000) } ?: 60_000
                withTimeoutOrNull(delay) { wake.receive() }
            }
        }
    }

    suspend fun apply(planId: String, commands: List<ScheduleCommand>, origin: String, author: String,
        questionIds: Set<String>, waitingTask: String? = null, expectedRunId: String? = null): Plan = lock.withLock {
        val p = store.planFor(planId) ?: error("План не найден")
        if (expectedRunId != null && expectedRunId != p.runId)
            throw ScheduleConflict(ScheduleConflictCode.STALE_RUN, p.runId, null, "Запуск изменился; команда не применена")
        if (commands.indices.all { "$origin-schedule-$it" in p.scheduleReceipts }) return@withLock p
        val problem = deliveryProblem(p, commands, origin)
        if (problem != null) throw ScheduleConflict(ScheduleConflictCode.ALREADY_DELIVERED, p.runId,
            commands.firstOrNull { it.operation != ScheduleOperation.CREATE }?.ruleId, problem)
        store.command(planId, PlanningMachine.Intent.Schedule(p.runId, commands, origin, author, questionIds, waitingTask,
            PlanningMachine.Stamp(Id.new(), now())))
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
            val admission = store.currentAdmission(id) ?: continue
            var p = store.planFor(id) ?: continue
            if (p.advanceScheduledMessages(now()) != p) p = store.command(id, PlanningMachine.Fact.ScheduleAdvanced(admission, PlanningMachine.Stamp(Id.new(), now())))
            if (p.intent != ExecutionIntent.RUN) continue
            for (rule in p.scheduledMessages.filter { it.status == ScheduledMessageStatus.READY }) {
                val latest = store.planFor(id) ?: break
                if (latest.intent != ExecutionIntent.RUN || store.currentAdmission(id) != admission) break
                try {
                    // The dispatcher reconciles a durable inbox receipt and finishes any missing
                    // projection before returning. Reusing this delivery ID cannot enqueue it twice.
                    if (dispatch(latest, rule)) store.command(id,
                        PlanningMachine.Fact.ScheduleDelivered(admission, rule.id, PlanningMachine.Stamp(Id.new(), now())))
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    if (store.failure.value != null) throw e
                    val committed = hasReceipt(store.planFor(id) ?: throw e, rule)
                    store.command(id, PlanningMachine.Fact.ScheduleFailed(admission, rule.id, committed,
                        PlanningMachine.Stamp(Id.new(), now())))
                    reportFailure(e, "delivery.failed", id, rule.id)
                }
            }
        }
    }

    private fun reportFailure(cause: Exception, event: String, planId: String? = null, ruleId: String? = null) {
        AppLog.error("planning.scheduler", event, fields = buildMap {
            put("causeType", cause::class.simpleName ?: "Exception")
            planId?.let { put("planId", it) }
            ruleId?.let { put("ruleId", it) }
        })
        onFailure("Не удалось доставить запланированное сообщение. Проверьте состояние плана и повторите действие.")
    }
}
