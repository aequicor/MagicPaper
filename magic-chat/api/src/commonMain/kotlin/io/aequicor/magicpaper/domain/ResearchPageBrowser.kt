package io.aequicor.magicpaper.domain

/** Explicit user-assisted reading. Implementations own an isolated, visible browser,
 * never the user's personal browser profile. Opening must not import content. */
interface ResearchPageBrowser {
    suspend fun open(url: String): ResearchBrowserPage
}

interface ResearchBrowserPage {
    /** Returns visible page text after the user requests reading, or a safe failure. */
    suspend fun read(): ResearchBrowserContent
    suspend fun close()
}

data class ResearchBrowserContent(val url: String, val text: String)

/** Application-owned copy only; do not construct this from a native exception message. */
class ResearchBrowserUnavailable(val reason: String, val requiresReopen: Boolean = false) : IllegalStateException(reason)
