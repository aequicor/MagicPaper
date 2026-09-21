package io.aequicor.magicpaper.data.browser

import io.aequicor.magicpaper.data.storage.EventJournal
import io.aequicor.magicpaper.domain.browser.BrowserMachine
import io.aequicor.magicpaper.domain.browser.BrowserSession
import io.aequicor.magicpaper.domain.browser.BrowserSessions

/** The composition root supplies durable storage; production has no in-memory fallback. */
fun createDesktopBrowserSessions(journal: EventJournal): BrowserSessions = DesktopBrowserSessions(journal)

private class DesktopBrowserSessions(private val journal: EventJournal) : BrowserSessions {
    private val availability = BrowserAvailability()
    override val available: Boolean get() = availability.available
    override fun create(ownerSessionId: String, requestId: String): BrowserSession = BrowserToolSession(
        journal, BrowserMachine.Owner(ownerSessionId, requestId), availability = availability)
}
