package io.aequicor.magicpaper.domain

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import io.aequicor.magicpaper.util.Id

interface SearchEngine {
    val provider: SearchProvider
    val displayName: String
    fun isConfigured(settings: AppSettings): Boolean
    suspend fun search(query: String, settings: AppSettings, limit: Int = 5): List<SearchHit>
    suspend fun searchWithDiagnostics(query: String, settings: AppSettings, limit: Int = 5): SearchResult =
        if (isConfigured(settings)) SearchResult(search(query, settings, limit))
        else SearchResult(issues = listOf("$displayName: подключение не настроено."))
}