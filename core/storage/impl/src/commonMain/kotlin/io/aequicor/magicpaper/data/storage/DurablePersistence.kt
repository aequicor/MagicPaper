package io.aequicor.magicpaper.data.storage

import io.aequicor.magicpaper.logging.AppLog

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.random.Random

enum class StorageArea(val storeName: String) {
    SECRETS("secrets"), DRAFTS("drafts"), BLOBS("draft-blobs"), NAVIGATION("navigation"), PRESENTATION("view-states"),
    EVENTS("events"),
}

/** Each write completes only after the filesystem commit / IndexedDB transaction has committed. */
interface DurableByteStore {
    suspend fun read(area: StorageArea, key: String): ByteArray?
    suspend fun write(area: StorageArea, key: String, bytes: ByteArray)
    suspend fun delete(area: StorageArea, key: String)
    suspend fun clear(area: StorageArea)
    suspend fun values(area: StorageArea): List<ByteArray>
    /** Shared by all repository instances targeting this backend, including browser tabs. */
    suspend fun <T> withDraftLock(block: suspend () -> T): T
}

class DurableSecretStore(private val backend: DurableByteStore) : SecretStore {
    override suspend fun read(reference: String): String? = backend.read(StorageArea.SECRETS, reference)?.decodeToString()
    override suspend fun write(reference: String, value: String) = backend.write(StorageArea.SECRETS, reference, value.encodeToByteArray())
    override suspend fun delete(reference: String) = backend.delete(StorageArea.SECRETS, reference)
}

class DurableDraftBlobStore(private val backend: DurableByteStore) : DraftBlobStore {
    override suspend fun read(id: String): ByteArray? = backend.read(StorageArea.BLOBS, id)
    override suspend fun write(id: String, bytes: ByteArray) {
        val previous = backend.read(StorageArea.BLOBS, id)
        if (previous != null) {
            if (!previous.contentEquals(bytes)) throw StorageException("immutable attachment", StorageException.Kind.CORRUPT)
            return
        }
        backend.write(StorageArea.BLOBS, id, bytes)
    }
    override suspend fun delete(id: String) = backend.delete(StorageArea.BLOBS, id)
}

