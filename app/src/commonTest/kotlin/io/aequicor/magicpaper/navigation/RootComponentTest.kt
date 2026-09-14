package io.aequicor.magicpaper.navigation

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.destroy
import io.aequicor.magicpaper.data.storage.NavigationSnapshotStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RootComponentTest {
    private class Store : NavigationSnapshotStore {
        var snapshot: String? = null
        override suspend fun load(): String? = snapshot
        override suspend fun save(snapshot: String) { this.snapshot = snapshot }
    }
    private data class Child(val route: AppRoute)

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun slowPresentationSaveDoesNotDelayNavigationAndFlushKeepsLatestCompleteJournal() = runTest {
        val release = CompletableDeferred<Unit>()
        val writes = mutableListOf<String>()
        val store = object : NavigationSnapshotStore {
            override suspend fun load(): String? = null
            override suspend fun save(snapshot: String) {
                release.await()
                writes += snapshot
            }
        }
        val lifecycle = LifecycleRegistry()
        val root = DefaultRootComponent(DefaultComponentContext(lifecycle), store,
            FeatureComponentFactory { visit, _, _ -> Child(visit.route) }, initialWelcomeRequired = false,
            dispatcher = StandardTestDispatcher(testScheduler),
            persistenceDispatcher = StandardTestDispatcher(testScheduler))
        try {
            runCurrent()
            val first = root.navigationState.value.journal.current.id
            root.savePresentationEntry(first, "compose", "scroll=0")
            runCurrent() // Hold the first write while scrolling and selecting other sessions.
            repeat(300) { root.savePresentationEntry(first, "compose", "scroll=$it") }
            root.savePresentationEntry(first, "shell", "sidebar=hidden")
            root.navigate(AppRoute.Chat("a"))
            root.navigate(AppRoute.Chat("b"))
            root.back()
            runCurrent()
            assertEquals(AppRoute.Chat("a"), root.stack.value.active.instance.route)
            assertTrue(root.navigationState.value.canGoForward)
            val flush = launch { root.awaitIdle() }
            runCurrent()
            assertFalse(flush.isCompleted)
            root.forward()
            runCurrent()
            assertEquals(AppRoute.Chat("b"), root.stack.value.active.instance.route)
            release.complete(Unit)
            flush.join()
            val saved = kotlinx.serialization.json.Json.decodeFromString<NavigationJournal>(writes.last())
            assertEquals(root.navigationState.value.journal, saved)
            assertEquals("scroll=299", presentationEntry(saved.presentation[first], "compose"))
            assertEquals("sidebar=hidden", presentationEntry(saved.presentation[first], "shell"))
            assertTrue(writes.size <= 2, "Obsolete snapshots must not accumulate behind a slow write")
        } finally {
            release.complete(Unit)
            lifecycle.destroy()
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun resetDuringSlowSaveKeepsNewJournalAndIgnoresOldSaveFailure() = runTest {
        val release = CompletableDeferred<Unit>()
        var attempts = 0
        var saved: String? = null
        val store = object : NavigationSnapshotStore {
            override suspend fun load(): String? = null
            override suspend fun save(snapshot: String) {
                if (++attempts == 1) {
                    release.await()
                    error("old journal unavailable")
                }
                saved = snapshot
            }
        }
        val lifecycle = LifecycleRegistry()
        val root = DefaultRootComponent(DefaultComponentContext(lifecycle), store,
            FeatureComponentFactory { visit, _, _ -> Child(visit.route) }, initialWelcomeRequired = false,
            dispatcher = StandardTestDispatcher(testScheduler),
            persistenceDispatcher = StandardTestDispatcher(testScheduler))
        try {
            runCurrent()
            val oldId = root.navigationState.value.journal.current.id
            root.savePresentation(oldId, "old scroll")
            runCurrent()
            assertEquals(1, attempts)
            root.navigate(AppRoute.Chat("old"))
            root.reset(AppRoute.Docs())
            runCurrent()
            assertEquals(AppRoute.Docs(), root.stack.value.active.instance.route)
            release.complete(Unit)
            root.awaitIdle()
            val journal = root.navigationState.value.journal
            assertNull(root.navigationState.value.error)
            assertTrue(journal.presentation.isEmpty())
            assertEquals(listOf(AppRoute.Docs()), journal.visits.map { it.route })
            assertEquals(journal, kotlinx.serialization.json.Json.decodeFromString<NavigationJournal>(requireNotNull(saved)))
            assertEquals(2, attempts)
        } finally {
            release.complete(Unit)
            lifecycle.destroy()
        }
    }

    @Test fun saveFailureIsVisibleAndDoesNotStopSubsequentNavigationOrPersistence() = runTest {
        var fail = true
        var saved: String? = null
        val store = object : NavigationSnapshotStore {
            override suspend fun load(): String? = null
            override suspend fun save(snapshot: String) {
                if (fail) error("disk unavailable")
                saved = snapshot
            }
        }
        val lifecycle = LifecycleRegistry()
        val root = DefaultRootComponent(DefaultComponentContext(lifecycle), store,
            FeatureComponentFactory { visit, _, _ -> Child(visit.route) }, initialWelcomeRequired = false,
            dispatcher = StandardTestDispatcher(testScheduler),
            persistenceDispatcher = StandardTestDispatcher(testScheduler))
        try {
            root.navigate(AppRoute.Chat("a")); root.awaitIdle()
            assertEquals(AppRoute.Chat("a"), root.stack.value.active.instance.route)
            assertEquals("Не удалось сохранить историю переходов.", root.navigationState.value.error)
            fail = false
            root.dismissNavigationError()
            root.navigate(AppRoute.Chat("b")); root.awaitIdle()
            assertNull(root.navigationState.value.error)
            assertEquals(root.navigationState.value.journal,
                kotlinx.serialization.json.Json.decodeFromString<NavigationJournal>(requireNotNull(saved)))
        } finally { lifecycle.destroy() }
    }

    @Test fun failedTransitionRetainsHistoryAndDoesNotStopLaterCommands() = runTest {
        val lifecycle = LifecycleRegistry()
        val store = Store()
        val root = DefaultRootComponent(DefaultComponentContext(lifecycle), store,
            FeatureComponentFactory { visit, _, _ ->
                if (visit.route == AppRoute.Docs("unavailable")) error("component unavailable")
                Child(visit.route)
            }, initialWelcomeRequired = false, dispatcher = StandardTestDispatcher(testScheduler))
        root.navigate(AppRoute.Chat("retained")); root.awaitIdle()
        val before = root.navigationState.value.journal
        val previousChild = root.stack.value.active.instance
        val snapshot = store.snapshot
        root.navigate(AppRoute.Docs("unavailable")); root.awaitIdle()
        assertEquals(before, root.navigationState.value.journal)
        assertEquals(snapshot, store.snapshot)
        assertTrue(previousChild === root.stack.value.active.instance)
        assertEquals(before.current, root.stack.value.active.configuration)
        assertTrue(root.navigationState.value.error != null)
        root.navigate(AppRoute.Settings()); root.awaitIdle()
        assertEquals(AppRoute.Settings(), root.navigationState.value.route)
        assertEquals(AppRoute.Settings(), root.stack.value.active.instance.route)
        lifecycle.destroy()
    }

    @Test fun restoredComponentFailureRetainsUnreadSnapshotAndAllowsInMemoryNavigation() = runTest {
        val saved = NavigationJournal().navigate(AppRoute.Docs("unavailable"))
        val store = Store().apply { snapshot = kotlinx.serialization.json.Json.encodeToString(saved) }
        val original = store.snapshot
        val lifecycle = LifecycleRegistry()
        val root = DefaultRootComponent(DefaultComponentContext(lifecycle), store,
            FeatureComponentFactory { visit, _, _ ->
                if (visit.route == AppRoute.Docs("unavailable")) error("component unavailable")
                Child(visit.route)
            }, initialWelcomeRequired = false, dispatcher = StandardTestDispatcher(testScheduler))
        root.awaitIdle()
        assertEquals(AppRoute.Chat(), root.stack.value.active.instance.route)
        assertEquals(AppRoute.Chat(), root.navigationState.value.route)
        assertTrue(root.navigationState.value.error != null)
        root.navigate(AppRoute.Settings()); root.awaitIdle()
        assertEquals(AppRoute.Settings(), root.stack.value.active.instance.route)
        assertEquals(original, store.snapshot)
        lifecycle.destroy()
    }

    @Test fun repeatedVisitsBackForwardAndCompleteRestartPreserveForwardBranch() = runTest {
        val store = Store()
        val lifecycle = LifecycleRegistry()
        val root = DefaultRootComponent(DefaultComponentContext(lifecycle), store,
            FeatureComponentFactory { visit, _, _ -> Child(visit.route) }, initialWelcomeRequired = false,
            dispatcher = StandardTestDispatcher(testScheduler))
        root.navigate(AppRoute.Chat("a")); root.navigate(AppRoute.Docs("guide")); root.navigate(AppRoute.Chat("a"))
        root.awaitIdle()
        val visits = root.navigationState.value.journal.visits
        assertEquals(4, visits.size)
        assertNotEquals(visits[1].id, visits[3].id)
        root.navigate(AppRoute.Chat("a")); root.back(); root.awaitIdle()
        assertEquals(4, root.navigationState.value.journal.visits.size)
        assertEquals(AppRoute.Docs("guide"), root.navigationState.value.route)
        lifecycle.destroy()

        val nextLifecycle = LifecycleRegistry()
        val restored = DefaultRootComponent(DefaultComponentContext(nextLifecycle), store,
            FeatureComponentFactory { visit, _, _ -> Child(visit.route) }, initialWelcomeRequired = false,
            dispatcher = StandardTestDispatcher(testScheduler))
        restored.awaitIdle()
        assertTrue(restored.navigationState.value.canGoForward)
        assertEquals(AppRoute.Docs("guide"), restored.stack.value.active.instance.route)
        restored.forward(); restored.awaitIdle()
        assertEquals(visits[3].id, restored.navigationState.value.journal.current.id)
        assertEquals(AppRoute.Chat("a"), restored.stack.value.active.instance.route)
        nextLifecycle.destroy()
    }

    @Test fun welcomeDefersLinkAndNativeBackDismissesDialogBeforeNavigation() = runTest {
        val lifecycle = LifecycleRegistry()
        val root = DefaultRootComponent(DefaultComponentContext(lifecycle), Store(),
            FeatureComponentFactory { visit, _, _ -> Child(visit.route) },
            initialDeepLink = "magicpaper://projects/project/sessions/session",
            dispatcher = StandardTestDispatcher(testScheduler))
        root.awaitIdle()
        assertFalse(root.navigationState.value.ready)
        assertEquals(AppRoute.Chat(), root.navigationState.value.route)
        root.setWelcomeRequired(true); root.awaitIdle()
        assertEquals(1, root.navigationState.value.journal.pendingRoutes.size)
        root.setWelcomeRequired(false); root.awaitIdle()
        assertEquals(AppRoute.Projects("project", "session"), root.navigationState.value.route)
        root.showDialog(DialogRoute("model", "session")); root.back(); root.awaitIdle()
        assertNull(root.dialogSlot.value.child)
        assertEquals(AppRoute.Projects("project", "session"), root.navigationState.value.route)
        root.back(); root.awaitIdle()
        assertEquals(AppRoute.Chat(), root.navigationState.value.route)
        lifecycle.destroy()
    }

    @Test fun browserTraversalClosesDialogAndMovesCursorAndNewVisitDropsForward() = runTest {
        val lifecycle = LifecycleRegistry()
        val root = DefaultRootComponent(DefaultComponentContext(lifecycle), Store(),
            FeatureComponentFactory { visit, _, _ -> Child(visit.route) }, initialWelcomeRequired = false,
            dispatcher = StandardTestDispatcher(testScheduler))
        root.navigate(AppRoute.Chat("a")); root.navigate(AppRoute.Docs()); root.awaitIdle()
        val journal = root.navigationState.value.journal
        root.savePresentation(journal.current.id, "scroll=31")
        root.showDialog(DialogRoute("model"))
        root.onBrowserVisit(journal.id, journal.visits[1].id); root.awaitIdle()
        assertNull(root.dialogSlot.value.child)
        assertTrue(root.navigationState.value.canGoForward)
        root.forward(); root.awaitIdle()
        assertEquals("scroll=31", root.navigationState.value.journal.presentation[journal.current.id])
        root.back(); root.navigate(AppRoute.Settings()); root.awaitIdle()
        assertFalse(root.navigationState.value.canGoForward)
        assertFalse(journal.current.id in root.navigationState.value.journal.presentation)
        lifecycle.destroy()
    }

    @Test fun malformedSavedStateAndExternalLinksDoNotCreateEntities() = runTest {
        val store = Store().apply { snapshot = "{\"visits\":[],\"cursor\":0}" }
        val lifecycle = LifecycleRegistry()
        val root = DefaultRootComponent(DefaultComponentContext(lifecycle), store,
            FeatureComponentFactory { visit, _, _ -> Child(visit.route) }, initialWelcomeRequired = false,
            dispatcher = StandardTestDispatcher(testScheduler))
        root.awaitIdle()
        assertEquals(AppRoute.Chat(), root.navigationState.value.route)
        assertTrue(root.navigationState.value.error != null)
        root.handleDeepLink("magicpaper://projects/a/../../settings"); root.awaitIdle()
        assertEquals(1, root.navigationState.value.journal.visits.size)
        lifecycle.destroy()
    }
    @Test fun resetClearsBothHistoryDirectionsPendingLinksAndVisitPresentation() = runTest {
        val store = Store()
        val lifecycle = LifecycleRegistry()
        val root = DefaultRootComponent(DefaultComponentContext(lifecycle), store,
            FeatureComponentFactory { visit, _, _ -> Child(visit.route) }, initialWelcomeRequired = false,
            dispatcher = StandardTestDispatcher(testScheduler))
        root.navigate(AppRoute.Docs()); root.awaitIdle()
        val previous = root.navigationState.value.journal
        root.savePresentation(previous.current.id, "query")
        root.back(); root.setWelcomeRequired(true)
        root.handleDeepLink("magicpaper://plugins/notes")
        root.showDialog(DialogRoute("model")); root.reset(); root.awaitIdle()
        val journal = root.navigationState.value.journal
        assertNotEquals(previous.id, journal.id)
        assertEquals(listOf(AppRoute.Chat()), journal.visits.map { it.route })
        assertTrue(journal.presentation.isEmpty())
        assertTrue(journal.pendingRoutes.isEmpty())
        assertFalse(journal.canGoBack || journal.canGoForward)
        assertNull(root.dialogSlot.value.child)
        lifecycle.destroy()
    }

    @Test fun unreadableHistoryRemainsUntouchedUntilExplicitReset() = runTest {
        var saves = 0
        val store = object : NavigationSnapshotStore {
            override suspend fun load(): String? = error("unavailable")
            override suspend fun save(snapshot: String) { saves++ }
        }
        val lifecycle = LifecycleRegistry()
        val root = DefaultRootComponent(DefaultComponentContext(lifecycle), store,
            FeatureComponentFactory { visit, _, _ -> visit.route }, initialWelcomeRequired = false,
            dispatcher = StandardTestDispatcher(testScheduler))
        root.navigate(AppRoute.Docs()); root.awaitIdle()
        assertEquals(AppRoute.Docs(), root.navigationState.value.route)
        assertEquals(0, saves)
        val error = root.navigationState.value.error
        root.dismissNavigationError(); root.reportNavigationError("temporary"); root.awaitIdle()
        assertEquals(error, root.navigationState.value.error)
        root.reset(); root.awaitIdle()
        assertEquals(1, saves)
        assertNull(root.navigationState.value.error)
        lifecycle.destroy()
    }

    @Test fun independentPresentationOwnersMergeEntriesAndRestoreTheSameVisit() = runTest {
        val store = Store()
        val lifecycle = LifecycleRegistry()
        val root = DefaultRootComponent(DefaultComponentContext(lifecycle), store,
            FeatureComponentFactory { visit, _, _ -> visit.route }, initialWelcomeRequired = false,
            dispatcher = StandardTestDispatcher(testScheduler))
        root.navigate(AppRoute.Docs()); root.awaitIdle()
        val visit = root.navigationState.value.journal.current
        root.savePresentationEntry(visit.id, "docs-query", "navigation")
        root.savePresentationEntry(visit.id, "compose", "{\"scroll\":42}")
        root.savePresentationEntry(visit.id, "docs-query", "drafts")
        root.awaitIdle(); lifecycle.destroy()
        val nextLifecycle = LifecycleRegistry()
        var restoredPresentation: String? = null
        val restored = DefaultRootComponent(DefaultComponentContext(nextLifecycle), store,
            FeatureComponentFactory { configuration, _, presentation ->
                if (configuration.id == visit.id) restoredPresentation = presentation
                configuration.route
            }, initialWelcomeRequired = false, dispatcher = StandardTestDispatcher(testScheduler))
        restored.awaitIdle()
        assertEquals(visit.id, restored.navigationState.value.journal.current.id)
        assertEquals("drafts", presentationEntry(restoredPresentation, "docs-query"))
        assertEquals("{\"scroll\":42}", presentationEntry(restoredPresentation, "compose"))
        nextLifecycle.destroy()
    }

}
