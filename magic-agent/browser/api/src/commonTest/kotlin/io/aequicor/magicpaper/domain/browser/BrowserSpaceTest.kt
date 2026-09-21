package io.aequicor.magicpaper.domain.browser

import io.aequicor.magicpaper.domain.browser.BrowserMachine.Fact
import io.aequicor.magicpaper.domain.browser.BrowserMachine.Intent
import io.aequicor.magicpaper.machine.verifyStateSpace
import kotlin.test.Test

/**
 * The representatives of [BrowserSpace], kept here rather than in the api so a shipped binary carries
 * no fixtures.
 *
 * Each one is built by running the machine from `initial`, never by constructing a state, which is
 * what the `internal constructor` on `State` is there to enforce.
 */
class BrowserSpaceTest {
    private val owner = BrowserMachine.Owner("session", "request")
    private val fingerprint = "a".repeat(64)
    private val click = BrowserMachine.Operation("click", BrowserMachine.Action.CLICK, fingerprint)
    private val snapshot = BrowserMachine.Operation("snapshot", BrowserMachine.Action.SNAPSHOT, fingerprint)
    private fun step(state: BrowserMachine.State, input: BrowserMachine.Input) = BrowserMachine.reduce(state, input).state

    private val new = BrowserMachine.initial()
    private val ready = step(new, Intent.Start(owner))
    private val executing = step(ready, Intent.Perform(click))
    private val closing = step(ready, Intent.Close)

    @Test fun declaredSpaceIsClosedAndMatchesEveryTransition() = verifyStateSpace(
        BrowserMachine,
        states = mapOf(
            BrowserSpace.NEW to new,
            BrowserSpace.READY to ready,
            BrowserSpace.EXECUTING to executing,
            BrowserSpace.CLOSING to closing,
            BrowserSpace.CLOSED to step(closing, Fact.Closed),
            // The click may have reached the page and its result was never seen: it must not be repeated.
            BrowserSpace.READY_UNKNOWN to step(executing, Fact.Failed("click", beforeEffect = false)),
            // The browser went away with the click in flight, so nothing is left to resolve it against.
            BrowserSpace.CLOSED_UNKNOWN to step(executing, Fact.Restored),
            BrowserSpace.PERSISTENCE_UNKNOWN to step(ready, Fact.PersistenceUnknown),
            BrowserSpace.PERSISTENCE_UNKNOWN_CLOSING to step(closing, Fact.PersistenceUnknown),
        ),
        inputs = mapOf(
            BrowserSpace.START to Intent.Start(owner),
            BrowserSpace.PERFORM_ACTION to Intent.Perform(BrowserMachine.Operation("next-click", BrowserMachine.Action.CLICK, fingerprint)),
            BrowserSpace.PERFORM_OBSERVATION to Intent.Perform(snapshot),
            BrowserSpace.CLOSE to Intent.Close,
            BrowserSpace.COMPLETED to Fact.Completed("click", setOf("tab-1")),
            BrowserSpace.FAILED_BEFORE_EFFECT to Fact.Failed("click", beforeEffect = true),
            BrowserSpace.FAILED_AFTER_EFFECT to Fact.Failed("click", beforeEffect = false),
            BrowserSpace.NEIGHBOUR_MISSING to Fact.NeighbourMissing("click"),
            BrowserSpace.CLOSED_FACT to Fact.Closed,
            BrowserSpace.RESTORED to Fact.Restored,
            BrowserSpace.PERSISTENCE_UNKNOWN_FACT to Fact.PersistenceUnknown,
        ),
    )
}
