package io.aequicor.magicpaper.backend

import io.aequicor.magicpaper.machine.Machine
import io.aequicor.magicpaper.machine.MachineId
import io.aequicor.magicpaper.machine.Step
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable data class NativeRunRef(val sessionId: String, val requestId: String)
@Serializable data class NativeAttemptRef(val run: NativeRunRef, val ordinal: Int)
@Serializable data class NativeProcessIdentity(val receiptId: String, val pid: Long, val startedAt: Long)
@Serializable enum class NativeOutcome { NOT_DISPATCHED, UNKNOWN, SUCCEEDED, FAILED }
@Serializable enum class NativeTermination { NOT_STARTED, LIVE, STOPPED, UNKNOWN }
@Serializable enum class NativeDelivery { PI_STDIN, CODEX_THREAD, CODEX_TURN, PROVIDER_STDIN, CLAUDE_STDIN }
@Serializable data class NativeRecoveryAcknowledgement(val id: String, val predecessor: NativeAttemptRef, val parentDecisionId: String)
/** Proves that a recorded run admitted no task launch; it makes no claim about other resource owners. */
@Serializable data class NativeNoDispatchProof(val run: NativeRunRef, val proofId: String, val journalGeneration: String)
@Serializable data class NativeNoDispatchAcknowledgement(val id: String, val proof: NativeNoDispatchProof, val parentDecisionId: String)
data class NativeNoDispatchItem(val proof: NativeNoDispatchProof, val acknowledgement: NativeNoDispatchAcknowledgement?)
data class NativeRecoveryConsumption(val acknowledgementId: String, val run: NativeRunRef)
data class NativeRecoveryItem(val attempt: NativeAttemptRef, val outcome: NativeOutcome, val termination: NativeTermination,
    val acknowledgement: NativeRecoveryAcknowledgement?)
data class NativeRecoverySummary(val items: List<NativeRecoveryItem>, val persistenceUnknown: Boolean,
    val noDispatch: List<NativeNoDispatchItem> = emptyList(), val consumptions: List<NativeRecoveryConsumption> = emptyList())
class NativeRecoveryRequired(val recovery: NativeRecoverySummary) : IllegalStateException(
    "Исход предыдущего запуска не подтверждён. Проверьте сохранённый результат перед новым запросом.")

/** Opaque revision includes the host's stream/reset fence; the backend never interprets it. */
data class NativeJournalRevision(val generation: String, val position: Long)
@Serializable data class NativeJournalEntry(val id: String, val input: NativeLifecycleMachine.Input)
data class NativeJournalSnapshot(val revision: NativeJournalRevision, val entries: List<NativeJournalEntry>, val positions: List<Long>)
interface NativeLifecycleJournal {
    suspend fun snapshot(): NativeJournalSnapshot
    /** Null is a concurrent change/reset. No native effect may follow it. */
    suspend fun append(expected: NativeJournalRevision, entry: NativeJournalEntry): NativeJournalRevision?
}

/** Private-to-the-owner observations; only a factory-created native interpreter receives this port. */
interface NativeAttemptEvents {
    suspend fun admitLaunch(run: NativeRunRef): NativeAttemptRef
    suspend fun attached(attempt: NativeAttemptRef, process: NativeProcessIdentity)
    suspend fun deliver(attempt: NativeAttemptRef, stage: NativeDelivery)
    suspend fun accepted(attempt: NativeAttemptRef, nativeThreadId: String?, nativeTurnId: String?)
    suspend fun terminal(attempt: NativeAttemptRef, outcome: NativeOutcome)
    suspend fun stopping(attempt: NativeAttemptRef)
    suspend fun stopped(attempt: NativeAttemptRef)
    suspend fun unavailable(attempt: NativeAttemptRef)
}

/** Factory-created lifecycle controller for model-only native resources in the same backend group. */
interface NativeExecutionLifecycle : NativeAttemptEvents {
    suspend fun begin(run: NativeRunRef, acknowledgement: NativeRecoveryAcknowledgement? = null,
        noDispatchAcknowledgement: NativeNoDispatchAcknowledgement? = null)
    suspend fun finished(run: NativeRunRef)
    suspend fun cancel(run: NativeRunRef)
    suspend fun closing()
    suspend fun closed()
    suspend fun reload()
    suspend fun inspect(sessionId: String? = null): NativeRecoverySummary
}

