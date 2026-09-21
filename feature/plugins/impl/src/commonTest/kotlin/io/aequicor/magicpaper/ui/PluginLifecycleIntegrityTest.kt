package io.aequicor.magicpaper.ui

import androidx.compose.runtime.Composable
import io.aequicor.magicpaper.data.storage.EventJournal
import io.aequicor.magicpaper.data.storage.InMemoryEventJournal
import io.aequicor.magicpaper.data.storage.InMemoryKeyValueStore
import io.aequicor.magicpaper.data.storage.JournalRecord
import io.aequicor.magicpaper.data.storage.JournalRevision
import io.aequicor.magicpaper.domain.PluginState
import io.aequicor.magicpaper.plugins.MagicPlugin
import io.aequicor.magicpaper.plugins.PersistentPlugin
import io.aequicor.magicpaper.plugins.PluginInputJournal
import io.aequicor.magicpaper.plugins.PluginRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PluginLifecycleIntegrityTest {
    @Test fun cancelledCloseWaitsForAdmittedWriteAndFlushesEveryPlugin() = runTest {
        val store = InMemoryKeyValueStore()
        val delegate = InMemoryEventJournal()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var blockWrite = false
        val journal = object : EventJournal by delegate {
            override suspend fun append(expected: JournalRevision, operation: String, at: Long, detail: String): JournalRecord? {
                if (blockWrite) {
                    entered.complete(Unit)
                    release.await()
                }
                return delegate.append(expected, operation, at, detail)
            }
        }
        val flushed = mutableListOf<String>()
        val first = TestPlugin("first") { flushed += "first"; error("Failed first draft flush") }
        val second = TestPlugin("second") { flushed += "second" }
        val registry = PluginRegistry().register(first).register(second)
        val dispatcher = StandardTestDispatcher(testScheduler)
        val service = DefaultPluginService(registry, store, journal, Json, dispatcher, dispatcher)
        service.start()
        blockWrite = true
        service.togglePlugin("first", false)
        entered.await()
        val observed = CompletableDeferred<Throwable>()
        val closing = launch {
            try { service.close() }
            catch (failure: Throwable) { observed.complete(failure) }
        }
        runCurrent()
        closing.cancel(CancellationException("Caller cancelled close"))
        runCurrent()
        assertFalse(closing.isCompleted)
        assertTrue(flushed.isEmpty())
        release.complete(Unit)
        closing.join()
        val failure = assertIs<CancellationException>(observed.await())
        assertTrue(generateSequence<Throwable>(failure) { it.cause }.any { cause ->
            cause.suppressedExceptions.any { it is IllegalStateException }
        })
        assertEquals(listOf("first", "second"), flushed)
        assertFalse(service.state.value.pluginStates.getValue("first").enabled)
        assertEquals(2, delegate.read(PluginInputJournal.STREAM).size)
        service.togglePlugin("first", true)
        runCurrent()
        assertEquals(2, delegate.read(PluginInputJournal.STREAM).size)
    }

    @Test fun invalidImportRetainsPreferencesAndDoesNotFenceSubsequentValidCommands() = runTest {
        val store = InMemoryKeyValueStore()
        val journal = InMemoryEventJournal()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val service = DefaultPluginService(PluginRegistry().register(TestPlugin("a")),
            store, journal, Json, dispatcher, dispatcher)
        service.start()
        service.importPreferences(listOf(PluginState("a", false, mapOf("config" to "keep"))))
        val saved = service.exportPreferences()
        val records = journal.read(PluginInputJournal.STREAM)
        for (invalid in listOf(listOf(PluginState("")), listOf(PluginState("a"), PluginState("a", false)))) {
            assertFails { service.importPreferences(invalid) }
            assertNotNull(service.state.value.error)
            assertEquals(saved, service.exportPreferences())
            assertEquals(records, journal.read(PluginInputJournal.STREAM))
        }
        service.togglePlugin("a", true)
        val changed = service.exportPreferences().single()
        assertTrue(changed.enabled)
        assertEquals(mapOf("config" to "keep"), changed.config)
        assertNull(service.state.value.error)
        assertEquals(records.size + 1, journal.read(PluginInputJournal.STREAM).size)
        service.close()
    }

    private class TestPlugin(override val id: String, private val flush: suspend () -> Unit = {}) : MagicPlugin, PersistentPlugin {
        override val title = id
        override val description = id
        override val icon = ""
        @Composable override fun Content() = Unit
        override suspend fun flushDrafts() = flush()
    }
}
