package io.aequicor.magicpaper.data.coding

import io.aequicor.magicpaper.data.storage.InMemoryKeyValueStore
import io.aequicor.magicpaper.data.storage.KeyValueStore
import io.aequicor.magicpaper.data.storage.StorageException
import io.aequicor.magicpaper.domain.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.*

class PersistedCodingModelCatalogTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val qwen = CodingModel("qwen-token-plan", "qwen3.8-max", levels = listOf("low", "medium", "xhigh"), defaultLevel = "medium")
    private val gpt = CodingModel("openai", "gpt-5", levels = listOf("low", "high"))

    private fun catalog(
        store: KeyValueStore = InMemoryKeyValueStore(),
        clock: () -> Long = { 100 },
        vararg sources: Pair<CodingEngine, CodingModelSource>,
    ) = PersistedCodingModelCatalog(store, json, sources.toMap(), clock)

    @Test fun refreshPublishesAndStampsTheSnapshot() = runTest {
        val catalog = catalog(clock = { 42 }, sources = arrayOf(CodingEngine.PI to CodingModelSource { listOf(qwen) }))
        val result = catalog.refresh(CodingEngine.PI) as CodingModelRefresh.Refreshed
        assertEquals(CodingModelSnapshot(CodingEngine.PI, listOf(qwen), 42), result.snapshot)
        assertEquals(result.snapshot, catalog.snapshots.value.getValue(CodingEngine.PI))
    }

    @Test fun snapshotSurvivesRestartWithoutPollingTheEngine() = runTest {
        val store = InMemoryKeyValueStore()
        val first = catalog(store, sources = arrayOf(
            CodingEngine.CODEX to CodingModelSource { listOf(gpt) },
            CodingEngine.PI to CodingModelSource { listOf(qwen) },
        ))
        first.refresh(CodingEngine.CODEX)
        first.refresh(CodingEngine.PI)
        val restarted = catalog(store)
        assertEquals(listOf(gpt), restarted.snapshots.value.getValue(CodingEngine.CODEX).models)
        assertEquals(listOf(qwen), restarted.snapshots.value.getValue(CodingEngine.PI).models)
    }

    @Test fun refreshingOneEngineLeavesTheOthersCached() = runTest {
        val store = InMemoryKeyValueStore()
        val first = catalog(store, sources = arrayOf(
            CodingEngine.CODEX to CodingModelSource { listOf(gpt) },
            CodingEngine.PI to CodingModelSource { listOf(qwen) },
        ))
        first.refresh(CodingEngine.CODEX)
        catalog(store, sources = arrayOf(CodingEngine.PI to CodingModelSource { listOf(qwen) })).refresh(CodingEngine.PI)
        assertEquals(setOf(CodingEngine.CODEX, CodingEngine.PI), catalog(store).snapshots.value.keys)
    }

    @Test fun platformWithoutEngineServesTheCacheAndRefreshReportsUnavailable() = runTest {
        val store = InMemoryKeyValueStore()
        catalog(store, sources = arrayOf(CodingEngine.PI to CodingModelSource { listOf(qwen) })).refresh(CodingEngine.PI)
        val cacheOnly = catalog(store)
        val result = cacheOnly.refresh(CodingEngine.PI) as CodingModelRefresh.Failed
        assertEquals(CodingModelRefreshFailure.UNAVAILABLE, result.reason)
        assertEquals(listOf(qwen), result.retained?.models)
    }

    @Test fun failedRefreshKeepsThePreviousSnapshot() = runTest {
        var fail = false
        val catalog = catalog(sources = arrayOf(CodingEngine.PI to CodingModelSource { if (fail) error("offline") else listOf(qwen) }))
        val first = (catalog.refresh(CodingEngine.PI) as CodingModelRefresh.Refreshed).snapshot
        fail = true
        val result = catalog.refresh(CodingEngine.PI) as CodingModelRefresh.Failed
        assertEquals(CodingModelRefreshFailure.FAILED, result.reason)
        assertEquals(first, result.retained)
        assertEquals(first, catalog.snapshots.value.getValue(CodingEngine.PI))
    }

    @Test fun emptyAnswerIsAFailureAndDoesNotEraseTheCatalog() = runTest {
        var models = listOf(qwen)
        val catalog = catalog(sources = arrayOf(CodingEngine.PI to CodingModelSource { models }))
        catalog.refresh(CodingEngine.PI)
        models = emptyList()
        val result = catalog.refresh(CodingEngine.PI) as CodingModelRefresh.Failed
        assertEquals(CodingModelRefreshFailure.EMPTY, result.reason)
        assertEquals(listOf(qwen), catalog.snapshots.value.getValue(CodingEngine.PI).models)
    }

    @Test fun cancellationIsControlFlowNotAFailure() = runTest {
        val catalog = catalog(sources = arrayOf(CodingEngine.PI to CodingModelSource { throw CancellationException("stop") }))
        assertFailsWith<CancellationException> { catalog.refresh(CodingEngine.PI) }
        assertNull(catalog.snapshots.value[CodingEngine.PI])
    }

    @Test fun corruptCacheIsIgnoredAndReplacedByTheNextRefresh() = runTest {
        val store = InMemoryKeyValueStore()
        store.write("coding-model-catalog", "{not json")
        val catalog = catalog(store, sources = arrayOf(CodingEngine.PI to CodingModelSource { listOf(qwen) }))
        assertNull(catalog.snapshots.value[CodingEngine.PI])
        assertEquals("{not json", store.read("coding-model-catalog"))
        catalog.refresh(CodingEngine.PI)
        assertEquals(listOf(qwen), catalog(store).snapshots.value.getValue(CodingEngine.PI).models)
    }

    @Test fun cacheWriteFailureDoesNotDiscardTheFetchedCatalog() = runTest {
        val broken = object : KeyValueStore by InMemoryKeyValueStore() {
            override fun write(key: String, value: String) = throw StorageException("write", StorageException.Kind.WRITE)
        }
        val catalog = catalog(broken, sources = arrayOf(CodingEngine.PI to CodingModelSource { listOf(qwen) }))
        assertIs<CodingModelRefresh.Refreshed>(catalog.refresh(CodingEngine.PI))
        assertEquals(listOf(qwen), catalog.snapshots.value.getValue(CodingEngine.PI).models)
    }
}
