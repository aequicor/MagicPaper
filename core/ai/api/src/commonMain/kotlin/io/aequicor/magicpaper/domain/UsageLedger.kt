package io.aequicor.magicpaper.domain

import kotlinx.coroutines.flow.StateFlow

/** Legacy archive query. New writes belong exclusively to the ledger's input journal. */
interface UsageRepository { fun load(): UsageArchive }

interface UsageLedger {
    val state: StateFlow<UsageArchive>
    val failure: StateFlow<String?>
    suspend fun start()
    suspend fun captureObservation(): UsageObservation
    suspend fun record(observation: UsageObservation, record: UsageRecord, replacesId: String? = null)
    suspend fun context(observation: UsageObservation, snapshot: ContextUsageSnapshot)
    suspend fun cumulative(observation: UsageObservation, key: String, fingerprint: String, total: TokenUsage, last: TokenUsage, record: UsageRecord)
    suspend fun exportArchive(): UsageArchive
    suspend fun replace(archive: UsageArchive)
    suspend fun clear()
    suspend fun <T> measure(profile: LlmProfile, block: suspend () -> T): T
}
