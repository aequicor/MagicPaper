package io.aequicor.magicpaper.navigation

import io.aequicor.magicpaper.machine.Branch
import io.aequicor.magicpaper.machine.EffectId
import io.aequicor.magicpaper.machine.InputId
import io.aequicor.magicpaper.machine.InputSpec
import io.aequicor.magicpaper.machine.PhaseId
import io.aequicor.magicpaper.machine.StateSpace
import io.aequicor.magicpaper.machine.acceptance

/**
 * The state space of [NavigationMachine].
 *
 * Its table test pins the *leading effect* of every cell, which this matrix cannot carry, so that
 * test stays exactly as it is. What is added here is what it could not state: that the declared
 * positions are closed under every input, and that four inputs it never tabulated — `Report`,
 * `ClearError`, `Restored` and `ProjectionFailed` — have a column at all.
 *
 * Which destinations exist is a value on the state ([RouteAvailability]), not a position, so the
 * matrix cannot carry it; `NavigationAvailabilityTest` pins it instead. The promise is that a
 * route the host cannot build never enters the journal, by any door:
 * - `Navigate`, `Resolve`, `Reset` and `ShowDialog` refuse it with `Reject`, state unchanged;
 * - `Link` names it in a notice, because an external link is the one way a user reaches it;
 * - `Restored` trims it from the journal it projects and says so in a notice, because a saved
 *   journal outlives the build that wrote it.
 * The screen that said "unavailable" is gone: nothing is left to render for such a route.
 */
object NavigationSpace : StateSpace<NavigationMachine.State, NavigationMachine.Input, NavigationMachine.Effect> {
    val OPENING = PhaseId("opening")
    val WELCOME = PhaseId("welcome")
    val RESTORING = PhaseId("restoring")
    val READY = PhaseId("ready")
    val IN_FLIGHT = PhaseId("in-flight")
    val DIALOG = PhaseId("dialog")
    val BLOCKED = PhaseId("journal-unreadable")
    val CLEANUP_UNKNOWN = PhaseId("cleanup-unknown")
    val NOTICE = PhaseId("notice")

    val NAVIGATE = InputId("Navigate")
    val RESOLVE = InputId("Resolve")
    val RESET = InputId("Reset")
    val BACK = InputId("Back")
    val FORWARD = InputId("Forward")
    val LINK = InputId("Link")
    val BROWSER_VISIT = InputId("BrowserVisit")
    val WELCOME_DONE = InputId("Welcome")
    val WELCOME_REQUIRED = InputId("WelcomeRequired")
    val SHOW_DIALOG = InputId("ShowDialog")
    val DISMISS_DIALOG = InputId("DismissDialog")
    val PRESENTATION = InputId("Presentation")
    val REPORT = InputId("Report")
    val CLEAR_ERROR = InputId("ClearError")
    val RESTORED = InputId("Restored")
    val RESTORE_FAILED_IN = InputId("RestoreFailed")
    val PROJECTED = InputId("Projected")
    val PROJECTION_FAILED = InputId("ProjectionFailed")
    val SAVE_FAILED = InputId("SaveFailed")
    val SAVE_FAILED_COMMITTED = InputId("SaveFailedCommitted")
    val SAVED = InputId("Saved")

    override val phases = listOf(OPENING, WELCOME, RESTORING, READY, IN_FLIGHT, DIALOG, BLOCKED, CLEANUP_UNKNOWN, NOTICE)

    override val inputs = listOf(
        InputSpec(NAVIGATE, Branch.INTENT), InputSpec(RESOLVE, Branch.INTENT), InputSpec(RESET, Branch.INTENT),
        InputSpec(BACK, Branch.INTENT), InputSpec(FORWARD, Branch.INTENT), InputSpec(LINK, Branch.INTENT),
        InputSpec(BROWSER_VISIT, Branch.INTENT), InputSpec(WELCOME_DONE, Branch.INTENT),
        InputSpec(WELCOME_REQUIRED, Branch.INTENT), InputSpec(SHOW_DIALOG, Branch.INTENT),
        InputSpec(DISMISS_DIALOG, Branch.INTENT), InputSpec(PRESENTATION, Branch.INTENT),
        InputSpec(REPORT, Branch.INTENT), InputSpec(CLEAR_ERROR, Branch.INTENT),
        InputSpec(RESTORED, Branch.FACT), InputSpec(RESTORE_FAILED_IN, Branch.FACT),
        InputSpec(PROJECTED, Branch.FACT), InputSpec(PROJECTION_FAILED, Branch.FACT),
        InputSpec(SAVE_FAILED, Branch.FACT), InputSpec(SAVE_FAILED_COMMITTED, Branch.FACT),
        InputSpec(SAVED, Branch.FACT),
    )