class DurableNavigationSnapshotStore(
    private val backend: DurableByteStore,
    private val journalId: String = "main",
    private val fallbackJournalId: String? = null,
) : NavigationSnapshotStore {
    private val mutex = Mutex()
    private val json = Json { encodeDefaults = true }
    private var resetEpoch: Long? = null
    private suspend fun <T> locked(block: suspend () -> T): T = mutex.withLock { backend.withDraftLock {
        val currentEpoch = readResetEpoch(backend)
        if (resetEpoch != null && resetEpoch != currentEpoch) throw StorageException("navigation reset in another window", StorageException.Kind.WRITE)
        resetEpoch = currentEpoch
        block()
    } }
    internal fun acknowledgeReset(epoch: Long) { resetEpoch = epoch }
    override suspend fun load(): String? = loadWithPresentations()?.snapshot
    override suspend fun loadWithPresentations(): NavigationSnapshotRecord? = locked {
        val bytes = backend.read(StorageArea.NAVIGATION, journalId)
            ?: fallbackJournalId?.let { backend.read(StorageArea.NAVIGATION, it) }
            ?: return@locked null
        val record = decodeNavigation(bytes)
        val presentations = record.presentationReferences.associateWith { reference ->
            val state = backend.read(StorageArea.PRESENTATION, reference)
                ?: throw StorageException("read navigation state", StorageException.Kind.CORRUPT)
            decodePresentation(state).also {
                if (it.reference != reference) throw StorageException("read navigation reference", StorageException.Kind.CORRUPT)
            }.snapshot
        }
        NavigationSnapshotRecord(record.snapshot, presentations)
    }
    override suspend fun save(snapshot: String) = saveWithPresentations(snapshot, emptyMap())
    override suspend fun saveWithPresentations(snapshot: String, presentations: Map<String, String>) = locked {
        presentations.forEach { (reference, payload) ->
            require(reference.isNotBlank())
            val previous = backend.read(StorageArea.PRESENTATION, reference)
            val state = StoredPresentation(reference = reference, snapshot = payload)
            if (previous != null) {
                if (decodePresentation(previous) != state) throw StorageException("immutable navigation state", StorageException.Kind.CORRUPT)
            } else {
                backend.write(StorageArea.PRESENTATION, reference, json.encodeToString(StoredPresentation.serializer(), state).encodeToByteArray())
            }
        }
        // A single journal record commits both the opaque snapshot and the complete reference set.
        // Failed state writes leave the previous journal intact; later successful saves collect orphans.
        val record = StoredNavigationSnapshot(snapshot = snapshot, presentationReferences = presentations.keys.toSet())
        backend.write(StorageArea.NAVIGATION, journalId, json.encodeToString(StoredNavigationSnapshot.serializer(), record).encodeToByteArray())
        try { cleanupPresentations() }
        catch (failure: CancellationException) { throw failure }
        catch (failure: Exception) { throw StorageException("clean navigation state", StorageException.Kind.CLEANUP, failure, committed = true) }
    }

    private suspend fun cleanupPresentations() {
        // This lock also covers staging in every other tab. A fork loads its state payloads under
        // the same lock and can re-stage them even if the source journal changes before fork save.
        val retained = backend.values(StorageArea.NAVIGATION).flatMap { decodeNavigation(it).presentationReferences }.toSet()
        val states = backend.values(StorageArea.PRESENTATION).map(::decodePresentation)
        if (!states.map { it.reference }.toSet().containsAll(retained)) {
            throw StorageException("verify navigation references", StorageException.Kind.CORRUPT)
        }
        var firstFailure: Exception? = null
        states.filter { it.reference !in retained }.forEach { state ->
            try { backend.delete(StorageArea.PRESENTATION, state.reference) }
            catch (failure: CancellationException) { throw failure }
            catch (failure: Exception) {
                if (firstFailure == null) firstFailure = failure else firstFailure?.addSuppressed(failure)
            }
        }
        firstFailure?.let { throw it }
    }

    private fun decodeNavigation(bytes: ByteArray): StoredNavigationSnapshot = try {
        val raw = bytes.decodeToString()
        // Earlier app builds stored journal JSON directly. The reset marker is a plain integer.
        if (!raw.trimStart().startsWith("{")) StoredNavigationSnapshot(snapshot = raw)
        else {
            val fields = json.parseToJsonElement(raw) as JsonObject
            if (fields.keys.none { it in setOf("storageFormat", "snapshot", "presentationReferences") }) StoredNavigationSnapshot(snapshot = raw)
            else {
                if (!fields.keys.containsAll(setOf("storageFormat", "version", "snapshot", "presentationReferences"))) {
                    throw StorageException("read navigation metadata", StorageException.Kind.CORRUPT)
                }
                json.decodeFromString(StoredNavigationSnapshot.serializer(), raw).also {
                    if (it.storageFormat != "magicpaper-navigation" || it.version != 1) throw StorageException("read navigation format", StorageException.Kind.CORRUPT)
                }
            }
        }
    } catch (failure: CancellationException) { throw failure }
    catch (failure: StorageException) { throw failure }
    catch (failure: Exception) { throw StorageException("read navigation snapshot", StorageException.Kind.CORRUPT, failure) }

    private fun decodePresentation(bytes: ByteArray): StoredPresentation = try {
        json.decodeFromString(StoredPresentation.serializer(), bytes.decodeToString()).also {
            if (it.version != 1 || it.reference.isBlank()) throw StorageException("read navigation state format", StorageException.Kind.CORRUPT)
        }
    } catch (failure: CancellationException) { throw failure }
    catch (failure: StorageException) { throw failure }
    catch (failure: Exception) { throw StorageException("read navigation state", StorageException.Kind.CORRUPT, failure) }
}

