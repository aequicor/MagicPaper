package io.aequicor.magicpaper.navigation

import io.aequicor.magicpaper.navigation.NavigationMachine.Effect
import io.aequicor.magicpaper.navigation.NavigationMachine.Fact
import io.aequicor.magicpaper.navigation.NavigationMachine.Intent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NavigationMachineTest {
    private val dialog = DialogRoute("feature-modal", "editor")
    private fun step(state: NavigationMachine.State, input: NavigationMachine.Input) = NavigationMachine.reduce(state, input).state
    private fun effects(state: NavigationMachine.State, input: NavigationMachine.Input) = NavigationMachine.reduce(state, input).effects
    private fun opened(welcomeRequired: Boolean? = null) = NavigationMachine.initial("journal", "visit-0", welcomeRequired = welcomeRequired)
    private fun ready() = step(opened(welcomeRequired = false), Fact.Restored(null, failed = false))
    private fun navigated() = step(step(ready(), Intent.Navigate(AppRoute.Docs(), "visit-1")), Fact.Projected)

    /** The primary effect of a transition, so the table pins what happened and not only whether it was allowed. */
    private fun mark(effects: List<Effect>) = when {
        effects.any { it is Effect.Reject } -> 'R'
        effects.any { it is Effect.Project } -> 'P'
        effects.any { it is Effect.Persist } -> 'S'
        effects.any { it is Effect.ShowDialog } -> 'O'
        effects.contains(Effect.DismissDialog) -> 'D'
        else -> '-'
    }

    @Test fun stateInputTableGuardsTheGateTheInFlightStepAndBlockedWrites() {
        val states = listOf(
            opened(), step(opened(welcomeRequired = true), Fact.Restored(null, failed = false)), ready(), navigated(),
            step(ready(), Intent.Navigate(AppRoute.Docs(), "visit-1")),
            step(ready(), Intent.ShowDialog(dialog)),
            step(opened(welcomeRequired = false), Fact.Restored(null, failed = true)),
            step(ready(), Intent.Report("Недоступно")),
        )
        val inputs = listOf(
            Intent.Navigate(AppRoute.Docs(), "next"), Intent.Resolve(AppRoute.Chat("session")),
            Intent.Reset(AppRoute.Chat(), "journal-2", "visit-9"), Intent.Back, Intent.Forward,
            Intent.Link("magicpaper://docs", "next"), Intent.BrowserVisit("journal", "visit-0"),
            Intent.Welcome(false), Intent.ShowDialog(dialog), Intent.DismissDialog(null),
            Intent.Presentation("visit-0", "scroll=2"), Fact.Projected, Fact.SaveFailed("journal"),
            Fact.Saved("journal"),
        )
        // Explicit acceptance table: each row is a state, each column an input above; the letter is
        // the primary effect — Project, Save, Open/Dismiss dialog, Reject, or none.
        val expected = listOf(
            "SPP--S--O-SR--", "SPP--S--O-SR--", "PPP--P--O-SR--", "-PPP--P-O-SR--",
            "RRRRRRRRRRRSRR", "PPPDDPD-ODSR--", "PPP--P--O--R--", "PPP--P--O-SR--",
        )
        states.forEachIndexed { row, state ->
            inputs.forEachIndexed { column, input ->
                val transition = NavigationMachine.reduce(state, input)
                assertEquals(expected[row][column], mark(transition.effects), "row=$row column=$column input=$input")
                if (expected[row][column] == 'R') assertEquals(state, transition.state, "row=$row column=$column")
            }
        }
    }

    @Test fun anUnreadableJournalBlocksEveryWriteUntilAnExplicitReset() {
        val blocked = step(opened(welcomeRequired = false), Fact.Restored(null, failed = true))
        assertTrue(blocked.persistenceBlocked)
        assertEquals(NavigationMachine.RESTORE_FAILED, blocked.message)
        // The shell keeps working: the transition is projected, only the write is withheld.
        val moved = step(blocked, Intent.Navigate(AppRoute.Docs(), "visit-1"))
        val taken = NavigationMachine.reduce(moved, Fact.Projected)
        assertEquals(AppRoute.Docs(), taken.state.route)
        assertTrue(taken.effects.none { it is Effect.Persist })
        // A later notice must not hide the reason nothing is being saved.
        assertEquals(NavigationMachine.RESTORE_FAILED, step(taken.state, Intent.Report("Иное")).message)
        val reset = step(taken.state, Intent.Reset(AppRoute.Chat(), "journal-2", "visit-9"))
        assertEquals(false, reset.persistenceBlocked)
        assertNull(reset.message)
        assertTrue(NavigationMachine.reduce(reset, Fact.Projected).effects.any { it is Effect.Persist })
    }

    @Test fun aDestinationThatCannotBeBuiltIsNeitherShownNorPersisted() {
        val ready = ready()
        val flight = NavigationMachine.reduce(ready, Intent.Navigate(AppRoute.Docs(), "visit-1"))
        assertEquals(listOf(Effect.Project(ready.journal.navigate(AppRoute.Docs(), "visit-1"))), flight.effects)
        assertEquals(ready.journal, flight.state.journal, "the journal advances only after construction")
        val failed = NavigationMachine.reduce(flight.state, Fact.ProjectionFailed)
        assertEquals(ready.journal, failed.state.journal)
        assertNull(failed.state.pending)
        assertEquals(NavigationMachine.TRANSITION_FAILED, failed.state.message)
        assertTrue(failed.effects.none { it is Effect.Persist })
        // Restore is the same step, but its failure is also the evidence that writes must stop.
        val restoring = NavigationMachine.reduce(opened(welcomeRequired = false), Fact.Restored(navigated().journal, failed = false))
        val lost = NavigationMachine.reduce(restoring.state, Fact.ProjectionFailed)
        assertTrue(lost.state.loaded && lost.state.persistenceBlocked)
        assertEquals(NavigationMachine.RESTORE_FAILED, lost.state.message)
    }

    @Test fun restoredHistoryIsProjectedWithoutBeingWrittenBack() {
        val stored = navigated().journal
        val restoring = NavigationMachine.reduce(opened(welcomeRequired = false), Fact.Restored(stored, failed = false))
        assertEquals(listOf(Effect.Project(stored)), restoring.effects)
        val restored = NavigationMachine.reduce(restoring.state, Fact.Projected)
        assertEquals(stored, restored.state.journal)
        assertTrue(restored.state.loaded)
        assertTrue(restored.effects.isEmpty(), "restoring is not an edit")
    }

    @Test fun deferredLinksAreReleasedInOrderEachWithItsOwnVisit() {
        var gated = step(opened(welcomeRequired = true), Fact.Restored(null, failed = false))
        gated = step(gated, Intent.Link("magicpaper://docs", "unused"))
        gated = step(gated, Intent.Link("magicpaper://plugins", "unused"))
        assertEquals(listOf(AppRoute.Docs(), AppRoute.Plugins()), gated.journal.pendingRoutes)
        // A repeated link does not queue twice, and no destination is built while the gate is closed.
        assertTrue(effects(gated, Intent.Link("magicpaper://plugins", "unused")).isEmpty())
        assertEquals(1, gated.journal.visits.size)
        // Missing identity is refused rather than silently dropping a queued destination.
        assertTrue(effects(gated, Intent.Welcome(false, listOf("visit-1"))).any { it is Effect.Reject })
        assertEquals(gated.journal.pendingRoutes, step(gated, Intent.Welcome(false, listOf("visit-1"))).journal.pendingRoutes)
        val released = NavigationMachine.reduce(gated, Intent.Welcome(false, listOf("visit-1", "visit-2")))
        val candidate = released.effects.filterIsInstance<Effect.Project>().single().journal
        assertEquals(listOf("visit-0", "visit-1", "visit-2"), candidate.visits.map { it.id })
        assertEquals(listOf(AppRoute.Chat(), AppRoute.Docs(), AppRoute.Plugins()), candidate.visits.map { it.route })
        assertTrue(candidate.pendingRoutes.isEmpty())
    }

    @Test fun anOldCompletionCannotDismissTheReopenedDialogAndBackClosesItFirst() {
        val shown = step(ready(), Intent.ShowDialog(dialog))
        val reopened = DialogRoute(dialog.kind, dialog.entityId)
        val again = step(step(shown, Intent.DismissDialog(null)), Intent.ShowDialog(reopened))
        assertEquals(emptyList(), effects(again, Intent.DismissDialog(dialog)))
        assertSame(reopened, again.dialog)
        assertNull(step(again, Intent.DismissDialog(reopened)).dialog)
        // Back closes the dialog before it moves, and only then does history move.
        val moved = step(navigated(), Intent.ShowDialog(dialog))
        val closed = step(moved, Intent.Back)
        assertNull(closed.dialog)
        assertEquals(AppRoute.Docs(), closed.route)
        assertTrue(effects(closed, Intent.Back).any { it is Effect.Project })
    }

    @Test fun aFailedWriteOfAnotherWindowJournalIsNotThisWindowsNotice() {
        val ready = ready()
        assertEquals(NavigationMachine.SAVE_FAILED, step(ready, Fact.SaveFailed("journal")).message)
        assertNull(step(ready, Fact.SaveFailed("journal-2")).message)
        assertNull(step(step(ready, Fact.SaveFailed("journal")), Intent.ClearError).message)
        // A visit that the other window's journal never had is refused with its own reason.
        assertEquals(NavigationMachine.JOURNAL_UNAVAILABLE, step(ready, Intent.BrowserVisit("journal-2", "visit-0")).message)
        assertEquals(NavigationMachine.VISIT_UNAVAILABLE, step(ready, Intent.BrowserVisit("journal", "visit-7")).message)
        assertEquals(NavigationMachine.LINK_UNSUPPORTED, step(ready, Intent.Link("magicpaper://unknown/../x", "next")).message)
    }

    @Test fun aCommittedSnapshotWhoseReplacedStateRemainsIsNotALostWrite() {
        val ready = ready()
        assertFalse(step(ready, Fact.SaveFailed("journal")).unknown, "a write that did not commit is a known outcome")
        val orphaned = step(ready, Fact.SaveFailed("journal", committed = true))
        assertTrue(orphaned.unknown)
        assertEquals(NavigationMachine.SAVE_FAILED, orphaned.message)
        // Dismissing the notice, moving on and even a reset do not prove the orphaned state is gone.
        assertTrue(step(orphaned, Intent.ClearError).unknown)
        assertTrue(step(step(orphaned, Intent.Navigate(AppRoute.Docs(), "visit-1")), Fact.Projected).unknown)
        assertTrue(step(orphaned, Intent.Reset(AppRoute.Chat(), "journal-2", "visit-9")).unknown)
        // Only an acknowledged write collects the orphans, whichever journal it belonged to.
        val settled = step(orphaned, Fact.Saved("journal"))
        assertFalse(settled.unknown)
        assertNull(settled.message)
        assertTrue(step(step(ready, Fact.SaveFailed("journal-2", committed = true)), Fact.Saved("journal-2")).unknown.not())
        // A foreign journal's failed write is not this window's notice, but its orphans are still real.
        val foreign = step(ready, Fact.SaveFailed("journal-2", committed = true))
        assertNull(foreign.message)
        assertTrue(foreign.unknown)
        // An acknowledged write clears only its own notice, never an unrelated one.
        assertEquals(NavigationMachine.VISIT_UNAVAILABLE,
            step(step(ready, Intent.BrowserVisit("journal", "visit-7")), Fact.Saved("journal")).message)
    }
}