/** Scoped by a journaled facade, never installed as a global registry or recreated by the adapter. */
class NativeAttemptContext(val run: NativeRunRef, val events: NativeAttemptEvents) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<NativeAttemptContext>
}

/** One owner of admission, external outcome and cleanup evidence. Resource handles are not state. */
object NativeLifecycleMachine : Machine<NativeLifecycleMachine.State, NativeLifecycleMachine.Input, NativeLifecycleMachine.Effect> {
    override val id = MachineId("native-lifecycle")
    override val space get() = NativeLifecycleSpace
    /** Bridge to the owner's own reducer: [Transition] and [reduce] keep every call site. */
    override fun step(state: State, input: Input) = reduce(state, input).let { Step(it.state, it.effects) }

    @ConsistentCopyVisibility
    data class State internal constructor(val runs: Map<NativeRunRef, Run> = emptyMap(), val closing: Boolean = false,
        val closed: Boolean = false, val persistenceUnknown: Boolean = false)
    @ConsistentCopyVisibility
    data class Run internal constructor(val ref: NativeRunRef, val active: Boolean = true,
        val attempts: List<Attempt> = emptyList(), val recoveryAcknowledgement: NativeRecoveryAcknowledgement? = null,
        val noDispatchProof: NativeNoDispatchProof? = null, val noDispatchAcknowledgement: NativeNoDispatchAcknowledgement? = null,
        val previousNoDispatchAcknowledgement: NativeNoDispatchAcknowledgement? = null)
    @ConsistentCopyVisibility
    data class Attempt internal constructor(val ref: NativeAttemptRef, val outcome: NativeOutcome = NativeOutcome.NOT_DISPATCHED,
        val termination: NativeTermination = NativeTermination.NOT_STARTED, val process: NativeProcessIdentity? = null,
        val deliveries: Set<NativeDelivery> = emptySet(), val nativeThreadId: String? = null, val nativeTurnId: String? = null,
        val acknowledgement: NativeRecoveryAcknowledgement? = null)

