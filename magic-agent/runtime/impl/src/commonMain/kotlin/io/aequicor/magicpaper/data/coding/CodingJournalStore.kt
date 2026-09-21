package io.aequicor.magicpaper.data.coding

import io.aequicor.magicpaper.data.storage.*
import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.logging.AppLog
import io.aequicor.magicpaper.util.Id
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One application-lived writer; legacy files are rebuildable projections of typed journal inputs. */
class CodingJournalStore(private val checkpoints: CodingCheckpointStore, private val journal: EventJournal,
    private val payloads: CodingPayloadStore, private val json: Json,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default) : CodingProjectOwner, TaskWorktreeSessionAccess {
    @Serializable private data class Envelope(val ref: CodingInputRef, val resetEpoch: Long)
    private data class Entry(var state: CodingMachine.State, var revision: JournalRevision, val records: MutableList<JournalRecord>)
    private val lock = Mutex()
    private val entries = mutableMapOf<String, Entry>()
    private var initialized = false
    private val _states = MutableStateFlow<Map<String, CodingMachine.State>>(emptyMap())
    override val states: StateFlow<Map<String, CodingMachine.State>> = _states.asStateFlow()
    private val _failures = MutableStateFlow<Map<String, String>>(emptyMap())
    override val failures: StateFlow<Map<String, String>> = _failures.asStateFlow()
    override suspend fun start(): Unit = withContext(dispatcher) { lock.withLock { initialize() } }
    override suspend fun all(): List<CodingProject> { start(); return states.value.values.filterNot { it.deleted }.mapNotNull { it.project }.sortedByDescending { it.createdAt } }
    override suspend fun sessions(projectId: String): List<CodingSession> { start(); return states.value[projectId]?.sessions?.values.orEmpty().sortedByDescending { it.createdAt } }
    override suspend fun messages(projectId: String, sessionId: String): List<CodingMessage> { start(); return states.value[projectId]?.histories?.get(sessionId).orEmpty() }
    override suspend fun orchestration(sessionId: String): OrchestrationState? { start(); return states.value.values.firstNotNullOfOrNull { it.orchestrations[sessionId] } }
    override suspend fun session(projectId: String, sessionId: String): CodingSession? = sessions(projectId).firstOrNull { it.id == sessionId }
    override suspend fun publish(projection: TaskWorktreeProjection) {
        dispatch(projection.owner.projectId, CodingMachine.Fact.WorktreeProjected(
            CodingMachine.SessionRef(projection.owner.sessionId, projection.generation), projection.task,
            CodingMachine.ChildRevision(projection.stream, projection.sequence, projection.resetEpoch, projection.sequence.toString()), projection.unknown))
    }

    override suspend fun dispatch(projectId: String, input: CodingMachine.Input): CodingMachine.Transition = withContext(dispatcher) {
        lock.withLock {
            initialize()
            val entry = entries.getOrPut(projectId) {
                val snapshot = snapshot(stream(projectId))
                check(snapshot.records.isEmpty()) { "Проект изменён другим владельцем" }
                Entry(CodingMachine.initial(), snapshot.revision, snapshot.records.toMutableList())
            }
            val frozenInput = freeze(input)
            val next = CodingMachine.reduce(entry.state, frozenInput)
            next.effects.filterIsInstance<CodingMachine.Effect.Reject>().firstOrNull()?.let { throw CodingCommandRejected(it.reason) }
            check(next.state.project?.id == projectId) { "Команда принадлежит другому проекту" }
            check(identitiesAvailable(projectId, next.state, entries)) { "Идентификатор уже принадлежит другому проекту" }
            if (next.state == entry.state && next.effects.isEmpty()) return@withLock next
            try { commit(projectId, entry, frozenInput, next.state) }
            catch (cancelled: CancellationException) {
                entry.state = CodingMachine.reduce(entry.state, CodingMachine.Fact.PersistenceUnknown).state
                publish(projectId, entry); report(projectId, "input.cancelled", cancelled)
                throw cancelled
            }
            catch (failure: Exception) {
                entry.state = CodingMachine.reduce(entry.state, CodingMachine.Fact.PersistenceUnknown).state
                publish(projectId, entry); report(projectId, "input.commit", failure); throw failure
            }
            publish(projectId, entry)
            checkpoint(projectId, entry)
            next
        }
    }
    private suspend fun initialize() {
        if (initialized) return
        val recovered = mutableMapOf<String, Entry>()
        val imports = mutableMapOf<String, CodingMachine.Fact.LegacyImported>()
        for (stream in journal.streams().filter { it.startsWith(PREFIX) }) {
            val snapshot = snapshot(stream)
            var state = CodingMachine.initial()
            for (record in snapshot.records) {
                val envelope = json.decodeFromString(Envelope.serializer(), record.detail)
                val next = CodingMachine.reduce(state, freeze(payloads.read(envelope.ref)))
                check(next.effects.none { it is CodingMachine.Effect.Reject }) { "Повреждён журнал проекта" }
                state = next.state
            }
            val id = checkNotNull(state.project).id
            check(stream(id) == stream && identitiesAvailable(id, state, recovered)) { "Неверная принадлежность проекта" }
            recovered[id] = Entry(state, snapshot.revision, snapshot.records.toMutableList())
        }
        for (legacy in checkpoints.legacyProjects(recovered.keys)) {
            val input = freeze(legacy) as CodingMachine.Fact.LegacyImported
            val id = input.project.id
            check(id !in recovered) { "Проект уже восстановлен" }
            val snapshot = snapshot(stream(id))
            check(snapshot.records.isEmpty()) { "Проект изменился во время восстановления" }
            val next = CodingMachine.reduce(CodingMachine.initial(), input)
            check(next.effects.none { it is CodingMachine.Effect.Reject } && identitiesAvailable(id, next.state, recovered)) { "Повреждено сохранённое состояние проекта" }
            recovered[id] = Entry(next.state, snapshot.revision, snapshot.records.toMutableList()); imports[id] = input
        }
        // Reserve all project/session/tombstone identities before the first migration write.
        for ((id, entry) in recovered) {
            val imported = imports[id]
            if (imported != null) commit(id, entry, imported, entry.state)
            else {
                val restored = CodingMachine.reduce(entry.state, CodingMachine.Fact.Restored).state
                if (restored != entry.state) commit(id, entry, CodingMachine.Fact.Restored, restored)
            }
        }
        entries.clear(); entries.putAll(recovered)
        _states.value = recovered.mapValues { it.value.state }; initialized = true
        for ((id, entry) in recovered) checkpoint(id, entry)
    }
    private fun freeze(input: CodingMachine.Input): CodingMachine.Input = json.decodeFromString(CodingMachine.Input.serializer(), json.encodeToString(CodingMachine.Input.serializer(), input))
    private fun identitiesAvailable(owner: String, candidate: CodingMachine.State, owners: Map<String, Entry>): Boolean {
        fun identities(state: CodingMachine.State) = state.sessions.keys + state.removedSessions + listOfNotNull(state.project?.id)
        val requested = identities(candidate).toSet() + owner
        return owners.none { (id, entry) -> id != owner &&
            (entry.state.initialized || entry.state.persistenceUnknown || entry.records.isNotEmpty()) &&
            (identities(entry.state) + id).any(requested::contains) }
    }
    private suspend fun snapshot(stream: String): JournalSnapshot {
        val snapshot = journal.snapshot(stream)
        check(snapshot.revision.stream == stream && snapshot.revision.seq >= 0 && snapshot.revision.resetEpoch >= 0) { "Неверная принадлежность журнала проекта" }
        var previous = 0L
        val inputs = mutableSetOf<String>()
        for (record in snapshot.records) {
            check(record.stream == stream && record.seq > previous && record.operation == OPERATION) { "Повреждён порядок журнала проекта" }
            val envelope = json.decodeFromString(Envelope.serializer(), record.detail)
            check(stream(envelope.ref.projectId) == stream && envelope.resetEpoch == snapshot.revision.resetEpoch && inputs.add(envelope.ref.inputId)) { "Неверное подтверждение ввода проекта" }
            previous = record.seq
        }
        if (snapshot.records.isNotEmpty()) check(previous == snapshot.revision.seq) { "Журнал проекта прочитан не полностью" }
        return snapshot
    }
    private suspend fun commit(id: String, entry: Entry, input: CodingMachine.Input, state: CodingMachine.State) = withContext(NonCancellable + dispatcher) {
        val expected = entry.revision
        val inputId = Id.new()
        val ref = payloads.save(id, inputId, input)
        check(ref.projectId == id && ref.inputId == inputId) { "Сохранён ввод другого проекта" }
        check(json.encodeToJsonElement(CodingMachine.Input.serializer(), payloads.read(ref)) ==
            json.encodeToJsonElement(CodingMachine.Input.serializer(), input)) { "Сохранён другой ввод проекта" }
        val at = Id.now()
        val encoded = json.encodeToString(Envelope.serializer(), Envelope(ref, expected.resetEpoch))
        val accepted = try {
            checkNotNull(journal.append(expected, OPERATION, at, encoded)) { "Проект изменён другим владельцем" }.also {
                check(it.stream == expected.stream && it.seq > expected.seq && it.operation == OPERATION && it.at == at && it.detail == encoded) { "Подтверждение принадлежит другой записи" }
            }
        } catch (failure: Exception) {
            val observed = try { snapshot(expected.stream) } catch (readFailure: Exception) { failure.addSuppressed(readFailure); throw failure }
            val record = observed.records.lastOrNull()?.takeIf { it.seq > expected.seq && it.operation == OPERATION && it.at == at &&
                it.detail == encoded && observed.revision.resetEpoch == expected.resetEpoch && observed.records.dropLast(1) == entry.records } ?: throw failure
            try {
                check(json.encodeToJsonElement(CodingMachine.Input.serializer(), payloads.read(ref)) ==
                    json.encodeToJsonElement(CodingMachine.Input.serializer(), input)) { "Сохранён другой ввод проекта" }
            } catch (invalid: Exception) { failure.addSuppressed(invalid); throw failure }
            if (failure is CancellationException) throw failure
            record
        }
        entry.records += accepted; entry.revision = expected.copy(seq = accepted.seq); entry.state = state
    }
    private suspend fun checkpoint(id: String, entry: Entry) {
        try { checkpoints.checkpoint(entry.state); _failures.update { it - id } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { report(id, "checkpoint", failure) }
    }
    private fun publish(id: String, entry: Entry) { _states.update { it + (id to entry.state) } }
    private fun report(id: String, operation: String, failure: Throwable) {
        AppLog.error("coding.journal", "storage.failed", mapOf("projectId" to id, "operation" to operation, "causeType" to failure::class.simpleName.orEmpty()))
        _failures.update { it + (id to "Не удалось подтвердить сохранение проекта. Проверьте хранилище и восстановите проект.") }
    }
    override suspend fun wipe(): Unit = withContext(NonCancellable + dispatcher) { lock.withLock {
        checkpoints.wipe()
        journal.streams().filter { it.startsWith(PREFIX) }.forEach { check(journal.drop(journal.snapshot(it).revision)) { "Проект изменился во время очистки" } }
        payloads.clear(); entries.clear(); _states.value = emptyMap(); _failures.value = emptyMap(); initialized = true
    } }
    companion object {
        private const val PREFIX = "coding-workflow:"
        private const val OPERATION = "coding.input.v1"
        fun stream(projectId: String) = PREFIX + projectId.encodeToByteArray().joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
    }
}

class CodingCommandRejected(message: String) : IllegalStateException(message)
