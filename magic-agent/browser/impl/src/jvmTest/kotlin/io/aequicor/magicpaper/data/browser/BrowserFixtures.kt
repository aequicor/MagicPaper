package io.aequicor.magicpaper.data.browser

import com.microsoft.playwright.Browser
import com.microsoft.playwright.Playwright
import io.aequicor.magicpaper.data.storage.EventJournal
import io.aequicor.magicpaper.data.storage.InMemoryEventJournal
import io.aequicor.magicpaper.domain.CodingInteractionMode
import io.aequicor.magicpaper.domain.browser.BrowserMachine
import io.aequicor.magicpaper.domain.browser.BrowserSession
import io.aequicor.magicpaper.domain.tools.*

internal fun testBrowser(journal: EventJournal = InMemoryEventJournal(), owner: BrowserMachine.Owner = BrowserMachine.Owner("s", "r"),
    launch: (Playwright) -> Browser = { it.chromium().launch() }, createPlaywright: () -> Playwright = { Playwright.create() },
    availability: BrowserAvailability = BrowserAvailability()) = BrowserToolSession(journal, owner, launch, createPlaywright, availability)

internal fun testBrowserTools(receipts: ToolReceiptStore, checkScope: suspend (ToolExecutionContext) -> Unit = {}): ToolSession {
    val registry = ToolRegistry(emptyList())
    return DefaultToolSession(ToolExecutionContext("p", "s", "s", "r", ToolRole.CHAT, CodingInteractionMode.CODE),
        registry, ToolExecutor(registry, receipts, checkScope = checkScope))
}

internal fun attachBrowser(tools: ToolSession, browser: BrowserSession): AutoCloseable {
    val detach = tools.attachNativeCommands(browser.commands)
    return AutoCloseable { try { browser.close() } finally { detach() } }
}
