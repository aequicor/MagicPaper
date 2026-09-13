package io.aequicor.magicpaper.domain

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import io.aequicor.magicpaper.util.Id

interface LlmProfileRepository {
    suspend fun load(): List<LlmProfile>
    suspend fun save(profile: LlmProfile)
    suspend fun delete(id: String)
    suspend fun replaceAll(profiles: List<LlmProfile>)
}