package io.aequicor.magicpaper.navigation

import io.aequicor.magicpaper.logging.AppLog

interface BrowserJournalLease { fun close() }

/** A document owns its journal until unload; duplicate tabs fork a different durable key. */
data class BrowserNavigationSession(
    val journalKey: String,
    val restoreFromKey: String?,
    val initialPath: String?,
    private val lease: BrowserJournalLease,
) {
    fun close() = lease.close()
}

internal suspend fun claimBrowserNavigationSession(
    existingKey: String?,
    restoreHint: String?,
    initialPath: String?,
    acquire: suspend (String) -> BrowserJournalLease?,
    nextKey: () -> String = { "web-${newNavigationId()}" },
): BrowserNavigationSession {
    var candidate = existingKey ?: nextKey()
    repeat(4) {
        val lease = acquire(candidate)
        if (lease != null) {
            AppLog.info("browser_navigation", "journal_acquired", mapOf("strategy" to if (candidate == existingKey) "reuse" else "fork"))
            return BrowserNavigationSession(candidate,
            restoreFromKey = if (candidate == existingKey) null else existingKey ?: restoreHint,
            initialPath = initialPath, lease = lease)
        }
        AppLog.debug("browser_navigation", "journal_busy", mapOf("strategy" to "fork", "attempt" to (it + 1).toString()))
        candidate = nextKey()
    }
    error("Не удалось открыть историю окна.")
}
