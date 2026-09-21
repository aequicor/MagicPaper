package io.aequicor.magicpaper.ui

import androidx.compose.runtime.Composable
import io.aequicor.magicpaper.data.storage.*
import io.aequicor.magicpaper.domain.PluginState
import io.aequicor.magicpaper.plugins.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.test.*

class PluginServiceTest {
    private val json = Json { encodeDefaults = true }
    private fun plugin(name: String) = object : MagicPlugin {
        override val id = name
        override val title = name
        override val description = name
        override val icon = ""
        @Composable override fun Content() = Unit
    }
    private class Journal(val delegate: EventJournal = InMemoryEventJournal()) : EventJournal by delegate {
        var fail = false
        var loseAcknowledgement = false
        var cancelNext = false
        override suspend fun append(expected: JournalRevision, operation: String, at: Long, detail: String): JournalRecord? {
            if (cancelNext) { cancelNext = false; throw CancellationException("cancelled write") }
            check(!fail) { "private journal failure" }
            val saved = delegate.append(expected, operation, at, detail)
            if (loseAcknowledgement) { loseAcknowledgement = false; error("private lost acknowledgement") }
            return saved
        }
    }
    private fun TestScope.service(store: KeyValueStore, journal: EventJournal, registry: PluginRegistry = PluginRegistry().register(plugin("a"))) =
        DefaultPluginService(registry, store, journal, json, StandardTestDispatcher(testScheduler), StandardTestDispatcher(testScheduler))
    private fun legacy(store: KeyValueStore, vararg states: PluginState) = store.write("plugins", json.encodeToString(ListSerializer(PluginState.serializer()), states.toList()))

    @Test fun restartPreservesDisabledStatePrivateConfigUnknownIdsAndRegistryOrder() = runTest {
        val store = InMemoryKeyValueStore()
        val journal = Journal()
        legacy(store, PluginState("missing", false, mapOf("data" to "private-value")), PluginState("b", true, mapOf("key" to "value")))
        val registry = PluginRegistry().register(plugin("b")).register(plugin("a"))
        val service = service(store, journal, registry)
        service.start(); service.togglePlugin("b", false); service.togglePlugin("a", false); service.close()
        val restored = service(store, journal, registry)
        restored.start()
        assertEquals(listOf("b", "a"), restored.state.value.plugins.map { it.id })
        assertTrue(restored.state.value.pluginStates.values.none { it.enabled })
        assertEquals(mapOf("data" to "private-value"), restored.state.value.pluginStates.getValue("missing").config)
        assertTrue(journal.read(PluginInputJournal.STREAM).none { "private-value" in it.detail || "config" in it.detail })
        restored.close()
    }

    @Test fun failedPreferenceWriteRetainsCommittedSettingAndSurfacesError() = runTest {
        val store = InMemoryKeyValueStore(); val journal = Journal()
        val service = service(store, journal)
        service.start(); journal.fail = true
        service.togglePlugin("a", false); service.close()
        assertTrue(service.state.value.pluginStates.getValue("a").enabled)
        assertNotNull(service.state.value.error)
        assertFalse(service.state.value.error!!.contains("private"))
        journal.fail = false
        val restored = service(store, journal); restored.start()
        assertTrue(restored.state.value.pluginStates.getValue("a").enabled)
        restored.close()
    }

    @Test fun resetDrainsOldWritesAndReloadsFreshDefaultsBeforeAcceptingNewActions() = runTest {
        val store = InMemoryKeyValueStore(); val journal = Journal()
        legacy(store, PluginState("a", true, mapOf("old" to "config")))
        val service = service(store, journal)
        service.start(); service.togglePlugin("a", false); service.prepareForReset()
        assertFalse(service.state.value.pluginStates.getValue("a").enabled)
        service.togglePlugin("a", false)
        // Keep the old cache deliberately: the dropped journal revision must still fence it out.
        journal.drop(PluginInputJournal.STREAM)
        service.start()
        assertTrue(service.state.value.pluginStates.getValue("a").enabled)
        assertTrue(service.state.value.pluginStates.getValue("a").config.isEmpty())
        service.togglePlugin("a", false); service.close()
        val restored = service(store, journal); restored.start()
        assertFalse(restored.state.value.pluginStates.getValue("a").enabled)
        assertTrue(restored.state.value.pluginStates.getValue("a").config.isEmpty())
        restored.close()
    }

