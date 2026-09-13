package io.aequicor.magicpaper.navigation

import io.aequicor.magicpaper.logging.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Small History API port makes the JS and Wasm bridge testable without a browser. */
interface BrowserHistoryPort {
    val state: String?
    val path: String
    fun replace(state: String, path: String)
    fun push(state: String, path: String)
    fun go(delta: Int)
    fun observe(onPop: (String?) -> Unit)
    fun loadSegment(id: String): String?
    fun saveSegment(id: String, snapshot: String)
    fun close()
}

@Serializable
data class BrowserVisitEntry(
    val journalKey: String,
    val journalId: String,
    val segmentId: String,
    val visitId: String,
    val version: Int = 1,
)

@Serializable
private data class BrowserSegment(val visitIds: List<String>)

/**
 * The only browser history writer. The journal owns all routes and the forward branch;
 * browser entries merely identify visits. Browser traversal is already committed when
 * popstate arrives, so it dismisses a dialog and follows that visit in one command.
 */
class BrowserHistoryBridge<C : Any>(
    private val root: RootComponent<C>,
    private val browser: BrowserHistoryPort,
    private val journalKey: String,
    scope: CoroutineScope,
    private val basePath: String = "",
    private val onError: (String) -> Unit = {},
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private var latest: NavigationJournal? = null
    private var segmentId = ""
    private var mountedIds = emptyList<String>()
    private var browserCursor = -1
    private var awaitingVisit: String? = null
    private var initialized = false
    private var rootRequestSequence = 0L
    private var pendingRootRequest: Long? = null
    private val synchronizationScope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
    private var failed = false
    private val subscription: Job

    init {
        guarded { browser.observe(::onPop) }
        subscription = scope.launch {
            root.navigationState.collect { state ->
                if (state.ready && !state.welcomeRequired) {
                    if (latest?.id != null && latest?.id != state.journal.id) {
                        initialized = false
                        awaitingVisit = null
                    }
                    latest = state.journal
                    guarded { synchronize() }
                }
            }
        }
    }

    fun close() { subscription.cancel(); synchronizationScope.cancel(); guarded { browser.close() } }

    private fun onPop(raw: String?) = guarded {
        val journal = latest ?: run { root.dismissDialog(); return@guarded }
        val entry = decodeEntry(raw) ?: run { root.dismissDialog(); return@guarded }
        AppLog.info("browser_navigation", "traverse", mapOf("visitId" to entry.visitId))
        if (entry.journalKey != journalKey) {
            // Duplicate-tab predecessors belong to the source key. Rebuild from this
            // tab's journal; never attach another live tab's persistence owner.
            initialized = false
            awaitingVisit = null
            if (entry.journalId == journal.id && entry.visitId != journal.current.id &&
                journal.visits.any { it.id == entry.visitId }) {
                requestRootVisit(journal.id, entry.visitId)
            } else { root.dismissDialog(); synchronize() }
            return@guarded
        }
        if (entry.journalId != journal.id || entry.segmentId != segmentId) {
            // A reset invalidates prior app entries; never restore a wiped journal.
            root.dismissDialog()
            browser.replace(entry(journal, journal.current), path(journal.current.route))
            onError("Этот переход больше недоступен.")
            return@guarded
        }
        val position = mountedIds.indexOf(entry.visitId)
        if (position < 0) { root.dismissDialog(); return@guarded }
        browserCursor = position
        if (awaitingVisit == entry.visitId) {
            awaitingVisit = null
            root.dismissDialog()
            synchronize()
        } else {
            awaitingVisit = null
            // Root's next emission is the only response to user browser traversal.
            requestRootVisit(entry.journalId, entry.visitId)
        }
    }

    private fun requestRootVisit(journalId: String, visitId: String) {
        val request = ++rootRequestSequence
        pendingRootRequest = request
        root.onBrowserVisit(journalId, visitId)
        synchronizationScope.launch {
            try {
                // Autosaves queued before the browser command may publish the old
                // cursor. They must not turn the already committed Back into Forward.
                root.awaitIdle()
                if (pendingRootRequest != request) return@launch
                pendingRootRequest = null
                val state = root.navigationState.value
                val journal = state.journal
                if (latest?.id != journal.id) { initialized = false; awaitingVisit = null }
                latest = journal
                // A rejected child transition retains the old journal; this is
                // the point at which an intentional browser rollback is allowed.
                if (state.ready && !state.welcomeRequired) guarded { synchronize() }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                failed = true
                AppLog.error("browser_navigation", "traversal_failed", failure, mapOf("result" to "synchronization_paused"))
                onError("Не удалось завершить переход. Обновите страницу.")
            }
        }
    }

    private fun synchronize() {
        val journal = latest ?: return
        if (failed) return
        if (awaitingVisit != null || pendingRootRequest != null) return
        if (!initialized) {
            val entry = decodeEntry(browser.state)
            // A cloned tab starts at the visit it duplicated, even if the source
            // tab has since persisted a different cursor before the clone loads.
            if (entry != null && entry.journalKey != journalKey && entry.journalId == journal.id &&
                entry.visitId != journal.current.id && journal.visits.any { it.id == entry.visitId }) {
                requestRootVisit(journal.id, entry.visitId)
                return
            }
            initialized = true
            val segment = entry?.let { browser.loadSegment(it.segmentId) }
                ?.let {
                    try { json.decodeFromString<BrowserSegment>(it) }
                    catch (failure: kotlinx.serialization.SerializationException) {
                        AppLog.error("browser_navigation", "segment_invalid", failure, mapOf("result" to "rebuild"))
                        onError("История браузера восстановлена из сохранённых переходов.")
                        null
                    }
                }
            if (entry != null && segment != null && entry.journalKey == journalKey && entry.journalId == journal.id &&
                entry.visitId in journal.visits.map { it.id } && entry.visitId in segment.visitIds &&
                segment.visitIds.all { id -> journal.visits.any { it.id == id } }) {
                AppLog.debug("browser_navigation", "segment_selected", mapOf("strategy" to "reuse"))
                segmentId = entry.segmentId
                mountedIds = segment.visitIds
                browserCursor = mountedIds.indexOf(entry.visitId)
                if (journal.current.id != entry.visitId) {
                    requestRootVisit(journal.id, entry.visitId)
                    return
                }
            } else {
                // This document is an app-owned entry. Earlier external entries are untouched.
                AppLog.debug("browser_navigation", "segment_selected", mapOf("strategy" to "rebuild", "count" to journal.visits.size.toString()))
                segmentId = newNavigationId()
                mountedIds = journal.visits.map { it.id }
                journal.visits.forEachIndexed { index, visit ->
                    if (index == 0) browser.replace(entry(journal, visit), path(visit.route))
                    else browser.push(entry(journal, visit), path(visit.route))
                }
                browserCursor = journal.visits.lastIndex
                persistSegment()
            }
        }

        val desired = journal.visits.map { it.id }
        if (desired != mountedIds) {
            val common = mountedIds.zip(desired).takeWhile { (old, new) -> old == new }.size
            check(common > 0) { "Browser navigation journal changed identity" }
            if (browserCursor != common - 1) {
                traverse(common - 1)
                return
            }
            // A new visit after Back truncates the old forward branch in both stores.
            mountedIds = mountedIds.take(common)
            journal.visits.drop(common).forEach { visit ->
                browser.push(entry(journal, visit), path(visit.route))
                mountedIds = mountedIds + visit.id
                browserCursor++
            }
            persistSegment()
        }
        if (browserCursor != journal.cursor) traverse(journal.cursor)
        else if (browser.path != path(journal.current.route)) browser.replace(entry(journal, journal.current), path(journal.current.route))
    }

    private fun traverse(index: Int) {
        if (index !in mountedIds.indices) return
        awaitingVisit = mountedIds[index]
        browser.go(index - browserCursor)
    }

    private fun entry(journal: NavigationJournal, visit: Visit): String = json.encodeToString(
        BrowserVisitEntry(journalKey, journal.id, segmentId, visit.id),
    )

    private fun path(route: AppRoute): String = basePath.trimEnd('/') + AppRouteCodec.path(route)
    private fun persistSegment() = browser.saveSegment(segmentId, json.encodeToString(BrowserSegment(mountedIds)))
    private fun decodeEntry(raw: String?): BrowserVisitEntry? = raw?.let {
        try { json.decodeFromString<BrowserVisitEntry>(it).takeIf { entry -> entry.version == 1 } }
        catch (_: kotlinx.serialization.SerializationException) {
            AppLog.debug("browser_navigation", "entry_ignored", mapOf("reason" to "foreign_history"))
            null
        }
    }
    private inline fun guarded(action: () -> Unit) {
        try { action() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            failed = true
            AppLog.error("browser_navigation", "history_failed", failure, mapOf("result" to "synchronization_paused"))
            onError("Не удалось обновить историю браузера. Обновите страницу.")
        }
    }
}
