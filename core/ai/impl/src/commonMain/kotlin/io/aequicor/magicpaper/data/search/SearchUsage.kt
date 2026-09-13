package io.aequicor.magicpaper.data.search

import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.util.Id
import kotlinx.coroutines.*

internal suspend fun <T> measuredSearch(ledger: UsageLedger?, provider: String, kind: UsageKind = UsageKind.SEARCH,
    pages: Long = 0, contentRequests: Long = if (kind == UsageKind.CONTENT) 1 else 0, block: suspend () -> T): T {
    if (ledger == null) return block()
    val record = UsageRecord(Id.new(), scope = currentCoroutineContext()[UsageOwner]?.scope ?: UsageScope(),
        provider = provider, kind = kind, pages = pages, contentRequests = contentRequests)
    ledger.record(record)
    var completed = false
    try { return block().also { completed = true } }
    finally { withContext(NonCancellable) { ledger.record(record.copy(completed = completed)) } }
}
