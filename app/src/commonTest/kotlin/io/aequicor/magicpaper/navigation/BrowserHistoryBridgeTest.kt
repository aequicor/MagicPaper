package io.aequicor.magicpaper.navigation

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.destroy
import io.aequicor.magicpaper.data.storage.NavigationSnapshotStore
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class BrowserHistoryBridgeTest {
    private class Store(var value: String? = null) : NavigationSnapshotStore {
        var blockSaves = false
        val permits = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.UNLIMITED)
        override suspend fun load() = value
        override suspend fun save(snapshot: String) { if (blockSaves) permits.receive(); value = snapshot }
    }
    private class Browser : BrowserHistoryPort {
        val entries = mutableListOf<Pair<String?, String>>(null to "https://external.example/", null to "/")
        var cursor = 1
        var listener: ((String?) -> Unit)? = null
        val segments = mutableMapOf<String, String>()
        var pendingDelta: Int? = null
        override val state get() = entries[cursor].first
        override val path get() = entries[cursor].second
        override fun replace(state: String, path: String) { entries[cursor] = state to path }
        override fun push(state: String, path: String) {
            while (entries.lastIndex > cursor) entries.removeAt(entries.lastIndex)
            entries += state to path
            cursor++
        }
        override fun go(delta: Int) { check(pendingDelta == null); pendingDelta = delta }
        override fun observe(onPop: (String?) -> Unit) { listener = onPop }
        override fun loadSegment(id: String) = segments[id]
        override fun saveSegment(id: String, snapshot: String) { segments[id] = snapshot }
        override fun close() { listener = null }
        fun completeTraversal() { val delta = pendingDelta ?: return; pendingDelta = null; cursor += delta; listener?.invoke(state) }
        fun userBack() { cursor--; listener?.invoke(state) }
        fun userForward() { cursor++; listener?.invoke(state) }
    }

    @Test fun restoresFullHistoryWithoutReplacingExternalEntryAndBrowserTraversalFollowsJournal() = runTest {
        val journal = NavigationJournal().navigate(AppRoute.Chat("a")).navigate(AppRoute.Docs()).moveTo(1)
        val store = Store(Json.encodeToString(journal))
        val lifecycle = LifecycleRegistry()
        val root = DefaultRootComponent(DefaultComponentContext(lifecycle), store,
            FeatureComponentFactory { visit, _, _ -> visit.route }, initialWelcomeRequired = false,
            dispatcher = StandardTestDispatcher(testScheduler))
        val browser = Browser()
        val bridge = BrowserHistoryBridge(root, browser, "web-test", backgroundScope)
        root.awaitIdle(); runCurrent()
        assertEquals("https://external.example/", browser.entries.first().second)
        assertEquals(4, browser.entries.size)
        browser.completeTraversal(); runCurrent()
        assertEquals("/chat/a", browser.path)
        browser.userForward(); root.awaitIdle(); runCurrent()
        assertEquals(AppRoute.Docs(), root.navigationState.value.route)
        root.showDialog(DialogRoute("model")); root.awaitIdle()
        browser.userBack(); root.awaitIdle(); runCurrent()
        assertEquals(AppRoute.Chat("a"), root.navigationState.value.route)
        assertEquals(null, root.dialogSlot.value.child)
        bridge.close(); lifecycle.destroy()
    }

    @Test fun newNavigationDuringAsynchronousBackDoesNotEchoOldCursorOrKeepDiscardedForward() = runTest {
        val lifecycle = LifecycleRegistry()
        val root = DefaultRootComponent(DefaultComponentContext(lifecycle), Store(),
            FeatureComponentFactory { visit, _, _ -> visit.route }, initialWelcomeRequired = false,
            dispatcher = StandardTestDispatcher(testScheduler))
        val browser = Browser()
        val bridge = BrowserHistoryBridge(root, browser, "web-test", backgroundScope)
        root.awaitIdle(); runCurrent()
        root.navigate(AppRoute.Chat("a")); root.navigate(AppRoute.Docs()); root.awaitIdle(); runCurrent()
        root.back(); root.awaitIdle(); runCurrent()
        assertTrue(browser.pendingDelta != null)
        root.navigate(AppRoute.Settings()); root.awaitIdle(); runCurrent()
        browser.completeTraversal(); runCurrent()
        assertEquals("/settings", browser.path)
        assertEquals(AppRoute.Settings(), root.navigationState.value.route)
        assertFalse(root.navigationState.value.canGoForward)
        assertFalse(browser.entries.any { it.second == "/docs" })
        bridge.close(); lifecycle.destroy()
    }

    @Test fun queuedPresentationCannotDelayOrReverseBrowserBack() = runTest {
        val lifecycle = LifecycleRegistry()
        val store = Store()
        val root = DefaultRootComponent(DefaultComponentContext(lifecycle), store,
            FeatureComponentFactory { visit, _, _ -> visit.route }, initialWelcomeRequired = false,
            dispatcher = StandardTestDispatcher(testScheduler))
        val browser = Browser()
        val bridge = BrowserHistoryBridge(root, browser, "web-test", backgroundScope)
        root.navigate(AppRoute.Docs()); root.navigate(AppRoute.Settings()); root.awaitIdle(); runCurrent()
        val settings = root.navigationState.value.journal.current.id
        root.showDialog(DialogRoute("model")); root.awaitIdle()
        store.blockSaves = true
        root.savePresentation(settings, "first"); runCurrent()
        root.savePresentation(settings, "later")
        browser.userBack()
        // The browser has committed Back, before the root processes its command.
        assertEquals(AppRoute.Settings(), root.navigationState.value.route)
        assertEquals("/docs", browser.path)
        assertEquals(null, browser.pendingDelta, "A queued old-cursor autosave must not undo Back")
        runCurrent()
        assertEquals(AppRoute.Docs(), root.navigationState.value.route)
        assertEquals(null, root.dialogSlot.value.child)
        assertEquals(null, browser.pendingDelta)
        // Releasing obsolete persistence must not move either cursor backwards.
        store.blockSaves = false
        store.permits.trySend(Unit); root.awaitIdle(); runCurrent()
        assertEquals(null, browser.pendingDelta)
        assertEquals(root.navigationState.value.journal.current.id,
            Json.decodeFromString<BrowserVisitEntry>(requireNotNull(browser.state)).visitId)
        assertEquals(AppRoute.Docs(), Json.decodeFromString<NavigationJournal>(requireNotNull(store.value)).current.route)
        bridge.close(); lifecycle.destroy()
    }

    @Test fun rapidBrowserPopsIgnoreEarlierAcknowledgementAndKeepTheLatestVisit() = runTest {
        val lifecycle = LifecycleRegistry()
        val store = Store()
        val root = DefaultRootComponent(DefaultComponentContext(lifecycle), store,
            FeatureComponentFactory { visit, _, _ -> visit.route }, initialWelcomeRequired = false,
            dispatcher = StandardTestDispatcher(testScheduler))
        val browser = Browser()
        val bridge = BrowserHistoryBridge(root, browser, "web-test", backgroundScope)
        root.navigate(AppRoute.Chat("a")); root.navigate(AppRoute.Docs()); root.navigate(AppRoute.Settings())
        root.awaitIdle(); runCurrent()
        store.blockSaves = true
        root.savePresentation(root.navigationState.value.journal.current.id, "pending"); runCurrent()
        browser.userBack(); browser.userBack()
        assertEquals("/chat/a", browser.path)
        runCurrent()
        assertEquals(AppRoute.Chat("a"), root.navigationState.value.route)
        assertEquals(null, browser.pendingDelta, "Root acknowledgement must not reverse the second Back")
        store.blockSaves = false
        store.permits.trySend(Unit); root.awaitIdle(); runCurrent()
        assertEquals("/chat/a", browser.path)
        assertEquals(null, browser.pendingDelta)
        assertEquals(root.navigationState.value.journal.current.id,
            Json.decodeFromString<BrowserVisitEntry>(requireNotNull(browser.state)).visitId)
        bridge.close(); lifecycle.destroy()
    }

    @Test fun failedForwardChildRestoresBrowserCursorWithoutLosingTheJournal() = runTest {
        val lifecycle = LifecycleRegistry()
        val store = Store()
        var failSettings = false
        val root = DefaultRootComponent(DefaultComponentContext(lifecycle), store,
            FeatureComponentFactory { visit, _, _ ->
                if (failSettings && visit.route == AppRoute.Settings()) error("component unavailable")
                visit.route
            }, initialWelcomeRequired = false, dispatcher = StandardTestDispatcher(testScheduler))
        val browser = Browser()
        val bridge = BrowserHistoryBridge(root, browser, "web-test", backgroundScope)
        root.navigate(AppRoute.Docs()); root.navigate(AppRoute.Settings()); root.awaitIdle(); runCurrent()
        root.back(); root.awaitIdle(); runCurrent(); browser.completeTraversal(); runCurrent()
        val before = root.navigationState.value.journal
        val saved = store.value
        val entries = browser.entries.toList()
        failSettings = true
        root.showDialog(DialogRoute("model")); root.awaitIdle()
        browser.userForward(); root.awaitIdle(); runCurrent()
        browser.completeTraversal(); runCurrent()
        assertEquals("/docs", browser.path)
        assertEquals(before, root.navigationState.value.journal)
        assertEquals(saved, store.value)
        assertEquals(entries, browser.entries)
        assertEquals(null, root.dialogSlot.value.child)
        failSettings = false
        browser.userForward(); root.awaitIdle(); runCurrent()
        assertEquals(AppRoute.Settings(), root.stack.value.active.instance)
        bridge.close(); lifecycle.destroy()
    }

    @Test fun reloadReusesExistingMatchingBrowserSegmentWithoutAppendingDuplicates() = runTest {
        val store = Store()
        val lifecycle = LifecycleRegistry()
        val root = DefaultRootComponent(DefaultComponentContext(lifecycle), store,
            FeatureComponentFactory { visit, _, _ -> visit.route }, initialWelcomeRequired = false,
            dispatcher = StandardTestDispatcher(testScheduler))
        val browser = Browser()
        val bridge = BrowserHistoryBridge(root, browser, "web-test", backgroundScope)
        root.navigate(AppRoute.Docs()); root.awaitIdle(); runCurrent()
        val size = browser.entries.size
        bridge.close(); lifecycle.destroy()
        val nextLifecycle = LifecycleRegistry()
        val next = DefaultRootComponent(DefaultComponentContext(nextLifecycle), store,
            FeatureComponentFactory { visit, _, _ -> visit.route }, initialWelcomeRequired = false,
            dispatcher = StandardTestDispatcher(testScheduler))
        val nextBridge = BrowserHistoryBridge(next, browser, "web-test", backgroundScope)
        next.awaitIdle(); runCurrent()
        assertEquals(size, browser.entries.size)
        assertEquals("/docs", browser.path)
        nextBridge.close(); nextLifecycle.destroy()
    }

    @Test fun duplicatedBrowserEntryUsesItsCopiedVisitAndIndependentJournalKey() = runTest {
        val store = Store()
        val lifecycle = LifecycleRegistry()
        val source = DefaultRootComponent(DefaultComponentContext(lifecycle), store,
            FeatureComponentFactory { visit, _, _ -> visit.route }, initialWelcomeRequired = false,
            dispatcher = StandardTestDispatcher(testScheduler))
        val browser = Browser()
        val sourceBridge = BrowserHistoryBridge(source, browser, "source", backgroundScope)
        source.navigate(AppRoute.Chat("a")); source.navigate(AppRoute.Docs()); source.awaitIdle(); runCurrent()
        source.back(); source.awaitIdle(); runCurrent(); browser.completeTraversal(); runCurrent()
        val copiedBrowser = Browser().apply {
            entries.clear(); entries.addAll(browser.entries)
            cursor = browser.cursor; segments.putAll(browser.segments)
        }
        source.forward(); source.awaitIdle(); runCurrent(); browser.completeTraversal(); runCurrent()
        val cloneStore = Store(store.value)
        val cloneLifecycle = LifecycleRegistry()
        val clone = DefaultRootComponent(DefaultComponentContext(cloneLifecycle), cloneStore,
            FeatureComponentFactory { visit, _, _ -> visit.route }, initialWelcomeRequired = false,
            dispatcher = StandardTestDispatcher(testScheduler))
        val cloneBridge = BrowserHistoryBridge(clone, copiedBrowser, "clone", backgroundScope)
        clone.awaitIdle(); runCurrent(); copiedBrowser.completeTraversal(); runCurrent(); clone.awaitIdle()
        assertEquals(AppRoute.Chat("a"), clone.navigationState.value.route)
        assertEquals("clone", Json.decodeFromString<BrowserVisitEntry>(requireNotNull(copiedBrowser.state)).journalKey)
        clone.navigate(AppRoute.Settings()); clone.awaitIdle(); runCurrent()
        assertEquals(AppRoute.Docs(), source.navigationState.value.route)
        assertEquals("/docs", browser.path)
        assertEquals(AppRoute.Settings(), clone.navigationState.value.route)
        cloneBridge.close(); cloneLifecycle.destroy(); sourceBridge.close(); lifecycle.destroy()
    }
}
