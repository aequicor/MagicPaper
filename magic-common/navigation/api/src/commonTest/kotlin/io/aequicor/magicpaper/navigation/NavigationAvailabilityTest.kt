package io.aequicor.magicpaper.navigation

import io.aequicor.magicpaper.navigation.NavigationMachine.Effect
import io.aequicor.magicpaper.navigation.NavigationMachine.Fact
import io.aequicor.magicpaper.navigation.NavigationMachine.Intent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Availability is a value on the state, so the acceptance matrix cannot state it: a route is
 * refused by what it names, not by the position the machine stands in. These tests carry the
 * promise made in [NavigationSpace] instead.
 */
class NavigationAvailabilityTest {
    /** A host with no coding: the destinations only the JVM build contributes are absent. */
    private val base = RouteAvailability.Base
    private val projects = AppRoute.Projects("project", "session")
    private val engines = AppRoute.Settings(SettingsSection.ENGINES)
    private val newSession = DialogRoute("new-session", "project")

    private fun opened(journal: NavigationJournal? = null, availability: RouteAvailability = base) =
        NavigationMachine.initial("journal", "visit-0", welcomeRequired = false, availability = availability)
            .let { if (journal == null) it else NavigationMachine.reduce(it, Fact.Restored(journal, failed = false)).state }
    private fun ready(availability: RouteAvailability = base) =
        NavigationMachine.reduce(opened(availability = availability), Fact.Restored(null, failed = false)).state
    private fun journal(vararg routes: AppRoute, cursor: Int = routes.lastIndex) = NavigationJournal(
        id = "journal", visits = routes.mapIndexed { index, route -> Visit("visit-$index", route) }, cursor = cursor,
        presentation = routes.indices.associate { "visit-$it" to "scroll=$it" })

    @Test fun anInadmissibleRouteIsRefusedByEveryEntryThatNamesADestination() {
        val state = ready()
        listOf(Intent.Navigate(projects, "next"), Intent.Navigate(engines, "next"),
            Intent.Resolve(projects), Intent.Reset(projects, "journal-2", "visit-9")).forEach { input ->
            val transition = NavigationMachine.reduce(state, input)
            assertEquals(state, transition.state, "input=$input")
            assertEquals(listOf<Effect>(Effect.Reject(NavigationMachine.ROUTE_UNAVAILABLE)), transition.effects, "input=$input")
        }
    }

    @Test fun anAdmissibleRouteStillNavigates() {
        val transition = NavigationMachine.reduce(ready(), Intent.Navigate(AppRoute.Settings(SettingsSection.MODELS), "next"))
        assertTrue(transition.effects.single() is Effect.Project)
    }

    @Test fun aHostThatContributesTheRouteAdmitsIt() {
        val host = base.with(kinds = setOf("projects"), settingsSections = setOf(SettingsSection.ENGINES))
        val state = ready(host)
        assertTrue(NavigationMachine.reduce(state, Intent.Navigate(projects, "next")).effects.single() is Effect.Project)
        assertTrue(NavigationMachine.reduce(state, Intent.Navigate(engines, "next")).effects.single() is Effect.Project)
    }

    @Test fun anInadmissibleLinkSaysSoAndChangesNothingElse() {
        val state = ready()
        listOf("magicpaper://projects/project/sessions/session", "/settings/engines").forEach { uri ->
            val transition = NavigationMachine.reduce(state, Intent.Link(uri, "next"))
            assertEquals(state.copy(error = NavigationMachine.SECTION_UNAVAILABLE), transition.state, uri)
            assertTrue(transition.effects.isEmpty(), uri)
        }
    }

    @Test fun anInadmissibleLinkIsNotQueuedBehindTheWelcomeGate() {
        val gated = NavigationMachine.reduce(opened(), Fact.Restored(null, failed = false)).state
            .let { NavigationMachine.reduce(it, Intent.Welcome(true)).state }
        val transition = NavigationMachine.reduce(gated, Intent.Link("magicpaper://projects", "next"))
        assertEquals(emptyList(), transition.state.journal.pendingRoutes)
        assertEquals(NavigationMachine.SECTION_UNAVAILABLE, transition.state.error)
    }

