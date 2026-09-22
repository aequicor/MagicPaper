package io.aequicor.magicpaper.domain

import io.aequicor.magicpaper.data.storage.*
import io.aequicor.magicpaper.domain.tools.RejectedToolCall
import io.aequicor.magicpaper.util.Id
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/** Only this adapter reads legacy snapshots. Inputs become authoritative after the first journal append. */
internal class MediaOperationJournal(private val journal: EventJournal, private val mediaStore: MediaStore) {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private val locks = MutableStateFlow<Map<String, Mutex>>(emptyMap())
    private val uncertain = MutableStateFlow<Map<String, MediaGenerationMachine.State>>(emptyMap())
    @Serializable private data class Entry(val id: String, val input: MediaGenerationMachine.Input, val resetEpoch: Long = 0)
    @Serializable private data class LegacyOperation(
        val id: String, val owner: MediaGenerationOwner, val request: MediaGenerationRequest,
        val selection: MediaModelSelection, val fingerprint: String, val media: GeneratedMedia,
        val jobId: String? = null, val output: MediaRemoteOutput? = null, val submitted: Boolean = false,
        val deleted: Boolean = false, val createdAt: Long = 0,
    )
    private fun stream(id: String): String {
        require(id.isNotBlank()) { "Отсутствует идентификатор генерации" }
        return PREFIX + id.encodeToByteArray().joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
    }
    private fun operationId(stream: String): String {
        check(stream.startsWith(PREFIX)) { "Чужой поток журнала генерации" }
        val hex = stream.removePrefix(PREFIX)
        check(hex.isNotEmpty() && hex.length % 2 == 0 && hex.all { it in '0'..'9' || it in 'a'..'f' }) { "Повреждён идентификатор потока генерации" }
        return hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray().decodeToString(throwOnInvalidSequence = true)
    }
    private fun lock(id: String): Mutex {
        locks.update { if (id in it) it else it + (id to Mutex()) }
        return locks.value.getValue(id)
    }
    suspend fun read(id: String): MediaGenerationMachine.State? = lock(id).withLock {
        uncertain.value[id] ?: load(id).second.takeUnless { it.stage == MediaGenerationMachine.Stage.NEW }
    }
    fun unconfirmed(id: String): MediaGenerationMachine.State? = uncertain.value[id]
    suspend fun all(): List<MediaGenerationMachine.State> {
        val persisted = journal.streams().filter { it.startsWith(PREFIX) }.map { replay(journal.snapshot(it), operationId(it)) }
        val legacyIds = mediaStore.records("operation-").values.map { json.decodeFromString<LegacyOperation>(it).id }
        return (persisted + legacyIds.mapNotNull { read(it) }).distinctBy { it.operation?.id }.map { state ->
            state.operation?.id?.let { uncertain.value[it] } ?: state
        }
    }
    suspend fun dispatch(id: String, input: MediaGenerationMachine.Input): MediaGenerationMachine.Transition = lock(id).withLock {
        val suppliedId = when (input) { is MediaGenerationMachine.Intent.Create -> input.operation.id; is MediaGenerationMachine.Fact.Import -> input.operation.id; else -> id }
        if (suppliedId != id) throw MediaMachineRejection("Идентификатор операции не совпадает с её журналом")
        uncertain.value[id]?.let { throw MediaJournalUnknown() }
        val (snapshot, state) = load(id)
        val next = MediaGenerationMachine.reduce(state, input)
        next.effects.filterIsInstance<MediaGenerationMachine.Effect.Reject>().firstOrNull()?.let {
            MachineTransitionLog.append(MediaGenerationMachine.id, MediaGenerationMachine.space, state, input, next.state, next.effects)
            throw MediaMachineRejection(it.reason)
        }
        append(snapshot, state, input, next)
        next
    }
    private suspend fun load(id: String): Pair<JournalSnapshot, MediaGenerationMachine.State> {
        val snapshot = journal.snapshot(stream(id))
        val restored = replay(snapshot, id)
        if (snapshot.records.isNotEmpty()) return snapshot to restored
        val legacy = mediaStore.readRecord("operation-${mediaStore.fingerprint(id)}")?.let { json.decodeFromString<LegacyOperation>(it) }
            ?: return snapshot to MediaGenerationMachine.initial()
        check(legacy.id == id) { "Идентификатор сохранённой генерации изменился" }
        val safe = MediaOperationData(legacy.id, legacy.owner, legacy.request.copy(prompt = ""), legacy.selection,
            legacy.fingerprint, legacy.media, mediaStore.fingerprint(json.encodeToString(legacy.request)), legacy.jobId,
            legacy.output?.copy(dataBase64 = ""), legacy.submitted, legacy.deleted, legacy.createdAt)
        val input = MediaGenerationMachine.Fact.Import(safe)
        val next = MediaGenerationMachine.reduce(MediaGenerationMachine.initial(), input)
        check(next.effects.none { it is MediaGenerationMachine.Effect.Reject }) { "Повреждена сохранённая генерация" }
        val revision = append(snapshot, MediaGenerationMachine.initial(), input, next)
        return JournalSnapshot(revision, emptyList()) to next.state
    }
    private fun replay(snapshot: JournalSnapshot, id: String): MediaGenerationMachine.State {
        check(snapshot.revision.stream == stream(id) && snapshot.revision.seq >= 0 && snapshot.revision.resetEpoch >= 0) { "Повреждена ревизия журнала генерации" }
        check(snapshot.records.isEmpty() || snapshot.records.last().seq == snapshot.revision.seq) { "Неполная ревизия журнала генерации" }
        var state = MediaGenerationMachine.initial()
        var sequence = 0L
        val entries = mutableSetOf<String>()
        for (record in snapshot.records) {
            check(record.stream == snapshot.revision.stream && record.seq > sequence && record.seq <= snapshot.revision.seq) { "Нарушен порядок журнала генерации" }
            sequence = record.seq
            check(record.operation == OPERATION) { "Неизвестная запись журнала генерации" }
            val entry = json.decodeFromString<Entry>(record.detail)
            check(entry.resetEpoch == snapshot.revision.resetEpoch && entry.id.isNotBlank() && entries.add(entry.id)) { "Запись принадлежит другому поколению журнала" }
            val before = state
            val next = MediaGenerationMachine.reduce(before, entry.input)
            check(next.effects.none { it is MediaGenerationMachine.Effect.Reject }) { "Повреждён журнал генерации" }
            check(next.state.operation?.id == id) { "Журнал принадлежит другой операции генерации" }
            MachineTransitionLog.replay(MediaGenerationMachine.id, MediaGenerationMachine.space, before, entry.input, next.state, next.effects)
            state = next.state
        }
        return state
    }
    private suspend fun append(snapshot: JournalSnapshot, state: MediaGenerationMachine.State,
        input: MediaGenerationMachine.Input, next: MediaGenerationMachine.Transition): JournalRevision {
        val detail = json.encodeToString(Entry(Id.new(), input, snapshot.revision.resetEpoch))
        val id = checkNotNull(next.state.operation ?: state.operation).id
        try {
            val record = journal.append(snapshot.revision, OPERATION, Id.now(), detail)
                ?: throw IllegalStateException("Журнал изменён другим владельцем")
            check(record.stream == snapshot.revision.stream && record.seq > snapshot.revision.seq &&
                record.operation == OPERATION && record.detail == detail) { "Подтверждение записи принадлежит другому журналу" }
            MachineTransitionLog.append(MediaGenerationMachine.id, MediaGenerationMachine.space, state, input, next.state, next.effects)
            return snapshot.revision.copy(seq = record.seq)
        } catch (failure: Exception) {
            val observed = try { withContext(NonCancellable) { journal.snapshot(snapshot.revision.stream) } }
            catch (readFailure: Exception) {
                markUnknown(id, state.takeUnless { it.operation == null } ?: next.state)
                failure.addSuppressed(readFailure)
                if (failure is CancellationException) throw failure
                throw MediaJournalUnknown(failure)
            }
            try { replay(observed, id) }
            catch (invalid: Exception) {
                markUnknown(id, state.takeUnless { it.operation == null } ?: next.state)
                failure.addSuppressed(invalid)
                if (failure is CancellationException) throw failure
                throw MediaJournalUnknown(failure)
            }
            if (observed.revision.resetEpoch == snapshot.revision.resetEpoch && observed.records.lastOrNull()?.let {
                    it.operation == OPERATION && it.detail == detail } == true) {
                if (failure is CancellationException) throw failure
                MachineTransitionLog.append(MediaGenerationMachine.id, MediaGenerationMachine.space, state, input, next.state, next.effects)
                return observed.revision
            }
            markUnknown(id, state.takeUnless { it.operation == null } ?: next.state)
            if (failure is CancellationException) throw failure
            throw MediaJournalUnknown(failure)
        }
    }
    private fun markUnknown(id: String, state: MediaGenerationMachine.State) {
        val input = MediaGenerationMachine.Fact.PersistenceUnknown
        val next = MediaGenerationMachine.reduce(state, input)
        MachineTransitionLog.append(MediaGenerationMachine.id, MediaGenerationMachine.space, state, input, next.state, next.effects)
        uncertain.update { it + (id to next.state) }
    }
    /** Producers must be joined and the application stores cleared before a new generation is accepted. */
    fun reset() { uncertain.value = emptyMap(); locks.value = emptyMap() }
    companion object { private const val PREFIX = "media-generation:"; private const val OPERATION = "media-generation.input.v1" }
}

internal class MediaMachineRejection(message: String) : IllegalStateException(message), RejectedToolCall
internal class MediaJournalUnknown(cause: Throwable? = null) : IllegalStateException("Не удалось подтвердить сохранение генерации. Повторная отправка отключена.", cause)