    @Serializable sealed interface Input
    @Serializable sealed interface Intent : Input {
        @Serializable @SerialName("io.aequicor.magicpaper.backend.NativeLifecycleMachine.Intent.Begin") data class Begin(val run: NativeRunRef, val acknowledgement: NativeRecoveryAcknowledgement? = null,
            val noDispatchAcknowledgement: NativeNoDispatchAcknowledgement? = null) : Intent
        @Serializable @SerialName("io.aequicor.magicpaper.backend.NativeLifecycleMachine.Intent.Cancel") data class Cancel(val run: NativeRunRef) : Intent
        @Serializable @SerialName("io.aequicor.magicpaper.backend.NativeLifecycleMachine.Intent.Stop") data class Stop(val attempt: NativeAttemptRef) : Intent
        @Serializable @SerialName("io.aequicor.magicpaper.backend.NativeLifecycleMachine.Intent.Acknowledge") data class Acknowledge(val acknowledgement: NativeRecoveryAcknowledgement) : Intent
        @Serializable @SerialName("io.aequicor.magicpaper.backend.NativeLifecycleMachine.Intent.AcknowledgeNoDispatch") data class AcknowledgeNoDispatch(val acknowledgement: NativeNoDispatchAcknowledgement) : Intent
        @Serializable @SerialName("io.aequicor.magicpaper.backend.NativeLifecycleMachine.Intent.Close") data object Close : Intent
    }
    @Serializable sealed interface Fact : Input {
        @Serializable @SerialName("io.aequicor.magicpaper.backend.NativeLifecycleMachine.Fact.LaunchRequested") data class LaunchRequested(val run: NativeRunRef) : Fact
        @Serializable @SerialName("io.aequicor.magicpaper.backend.NativeLifecycleMachine.Fact.Attached") data class Attached(val attempt: NativeAttemptRef, val process: NativeProcessIdentity) : Fact
        @Serializable @SerialName("io.aequicor.magicpaper.backend.NativeLifecycleMachine.Fact.DeliveryRequested") data class DeliveryRequested(val attempt: NativeAttemptRef, val stage: NativeDelivery) : Fact
        @Serializable @SerialName("io.aequicor.magicpaper.backend.NativeLifecycleMachine.Fact.Accepted") data class Accepted(val attempt: NativeAttemptRef, val threadId: String?, val turnId: String?) : Fact
        @Serializable @SerialName("io.aequicor.magicpaper.backend.NativeLifecycleMachine.Fact.Terminal") data class Terminal(val attempt: NativeAttemptRef, val outcome: NativeOutcome) : Fact
        @Serializable @SerialName("io.aequicor.magicpaper.backend.NativeLifecycleMachine.Fact.Stopping") data class Stopping(val attempt: NativeAttemptRef) : Fact
        @Serializable @SerialName("io.aequicor.magicpaper.backend.NativeLifecycleMachine.Fact.Stopped") data class Stopped(val attempt: NativeAttemptRef) : Fact
        @Serializable @SerialName("io.aequicor.magicpaper.backend.NativeLifecycleMachine.Fact.Unavailable") data class Unavailable(val attempt: NativeAttemptRef) : Fact
        @Serializable @SerialName("io.aequicor.magicpaper.backend.NativeLifecycleMachine.Fact.RunFinished") data class RunFinished(val run: NativeRunRef) : Fact
        @Serializable @SerialName("io.aequicor.magicpaper.backend.NativeLifecycleMachine.Fact.NoDispatchConfirmed") data class NoDispatchConfirmed(val proof: NativeNoDispatchProof) : Fact
        @Serializable @SerialName("io.aequicor.magicpaper.backend.NativeLifecycleMachine.Fact.NeighbourMissing") data class NeighbourMissing(val run: NativeRunRef, val key: String) : Fact
        @Serializable @SerialName("io.aequicor.magicpaper.backend.NativeLifecycleMachine.Fact.Closed") data object Closed : Fact
        @Serializable @SerialName("io.aequicor.magicpaper.backend.NativeLifecycleMachine.Fact.Restored") data object Restored : Fact
        @Serializable @SerialName("io.aequicor.magicpaper.backend.NativeLifecycleMachine.Fact.PersistenceUnknown") data object PersistenceUnknown : Fact
    }
    sealed interface Effect {
        data class Execute(val run: NativeRunRef) : Effect
        data class Launch(val attempt: NativeAttemptRef) : Effect
        data class Deliver(val attempt: NativeAttemptRef, val stage: NativeDelivery) : Effect
        data class Stop(val attempt: NativeAttemptRef) : Effect
        data class Publish(val run: NativeRunRef) : Effect
        data class Reject(val reason: String) : Effect
    }
    data class Transition(val state: State, val effects: List<Effect> = emptyList())

