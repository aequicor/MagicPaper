package io.aequicor.magicpaper.domain.tools

import io.aequicor.magicpaper.data.storage.KeyValueStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString

class StoredToolReceipts(private val store: KeyValueStore) : ToolReceiptStore {
    private val lock = Mutex()
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    // Bucket by request, retaining complete IDs to distinguish hash collisions.
    private fun key(id: String) = "agent-tools-" + id.substringBeforeLast("/").hashCode().toUInt().toString(16)
    override suspend fun get(id: String): ToolReceipt? = withContext(Dispatchers.Default) { lock.withLock {
        store.read(key(id))?.let { json.decodeFromString<List<ToolReceipt>>(it).firstOrNull { receipt -> receipt.id == id } }
    } }
    override suspend fun forRequest(prefix: String): List<ToolReceipt> = withContext(Dispatchers.Default) { lock.withLock {
        store.read(key("$prefix/_"))?.let { json.decodeFromString<List<ToolReceipt>>(it).filter { receipt -> receipt.id.startsWith("$prefix/") } }.orEmpty()
    } }
    override suspend fun claim(receipt: ToolReceipt): ToolReceipt? = withContext(Dispatchers.Default) { lock.withLock {
        val bucket = store.read(key(receipt.id))?.let { json.decodeFromString<List<ToolReceipt>>(it) }.orEmpty()
        bucket.firstOrNull { it.id == receipt.id }.also { previous ->
            if (previous == null) store.write(key(receipt.id), json.encodeToString(bucket + receipt))
        }
    } }
    override suspend fun save(receipt: ToolReceipt) = withContext(Dispatchers.Default) { lock.withLock {
        val bucket = store.read(key(receipt.id))?.let { json.decodeFromString<List<ToolReceipt>>(it) }.orEmpty()
        store.write(key(receipt.id), json.encodeToString(bucket.filterNot { it.id == receipt.id } + receipt))
    } }
}
