package io.aequicor.magicpaper.data.storage

import io.aequicor.magicpaper.logging.AppLog

/** Errors deliberately contain neither stored values nor platform exception messages. */
class StorageException(val operation: String, val kind: Kind, cause: Throwable? = null, val committed: Boolean = false) : Exception("Storage $operation failed ($kind)", cause) {
    enum class Kind { UNAVAILABLE, READ, WRITE, QUOTA, CORRUPT, MISSING_SECRET, CLEANUP }
    internal var diagnosticReported = false
}

/** The first handling owner reports the cause; propagated UI/close handling does not duplicate it. */
fun logPersistenceFailure(component: String, event: String, error: Throwable, fields: Map<String, String> = emptyMap()) {
    if (error is StorageException) {
        if (error.diagnosticReported) return
        error.diagnosticReported = true
    }
    AppLog.error(component, event, error, fields)
}

/** Plaintext secrets use a separate durable backend; values must never appear in ordinary records. */
interface SecretStore {
    suspend fun read(reference: String): String?
    suspend fun write(reference: String, value: String)
    suspend fun delete(reference: String)
}

/** Test/preview storage; production factories never substitute it for an unavailable backend. */
class InMemorySecretStore : SecretStore {
    private val values = mutableMapOf<String, String>()
    override suspend fun read(reference: String): String? = values[reference]
    override suspend fun write(reference: String, value: String) { values[reference] = value }
    override suspend fun delete(reference: String) { values.remove(reference) }
}

/** Features own the payload schema, including request identity and generation where relevant. */
data class DraftRecord(
    val key: String,
    val revision: Long,
    val payload: String,
    val secrets: Map<String, String> = emptyMap(),
    val blobIds: List<String> = emptyList(),
    val generation: Long = 0,
    val ownerEpoch: Long = 0,
    val resetEpoch: Long = 0,
)

interface DraftRepository {
    /** Incremented by application reset; writers created before it cannot repopulate storage. */
    val generation: Long get() = 0

    suspend fun load(key: String): DraftRecord?
    suspend fun revision(key: String): Long
    suspend fun ownerEpoch(key: String): Long = 0
    /** Durable across repository instances and browser tabs; captured when a writer is opened. */
    suspend fun resetEpoch(): Long = 0
    suspend fun keys(prefix: String): List<String> = throw StorageException("list drafts", StorageException.Kind.UNAVAILABLE)
    /** Returns false when the revision is older than the durable revision. */
    suspend fun save(draft: DraftRecord): Boolean
    /** The production repository holds its draft lock while staging bytes and committing references. */
    suspend fun saveWithBlobs(draft: DraftRecord, writeBlobs: suspend () -> Unit): Boolean {
        writeBlobs()
        return save(draft)
    }
    /** Retains a revision tombstone so pending old autosaves cannot resurrect a discarded draft. */
    suspend fun delete(key: String, revision: Long): Boolean
    suspend fun deleteIfOwned(key: String, revision: Long, ownerEpoch: Long, resetEpoch: Long = 0): Boolean =
        if (this.resetEpoch() == resetEpoch && ownerEpoch(key) == ownerEpoch) delete(key, revision) else false
    /** Revokes already-open writers when the owning entity is explicitly removed. */
    suspend fun remove(key: String) { check(delete(key, revision(key) + 1)) }
    /** Retries deferred cleanup after the associated record was already committed. */
    suspend fun retryCleanup() { }
}

interface DraftBlobStore {
    suspend fun read(id: String): ByteArray?
    suspend fun write(id: String, bytes: ByteArray)
    suspend fun delete(id: String)
}

class InMemoryDraftRepository : DraftRepository {
    private val records = mutableMapOf<String, DraftRecord>()
    private val revisions = mutableMapOf<String, Long>()
    private val epochs = mutableMapOf<String, Long>()
    override suspend fun ownerEpoch(key: String): Long = epochs[key] ?: 0
    override suspend fun keys(prefix: String): List<String> = records.keys.filter { it.startsWith(prefix) }
    override suspend fun load(key: String): DraftRecord? = records[key]
    override suspend fun revision(key: String): Long = revisions[key] ?: 0L
    override suspend fun save(draft: DraftRecord): Boolean {
        if (draft.resetEpoch != resetEpoch() || draft.ownerEpoch != ownerEpoch(draft.key)) return false
        if (draft.revision <= (revisions[draft.key] ?: -1L)) return false
        records[draft.key] = draft
        revisions[draft.key] = draft.revision
        return true
    }
    override suspend fun delete(key: String, revision: Long): Boolean {
        val previousRevision = revisions[key] ?: -1L
        if (revision < previousRevision || revision == previousRevision && records.containsKey(key)) return false
        records.remove(key)
        revisions[key] = revision
        return true
    }
    override suspend fun remove(key: String) {
        epochs[key] = ownerEpoch(key) + 1
        delete(key, revision(key) + 1)
    }
}

class InMemoryDraftBlobStore : DraftBlobStore {
    private val values = mutableMapOf<String, ByteArray>()
    override suspend fun read(id: String): ByteArray? = values[id]?.copyOf()
    override suspend fun write(id: String, bytes: ByteArray) { values[id] = bytes.copyOf() }
    override suspend fun delete(id: String) { values.remove(id) }
}

/** The navigation owner serializes its journal and orders writes by revision. */
interface NavigationSnapshotStore {
    suspend fun load(): String?
    suspend fun save(snapshot: String)

    /** Reads the journal and all its referenced state in one backend transaction/critical section. */
    suspend fun loadWithPresentations(): NavigationSnapshotRecord? =
        load()?.let { NavigationSnapshotRecord(it) }

    /**
     * Stages immutable state records before committing the journal and its complete reference set.
     * Cleanup retains references from every journal, including journals belonging to other tabs.
     */
    suspend fun saveWithPresentations(snapshot: String, presentations: Map<String, String>) {
        if (presentations.isNotEmpty()) throw StorageException("save navigation state", StorageException.Kind.UNAVAILABLE)
        save(snapshot)
    }
}

data class NavigationSnapshotRecord(
    val snapshot: String,
    /** Opaque reference -> state payload; these payloads are stored outside the journal. */
    val presentations: Map<String, String> = emptyMap(),
)

class PersistenceStores(
    val secrets: SecretStore,
    val drafts: DraftRepository,
    val blobs: DraftBlobStore,
    val navigation: NavigationSnapshotStore,
    /** Append-only evidence of what was done, cleared by the same reset as the state itself. */
    val events: EventJournal,
    private val clear: suspend () -> Unit,
) {
    /** Called after application-owned draft/navigation writers have been flushed and stopped. */
    suspend fun clearOwnedData() = clear()
}

/** Stable backend reference; access/refresh remain owned by Codex app-server's canonical auth file. */
object SecretReferences {
    const val CODEX_AUTH = "codex:auth"
}
