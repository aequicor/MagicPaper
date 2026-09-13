package io.aequicor.magicpaper.domain

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import io.aequicor.magicpaper.util.Id

interface SettingsRepository {
    suspend fun load(): AppSettings
    suspend fun save(settings: AppSettings)
    suspend fun pluginStates(): List<PluginState>
    suspend fun savePluginStates(states: List<PluginState>)
    suspend fun wipe()
}