    override val effects = listOf(
        EffectId("Reject"), EffectId("Project"), EffectId("Persist"),
        EffectId("ShowDialog"), EffectId("DismissDialog"),
    )

    override val accepts = acceptance(phases, inputs, ROWS)

    override fun label(state: NavigationMachine.State): PhaseId = when {
        // A step in work outranks everything: while it runs the machine refuses every other input.
        state.pending != null -> IN_FLIGHT
        state.dialog != null -> DIALOG
        // An unreadable journal outranks a later notice: it explains why nothing is being saved.
        state.restoreError != null -> BLOCKED
        state.unacknowledgedCleanup -> CLEANUP_UNKNOWN
        !state.welcomeResolved -> OPENING
        state.welcomeRequired -> WELCOME
        !state.loaded -> RESTORING
        state.error != null -> NOTICE
        else -> READY
    }

    override fun name(input: NavigationMachine.Input): InputId = when (input) {
        is NavigationMachine.Intent.Navigate -> NAVIGATE
        is NavigationMachine.Intent.Resolve -> RESOLVE
        is NavigationMachine.Intent.Reset -> RESET
        NavigationMachine.Intent.Back -> BACK
        NavigationMachine.Intent.Forward -> FORWARD
        is NavigationMachine.Intent.Link -> LINK
        is NavigationMachine.Intent.BrowserVisit -> BROWSER_VISIT
        // The gate going up and the gate coming down are different transitions, not one input.
        is NavigationMachine.Intent.Welcome -> if (input.required) WELCOME_REQUIRED else WELCOME_DONE
        is NavigationMachine.Intent.ShowDialog -> SHOW_DIALOG
        is NavigationMachine.Intent.DismissDialog -> DISMISS_DIALOG
        is NavigationMachine.Intent.Presentation -> PRESENTATION
        is NavigationMachine.Intent.Report -> REPORT
        NavigationMachine.Intent.ClearError -> CLEAR_ERROR
        is NavigationMachine.Fact.Restored -> if (input.failed) RESTORE_FAILED_IN else RESTORED
        NavigationMachine.Fact.Projected -> PROJECTED
        NavigationMachine.Fact.ProjectionFailed -> PROJECTION_FAILED
        // Only a committed write whose cleanup was lost opens an unknown outcome.
        is NavigationMachine.Fact.SaveFailed -> if (input.committed) SAVE_FAILED_COMMITTED else SAVE_FAILED
        is NavigationMachine.Fact.Saved -> SAVED
    }

    override fun name(effect: NavigationMachine.Effect): EffectId = when (effect) {
        is NavigationMachine.Effect.Reject -> EffectId("Reject")
        is NavigationMachine.Effect.Project -> EffectId("Project")
        is NavigationMachine.Effect.Persist -> EffectId("Persist")
        is NavigationMachine.Effect.ShowDialog -> EffectId("ShowDialog")
        NavigationMachine.Effect.DismissDialog -> EffectId("DismissDialog")
    }

    override fun unknown(state: NavigationMachine.State) = state.unknown

    override fun rejected(effect: NavigationMachine.Effect) = effect is NavigationMachine.Effect.Reject
}

// Rows follow `phases`, columns follow `inputs`. Only two facts are accepted while a step is in
// work, and they are the two that end it: the shell must not advance past a screen it is still
// building. Every other position accepts everything except those same two.
private val ROWS = listOf(
    /* opening            */ "111111111111111100111",
    /* welcome            */ "111111111111111100111",
    /* restoring          */ "111111111111111100111",
    /* ready              */ "111111111111111100111",
    /* in-flight          */ "000000000000000011000",
    /* dialog             */ "111111111111111100111",
    /* journal-unreadable */ "111111111111111100111",
    /* cleanup-unknown    */ "111111111111111100111",
    /* notice             */ "111111111111111100111",
)
