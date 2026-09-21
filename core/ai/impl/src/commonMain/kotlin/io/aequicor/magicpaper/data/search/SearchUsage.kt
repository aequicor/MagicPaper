package io.aequicor.magicpaper.data.search

import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.util.Id
import kotlinx.coroutines.*

internal suspend fun <T> measuredSearch(ledger: UsageLedger?, provider: String, kind: UsageKind = UsageKind.SEARCH,
    pages: Long = 0, contentRequests: Long = if (kind == UsageKind.CONTENT) 1 else 0, block: suspend () -> T): T {
    if (ledger == null) return block()
    val observation = ledger.captureObservation()
    val record = UsageRecord(Id.new(), scope = currentCoroutineContext()[UsageOwner]?.scope ?: UsageScope(),
        provider = provider, kind = kind, pages = pages, contentRequests = contentRequests)
    ledger.record(observation, record)
    var completed = false
    var primary: Throwable? = null
    try { return block().also { completed = true } }
    catch (failure: Throwable) { primary = failure; throw failure }
    finally { withContext(NonCancellable) {
        try { ledger.record(observation, record.copy(completed = completed)) }
        catch (cleanup: Throwable) {
            val original = primary
            if (original == null) throw cleanup
            if (original !== cleanup) original.addSuppressed(cleanup)
        }
    } }
}
