package io.aequicor.magicpaper.navigation

import io.aequicor.magicpaper.navigation.NavigationMachine.Fact
import io.aequicor.magicpaper.navigation.NavigationMachine.Intent
import io.aequicor.magicpaper.machine.PhaseId
import io.aequicor.magicpaper.machine.InputId
import io.aequicor.magicpaper.machine.verifyStateSpace
import kotlin.test.Test

/**
 * Representatives of [NavigationSpace]. They stay in the test source set: the api names every
 * position, but a shipped binary — the browser bundle among them — carries no fixtures.
 */
class NavigationSpaceTest {
    private val dialog = DialogRoute("feature-modal", "editor")
    private fun step(state: NavigationMachine.State, input: NavigationMachine.Input) =
        NavigationMachine.reduce(state, input).state
    private fun opened(welcomeRequired: Boolean? = null) =
        NavigationMachine.initial("journal", "visit-0", welcomeRequired = welcomeRequired)
    private fun ready() = step(opened(welcomeRequired = false), Fact.Restored(null, failed = false))

    private val states: Map<PhaseId, NavigationMachine.State> = mapOf(
        NavigationSpace.OPENING to opened(),
        NavigationSpace.WELCOME to step(opened(welcomeRequired = true), Fact.Restored(null, failed = false)),
        NavigationSpace.RESTORING to opened(welcomeRequired = false),
        NavigationSpace.READY to ready(),
        NavigationSpace.IN_FLIGHT to step(ready(), Intent.Navigate(AppRoute.Docs(), "visit-1")),
        NavigationSpace.DIALOG to step(ready(), Intent.ShowDialog(dialog)),
        NavigationSpace.BLOCKED to step(opened(welcomeRequired = false), Fact.Restored(null, failed = true)),
        NavigationSpace.CLEANUP_UNKNOWN to step(ready(), Fact.SaveFailed("journal", committed = true)),
        NavigationSpace.NOTICE to step(ready(), Intent.Report("Недоступно")),
    )

    private val inputs: Map<InputId, NavigationMachine.Input> = mapOf(
        NavigationSpace.NAVIGATE to Intent.Navigate(AppRoute.Docs(), "next"),
        NavigationSpace.RESOLVE to Intent.Resolve(AppRoute.Chat("session")),
        NavigationSpace.RESET to Intent.Reset(AppRoute.Chat(), "journal-2", "visit-9"),
        NavigationSpace.BACK to Intent.Back,
        NavigationSpace.FORWARD to Intent.Forward,
        NavigationSpace.LINK to Intent.Link("magicpaper://docs", "next"),
        NavigationSpace.BROWSER_VISIT to Intent.BrowserVisit("journal", "visit-0"),
        NavigationSpace.WELCOME_DONE to Intent.Welcome(false),
        NavigationSpace.WELCOME_REQUIRED to Intent.Welcome(true),
        NavigationSpace.SHOW_DIALOG to Intent.ShowDialog(dialog),
        NavigationSpace.DISMISS_DIALOG to Intent.DismissDialog(null),
        NavigationSpace.PRESENTATION to Intent.Presentation("visit-0", "scroll=2"),
        NavigationSpace.REPORT to Intent.Report("Недоступно"),
        NavigationSpace.CLEAR_ERROR to Intent.ClearError,
        NavigationSpace.RESTORED to Fact.Restored(null, failed = false),
        NavigationSpace.RESTORE_FAILED_IN to Fact.Restored(null, failed = true),
        NavigationSpace.PROJECTED to Fact.Projected,
        NavigationSpace.PROJECTION_FAILED to Fact.ProjectionFailed,
        NavigationSpace.SAVE_FAILED to Fact.SaveFailed("journal"),
        NavigationSpace.SAVE_FAILED_COMMITTED to Fact.SaveFailed("journal", committed = true),
        NavigationSpace.SAVED to Fact.Saved("journal"),
    )

    @Test fun declaredSpaceIsClosedAndMatchesEveryTransition() =
        verifyStateSpace(NavigationMachine, states, inputs)
}
