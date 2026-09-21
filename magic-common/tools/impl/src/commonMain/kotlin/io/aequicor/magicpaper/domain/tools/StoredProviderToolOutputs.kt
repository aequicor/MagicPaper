package io.aequicor.magicpaper.domain.tools

import io.aequicor.magicpaper.data.storage.KeyValueStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.*

class StoredProviderToolOutputs(private val store: KeyValueStore) : ProviderToolOutputs {
    private val lock = Mutex()
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private suspend fun key(runId: String, attempt: String) = "provider-tool-output-" + toolArgumentsFingerprint(buildJsonObject {
        put("runId", runId); put("attempt", attempt)
    })
    override suspend fun get(runId: String, attemptId: String): ProviderToolOutput? = lock.withLock {
        store.read(key(runId, attemptId))?.let { json.decodeFromString<ProviderToolOutput>(it) }
    }
    override suspend fun save(output: ProviderToolOutput) = lock.withLock {
        val key = key(output.ref.runId, output.ref.attempt)
        val previous = store.read(key)?.let { json.decodeFromString<ProviderToolOutput>(it) }
        check(previous == null || previous == output) { "Сохранённый ответ уже принадлежит другой попытке" }
        if (previous == null) store.write(key, json.encodeToString(output))
    }
}

internal suspend fun providerOutputDigest(runId: String, attempt: String, identity: String, text: String) =
    toolArgumentsFingerprint(buildJsonObject {
        put("runId", runId); put("attempt", attempt); put("identity", identity); put("text", text)
    })
