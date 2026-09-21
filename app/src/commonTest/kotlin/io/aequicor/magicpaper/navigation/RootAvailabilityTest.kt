package io.aequicor.magicpaper.navigation

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.destroy
import io.aequicor.magicpaper.data.storage.NavigationSnapshotStore
import io.aequicor.magicpaper.ui.AppContributions
import io.aequicor.magicpaper.ui.AppDialogContribution
import io.aequicor.magicpaper.ui.AppRouteContribution
import io.aequicor.magicpaper.ui.SettingsComponent
import io.aequicor.magicpaper.ui.SettingsContributions
import io.aequicor.magicpaper.ui.SettingsNavigationEntry
import io.aequicor.magicpaper.ui.SettingsOutput
import io.aequicor.magicpaper.ui.SettingsPage
import io.aequicor.magicpaper.ui.SettingsPageRegistration
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A host opens only what it contributes; a build without coding never reaches a "not available" screen. */
class RootAvailabilityTest {
    private class Store(var snapshot: String? = null) : NavigationSnapshotStore {
        override suspend fun load(): String? = snapshot
        override suspend fun save(snapshot: String) { this.snapshot = snapshot }
    }
    private data class Child(val route: AppRoute)

    private val projects = AppRoute.Projects("project", "session")
    private val engines = AppRoute.Settings(SettingsSection.ENGINES)

    private fun saved(vararg routes: AppRoute) = Json.encodeToString(NavigationJournal(id = "saved",
        visits = routes.mapIndexed { index, route -> Visit("visit-$index", route) }, cursor = routes.lastIndex))

    private fun kotlinx.coroutines.test.TestScope.host(store: Store, lifecycle: LifecycleRegistry, availability: RouteAvailability) =
        DefaultRootComponent(DefaultComponentContext(lifecycle), store,
            FeatureComponentFactory { visit, _, _ -> Child(visit.route) }, initialWelcomeRequired = false,
            dispatcher = StandardTestDispatcher(testScheduler), persistenceDispatcher = StandardTestDispatcher(testScheduler),
            availability = availability)

    @Test fun aJournalSavedByABuildWithCodingRestoresWithoutItsScreens() = runTest {
        val lifecycle = LifecycleRegistry()
        val root = host(Store(saved(AppRoute.Docs(), projects, engines)), lifecycle, RouteAvailability.Base)
        try {
            root.awaitIdle()
            assertEquals(AppRoute.Docs(), root.stack.value.active.instance.route)
            assertEquals(listOf(AppRoute.Docs()), root.navigationState.value.journal.visits.map { it.route }.take(1))
            assertTrue(root.stack.value.backStack.none { it.instance.route == projects })
            assertEquals(NavigationMachine.SECTION_UNAVAILABLE, root.navigationState.value.error)
        } finally { lifecycle.destroy() }
    }

    @Test fun aLinkToAScreenTheHostLacksOpensNothingAndSaysSo() = runTest {
        val lifecycle = LifecycleRegistry()
        val root = host(Store(), lifecycle, RouteAvailability.Base)
        try {
            root.awaitIdle()
            val before = root.navigationState.value.journal
            root.handleDeepLink("magicpaper://projects/project/sessions/session"); root.awaitIdle()
            assertEquals(before, root.navigationState.value.journal)
            assertEquals(NavigationMachine.SECTION_UNAVAILABLE, root.navigationState.value.error)
            assertEquals(AppRoute.Chat(), root.stack.value.active.instance.route)
        } finally { lifecycle.destroy() }
    }

    @Test fun aDestinationRequestedInCodeIsDroppedAndNeverBuilt() = runTest {
        val lifecycle = LifecycleRegistry()
        val built = mutableListOf<AppRoute>()
        val root = DefaultRootComponent(DefaultComponentContext(lifecycle), Store(),
            FeatureComponentFactory { visit, _, _ -> built += visit.route; Child(visit.route) }, initialWelcomeRequired = false,
            dispatcher = StandardTestDispatcher(testScheduler), persistenceDispatcher = StandardTestDispatcher(testScheduler),
            availability = RouteAvailability.Base)
        try {
            root.awaitIdle()
            root.navigate(engines); root.navigate(projects); root.awaitIdle()
            root.showDialog(DialogRoute("new-session", "project")); root.awaitIdle()
            assertEquals(listOf<AppRoute>(AppRoute.Chat()), built)
            assertNull(root.dialogSlot.value.child)
            assertNull(root.navigationState.value.error)
        } finally { lifecycle.destroy() }
    }

    @Test fun availabilityIsDerivedFromWhatTheHostContributes() {
        val none = routeAvailability(AppContributions(), SettingsContributions())
        assertEquals(RouteAvailability.Base, none)
        assertFalse(none.admits(projects))
        assertFalse(none.admits(engines))
        assertFalse(none.admits(DialogRoute("new-session")))

        val page = SettingsPageRegistration(SettingsPage.ENGINES, SettingsNavigationEntry("⚙", "Движки", "", SettingsOutput.Engines),
            SettingsComponent.Factory { _, _, _ -> error("Derivation must not create components") })
        val route = object : AppRouteContribution {
            override val kind = "projects"
            override fun create(context: com.arkivanov.decompose.ComponentContext, route: AppRoute,
                                events: io.aequicor.magicpaper.di.NavigationEvents) = error("Derivation must not create screens")
        }
        val dialog = object : AppDialogContribution {
            override val kind = "new-session"
            @androidx.compose.runtime.Composable override fun Content(dialog: DialogRoute, root: RootComponent<io.aequicor.magicpaper.navigation.AppChild>) = Unit
        }
        val native = routeAvailability(AppContributions(routes = listOf(route), dialogs = listOf(dialog)), SettingsContributions(pages = listOf(page)))
        assertTrue(native.admits(projects))
        assertTrue(native.admits(engines))
        assertFalse(native.admits(AppRoute.Settings(SettingsSection.COMPUTER)), "only the registered page opens")
        assertTrue(native.admits(DialogRoute("new-session")))
    }
}