    @Test fun importUsesTheSameOwnerAndLegacySnapshotCannotOverrideIt() = runTest {
        val store = InMemoryKeyValueStore(); val journal = Journal(); val service = service(store, journal)
        service.start(); service.togglePlugin("a", true)
        service.importPreferences(listOf(PluginState("a", false, mapOf("imported" to "value")), PluginState("missing", false)))
        legacy(store, PluginState("a", true, mapOf("stale" to "snapshot")))
        service.start()
        assertFalse(service.state.value.pluginStates.getValue("a").enabled)
        assertEquals(mapOf("imported" to "value"), service.state.value.pluginStates.getValue("a").config)
        service.togglePlugin("a", true)
        assertTrue(service.exportPreferences().first { it.id == "a" }.enabled)
        assertTrue(service.exportPreferences().any { it.id == "missing" })
        service.close()
    }

    @Test fun lostJournalAcknowledgementSettlesOnlyTheExactCommittedInput() = runTest {
        val store = InMemoryKeyValueStore(); val journal = Journal(); val service = service(store, journal)
        service.start(); journal.loseAcknowledgement = true
        service.importPreferences(listOf(PluginState("a", false, mapOf("secret-config" to "private"))))
        assertNull(service.state.value.error)
        assertFalse(service.state.value.pluginStates.getValue("a").enabled)
        assertEquals(2, journal.read(PluginInputJournal.STREAM).size)
        service.close()
        val restored = service(store, journal); restored.start()
        assertEquals(mapOf("secret-config" to "private"), restored.exportPreferences().single().config)
        restored.close()
    }

    @Test fun corruptLegacyAndMissingPayloadNeverBecomeEmptyPreferences() = runTest {
        val store = InMemoryKeyValueStore(); val journal = Journal(); store.write("plugins", "{broken")
        val service = service(store, journal)
        assertFails { service.start() }
        assertEquals("{broken", store.read("plugins"))
        assertTrue(journal.read(PluginInputJournal.STREAM).isEmpty())
        assertNotNull(service.state.value.error); service.close()
        store.delete("plugins")
        val valid = service(store, journal); valid.start(); valid.importPreferences(listOf(PluginState("a", false))); valid.close()
        store.delete(store.keys(PluginInputJournal.PREFIX).last())
        val broken = service(store, journal)
        assertFails { broken.start() }
        assertFalse(broken.state.value.loaded)
        assertNotNull(broken.state.value.error); broken.close()
    }

    @Test fun oldWriterCannotRecreatePreferencesAfterResetOrOverwriteAnotherOwner() = runTest {
        val store = InMemoryKeyValueStore(); val journal = Journal()
        val old = service(store, journal); old.start()
        val current = service(store, journal); current.start()
        current.importPreferences(listOf(PluginState("a", false, mapOf("new" to "keep"))))
        old.togglePlugin("a", true); old.close()
        assertNotNull(old.state.value.error)
        val restored = service(store, journal); restored.start()
        assertFalse(restored.state.value.pluginStates.getValue("a").enabled)
        assertEquals(mapOf("new" to "keep"), restored.state.value.pluginStates.getValue("a").config)
        journal.drop(PluginInputJournal.STREAM)
        current.togglePlugin("a", true); current.close()
        assertNotNull(current.state.value.error)
        restored.close()
        val fresh = service(store, journal); fresh.start()
        assertTrue(fresh.state.value.pluginStates.getValue("a").enabled)
        assertTrue(fresh.state.value.pluginStates.getValue("a").config.isEmpty()); fresh.close()
    }

    @Test fun cancelledWriterPublishesUnknownAndBarriersFailWithoutHangingUntilReload() = runTest {
        val store = InMemoryKeyValueStore(); val journal = Journal(); val service = service(store, journal)
        service.start(); journal.cancelNext = true
        service.togglePlugin("a", false)
        runCurrent()
        assertNotNull(service.state.value.error)
        assertTrue(service.state.value.pluginStates.getValue("a").enabled)
        withTimeout(1000) { assertFails { service.exportPreferences() } }
        service.start()
        assertNull(service.state.value.error)
        service.togglePlugin("a", false)
        assertFalse(service.exportPreferences().single().enabled)
        service.close()
    }

    @Test fun absentNeighbourIsAVisibleFactAndClearDoesNotReviveLegacyConfig() = runTest {
        val store = InMemoryKeyValueStore(); val journal = Journal(); val service = service(store, journal)
        legacy(store, PluginState("missing", false, mapOf("old" to "keep")))
        service.start(); service.togglePlugin("missing", true)
        assertFalse(service.exportPreferences().first { it.id == "missing" }.enabled)
        assertNotNull(service.state.value.error)
        service.clearPreferences(); assertTrue(service.state.value.pluginStates.values.all { it.enabled && it.config.isEmpty() })
        service.close()
        val restored = service(store, journal); restored.start()
        assertFalse("missing" in restored.state.value.pluginStates); restored.close()
    }
}
