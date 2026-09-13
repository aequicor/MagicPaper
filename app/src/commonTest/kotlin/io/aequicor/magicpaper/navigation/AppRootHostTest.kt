package io.aequicor.magicpaper.navigation

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.destroy
import io.aequicor.magicpaper.data.storage.*
import io.aequicor.magicpaper.di.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.koin.dsl.module
import kotlin.test.*

/** Exercise host composition before services finish bootstrapping, using the real AppRoot. */
@OptIn(ExperimentalCoroutinesApi::class)
class AppRootHostTest {
    @Test fun coldHostDefersPlatformLinkAndResetAcknowledgesNewDurableJournal() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        var savedSnapshot: String? = null
        val persistence = PersistenceStores(InMemorySecretStore(), InMemoryDraftRepository(), InMemoryDraftBlobStore(),
            object : NavigationSnapshotStore {
                override suspend fun load() = savedSnapshot
                override suspend fun save(snapshot: String) { savedSnapshot = snapshot }
            }, clear = {})
        val events = NavigationEvents()
        val runtime = MagicPaperRuntime(
            navigationSession = NavigationSessionConfig(initialDeepLink = "magicpaper://docs/guide"),
            onPlatformStarted = {}, onPlatformClosed = {},
            definitions = { module { single { persistence }; single { events } } },
        )
        val lifecycle = LifecycleRegistry()
        try {
            val root = createAppRoot(runtime, DefaultComponentContext(lifecycle))
            root.awaitIdle()
            assertTrue(root is ApplicationRoot)
            assertFalse(root.navigationState.value.ready)
            assertEquals(AppRoute.Chat(), root.navigationState.value.route)
            assertEquals(listOf(AppRoute.Docs("guide")), root.navigationState.value.journal.pendingRoutes)
            val oldJournal = root.navigationState.value.journal.id
            events.reset()
            assertNotEquals(oldJournal, root.navigationState.value.journal.id)
            assertTrue(root.navigationState.value.journal.pendingRoutes.isEmpty())
            assertEquals(AppRoute.Docs(), root.navigationState.value.route)
            val neutralVisitId = root.navigationState.value.journal.current.id
            persistence.clearOwnedData()
            events.resetComplete()
            assertEquals(AppRoute.Chat(), root.navigationState.value.route)
            assertNotEquals(neutralVisitId, root.navigationState.value.journal.current.id)
            assertNotNull(savedSnapshot)
        } finally {
            lifecycle.destroy()
            runtime.close(); runtime.awaitClosed()
            Dispatchers.resetMain()
        }
    }
}
