package io.aequicor.magicpaper.domain

import io.aequicor.magicpaper.data.storage.*
import io.aequicor.magicpaper.logging.AppLog
import io.aequicor.magicpaper.util.Id
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The only durable writer of chat aggregates. Session files are a compatibility cache, never recovery authority. */
class ChatJournalStore(private val checkpoints: ChatCheckpointStore, private val journal: EventJournal,
    private val payloads: ChatPayloadStore, private val json: Json,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default) : ChatRepository {
    @Serializable private data class Envelope(val ref: ChatInputRef, val resetEpoch: Long)
    private data class Entry(var state: ChatMachine.State, var revision: JournalRevision, val records: MutableList<JournalRecord>)
    private val lock = Mutex()
    private val entries = mutableMapOf<String, Entry>()
    private var initialized = false
    private val _states = MutableStateFlow<Map<String, ChatMachine.State>>(emptyMap())
    val states: StateFlow<Map<String, ChatMachine.State>> = _states.asStateFlow()
    private val _failures = MutableStateFlow<Map<String, String>>(emptyMap())
    val failures: StateFlow<Map<String, String>> = _failures.asStateFlow()

    suspend fun start(): Unit = withContext(dispatcher) { lock.withLock { initialize() } }
    override suspend fun sessions(): List<ChatSession> { start(); return states.value.values.flatMap { it.sessions.values }.sortedByDescending { it.updatedAt } }
    override suspend fun session(id: String): ChatSession? { start(); return states.value.values.firstNotNullOfOrNull { it.sessions[id] } }
    fun stateFor(id: String): ChatMachine.State? = states.value[id] ?: states.value.values.firstOrNull { it.notebookId == id || id in it.sessions || id in it.removedIds }

    suspend fun dispatch(notebookId: String, input: ChatMachine.Input): ChatMachine.Transition = withContext(dispatcher) {
        lock.withLock {
            initialize()
            val entry = entries.getOrPut(notebookId) {
                val snapshot = validatedSnapshot(stream(notebookId))
                check(snapshot.records.isEmpty()) { "Чат создан другим владельцем. Восстановите историю." }
                Entry(ChatMachine.initial(), snapshot.revision, snapshot.records.toMutableList())
            }
            val next = ChatMachine.reduce(entry.state, input)
            check(next.state.notebookId == notebookId) { "Команда принадлежит другому чату" }
            next.effects.filterIsInstance<ChatMachine.Effect.Reject>().firstOrNull()?.let { throw ChatCommandRejected(it.reason) }
            if (!identitiesAvailable(notebookId, next.state, entries))
                throw ChatCommandRejected("Идентификатор вопроса уже принадлежит другому чату")
            if (next.state == entry.state && next.effects.isEmpty()) return@withLock next
            try { commit(notebookId, entry, input, next.state) }
            catch (failure: Exception) {
                entry.state = ChatMachine.reduce(entry.state, ChatMachine.Fact.PersistenceUnknown).state
                publish(notebookId, entry)
                report(notebookId, "input.commit", failure)
                throw failure
            }
            publish(notebookId, entry)
            checkpoint(notebookId, entry)
            next
        }
    }

    private suspend fun initialize() {
        if (initialized) return
        val found = mutableMapOf<String, Entry>()
        val imports = mutableMapOf<String, ChatMachine.Fact.LegacyImported>()
        for (stream in journal.streams().filter { it.startsWith(PREFIX) }) {
            val snapshot = validatedSnapshot(stream)
            var state = ChatMachine.initial()
            for (record in snapshot.records) {
                check(record.operation == OPERATION) { "Неизвестная запись чата" }
                val ref = json.decodeFromString(Envelope.serializer(), record.detail).ref
                check(stream(ref.notebookId) == stream) { "Запись принадлежит другому чату" }
                val input = payloads.read(ref)
                val next = ChatMachine.reduce(state, input)
                check(next.effects.none { it is ChatMachine.Effect.Reject }) { "Повреждён журнал чата" }
                state = next.state
            }
            check(state.initialized && stream(state.notebookId) == stream) { "Чат не инициализирован" }
            check(identitiesAvailable(state.notebookId, state, found)) { "Идентификатор вопроса принадлежит нескольким чатам" }
            found[state.notebookId] = Entry(state, snapshot.revision, snapshot.records.toMutableList())
        }
        val covered = found.values.flatMap { it.state.sessions.keys + it.state.removedIds }.toSet()
        val legacy = checkpoints.legacySessions(covered).groupBy { it.researchChatId }
        for ((id, sessions) in legacy) {
            check(id !in found) { "Сохранённый вопрос не входит в журнал своего чата" }
            val snapshot = validatedSnapshot(stream(id))
            check(snapshot.records.isEmpty()) { "Чат изменился во время восстановления" }
            val input = ChatMachine.Fact.LegacyImported(id, sessions)
            val next = ChatMachine.reduce(ChatMachine.initial(), input)
            check(next.effects.none { it is ChatMachine.Effect.Reject }) { "Повреждена сохранённая история" }
            check(identitiesAvailable(id, next.state, found)) { "Идентификатор вопроса принадлежит нескольким чатам" }
            found[id] = Entry(next.state, snapshot.revision, snapshot.records.toMutableList())
            imports[id] = input
        }
        // Validate the whole ownership set before writing restoration/import facts or publishing any state.
        for ((id, entry) in found) {
            val imported = imports[id]
            if (imported != null) commit(id, entry, imported, entry.state)
            else {
                val restored = ChatMachine.reduce(entry.state, ChatMachine.Fact.Restored).state
                if (restored != entry.state) commit(id, entry, ChatMachine.Fact.Restored, restored)
            }
        }
        entries.clear(); entries.putAll(found)
        _states.value = found.mapValues { it.value.state }
        initialized = true
        for ((id, entry) in found) checkpoint(id, entry)
    }

    private fun identitiesAvailable(ownerId: String, candidate: ChatMachine.State, owners: Map<String, Entry>): Boolean {
        val requested = ownedIdentities(candidate) + ownerId
        return owners.any { (id, entry) ->
            id != ownerId && (entry.state.initialized || entry.state.persistenceUnknown || entry.records.isNotEmpty()) &&
                (ownedIdentities(entry.state) + id).any(requested::contains)
        }.not()
    }
    private fun ownedIdentities(state: ChatMachine.State): Set<String> =
        (state.sessions.keys + state.removedIds + state.notebookId).filter { it.isNotBlank() }.toSet()

    private suspend fun validatedSnapshot(stream: String): JournalSnapshot {
        val snapshot = journal.snapshot(stream)
        check(snapshot.revision.stream == stream && snapshot.revision.seq >= 0 && snapshot.revision.resetEpoch >= 0) {
            "Неверная принадлежность или поколение журнала чата"
        }
        var previous = 0L
        val inputIds = mutableSetOf<String>()
        for (record in snapshot.records) {
            check(record.stream == stream && record.seq > previous && record.operation == OPERATION) { "Повреждён порядок журнала чата" }
            val envelope = json.decodeFromString(Envelope.serializer(), record.detail)
            check(stream(envelope.ref.notebookId) == stream && envelope.resetEpoch == snapshot.revision.resetEpoch &&
                inputIds.add(envelope.ref.inputId)) { "Неверная принадлежность записи чата" }
            previous = record.seq
        }
        if (snapshot.records.isNotEmpty()) check(previous == snapshot.revision.seq) { "Журнал чата прочитан не полностью" }
        return snapshot
    }

    private suspend fun commit(id: String, entry: Entry, input: ChatMachine.Input, next: ChatMachine.State) =
        withContext(NonCancellable + dispatcher) {
            val expected = entry.revision
            val ref = payloads.save(id, Id.new(), input)
            val encoded = json.encodeToString(Envelope.serializer(), Envelope(ref, expected.resetEpoch))
            val accepted = try {
                checkNotNull(journal.append(expected, OPERATION, Id.now(), encoded)) { "Чат изменён другим владельцем" }.also {
                    check(it.stream == expected.stream && it.seq > expected.seq && it.operation == OPERATION && it.detail == encoded) {
                        "Подтверждение сохранения принадлежит другой записи"
                    }
                }
            } catch (failure: Exception) {
                val observed = try { validatedSnapshot(expected.stream) } catch (readFailure: Exception) {
                    failure.addSuppressed(readFailure); throw failure
                }
                val record = observed.records.lastOrNull()?.takeIf {
                    it.seq > expected.seq && it.operation == OPERATION && it.detail == encoded &&
                        observed.revision.resetEpoch == expected.resetEpoch && observed.records.dropLast(1) == entry.records
                } ?: throw failure
                try {
                    check(json.encodeToJsonElement(ChatMachine.Input.serializer(), payloads.read(ref)) ==
                        json.encodeToJsonElement(ChatMachine.Input.serializer(), input)) { "Сохранён другой ввод чата" }
                } catch (invalidPayload: Exception) { failure.addSuppressed(invalidPayload); throw failure }
                if (failure is CancellationException) throw failure
                record
            }
            entry.records += accepted
            entry.revision = expected.copy(seq = accepted.seq)
            entry.state = next
        }

    private suspend fun checkpoint(id: String, entry: Entry) {
        try {
            entry.state.sessions.values.forEach { checkpoints.save(it) }
            entry.state.removedIds.forEach { checkpoints.delete(it) }
            _failures.update { it - id }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { report(id, "checkpoint", failure) }
    }
    private fun publish(id: String, entry: Entry) { _states.update { it + (id to entry.state) } }
    private fun report(id: String, operation: String, failure: Throwable) {
        AppLog.error("chat.journal", "storage.failed", mapOf("notebookId" to id, "operation" to operation, "causeType" to failure::class.simpleName.orEmpty()))
        _failures.update { it + (id to "Не удалось подтвердить сохранение чата. Проверьте хранилище и восстановите чат.") }
    }

    /** Called only after the service has cancelled/joined its effect jobs; lock also drains admitted non-cancellable commits. */
    suspend fun wipe(): Unit = withContext(NonCancellable + dispatcher) {
        lock.withLock {
            // Never drop the authoritative journals while an old checkpoint can still be re-imported.
            checkpoints.wipe()
            journal.streams().filter { it.startsWith(PREFIX) }.forEach { stream ->
                check(journal.drop(journal.snapshot(stream).revision)) { "Чат изменился во время очистки" }
            }
            payloads.clear()
            entries.clear(); _states.value = emptyMap(); _failures.value = emptyMap(); initialized = true
        }
    }
    private fun stream(id: String) = PREFIX + id.encodeToByteArray().joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
    private companion object { const val PREFIX = "chat-workflow:"; const val OPERATION = "chat.input.v1" }
}

class ChatCommandRejected(message: String) : IllegalStateException(message)
