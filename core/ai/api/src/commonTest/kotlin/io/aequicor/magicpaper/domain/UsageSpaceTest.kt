package io.aequicor.magicpaper.domain

import io.aequicor.magicpaper.domain.UsageMachine.Fact
import io.aequicor.magicpaper.domain.UsageMachine.Intent
import io.aequicor.magicpaper.machine.verifyStateSpace
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The representatives of [UsageSpace], kept here rather than in the api so a shipped binary carries
 * no fixtures.
 *
 * Each one is built by running the machine from `initial`, never by constructing a state, which is
 * what the `internal constructor` on `State` is there to enforce. Every loaded one is at generation
 * `g1`, so one observation fits all of them.
 */
class UsageSpaceTest {
    private fun stamp(id: String) = UsageMachine.Stamp(id, 10)
    private val capture = UsageObservation.Captured("g1")
    private val record = UsageRecord("request", createdAt = 2, scope = UsageScope.chat("owner"), provider = "provider", model = "model")
    private fun step(state: UsageMachine.State, input: UsageMachine.Input) = UsageMachine.reduce(state, input).state

    private val new = UsageMachine.initial()
    private val loaded = step(new, Fact.Initialized(UsageArchive(startedAt = 1), stamp("g1")))

    /**
     * Unconfirmed persistence fences before anything else, an archive that was never loaded included.
     * The representatives reach it only from a loaded one, so they cannot tell the two orders apart.
     */
    @Test fun unconfirmedPersistenceOutranksAnArchiveThatWasNeverLoaded() {
        assertEquals(UsageSpace.PERSISTENCE_UNKNOWN, UsageSpace.label(step(new, Fact.PersistenceUnknown(stamp("unknown")))))
    }

    @Test fun declaredSpaceIsClosedAndMatchesEveryTransition() = verifyStateSpace(
        UsageMachine,
        states = mapOf(
            UsageSpace.NEW to new,
            UsageSpace.LOADED to loaded,
            UsageSpace.PERSISTENCE_UNKNOWN to step(loaded, Fact.PersistenceUnknown(stamp("unknown"))),
        ),
        inputs = mapOf(
            UsageSpace.IMPORT to Intent.Import(UsageArchive(startedAt = 1), stamp("g2")),
            UsageSpace.CLEAR to Intent.Clear(stamp("g3")),
            UsageSpace.INITIALIZED to Fact.Initialized(UsageArchive(startedAt = 1), stamp("g1")),
            UsageSpace.RECORDED to Fact.Recorded(capture, record, null, stamp("record")),
            UsageSpace.CONTEXT_OBSERVED to Fact.ContextObserved(capture, ContextUsageSnapshot("chat:owner", "m", updatedAt = 3), stamp("context")),
            UsageSpace.CUMULATIVE_OBSERVED to Fact.CumulativeObserved(capture, UsageMachine.CumulativeProof("thread", "one",
                TokenUsage(total = 10), TokenUsage(total = 10), record.copy(id = "source", completed = true)), stamp("counter")),
            UsageSpace.PERSISTENCE_UNKNOWN_FACT to Fact.PersistenceUnknown(stamp("unknown")),
        ),
    )
}
