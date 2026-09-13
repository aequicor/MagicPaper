package io.aequicor.magicpaper.navigation

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.destroy
import io.aequicor.magicpaper.data.storage.NavigationSnapshotStore
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RootDialogLifecycleTest {
    private class Store : NavigationSnapshotStore {
        override suspend fun load(): String? = null
        override suspend fun save(snapshot: String) = Unit
    }

    @Test fun oldCompletionCannotDismissAReopenedDialogForTheSameProject() = runTest {
        val lifecycle = LifecycleRegistry()
        val root = DefaultRootComponent(DefaultComponentContext(lifecycle), Store(),
            FeatureComponentFactory { visit, _, _ -> visit.route }, initialWelcomeRequired = false,
            dispatcher = StandardTestDispatcher(testScheduler))
        try {
            val first = DialogRoute("new-session", "project")
            root.showDialog(first); root.awaitIdle()
            root.back(); root.awaitIdle()
            val reopened = DialogRoute("new-session", "project")
            root.showDialog(reopened); root.awaitIdle()
            root.dismissDialog(first); root.awaitIdle()
            kotlin.test.assertSame(reopened, root.dialogSlot.value.child?.configuration)
            root.dismissDialog(reopened); root.awaitIdle()
            assertNull(root.dialogSlot.value.child)
        } finally { lifecycle.destroy() }
    }

    @Test fun featureModalUsesRootSlotAndNativeBackDismissesBeforeMoving() = runTest {
        val lifecycle = LifecycleRegistry()
        val root = DefaultRootComponent(DefaultComponentContext(lifecycle), Store(),
            FeatureComponentFactory { visit, _, _ -> visit.route }, initialWelcomeRequired = false,
            dispatcher = StandardTestDispatcher(testScheduler))
        val dialogs = RootDialogLifecycle(root)
        root.navigate(AppRoute.Docs()); root.awaitIdle()
        var dismissals = 0
        dialogs.register("editor") { dismissals++ }; root.awaitIdle()
        assertEquals(DialogRoute("feature-modal", "editor"), root.dialogSlot.value.child?.configuration)
        root.back(); root.awaitIdle()
        assertNull(root.dialogSlot.value.child)
        assertEquals(1, dismissals)
        assertEquals(AppRoute.Docs(), root.navigationState.value.route)
        root.back(); root.awaitIdle()
        assertEquals(AppRoute.Chat(), root.navigationState.value.route)
        dialogs.close(); lifecycle.destroy()
    }

    @Test fun browserTraversalDismissesFeatureModalAndFollowsRequestedVisit() = runTest {
        val lifecycle = LifecycleRegistry()
        val root = DefaultRootComponent(DefaultComponentContext(lifecycle), Store(),
            FeatureComponentFactory { visit, _, _ -> visit.route }, initialWelcomeRequired = false,
            dispatcher = StandardTestDispatcher(testScheduler))
        val dialogs = RootDialogLifecycle(root)
        root.navigate(AppRoute.Chat("session")); root.navigate(AppRoute.Docs()); root.awaitIdle()
        val journal = root.navigationState.value.journal
        var dismissed = false
        dialogs.register("editor") { dismissed = true }; root.awaitIdle()
        root.onBrowserVisit(journal.id, journal.visits[1].id); root.awaitIdle()
        assertEquals(true, dismissed)
        assertNull(root.dialogSlot.value.child)
        assertEquals(AppRoute.Chat("session"), root.navigationState.value.route)
        assertEquals(true, root.navigationState.value.canGoForward)
        dialogs.close(); lifecycle.destroy()
    }

    @Test fun fastDisposalDoesNotLeaveAnOrphanOrDismissANewerModal() = runTest {
        val lifecycle = LifecycleRegistry()
        val root = DefaultRootComponent(DefaultComponentContext(lifecycle), Store(),
            FeatureComponentFactory { visit, _, _ -> visit.route }, initialWelcomeRequired = false,
            dispatcher = StandardTestDispatcher(testScheduler))
        val dialogs = RootDialogLifecycle(root)
        dialogs.register("first") { error("Disposed content must not receive dismissal") }
        dialogs.unregister("first")
        root.awaitIdle()
        assertNull(root.dialogSlot.value.child)
        dialogs.register("first") { }
        dialogs.register("second") { }
        dialogs.unregister("first")
        root.awaitIdle()
        assertEquals("second", root.dialogSlot.value.child?.configuration?.entityId)
        dialogs.close(); lifecycle.destroy()
    }
}
