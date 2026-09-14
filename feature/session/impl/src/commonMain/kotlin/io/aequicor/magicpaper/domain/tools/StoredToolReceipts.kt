package io.aequicor.magicpaper.domain.tools

import io.aequicor.magicpaper.data.storage.KeyValueStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString

/** Atomicity is one request bucket on one shared store instance; it does not include external effects. */
class StoredToolReceipts(private val store: KeyValueStore) : ToolReceiptStore {
    // All instances in this application process participate, including replacement runtimes.
    private val lock get() = transactionLock
    private companion object { val transactionLock = Mutex() }
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    // Bucket by request, retaining complete IDs to distinguish hash collisions.
    private fun key(id: String) = "agent-tools-" + id.substringBeforeLast("/").hashCode().toUInt().toString(16)
    private suspend fun bucket(key: String): List<ToolReceipt> {
        val original = store.read(key)?.let { json.decodeFromString<List<ToolReceipt>>(it) }.orEmpty()
        val migrated = original.map { it.forPersistence() }
        // Legacy raw arguments are fingerprinted before being redacted, preserving retry identity.
        if (migrated != original) store.write(key, json.encodeToString(migrated))
        return migrated
    }
    override suspend fun get(id: String): ToolReceipt? = withContext(Dispatchers.Default) { lock.withLock {
        bucket(key(id)).firstOrNull { receipt -> receipt.id == id }
    } }
    override suspend fun forRequest(prefix: String): List<ToolReceipt> = withContext(Dispatchers.Default) { lock.withLock {
        (bucket(key("$prefix/_")) + bucket(key("$prefix/native/_")))
            .filter { receipt -> receipt.id.startsWith("$prefix/") }.distinctBy { it.id }
    } }
    override suspend fun forOwner(projectId: String, ownerSessionId: String): List<ToolReceipt> = withContext(Dispatchers.Default) { lock.withLock {
        store.keys("agent-tools-").flatMap { bucket(it) }
            .filter { it.id.startsWith("$projectId/$ownerSessionId/") }.distinctBy { it.id }
    } }
    override suspend fun claim(receipt: ToolReceipt): ToolReceipt? = withContext(Dispatchers.Default) { lock.withLock {
        val bucket = bucket(key(receipt.id))
        val safe = receipt.forPersistence()
        bucket.firstOrNull { it.id == receipt.id }.also { previous ->
            if (previous == null) store.write(key(receipt.id), json.encodeToString(bucket + safe))
        }
    } }
    override suspend fun save(receipt: ToolReceipt) = withContext(Dispatchers.Default) { lock.withLock {
        val bucket = bucket(key(receipt.id))
        val safe = receipt.forPersistence()
        bucket.firstOrNull { it.id == receipt.id }?.validateUpdate(safe)
        store.write(key(receipt.id), json.encodeToString(bucket.filterNot { it.id == receipt.id } + safe))
    } }
}
