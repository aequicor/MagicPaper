@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package io.aequicor.magicpaper.navigation

import io.aequicor.magicpaper.logging.AppLog
import kotlinx.browser.localStorage
import kotlinx.browser.sessionStorage
import kotlinx.browser.window
import kotlinx.serialization.json.Json
import kotlinx.coroutines.suspendCancellableCoroutine
import org.w3c.dom.events.Event
import kotlin.js.JsAny
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

suspend fun browserNavigationSession(basePath: String = ""): BrowserNavigationSession {
    val json = Json { ignoreUnknownKeys = true }
    val entry = navigationString(window.history.state)?.let {
        try { json.decodeFromString<BrowserVisitEntry>(it) }
        catch (_: kotlinx.serialization.SerializationException) {
            AppLog.debug("browser_navigation", "entry_ignored", mapOf("reason" to "foreign_history"))
            null
        }
    }
        ?.takeIf { it.version == 1 }
    val path = window.location.pathname.removePrefix(basePath.trimEnd('/'))
    val session = claimBrowserNavigationSession(entry?.journalKey,
        restoreHint = sessionStorage.getItem(TAB_KEY) ?: localStorage.getItem(LAST_KEY),
        initialPath = if (entry != null || path == "/" || path.isEmpty()) null else path,
        acquire = ::acquireBrowserJournalLease)
    try {
        sessionStorage.setItem(TAB_KEY, session.journalKey)
        localStorage.setItem(LAST_KEY, session.journalKey)
        return session
    } catch (failure: Throwable) {
        session.close()
        throw failure
    }
}

private suspend fun acquireBrowserJournalLease(key: String): BrowserJournalLease? = suspendCancellableCoroutine { continuation ->
    var release: () -> Unit = {}
    continuation.invokeOnCancellation { release() }
    release = requestBrowserJournalLease("magicpaper:journal:$key") { result ->
        if (!continuation.isActive) { release(); return@requestBrowserJournalLease }
        when (result) {
            1 -> continuation.resume(object : BrowserJournalLease { override fun close() = release() })
            0 -> continuation.resume(null)
            else -> continuation.resumeWithException(IllegalStateException("Не удалось открыть историю окна."))
        }
    }
    if (continuation.isCancelled) release()
}

class WindowBrowserHistoryPort : BrowserHistoryPort {
    private var listener: ((Event) -> Unit)? = null
    override val state: String? get() = navigationString(window.history.state)
    override val path: String get() = window.location.pathname
    override fun replace(state: String, path: String) { window.history.replaceState(navigationJsString(state), "", path) }
    override fun push(state: String, path: String) { window.history.pushState(navigationJsString(state), "", path) }
    override fun go(delta: Int) { window.history.go(delta) }
    override fun observe(onPop: (String?) -> Unit) {
        close()
        listener = { _: Event -> onPop(state) }.also { window.addEventListener("popstate", it) }
    }
    override fun loadSegment(id: String): String? = localStorage.getItem("magicpaper:browser-segment:$id")
    override fun saveSegment(id: String, snapshot: String) { localStorage.setItem("magicpaper:browser-segment:$id", snapshot) }
    override fun close() { listener?.let { window.removeEventListener("popstate", it) }; listener = null }
}

internal expect fun navigationJsString(value: String): JsAny
internal expect fun navigationString(value: JsAny?): String?
expect fun browserDocumentHidden(): Boolean
internal expect fun requestBrowserJournalLease(name: String, onResult: (Int) -> Unit): () -> Unit
private const val TAB_KEY = "magicpaper:navigation:tab"
private const val LAST_KEY = "magicpaper:navigation:last-tab"
