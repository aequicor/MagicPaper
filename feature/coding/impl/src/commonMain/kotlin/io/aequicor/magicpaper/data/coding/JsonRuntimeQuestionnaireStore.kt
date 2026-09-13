package io.aequicor.magicpaper.data.coding

import io.aequicor.magicpaper.data.storage.KeyValueStore
import io.aequicor.magicpaper.domain.RuntimeQuestionnaireRecord
import io.aequicor.magicpaper.domain.RuntimeQuestionnaireStore
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** Single-key snapshot. The desktop store atomically replaces this file after syncing its data. */
class JsonRuntimeQuestionnaireStore(private val store: KeyValueStore, private val key: String = "runtime-questionnaires") : RuntimeQuestionnaireStore {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private val serializer = ListSerializer(RuntimeQuestionnaireRecord.serializer())
    override fun load() = store.read(key)?.let { json.decodeFromString(serializer, it) }.orEmpty()
    override fun save(records: List<RuntimeQuestionnaireRecord>) = store.write(key, json.encodeToString(serializer, records))
}
