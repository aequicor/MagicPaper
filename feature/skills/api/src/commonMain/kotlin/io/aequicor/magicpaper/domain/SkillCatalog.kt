package io.aequicor.magicpaper.domain

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import io.aequicor.magicpaper.util.Id

interface SkillCatalog {
    suspend fun entries(): List<CatalogEntry>
    suspend fun search(query: String, limit: Int = 10): List<CatalogEntry>
}