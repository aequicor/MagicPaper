package io.aequicor.magicpaper.domain

import io.aequicor.magicpaper.domain.RequestPinMachine.Fact
import io.aequicor.magicpaper.domain.RequestPinMachine.Intent
import io.aequicor.magicpaper.machine.verifyStateSpace
import kotlin.test.Test

/**
 * The representatives of [RequestPinSpace], kept here rather than in the api so a shipped binary —
 * the browser bundle included — carries no fixtures.
 *
 * Each one is built by running the machine from `initial`, never by constructing a state, which is
 * what the `internal constructor` on `State` is there to enforce.
 */
class RequestPinSpaceTest {
    private val source = PinMessage("input", "User request", true)
    private fun step(state: RequestPinMachine.State, input: RequestPinMachine.Input) = RequestPinMachine.reduce(state, input).state

    private val new = RequestPinMachine.initial()
    private val idle = step(new, Fact.Initialized(emptyList()))
    private val pending = step(idle, Intent.Sync(listOf(source), "profile-token", true))
    private val active = step(pending, Intent.Analyse("attempt"))

    @Test fun declaredSpaceIsClosedAndMatchesEveryTransition() = verifyStateSpace(
        RequestPinMachine,
        states = mapOf(
            RequestPinSpace.NEW to new,
            RequestPinSpace.IDLE to idle,
            RequestPinSpace.PENDING to pending,
            RequestPinSpace.ACTIVE to active,
            // A restart while the model call was in flight: the answer may or may not have been billed.
            RequestPinSpace.UNKNOWN to step(active, Fact.Restored),
            // A known failure — the response came back unusable — may be retried on reopen.
            RequestPinSpace.FAILED to step(active, Fact.Failed("attempt", unknown = false)),
            RequestPinSpace.REMOVED to step(pending, Intent.Remove),
            RequestPinSpace.PERSISTENCE_UNKNOWN to step(active, Fact.PersistenceUnknown),
        ),
        inputs = mapOf(
            RequestPinSpace.SYNC to Intent.Sync(listOf(source), "profile-token", true),
            RequestPinSpace.ANALYSE to Intent.Analyse("attempt"),
            RequestPinSpace.REMOVE to Intent.Remove,
            RequestPinSpace.INITIALIZED to Fact.Initialized(emptyList()),
            RequestPinSpace.COMPLETED to Fact.Completed("attempt", "Summary", true),
            RequestPinSpace.FAILED_KNOWN to Fact.Failed("attempt", unknown = false),
            RequestPinSpace.FAILED_UNKNOWN to Fact.Failed("attempt", unknown = true),
            RequestPinSpace.RESTORED to Fact.Restored,
            RequestPinSpace.PERSISTENCE_UNKNOWN_FACT to Fact.PersistenceUnknown,
        ),
    )
}