@Serializable
private data class StoredNavigationSnapshot(
    val storageFormat: String = "magicpaper-navigation",
    val version: Int = 1,
    val snapshot: String,
    val presentationReferences: Set<String> = emptySet(),
)

@Serializable
private data class StoredPresentation(
    val version: Int = 1,
    val reference: String,
    val snapshot: String,
)

private const val RESET_EPOCH_KEY = "\u0000magicpaper-reset-epoch"
private suspend fun readResetEpoch(backend: DurableByteStore): Long {
    val raw = backend.read(StorageArea.NAVIGATION, RESET_EPOCH_KEY) ?: return 0
    return raw.decodeToString().toLongOrNull()?.takeIf { it >= 0 }
        ?: throw StorageException("read reset epoch", StorageException.Kind.CORRUPT)
}

@Serializable
private data class StoredDraft(
    val revision: Long,
    val payload: String? = null,
    val secretReferences: Map<String, String> = emptyMap(),
    val blobIds: List<String> = emptyList(),
    val key: String? = null,
    val ownerEpoch: Long = 0,
    /** Retained after cleanup so interrupted deletion is retried by subsequent transactions. */
    val retiredBlobIds: Set<String> = emptySet(),
    val retiredSecretReferences: Set<String> = emptySet(),
)

/** A single application-owned instance serializes writes, including writes to secret records. */
class DurableDraftRepository(
    private val backend: DurableByteStore,
    private val secrets: SecretStore,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : DraftRepository {
    private val mutex = Mutex()
    override var generation: Long = 0
        private set
    private suspend fun <T> locked(block: suspend () -> T): T = mutex.withLock { backend.withDraftLock(block) }
    suspend fun reset(clear: suspend () -> Unit): Long = locked {
        val epoch = readResetEpoch(backend) + 1
        generation++
        AppLog.info("DurableDraftRepository", "reset_started", mapOf("generation" to epoch.toString()))
        // Even a partially failed clear must invalidate writers from other live windows.
        var primaryFailure: Throwable? = null
        try { clear() }
        catch (failure: Throwable) { primaryFailure = failure; throw failure }
        finally {
            try { withContext(NonCancellable) { backend.write(StorageArea.NAVIGATION, RESET_EPOCH_KEY, epoch.toString().encodeToByteArray()) } }
            catch (failure: CancellationException) { throw failure }
            catch (failure: Exception) {
                if (primaryFailure == null) throw failure
                primaryFailure?.addSuppressed(failure)
                logPersistenceFailure("DurableDraftRepository", "reset_fence_failed", failure, mapOf("generation" to epoch.toString()))
            }
        }
        AppLog.info("DurableDraftRepository", "reset_completed", mapOf("generation" to epoch.toString()))
        epoch
    }

    override suspend fun revision(key: String): Long = locked { (stored(key)?.revision ?: 0L).coerceAtLeast(0) }
    override suspend fun ownerEpoch(key: String): Long = locked { stored(key)?.ownerEpoch ?: 0L }
    override suspend fun resetEpoch(): Long = locked { readResetEpoch(backend) }
    override suspend fun keys(prefix: String): List<String> = locked {
        allStored().filter { it.payload != null }.mapNotNull { it.key }.filter { it.startsWith(prefix) }
    }

    override suspend fun load(key: String): DraftRecord? = locked {
        val draft = stored(key) ?: return@locked null
        val payload = draft.payload ?: return@locked null
        val fields = draft.secretReferences.mapValues { (_, reference) ->
            secrets.read(reference) ?: throw StorageException("read draft", StorageException.Kind.MISSING_SECRET)
        }
        DraftRecord(key, draft.revision, payload, fields, draft.blobIds, generation, draft.ownerEpoch, readResetEpoch(backend))
    }

    override suspend fun save(draft: DraftRecord): Boolean = saveWithBlobs(draft) {}

    override suspend fun saveWithBlobs(draft: DraftRecord, writeBlobs: suspend () -> Unit): Boolean = locked {
        if (draft.generation != generation || draft.resetEpoch != readResetEpoch(backend)) return@locked false
        require(draft.revision >= 0)
        val previous = stored(draft.key)
        if (draft.ownerEpoch != (previous?.ownerEpoch ?: 0L) || previous != null && draft.revision <= previous.revision) return@locked false
        // Write cleanup intent before staging bytes. A failed/crashed transaction leaves the old
        // payload intact and the new, unreferenced blob IDs available to a later cleanup pass.
        val retired = previous?.retiredBlobIds.orEmpty() + previous?.blobIds.orEmpty() + draft.blobIds
        val refs = draft.secrets.mapValues { "draft:" + storageId() }
        val retiredSecrets = previous?.retiredSecretReferences.orEmpty() + previous?.secretReferences?.values.orEmpty() + refs.values
        if (retired.isNotEmpty() || retiredSecrets.isNotEmpty()) {
            writeStored(draft.key, (previous ?: StoredDraft(-1, key = draft.key)).copy(
                retiredBlobIds = retired, retiredSecretReferences = retiredSecrets))
        }
        writeBlobs()
        draft.blobIds.forEach { if (backend.read(StorageArea.BLOBS, it) == null) throw StorageException("commit attachment", StorageException.Kind.CORRUPT) }
        // Unique refs leave the previous committed record usable if any part of this save fails.
        draft.secrets.forEach { (field, value) ->
            val ref = refs.getValue(field)
            secrets.write(ref, value)
            if (secrets.read(ref) != value) throw StorageException("verify draft", StorageException.Kind.WRITE)
        }
        val next = StoredDraft(draft.revision, draft.payload, refs, draft.blobIds, draft.key, draft.ownerEpoch, retired, retiredSecrets)
        writeStored(draft.key, next)
        cleanup()
        true
    }

    override suspend fun delete(key: String, revision: Long): Boolean = locked { deleteLocked(key, revision) }
    override suspend fun deleteIfOwned(key: String, revision: Long, ownerEpoch: Long, resetEpoch: Long): Boolean = locked {
        if (readResetEpoch(backend) != resetEpoch || (stored(key)?.ownerEpoch ?: 0L) != ownerEpoch) false else deleteLocked(key, revision)
    }
    override suspend fun remove(key: String) = locked {
        val previous = stored(key)
        deleteLocked(key, (previous?.revision ?: 0L) + 1, (previous?.ownerEpoch ?: 0L) + 1)
        Unit
    }
    private suspend fun deleteLocked(key: String, revision: Long, epoch: Long? = null): Boolean {
        require(revision >= 0)
        val previous = stored(key)
        if (previous != null && (revision < previous.revision || revision == previous.revision && previous.payload != null)) return false
        val tombstone = StoredDraft(revision, key = key, ownerEpoch = epoch ?: previous?.ownerEpoch ?: 0,
            retiredBlobIds = previous?.retiredBlobIds.orEmpty() + previous?.blobIds.orEmpty(),
            retiredSecretReferences = previous?.retiredSecretReferences.orEmpty() + previous?.secretReferences?.values.orEmpty())
        writeStored(key, tombstone)
        cleanup()
        return true
    }

    private suspend fun writeStored(key: String, draft: StoredDraft) =
        backend.write(StorageArea.DRAFTS, key, json.encodeToString(StoredDraft.serializer(), draft).encodeToByteArray())

    private fun decode(raw: ByteArray): StoredDraft = try { json.decodeFromString(StoredDraft.serializer(), raw.decodeToString()) }
        catch (error: CancellationException) { throw error }
        catch (failure: Exception) { throw StorageException("read draft", StorageException.Kind.CORRUPT, failure) }
    private suspend fun allStored() = backend.values(StorageArea.DRAFTS).map(::decode)

    private suspend fun stored(key: String): StoredDraft? {
        val raw = backend.read(StorageArea.DRAFTS, key) ?: return null
        return decode(raw)
    }

    override suspend fun retryCleanup() = locked { cleanup() }

    private suspend fun cleanup() {
        // A cleanup error explicitly reports that the new payload/tombstone was already committed.
        // Retained intent lets the same operation or a later owner retry without restoring old input.
        try {
            val snapshots = allStored()
            val active = snapshots.filter { it.payload != null }
            val retainedBlobs = active.flatMap { it.blobIds }.toSet()
            val retainedSecrets = active.flatMap { it.secretReferences.values }.toSet()
            var firstFailure: Exception? = null
            suspend fun attempt(remove: suspend () -> Unit) {
                try { remove() }
                catch (failure: CancellationException) { throw failure }
                catch (failure: Exception) {
                    if (firstFailure == null) firstFailure = failure else firstFailure?.addSuppressed(failure)
                }
            }
            (snapshots.flatMap { it.retiredSecretReferences }.toSet() - retainedSecrets).forEach { attempt { secrets.delete(it) } }
            (snapshots.flatMap { it.retiredBlobIds }.toSet() - retainedBlobs).forEach { attempt { backend.delete(StorageArea.BLOBS, it) } }
            firstFailure?.let { throw it }
            // Every unreferenced value has now been removed. Compact under the same lock so
            // autosaving a secret cannot grow the cleanup journal on every keystroke.
            snapshots.filter { it.key != null && (it.retiredBlobIds.isNotEmpty() || it.retiredSecretReferences.isNotEmpty()) }.forEach {
                writeStored(checkNotNull(it.key), it.copy(retiredBlobIds = emptySet(), retiredSecretReferences = emptySet()))
            }
        } catch (error: CancellationException) { throw error }
        catch (failure: Exception) { throw StorageException("clean draft references", StorageException.Kind.CLEANUP, failure, committed = true) }
    }
}

/**
 * One record per fact, at a key that is never written twice.
 *
 * The sequence lives in the same area under [CURSOR_KEY], so it is shared by every instance
 * against this backend — including other browser tabs — and so an application reset clears it
 * along with the records it numbered. Appending reserves the next number and commits it before
 * writing the record: a crash in between leaves a gap in the keys, never a record written over.
 */
class DurableEventJournal(private val backend: DurableByteStore) : EventJournal {
    private val mutex = Mutex()
    private val json = Json { encodeDefaults = true }

    override suspend fun append(stream: String, operation: String, at: Long, detail: String): JournalRecord {
        require(stream.isNotBlank()) { "Записи журнала принадлежат потоку" }
        return mutex.withLock { backend.withDraftLock {
            val seq = reserve()
            val record = JournalRecord(seq, at, stream, operation, detail)
            try { backend.write(StorageArea.EVENTS, key(seq), json.encodeToString(StoredEvent.serializer(), record.stored()).encodeToByteArray()) }
            catch (failure: CancellationException) { throw failure }
            catch (failure: StorageException) { throw failure }
            catch (failure: Exception) { throw StorageException("append journal record", StorageException.Kind.WRITE, failure) }
            record
        } }
    }

    override suspend fun read(stream: String): List<JournalRecord> = mutex.withLock { backend.withDraftLock {
        records().filter { it.stream == stream }
    } }

    override suspend fun drop(stream: String) { mutex.withLock { backend.withDraftLock {
        var firstFailure: Exception? = null
        records().filter { it.stream == stream }.forEach {
            try { backend.delete(StorageArea.EVENTS, key(it.seq)) }
            catch (failure: CancellationException) { throw failure }
            catch (failure: Exception) {
                if (firstFailure == null) firstFailure = failure else firstFailure?.addSuppressed(failure)
            }
        }
        firstFailure?.let { throw StorageException("drop journal stream", StorageException.Kind.CLEANUP, it, committed = true) }
    } } }

    /** Commits the next number before its record exists, so no number is ever handed out twice. */
    private suspend fun reserve(): Long {
        val stored = backend.read(StorageArea.EVENTS, CURSOR_KEY)?.decodeToString()
        val current = if (stored == null) recover() else stored.toLongOrNull()?.takeIf { it >= 0 }
            ?: throw StorageException("read journal cursor", StorageException.Kind.CORRUPT)
        val next = current + 1
        backend.write(StorageArea.EVENTS, CURSOR_KEY, next.toString().encodeToByteArray())
        return next
    }

    /**
     * A cursor lost while records remain would restart the sequence over them. Recovering it
     * from the records themselves costs one scan, and only on a journal that has no cursor.
     */
    private suspend fun recover(): Long = records().maxOfOrNull { it.seq } ?: 0

    private suspend fun records(): List<JournalRecord> = backend.values(StorageArea.EVENTS)
        .mapNotNull { bytes ->
            // The cursor shares the area and is not a record; anything else that fails to parse
            // is a corrupt journal, which must not silently read as a shorter history.
            val raw = bytes.decodeToString()
            if (raw.toLongOrNull() != null) null else decode(raw)
        }
        .sortedBy { it.seq }

    private fun decode(raw: String): JournalRecord = try {
        json.decodeFromString(StoredEvent.serializer(), raw).let {
            if (it.storageFormat != EVENT_FORMAT || it.version != 1 || it.seq <= 0 || it.stream.isBlank()) {
                throw StorageException("read journal format", StorageException.Kind.CORRUPT)
            }
            JournalRecord(it.seq, it.at, it.stream, it.operation, it.detail)
        }
    } catch (failure: CancellationException) { throw failure }
    catch (failure: StorageException) { throw failure }
    catch (failure: Exception) { throw StorageException("read journal record", StorageException.Kind.CORRUPT, failure) }

    private fun JournalRecord.stored() = StoredEvent(seq = seq, at = at, stream = stream, operation = operation, detail = detail)

    private companion object {
        const val CURSOR_KEY = "seq"
        const val EVENT_FORMAT = "magicpaper-journal"
        /** Padded so the backend's own key order matches the sequence, for eyes and for tools. */
        fun key(seq: Long) = "e" + seq.toString().padStart(18, '0')
    }
}

@Serializable
private data class StoredEvent(
    val storageFormat: String = "magicpaper-journal",
    val version: Int = 1,
    val seq: Long,
    val at: Long,
    val stream: String,
    val operation: String,
    val detail: String = "",
)

fun persistenceStores(backend: DurableByteStore, journalId: String = "main", secrets: SecretStore = DurableSecretStore(backend), fallbackJournalId: String? = null): PersistenceStores {
    val drafts = DurableDraftRepository(backend, secrets)
    val navigation = DurableNavigationSnapshotStore(backend, journalId, fallbackJournalId)
    return PersistenceStores(secrets, drafts, DurableDraftBlobStore(backend), navigation, DurableEventJournal(backend)) {
        val epoch = drafts.reset {
            var firstFailure: Exception? = null
            StorageArea.entries.forEach { area ->
                try { backend.clear(area) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) {
                    if (firstFailure == null) firstFailure = failure else firstFailure?.addSuppressed(failure)
                }
            }
            firstFailure?.let { throw it }
        }
        navigation.acknowledgeReset(epoch)
    }
}

fun storageId(): String = Random.nextBytes(24).joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }

/** Explicit test/preview backend; never selected as a fallback after persistence errors. */
class InMemoryDurableByteStore : DurableByteStore {
    private val values = mutableMapOf<Pair<StorageArea, String>, ByteArray>()
    private val draftMutex = Mutex()
    override suspend fun <T> withDraftLock(block: suspend () -> T): T = draftMutex.withLock { block() }
    override suspend fun values(area: StorageArea): List<ByteArray> = values.filterKeys { it.first == area }.values.map { it.copyOf() }
    override suspend fun read(area: StorageArea, key: String): ByteArray? = values[area to key]?.copyOf()
    override suspend fun write(area: StorageArea, key: String, bytes: ByteArray) { values[area to key] = bytes.copyOf() }
    override suspend fun delete(area: StorageArea, key: String) { values.remove(area to key) }
    override suspend fun clear(area: StorageArea) { values.keys.filter { it.first == area }.forEach { values.remove(it) } }
}
