package io.aequicor.magicpaper.data.storage

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlin.random.Random

/** Persistent DTOs retain only references; domain models remain hydrated for existing consumers/export. */
internal class CredentialRecords(private val secrets: SecretStore, private val store: KeyValueStore, private val cleanupKey: String) {
    suspend fun migrate(record: JsonObject, fields: Set<String>, identity: String): JsonObject {
        val refs = references(record).toMutableMap()
        for (field in fields) {
            val legacy = (record[field] as? JsonPrimitive)?.contentOrNull ?: continue
            if (legacy.isEmpty()) continue
            // Deterministic migration refs make interrupted migration safe to retry before scrubbing.
            val reference = "legacy:$identity:$field"
            stage(setOf(reference))
            if (secrets.read(reference) != legacy) secrets.write(reference, legacy)
            if (secrets.read(reference) != legacy) throw StorageException("verify credential migration", StorageException.Kind.WRITE)
            refs[field] = JsonPrimitive(reference)
        }
        return JsonObject((record - fields - REFS) + if (refs.isEmpty()) emptyMap() else mapOf(REFS to JsonObject(refs)))
    }

    suspend fun hydrate(record: JsonObject, fields: Set<String>): JsonObject {
        val values = mutableMapOf<String, JsonPrimitive>()
        for ((field, value) in references(record)) {
            if (field !in fields) continue
            val reference = (value as? JsonPrimitive)?.contentOrNull
                ?: throw StorageException("read credential reference", StorageException.Kind.CORRUPT)
            values[field] = JsonPrimitive(secrets.read(reference)
                ?: throw StorageException("read credential", StorageException.Kind.MISSING_SECRET))
        }
        return JsonObject((record - REFS) + values)
    }

    suspend fun encode(hydrated: JsonObject, fields: Set<String>): JsonObject {
        val refs = mutableMapOf<String, JsonPrimitive>()
        for (field in fields) {
            val value = (hydrated[field] as? JsonPrimitive)?.contentOrNull.orEmpty()
            if (value.isEmpty()) continue // An imported blank explicitly clears a previous key.
            val reference = "credential:" + Random.nextBytes(24).joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
            stage(setOf(reference))
            secrets.write(reference, value)
            if (secrets.read(reference) != value) throw StorageException("verify credential", StorageException.Kind.WRITE)
            refs[field] = JsonPrimitive(reference)
        }
        return JsonObject((hydrated - fields - REFS) + if (refs.isEmpty()) emptyMap() else mapOf(REFS to JsonObject(refs)))
    }

    /** Only opaque references are journaled; staging precedes deleting/replacing ordinary records. */
    fun stageCleanup(previous: List<JsonObject>) {
        stage(previous.flatMap { references(it).values }.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.toSet())
    }

    suspend fun clean(next: List<JsonObject>) {
        try { cleanPending(next) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { throw StorageException("clean credential references", StorageException.Kind.CLEANUP, failure, committed = true) }
    }

    private suspend fun cleanPending(next: List<JsonObject>) {
        val retained = next.flatMap { references(it).values }.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.toSet()
        val pending = pending().toMutableSet()
        var firstFailure: Exception? = null
        for (key in pending.toList()) {
            try {
                if (key !in retained) secrets.delete(key)
                pending -= key
            } catch (error: CancellationException) { throw error }
            catch (failure: Exception) { if (firstFailure == null) firstFailure = failure else firstFailure.addSuppressed(failure) }
        }
        try { savePending(pending) }
        catch (error: CancellationException) { throw error }
        catch (failure: Exception) { if (firstFailure == null) firstFailure = failure else firstFailure.addSuppressed(failure) }
        firstFailure?.let { throw it }
    }

    private fun pending(): Set<String> = try {
        store.read(cleanupKey)?.let { Json.decodeFromString<Set<String>>(it) }.orEmpty()
    } catch (error: CancellationException) { throw error }
    catch (failure: Exception) { throw StorageException("read credential cleanup", StorageException.Kind.CORRUPT, failure) }

    private fun stage(references: Set<String>) {
        if (references.isNotEmpty()) savePending(pending() + references)
    }

    private fun savePending(references: Set<String>) {
        if (references.isEmpty()) store.delete(cleanupKey)
        else store.write(cleanupKey, Json.encodeToString(references))
    }

    private fun references(record: JsonObject): Map<String, kotlinx.serialization.json.JsonElement> {
        val value = record[REFS] ?: return emptyMap()
        return value as? JsonObject ?: throw StorageException("read credential references", StorageException.Kind.CORRUPT)
    }

    private companion object { const val REFS = "secretReferences" }
}

internal fun explicitOrTestSecrets(store: KeyValueStore): SecretStore =
    (store as? InMemoryKeyValueStore)?.secrets ?: error("Persistent repositories require an explicit SecretStore")