    fun initial() = State()
    fun reduce(state: State, input: Input): Transition {
        fun reject(reason: String) = Transition(state, listOf(Effect.Reject(reason)))
        fun accept(next: State, vararg effects: Effect) = Transition(next, effects.toList())
        fun update(ref: NativeAttemptRef, change: (Attempt) -> Attempt, vararg effects: Effect): Transition {
            val run = state.runs[ref.run] ?: return reject("Запуск не найден")
            val attempt = run.attempts.getOrNull(ref.ordinal) ?: return reject("Попытка не найдена")
            if (attempt.ref != ref) return reject("Попытка устарела")
            return accept(state.copy(runs = state.runs + (ref.run to run.copy(attempts = run.attempts.map {
                if (it.ref == ref) change(it) else it
            }))), *effects)
        }
        fun attempt(ref: NativeAttemptRef) = state.runs[ref.run]?.attempts?.getOrNull(ref.ordinal)?.takeIf { it.ref == ref }
        if (input == Fact.PersistenceUnknown) return accept(state.copy(persistenceUnknown = true))
        if (state.persistenceUnknown) return reject("Состояние журнала запусков не подтверждено")
        return when (input) {
            is Intent.Begin -> {
                val sessionRuns = state.runs.values.filter { it.ref.sessionId == input.run.sessionId }
                val attempts = sessionRuns.flatMap { it.attempts }
                val unresolved = attempts.filter { it.outcome == NativeOutcome.UNKNOWN || it.termination in setOf(NativeTermination.LIVE, NativeTermination.UNKNOWN) }
                val consumed = state.runs.values.mapNotNull { it.recoveryAcknowledgement?.id }.toSet()
                val pending = attempts.filter { it.acknowledgement != null && it.acknowledgement.id !in consumed }
                val consumedNoDispatch = state.runs.values.mapNotNull { it.previousNoDispatchAcknowledgement?.id }.toSet()
                val undispatched = sessionRuns.filter { it.noDispatchProof != null }
                val pendingNoDispatch = undispatched.mapNotNull { it.noDispatchAcknowledgement }.filter { it.id !in consumedNoDispatch }
                when {
                    input.acknowledgement != null && input.noDispatchAcknowledgement != null -> reject("Восстановление содержит два разных решения")
                    undispatched.any { it.noDispatchAcknowledgement == null } -> reject("Запуск не был отправлен; требуется явное решение продолжить")
                    pendingNoDispatch.isNotEmpty() && input.noDispatchAcknowledgement != pendingNoDispatch.last() -> reject("Нет решения продолжить неотправленный запуск")
                    input.noDispatchAcknowledgement != null && input.noDispatchAcknowledgement !in pendingNoDispatch -> reject("Решение о неотправленном запуске уже использовано или устарело")
                    unresolved.any { it.termination != NativeTermination.STOPPED || it.acknowledgement == null } -> reject("Предыдущий исход не подтверждён")
                    pending.isNotEmpty() && input.acknowledgement != pending.last().acknowledgement -> reject("Нет явного решения продолжить после неизвестного исхода")
                    input.acknowledgement != null && pending.none { it.acknowledgement == input.acknowledgement } -> reject("Решение о восстановлении уже использовано или устарело")
                    state.closing || state.closed -> reject("Движок закрыт")
                    input.run in state.runs -> reject("Этот запрос уже принят; повторная отправка запрещена")
                    state.runs.values.any { it.ref.sessionId == input.run.sessionId && it.active } -> reject("Сессия уже выполняется")
                    input.run.sessionId.isBlank() || input.run.requestId.isBlank() -> reject("Идентификатор запуска пуст")
                    else -> accept(state.copy(runs = state.runs + (input.run to Run(input.run, recoveryAcknowledgement = input.acknowledgement,
                        previousNoDispatchAcknowledgement = input.noDispatchAcknowledgement))), Effect.Execute(input.run))
                }
            }
            is Fact.LaunchRequested -> {
                val run = state.runs[input.run]
                val previous = run?.attempts?.lastOrNull()
                when {
                    run == null || !run.active || state.closing || state.closed -> reject("Запуск больше не разрешён")
                    previous != null && (previous.termination != NativeTermination.STOPPED || previous.outcome != NativeOutcome.SUCCEEDED) -> reject("Продолжение без подтверждённой предыдущей попытки запрещено")
                    else -> {
                        val ref = NativeAttemptRef(input.run, run.attempts.size)
                        accept(state.copy(runs = state.runs + (input.run to run.copy(attempts = run.attempts + Attempt(ref)))), Effect.Launch(ref))
                    }
                }
            }
            is Fact.Attached -> {
                val old = attempt(input.attempt)
                if (old == null || old.process != null || old.termination != NativeTermination.NOT_STARTED) reject("Процесс уже привязан или попытка закрыта")
                else update(input.attempt, { it.copy(process = input.process, termination = NativeTermination.LIVE) })
            }
            is Fact.DeliveryRequested -> {
                val old = attempt(input.attempt)
                if (state.closing || state.closed || state.runs[input.attempt.run]?.active != true || old?.termination != NativeTermination.LIVE ||
                    input.stage in old.deliveries || old.outcome in setOf(NativeOutcome.SUCCEEDED, NativeOutcome.FAILED)) reject("Отправка запроса больше не разрешена")
                else update(input.attempt, { it.copy(outcome = NativeOutcome.UNKNOWN, deliveries = it.deliveries + input.stage) }, Effect.Deliver(input.attempt, input.stage))
            }
            is Fact.Accepted -> if (attempt(input.attempt)?.deliveries.isNullOrEmpty()) reject("Подтверждение не связано с отправкой")
                else update(input.attempt, { it.copy(nativeThreadId = input.threadId ?: it.nativeThreadId, nativeTurnId = input.turnId ?: it.nativeTurnId) })
            is Fact.Terminal -> if (input.outcome !in setOf(NativeOutcome.SUCCEEDED, NativeOutcome.FAILED) || (attempt(input.attempt)?.outcome != NativeOutcome.UNKNOWN || attempt(input.attempt)?.acknowledgement != null))
                reject("Недостаточно доказательств результата") else update(input.attempt, { it.copy(outcome = input.outcome) })
            is Fact.Stopping -> update(input.attempt, { if (it.termination == NativeTermination.STOPPED) it else it.copy(termination = NativeTermination.UNKNOWN) }, Effect.Stop(input.attempt))
            is Intent.Stop -> update(input.attempt, { if (it.termination == NativeTermination.STOPPED) it else it.copy(termination = NativeTermination.UNKNOWN) }, Effect.Stop(input.attempt))
            is Fact.Stopped -> update(input.attempt, { it.copy(termination = NativeTermination.STOPPED) })
            is Fact.Unavailable -> update(input.attempt, { it.copy(termination = if (it.process == null && it.deliveries.isEmpty()) NativeTermination.STOPPED else NativeTermination.UNKNOWN) })
            is Intent.Cancel -> {
                val run = state.runs[input.run] ?: return reject("Запуск не найден")
                accept(state.copy(runs = state.runs + (input.run to run.copy(active = false))), *run.attempts.filter { it.termination != NativeTermination.STOPPED }.map { Effect.Stop(it.ref) }.toTypedArray())
            }
            is Fact.RunFinished -> {
                val run = state.runs[input.run] ?: return reject("Запуск не найден")
                accept(state.copy(runs = state.runs + (input.run to run.copy(active = false))), Effect.Publish(input.run))
            }
            is Fact.NoDispatchConfirmed -> {
                val proof = input.proof
                val run = state.runs[proof.run]
                when {
                    run == null || run.active || run.attempts.isNotEmpty() -> reject("Отсутствие запуска не подтверждено")
                    state.runs.values.lastOrNull { it.ref.sessionId == proof.run.sessionId }?.ref != proof.run -> reject("Подтверждение относится к прежнему запросу")
                    proof.proofId.isBlank() || proof.journalGeneration.isBlank() -> reject("Подтверждение запуска не имеет идентичности")
                    run.noDispatchProof != null && run.noDispatchProof != proof -> reject("Подтверждение запуска изменилось")
                    state.runs.values.any { it.ref != run.ref && it.noDispatchProof?.proofId == proof.proofId } -> reject("Подтверждение уже принадлежит другому запуску")
                    else -> accept(state.copy(runs = state.runs + (run.ref to run.copy(noDispatchProof = proof))))
                }
            }
            is Fact.NeighbourMissing -> {
                val run = state.runs[input.run] ?: return reject("Запуск не найден")
                accept(state.copy(runs = state.runs + (input.run to run.copy(active = false))), Effect.Publish(input.run))
            }
            is Intent.Acknowledge -> {
                val old = attempt(input.acknowledgement.predecessor)
                if (old == null || old.termination != NativeTermination.STOPPED || old.acknowledgement != null ||
                    input.acknowledgement.parentDecisionId.isBlank() || input.acknowledgement.id.isBlank()) reject("Предыдущую попытку ещё нельзя оставить позади")
                else update(old.ref, { it.copy(acknowledgement = input.acknowledgement) })
            }
            is Intent.AcknowledgeNoDispatch -> {
                val ack = input.acknowledgement
                val run = state.runs[ack.proof.run]
                if (run == null || run.active || run.attempts.isNotEmpty() || run.noDispatchProof != ack.proof ||
                    run.noDispatchAcknowledgement != null || ack.id.isBlank() || ack.parentDecisionId.isBlank())
                    reject("Неотправленный запуск ещё нельзя оставить позади")
                else accept(state.copy(runs = state.runs + (run.ref to run.copy(noDispatchAcknowledgement = ack))))
            }
            Intent.Close -> accept(state.copy(closing = true), *state.runs.values.flatMap { it.attempts }.filter { it.termination != NativeTermination.STOPPED }.map { Effect.Stop(it.ref) }.toTypedArray())
            Fact.Closed -> if (state.runs.values.flatMap { it.attempts }.any { it.termination != NativeTermination.STOPPED }) reject("Остановка процессов не подтверждена")
                else accept(state.copy(closing = true, closed = true))
            Fact.Restored -> accept(state.copy(closing = false, closed = false, runs = state.runs.mapValues { (_, run) -> run.copy(active = false,
                attempts = run.attempts.map { attempt -> if (attempt.termination == NativeTermination.STOPPED) attempt else attempt.copy(termination = NativeTermination.UNKNOWN) }) }))
            Fact.PersistenceUnknown -> error("Handled above")
        }
    }
}
