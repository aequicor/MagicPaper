package io.aequicor.magicpaper.ui

import io.aequicor.magicpaper.domain.PluginState
import io.aequicor.magicpaper.plugins.PluginPreferences

/** Settings tests observe the command boundary; plugin journal durability has its own owner tests. */
internal class TestPluginPreferences : PluginPreferences {
    private var preferences = emptyList<PluginState>()
    override suspend fun exportPreferences() = preferences
    override suspend fun importPreferences(preferences: List<PluginState>) { this.preferences = preferences.toList() }
    override suspend fun clearPreferences() { preferences = emptyList() }
}
