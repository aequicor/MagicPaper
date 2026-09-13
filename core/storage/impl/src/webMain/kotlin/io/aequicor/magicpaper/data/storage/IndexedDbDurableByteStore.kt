package io.aequicor.magicpaper.data.storage

import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/** IndexedDB object stores separate ordinary drafts/navigation, attachment bytes and plaintext secrets. */
class IndexedDbDurableByteStore(private val databaseName: String = "magicpaper-persistence") : DurableByteStore {
    override suspend fun <T> withDraftLock(block: suspend () -> T): T {
        val callerContext = currentCoroutineContext()
        val lease = storageId()
        // A queued lock must always be released even when its coroutine is cancelled before grant.
        return withContext(NonCancellable) {
            indexedDbOperation(databaseName, StorageArea.DRAFTS.storeName, lease, "lock", null)
            try { withContext(callerContext) { block() } }
            finally { indexedDbOperation(databaseName, StorageArea.DRAFTS.storeName, lease, "unlock", null) }
        }
    }
    @OptIn(ExperimentalEncodingApi::class)
    override suspend fun values(area: StorageArea): List<ByteArray> {
        val encoded = indexedDbOperation(databaseName, area.storeName, "", "values", null)
            ?: throw StorageException("list records", StorageException.Kind.READ)
        val values = Json.decodeFromString<List<String>>(encoded)
        return values.map { if (area == StorageArea.BLOBS) Base64.decode(it) else it.encodeToByteArray() }
    }

    @OptIn(ExperimentalEncodingApi::class)
    override suspend fun read(area: StorageArea, key: String): ByteArray? {
        val value = indexedDbOperation(databaseName, area.storeName, key, "read", null) ?: return null
        return if (area == StorageArea.BLOBS) Base64.decode(value) else value.encodeToByteArray()
    }

    @OptIn(ExperimentalEncodingApi::class)
    override suspend fun write(area: StorageArea, key: String, bytes: ByteArray) {
        val value = if (area == StorageArea.BLOBS) Base64.encode(bytes) else bytes.decodeToString()
        indexedDbOperation(databaseName, area.storeName, key, "write", value)
    }

    override suspend fun delete(area: StorageArea, key: String) {
        indexedDbOperation(databaseName, area.storeName, key, "delete", null)
    }
    override suspend fun clear(area: StorageArea) { indexedDbOperation(databaseName, area.storeName, "", "clear", null) }
}

internal expect suspend fun indexedDbOperation(database: String, store: String, key: String, operation: String, value: String?): String?

fun browserPersistenceStores(journalId: String = "main", databaseName: String = "magicpaper-persistence", fallbackJournalId: String? = null): PersistenceStores =
    persistenceStores(IndexedDbDurableByteStore(databaseName), journalId, fallbackJournalId = fallbackJournalId)