    @Test fun anInadmissibleDialogKindIsRefused() {
        val state = ready()
        val transition = NavigationMachine.reduce(state, Intent.ShowDialog(newSession))
        assertEquals(state, transition.state)
        assertEquals(listOf<Effect>(Effect.Reject(NavigationMachine.ROUTE_UNAVAILABLE)), transition.effects)
        assertEquals(newSession, NavigationMachine.reduce(ready(base.with(dialogKinds = setOf("new-session"))),
            Intent.ShowDialog(newSession)).state.dialog)
        assertEquals(DialogRoute("feature-modal", "editor"),
            NavigationMachine.reduce(state, Intent.ShowDialog(DialogRoute("feature-modal", "editor"))).state.dialog)
    }

    @Test fun aRestoredJournalIsTrimmedBeforeItIsProjected() {
        val saved = journal(AppRoute.Chat(), projects, AppRoute.Docs(), engines, AppRoute.Plugins())
        val restored = NavigationMachine.reduce(ready().let { NavigationMachine.initial("j", "v", welcomeRequired = false, availability = base) },
            Fact.Restored(saved, failed = false))
        val projected = (restored.effects.single() as Effect.Project).journal
        assertEquals(listOf(AppRoute.Chat(), AppRoute.Docs(), AppRoute.Plugins()), projected.visits.map { it.route })
        assertEquals(listOf("visit-0", "visit-2", "visit-4"), projected.visits.map { it.id })
        assertEquals(2, projected.cursor)
        assertEquals(setOf("visit-0", "visit-2", "visit-4"), projected.presentation.keys)
        assertEquals(NavigationMachine.SECTION_UNAVAILABLE, restored.state.error)
    }

    @Test fun anAdmissibleRestoredJournalIsProjectedUntouchedAndSilently() {
        val saved = journal(AppRoute.Chat(), AppRoute.Docs())
        val restored = NavigationMachine.reduce(NavigationMachine.initial("j", "v", welcomeRequired = false, availability = base),
            Fact.Restored(saved, failed = false))
        assertEquals(saved, (restored.effects.single() as Effect.Project).journal)
        assertNull(restored.state.error)
    }

    @Test fun whenTheCurrentVisitIsGoneTheUserLandsOnTheNearestEarlierOne() {
        val trimmed = base.restrict(journal(AppRoute.Docs(), AppRoute.Chat("a"), projects, AppRoute.Plugins(), cursor = 2))
        assertEquals(AppRoute.Chat("a"), trimmed.current.route)
        assertEquals(listOf(AppRoute.Docs(), AppRoute.Chat("a"), AppRoute.Plugins()), trimmed.visits.map { it.route })
        assertTrue(trimmed.canGoForward, "the tail after the trimmed visit stays reachable")
    }

    @Test fun whenNothingEarlierSurvivesTheCurrentVisitBecomesChatWithoutItsPresentation() {
        val trimmed = base.restrict(journal(projects, engines, AppRoute.Docs(), cursor = 0))
        assertEquals(listOf(AppRoute.Chat(), AppRoute.Docs()), trimmed.visits.map { it.route })
        assertEquals("visit-0", trimmed.current.id)
        assertEquals(0, trimmed.cursor)
        assertFalse("visit-0" in trimmed.presentation)
        assertEquals(setOf("visit-2"), trimmed.presentation.keys)
    }

    @Test fun aJournalOfNothingButInadmissibleRoutesRestoresToOneChatVisit() {
        val trimmed = base.restrict(journal(projects, engines))
        assertEquals(listOf(AppRoute.Chat()), trimmed.visits.map { it.route })
        assertEquals(emptyMap(), trimmed.presentation)
    }

    @Test fun queuedRoutesTheHostCannotOpenAreDroppedOnRestore() {
        val saved = journal(AppRoute.Chat()).copy(pendingRoutes = listOf(projects, AppRoute.Docs()))
        assertEquals(listOf<AppRoute>(AppRoute.Docs()), base.restrict(saved).pendingRoutes)
    }
}
