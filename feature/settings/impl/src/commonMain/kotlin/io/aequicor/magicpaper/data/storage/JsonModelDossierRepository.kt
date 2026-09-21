package io.aequicor.magicpaper.data.storage

import io.aequicor.magicpaper.domain.ModelDossier
import io.aequicor.magicpaper.domain.ModelDossierRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** Retains the existing storage key and serialized type while moving the owner. */
class JsonModelDossierRepository(private val store: KeyValueStore, private val json: Json) : ModelDossierRepository {
    private val mutex = Mutex()
    private val serializer = ListSerializer(ModelDossier.serializer())
    private fun read(): List<ModelDossier> {
        val raw = store.read(KEY) ?: return emptyList()
        return try { json.decodeFromString(serializer, raw) }
        catch (failure: SerializationException) { throw StorageException("read model dossiers", StorageException.Kind.CORRUPT, failure) }
    }
    override suspend fun dossiers(): List<ModelDossier> = mutex.withLock { read() }
    suspend fun saveDossier(dossier: ModelDossier) = mutex.withLock {
        val values = read().filterNot { it.profileId == dossier.profileId && it.modelId == dossier.modelId } + dossier
        store.write(KEY, json.encodeToString(serializer, values))
    }
    suspend fun clearDossiers() = mutex.withLock { store.delete(KEY) }
    private companion object { const val KEY = "model-dossiers" }
}
