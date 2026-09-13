package io.aequicor.magicpaper.domain

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import io.aequicor.magicpaper.util.Id

interface UsageRepository {
    fun load(): UsageArchive
    fun save(archive: UsageArchive)
}

interface UsageLedger {
    val state: StateFlow<UsageArchive>
    val failure: StateFlow<String?>
    suspend fun record(record: UsageRecord, replacesId: String? = null)
    suspend fun context(snapshot: ContextUsageSnapshot)
    suspend fun cumulative(key: String, fingerprint: String, total: TokenUsage, last: TokenUsage, record: UsageRecord)
    suspend fun replace(archive: UsageArchive)
    suspend fun clear()
    suspend fun <T> measure(profile: LlmProfile, block: suspend () -> T): T
}
