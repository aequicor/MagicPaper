package io.aequicor.magicpaper.domain

import io.aequicor.magicpaper.data.storage.EventJournal
import io.aequicor.magicpaper.data.storage.JournalRevision
import io.aequicor.magicpaper.data.storage.JournalSnapshot
import io.aequicor.magicpaper.data.storage.MachineTransitionLog
import io.aequicor.magicpaper.logging.AppLog
import io.aequicor.magicpaper.util.Id
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Journal is the authority; the old snapshot is read once and retained only for migration/reset. */
class DefaultRuntimeQuestionnaireService(
    private val journal: EventJournal,
    namespace: String,
    private val legacyStore: RuntimeQuestionnaireStore? = null,
    private val maxPending: Int = 64,
) : RuntimeQuestionnaireService {
    init { require(namespace.isNotBlank()); require(maxPending > 0) }
    // A reversible encoding avoids collisions and preserves arbitrary native legacy directory IDs.
    private val stream = "questionnaire:" + namespace.encodeToByteArray().joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
    private val lock = Mutex()
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private var machine = QuestionnaireMachine.initial(maxPending)
    private var revision: JournalRevision? = null
    private val pending = mutableMapOf<String, CompletableDeferred<List<PlanningAnswer>>>()
    private val liveRequests = mutableMapOf<String, UserInteractionRequest>()
    private val answers = mutableMapOf<String, List<PlanningAnswer>>()
    private val requestState = MutableStateFlow<List<UserInteractionRequest>>(emptyList())
    override val requests = requestState.asStateFlow()
    private val historyState = MutableStateFlow<List<RuntimeQuestionnaireRecord>>(emptyList())
    override val history = historyState.asStateFlow()
    private val persistenceState = MutableStateFlow(QuestionnairePersistence.READY)
    override val persistence = persistenceState.asStateFlow()

    @Serializable private data class Entry(val id: String, val input: QuestionnaireMachine.Input)

    override suspend fun start() = lock.withLock { startLocked() }

    private suspend fun startLocked() {
        if (revision != null) return
        try {
            val snapshot = journal.snapshot(stream)
            machine = replay(snapshot)
            revision = snapshot.revision
            if (!machine.initialized) {
                val legacy = legacyStore?.load().orEmpty().map(::redactQuestionnaire)
                commit(QuestionnaireMachine.Fact.Initialized(legacy))
            }
            commit(QuestionnaireMachine.Fact.Restored)
            publish()
        } catch (failure: Exception) {
            markUnknown()
            report("restore", failure)
            throw storageFailure(failure)
        }
    }

    /** Replays data only. No validation, transport callback, waiter completion or legacy write. */
    private fun replay(snapshot: JournalSnapshot): QuestionnaireMachine.State {
        var restored = QuestionnaireMachine.initial(maxPending)
        snapshot.records.forEach { record ->
            check(record.operation == "questionnaire.input.v1") { "Неизвестная запись журнала опросников" }
            val input = json.decodeFromString<Entry>(record.detail).input
            val before = restored
            val transition = QuestionnaireMachine.reduce(before, input)
            check(transition.effects.none { it is QuestionnaireMachine.Effect.Reject }) { "Повреждён журнал опросников" }
            MachineTransitionLog.replay(QuestionnaireMachine.id, QuestionnaireMachine.space, before, input, transition.state, transition.effects)
            restored = transition.state
        }
        return restored
    }

    override suspend fun recover() = lock.withLock {
        try {
            val snapshot = journal.snapshot(stream)
            val restored = replay(snapshot)
            // Stop only local awaiters. Recovery never replays completion/delivery effects.
            pending.values.forEach { it.cancel(CancellationException("Опросники восстановлены; подключите запрос повторно")) }
            pending.clear(); liveRequests.clear(); answers.clear()
            machine = restored
            revision = snapshot.revision
            if (!machine.initialized) commit(QuestionnaireMachine.Fact.Initialized(legacyStore?.load().orEmpty().map(::redactQuestionnaire)))
            commit(QuestionnaireMachine.Fact.Restored)
            publish()
        } catch (failure: Exception) {
            markUnknown(); report("recover", failure); throw storageFailure(failure)
        }
    }

    override suspend fun ask(request: UserInteractionRequest): List<PlanningAnswer> {
        val result = CompletableDeferred<List<PlanningAnswer>>()
        lock.withLock {
            startLocked()
            require(request.id !in pending) { "Запрос уже существует" }
            pending[request.id] = result
            liveRequests[request.id] = request
            try {
                interpret(commit(QuestionnaireMachine.Intent.Attach(redactQuestionnaire(RuntimeQuestionnaireRecord(request)).request)))
                publish()
            } catch (failure: Exception) {
                pending.remove(request.id); liveRequests.remove(request.id); publish()
                throw failure
            }
        }
        return try { result.await() } finally {
            withContext(NonCancellable) { lock.withLock {
                // A recovered request may already have a replacement waiter with the same ID.
                if (pending[request.id] !== result) return@withLock
                pending.remove(request.id); liveRequests.remove(request.id)
                val validationToken = machine.validation[request.id]
                if (!machine.persistenceUnknown && (request.id in machine.attached || request.id in machine.validation)) {
                    try { commit(QuestionnaireMachine.Intent.Detach(request.id)) }
                    catch (failure: Exception) {
                        // Preserve the primary cancellation; expose cleanup failure through recovery state.
                        markUnknown(); report("detach", failure)
                    }
                }
                validationToken?.let(answers::remove)
                publish()
            } }
        }
    }

    override suspend fun respond(id: String, answers: List<PlanningAnswer>) = lock.withLock {
        startLocked()
        check(id in pending && machine.records[id]?.status == RuntimeQuestionnaireStatus.OPEN) { "Обращение уже закрыто" }
        val previous = machine.validation[id]
        val token = previous ?: Id.new()
        check(previous == null || this.answers[token] == answers) { "Дождитесь восстановления предыдущего ответа" }
        this.answers[token] = answers
        try {
            if (previous == null) interpret(commit(QuestionnaireMachine.Intent.Submit(id, token)))
            else interpret(listOf(QuestionnaireMachine.Effect.Validate(id, token)))
        } finally {
            // Retain only a validation whose commit still needs reconciliation/retry.
            if (machine.validation[id] != token) this.answers.remove(token)
            publish()
        }
    }

    private suspend fun interpret(effects: List<QuestionnaireMachine.Effect>) {
        effects.forEach { effect -> when (effect) {
            is QuestionnaireMachine.Effect.Validate -> {
                val request = machine.records.getValue(effect.id).request
                val answer = answers.getValue(effect.token)
                try { validateInteractionAnswers(request.questions, answer) }
                catch (invalid: IllegalArgumentException) {
                    commit(QuestionnaireMachine.Fact.AnswersInvalid(effect.id, effect.token))
                    throw invalid
                }
                val safe = redactQuestionnaire(RuntimeQuestionnaireRecord(request, answers = answer))
                interpret(commit(QuestionnaireMachine.Fact.AnswersValidated(effect.id, effect.token, safe.answers, safe.answersRedacted)))
            }
            is QuestionnaireMachine.Effect.Complete -> {
                val answer = effect.token?.let(answers::get) ?: machine.records.getValue(effect.id).answers
                check(pending.getValue(effect.id).complete(answer)) { "Ответ уже отправлен" }
            }
            is QuestionnaireMachine.Effect.Cancel -> pending[effect.id]?.cancel(CancellationException("Сессия остановлена"))
            is QuestionnaireMachine.Effect.Reject -> error(effect.reason)
        } }
    }

    override suspend fun beginDelivery(id: String): String = lock.withLock {
        startLocked()
        val attempt = Id.new()
        commit(QuestionnaireMachine.Intent.BeginDelivery(id, attempt))
        publish()
        attempt
    }

    override suspend fun finishDelivery(id: String, attemptId: String, outcome: QuestionnaireDeliveryOutcome) = lock.withLock {
        startLocked()
        commit(QuestionnaireMachine.Fact.DeliveryObserved(id, attemptId, outcome == QuestionnaireDeliveryOutcome.CONFIRMED))
        publish()
    }

    override suspend fun revoke(sessionId: String, generation: Long?) = lock.withLock {
        startLocked()
        interpret(commit(QuestionnaireMachine.Intent.Revoke(sessionId, generation)))
        publish()
    }

    override suspend fun clearForReset() = lock.withLock {
        startLocked()
        check(pending.isEmpty()) { "Дождитесь закрытия опросников" }
        // Empty the migration source before global reset can drop our import marker.
        // A failed legacy clear leaves the journal and all evidence intact.
        try { legacyStore?.save(emptyList()) }
        catch (failure: Exception) { report("legacy_reset", failure); throw storageFailure(failure) }
        commit(QuestionnaireMachine.Intent.Reset)
        answers.clear(); liveRequests.clear(); publish()
        revision = null
    }

    /** Append-before-effects with exact readback after an ambiguous store failure. */
    private suspend fun commit(input: QuestionnaireMachine.Input): List<QuestionnaireMachine.Effect> {
        val before = machine
        val next = QuestionnaireMachine.reduce(before, input)
        next.effects.filterIsInstance<QuestionnaireMachine.Effect.Reject>().firstOrNull()?.let {
            MachineTransitionLog.append(QuestionnaireMachine.id, QuestionnaireMachine.space, before, input, next.state, next.effects)
            throw IllegalArgumentException(it.reason)
        }
        val expected = checkNotNull(revision)
        val entry = Entry(Id.new(), input)
        val detail = json.encodeToString(Entry.serializer(), entry)
        try {
            val record = journal.append(expected, "questionnaire.input.v1", Id.now(), detail)
            if (record == null) {
                markUnknown()
                error("Журнал опросников изменён другим владельцем; требуется восстановление")
            }
            revision = expected.copy(seq = record.seq)
        } catch (failure: Exception) {
            val observed = try { withContext(NonCancellable) { journal.snapshot(stream) } }
            catch (readFailure: Exception) {
                markUnknown(); report("append_readback", readFailure); report("append_unknown", failure)
                failure.addSuppressed(readFailure)
                throw storageFailure(failure)
            }
            val committed = observed.records.lastOrNull()?.takeIf { it.detail == detail && it.operation == "questionnaire.input.v1" }
            if (committed != null && observed.revision.resetEpoch == expected.resetEpoch) {
                revision = observed.revision
                // The exact event survived the failed write. No second append is needed.
                AppLog.info("questionnaire", "append_reconciled", mapOf("operation" to entry.id))
            } else {
                if (observed.revision != expected) markUnknown()
                report(if (machine.persistenceUnknown) "append_unknown" else "append_failed", failure)
                throw storageFailure(failure)
            }
            // Cancellation still records durable progress, but must never execute its effects.
            if (failure is CancellationException) {
                MachineTransitionLog.append(QuestionnaireMachine.id, QuestionnaireMachine.space, before, input, next.state, next.effects)
                machine = next.state
                // Do not leave an invisible live waiter after a committed answer was interrupted.
                // Its later explicit reattachment can read the answer, without replaying delivery.
                next.effects.filterIsInstance<QuestionnaireMachine.Effect.Complete>().forEach { pending[it.id]?.cancel(failure) }
                publish()
                throw failure
            }
        }
        MachineTransitionLog.append(QuestionnaireMachine.id, QuestionnaireMachine.space, before, input, next.state, next.effects)
        machine = next.state
        publish()
        return next.effects
    }

    private fun markUnknown() {
        val before = machine
        val next = QuestionnaireMachine.reduce(before, QuestionnaireMachine.Fact.PersistenceUnknown)
        MachineTransitionLog.append(QuestionnaireMachine.id, QuestionnaireMachine.space, before, QuestionnaireMachine.Fact.PersistenceUnknown, next.state, next.effects)
        machine = next.state
        publish()
    }
    private fun publish() {
        historyState.value = machine.records.values.toList()
        persistenceState.value = if (machine.persistenceUnknown) QuestionnairePersistence.UNKNOWN else QuestionnairePersistence.READY
        requestState.value = machine.attached.mapNotNull { id -> liveRequests[id]?.copy(
            initialAnswers = machine.records[id]?.answers.orEmpty(), outcomeUnknown = machine.persistenceUnknown,
            submitting = false, error = if (machine.persistenceUnknown) "Не удалось подтвердить сохранение ответа. Перезапустите приложение для восстановления." else null) }
    }
    private fun storageFailure(failure: Exception): Exception = when (failure) {
        is CancellationException, is QuestionnairePersistenceException -> failure
        else -> QuestionnairePersistenceException(failure)
    }
    private fun report(operation: String, failure: Throwable) {
        // Exception messages may contain storage payloads. Diagnostics include only the type.
        AppLog.error("questionnaire", "operation_failed", mapOf("operation" to operation, "cause" to failure::class.simpleName.orEmpty(),
            "outcome" to if (machine.persistenceUnknown) "unknown" else "unchanged"))
    }
}

/** One writer per namespace for the entire application's lifetime, including native client clones. */
class DefaultRuntimeQuestionnaireFactory(private val journal: EventJournal) : RuntimeQuestionnaireFactory {
    private val instances = MutableStateFlow<Map<String, RuntimeQuestionnaireService>>(emptyMap())
    override suspend fun clearForReset() {
        instances.value.values.forEach { it.clearForReset() }
    }
    override fun create(namespace: String, legacyStore: RuntimeQuestionnaireStore?): RuntimeQuestionnaireService {
        while (true) {
            val current = instances.value
            current[namespace]?.let { return it }
            val created = DefaultRuntimeQuestionnaireService(journal, namespace, legacyStore)
            if (instances.compareAndSet(current, current + (namespace to created))) return created
        }
    }
}
