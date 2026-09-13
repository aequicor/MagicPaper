package io.aequicor.magicpaper.ui

import androidx.compose.runtime.Composable
import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.plugins.*
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class PluginServiceTest {
    private class Settings(var plugins: List<PluginState>) : SettingsRepository {
        var fail = false
        override suspend fun load() = AppSettings()
        override suspend fun save(settings: AppSettings) = Unit
        override suspend fun pluginStates() = plugins
        override suspend fun savePluginStates(states: List<PluginState>) { check(!fail); plugins = states }
        override suspend fun wipe() { plugins = emptyList() }
    }
    private fun plugin(name: String) = object : MagicPlugin {
        override val id = name
        override val title = name
        override val description = name
        override val icon = ""
        @Composable override fun Content() = Unit
    }
    @Test fun restartPreservesDisabledStatePrivateConfigUnknownIdsAndRegistryOrder() = runTest {
        val settings = Settings(listOf(PluginState("missing", false, mapOf("data" to "keep")), PluginState("b", true, mapOf("key" to "value"))))
        val registry = PluginRegistry().register(plugin("b")).register(plugin("a"))
        val service = DefaultPluginService(registry, settings, StandardTestDispatcher(testScheduler))
        service.start()
        service.togglePlugin("b", false)
        service.togglePlugin("a", false)
        service.close()
        assertEquals(listOf("missing", "b", "a"), settings.plugins.map { it.id })
        assertEquals(mapOf("key" to "value"), settings.plugins[1].config)
        val restored = DefaultPluginService(registry, settings, StandardTestDispatcher(testScheduler))
        restored.start()
        assertEquals(listOf("b", "a"), restored.state.value.plugins.map { it.id })
        assertTrue(restored.state.value.pluginStates.values.none { it.enabled })
        restored.close()
    }
    @Test fun failedPreferenceWriteRetainsCommittedSettingAndSurfacesError() = runTest {
        val settings = Settings(listOf(PluginState("a"))).apply { fail = true }
        val service = DefaultPluginService(PluginRegistry().register(plugin("a")), settings, StandardTestDispatcher(testScheduler))
        service.start(); service.togglePlugin("a", false); service.close()
        assertTrue(service.state.value.pluginStates.getValue("a").enabled)
        assertNotNull(service.state.value.error)
    }
    @Test fun resetDrainsOldWritesAndReloadsFreshDefaultsBeforeAcceptingNewActions() = runTest {
        val settings = Settings(listOf(PluginState("a", true, mapOf("old" to "config"))))
        val service = DefaultPluginService(PluginRegistry().register(plugin("a")), settings, StandardTestDispatcher(testScheduler))
        service.start()
        service.togglePlugin("a", false)
        service.prepareForReset()
        assertFalse(settings.plugins.single().enabled)
        service.togglePlugin("a", false) // An action from the old screen cannot cross the reset.
        settings.wipe()
        service.start()
        assertTrue(service.state.value.pluginStates.getValue("a").enabled)
        assertTrue(service.state.value.pluginStates.getValue("a").config.isEmpty())
        service.togglePlugin("a", false)
        service.close()
        assertFalse(settings.plugins.single().enabled)
        assertTrue(settings.plugins.single().config.isEmpty())
    }

    @Test fun reloadAppliesImportedPreferencesWithTheExistingWriterOwner() = runTest {
        val settings = Settings(listOf(PluginState("a")))
        val service = DefaultPluginService(PluginRegistry().register(plugin("a")), settings, StandardTestDispatcher(testScheduler))
        service.start()
        settings.plugins = listOf(PluginState("a", false, mapOf("imported" to "value")))
        service.start()
        assertFalse(service.state.value.pluginStates.getValue("a").enabled)
        assertEquals(mapOf("imported" to "value"), service.state.value.pluginStates.getValue("a").config)
        service.togglePlugin("a", true)
        service.close()
        assertTrue(settings.plugins.single().enabled)
        assertEquals(mapOf("imported" to "value"), settings.plugins.single().config)
    }

}
