package io.aequicor.magicpaper.data.storage

import io.aequicor.magicpaper.domain.PinConversation
import io.aequicor.magicpaper.domain.RequestPinRecord
import io.aequicor.magicpaper.domain.RequestPinRepository
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class JsonRequestPinRepository(private val store: KeyValueStore, private val json: Json) : RequestPinRepository {
    @Serializable private data class Cache(val version: Int = 1, val records: List<RequestPinRecord>)

    override fun load(conversation: PinConversation): List<RequestPinRecord> = store.read(key(conversation))?.let { raw ->
        runCatching { json.decodeFromString<Cache>(raw).takeIf { it.version == 1 }?.records }.getOrNull()
    }.orEmpty()

    override fun save(conversation: PinConversation, records: List<RequestPinRecord>) {
        store.write(key(conversation), json.encodeToString(Cache.serializer(), Cache(records = records)))
    }

    override fun delete(conversation: PinConversation) = store.delete(key(conversation))
    override fun clear() { store.keys(PREFIX).forEach { store.delete(it) } }
    private fun key(conversation: PinConversation) = PREFIX + json.encodeToString(PinConversation.serializer(), conversation)
    private companion object { const val PREFIX = "request-pins:" }
}
