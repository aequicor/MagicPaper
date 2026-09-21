package io.aequicor.magicpaper.domain.browser

import io.aequicor.magicpaper.domain.tools.ToolCommand

/** Application-owned installation capability; each explicit run owns an isolated browser session. */
interface BrowserSessions {
    val available: Boolean
    fun create(ownerSessionId: String, requestId: String): BrowserSession
}

/** Closing a native run detaches commands and releases its browser resources. */
interface BrowserSession : AutoCloseable {
    val commands: List<ToolCommand<*, *>>
}
