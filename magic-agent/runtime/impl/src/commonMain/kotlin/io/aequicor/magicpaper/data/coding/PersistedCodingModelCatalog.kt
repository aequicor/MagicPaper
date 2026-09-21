package io.aequicor.magicpaper.data.coding

import io.aequicor.magicpaper.data.storage.KeyValueStore
import io.aequicor.magicpaper.data.storage.StorageException
import io.aequicor.magicpaper.data.storage.logPersistenceFailure
import io.aequicor.magicpaper.domain.CodingEngine
import io.aequicor.magicpaper.domain.CodingModelCatalog
import io.aequicor.magicpaper.domain.CodingModelRefresh
import io.aequicor.magicpaper.domain.CodingModelRefreshFailure
import io.aequicor.magicpaper.domain.CodingModelSnapshot
import io.aequicor.magicpaper.domain.CodingModelSource
import io.aequicor.magicpaper.logging.AppLog
import io.aequicor.magicpaper.util.Id
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Каталог моделей движков с дисковым кэшем последних удачных снимков.
 *
 * Кэш производный: движок остаётся источником истины, поэтому нечитаемая или повреждённая
 * запись не роняет запуск, а считается отсутствующей и логируется. Заменяет её только
 * успешный [refresh]. Платформа без процесса движка (веб, Android) получает пустой [sources]
 * и работает по кэшу. Набор движков определяют [sources] и сам кэш, а не перечисление
 * `CodingEngine`: установленные бэкенды известны только сборке приложения.
 */
class PersistedCodingModelCatalog(
    private val store: KeyValueStore,
    private val json: Json,
    private val sources: Map<CodingEngine, CodingModelSource>,
    private val now: () -> Long = Id::now,
) : CodingModelCatalog {
    private val state = MutableStateFlow(readCache())
    private val writes = Mutex()

    override val snapshots: StateFlow<Map<CodingEngine, CodingModelSnapshot>> = state.asStateFlow()

    /** Опрос идёт без блокировки: два одновременных опроса одного движка безвредны, побеждает поздний. */
    override suspend fun refresh(engine: CodingEngine): CodingModelRefresh {
        val source = sources[engine] ?: return failed(engine, CodingModelRefreshFailure.UNAVAILABLE)
        val models = try {
            source.fetch()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            AppLog.error("coding.models", "refresh.failed", failure, mapOf("engine" to engine.name))
            return failed(engine, CodingModelRefreshFailure.FAILED)
        }
        if (models.isEmpty()) {
            AppLog.info("coding.models", "refresh.empty", mapOf("engine" to engine.name))
            return failed(engine, CodingModelRefreshFailure.EMPTY)
        }
        val snapshot = CodingModelSnapshot(engine, models, now())
        state.update { it + (engine to snapshot) }
        persist()
        AppLog.info("coding.models", "refresh.done", mapOf("engine" to engine.name, "models" to models.size.toString()))
        return CodingModelRefresh.Refreshed(snapshot)
    }

    private fun failed(engine: CodingEngine, reason: CodingModelRefreshFailure) =
        CodingModelRefresh.Failed(engine, reason, state.value[engine])

    /**
     * Пишет актуальное состояние целиком. Сбой записи не отменяет полученный каталог: он уже
     * опубликован, а следующий запуск опросит движок заново.
     */
    private suspend fun persist() = writes.withLock {
        try {
            store.write(KEY, json.encodeToString(SERIALIZER, state.value.values.toList()))
        } catch (failure: StorageException) {
            logPersistenceFailure("coding.models", "cache.write-failed", failure)
        }
    }

    private fun readCache(): Map<CodingEngine, CodingModelSnapshot> {
        val raw = try {
            store.read(KEY)
        } catch (failure: StorageException) {
            logPersistenceFailure("coding.models", "cache.read-failed", failure)
            return emptyMap()
        } ?: return emptyMap()
        return try {
            json.decodeFromString(SERIALIZER, raw).associateBy { it.engine }
        } catch (failure: SerializationException) {
            AppLog.error("coding.models", "cache.corrupt", failure)
            emptyMap()
        }
    }

    private companion object {
        const val KEY = "coding-model-catalog"
        val SERIALIZER = ListSerializer(CodingModelSnapshot.serializer())
    }
}
