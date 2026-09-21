package io.aequicor.magicpaper.data.storage

import io.aequicor.magicpaper.data.storage.DraftMachine.Fact
import io.aequicor.magicpaper.data.storage.DraftMachine.Intent
import io.aequicor.magicpaper.machine.verifyStateSpace
import kotlin.test.Test

/**
 * The representatives of [DraftSpace], kept here rather than in the api: a shipped binary — the
 * browser bundle included — has no business carrying fixtures. The api still names every position;
 * these are only the example values the harness drives them with.
 *
 * Each one is built by running the machine from `initial`, never by constructing a state, which is
 * what the `internal constructor` on `State` is there to enforce.
 */
class DraftSpaceTest {
    private val generation = 7L
    private val epoch = 3L
    private val restored = Fact.Restored(revision = 5, ownerEpoch = 2, resetEpoch = epoch, stored = true)
    private val plain = DraftFailure("save draft", StorageException.Kind.WRITE, committed = false)
    private val committed = DraftFailure("clean draft", StorageException.Kind.CLEANUP, committed = true)

    private fun step(state: DraftMachine.State, input: DraftMachine.Input) = DraftMachine.reduce(state, input).state

    private val fresh = DraftMachine.initial(generation)
    private val freshEdited = step(fresh, Intent.Edit(generation))
    private val opened = step(fresh, Fact.ResetEpochObserved(epoch))
    private val loaded = step(opened, restored)
    private val editing = step(loaded, Intent.Edit(generation))
    private val writing = step(editing, Intent.Persist(generation, 1))
    private val saved = step(writing, Fact.Written(1))

    @Test fun declaredSpaceIsClosedAndMatchesEveryTransition() = verifyStateSpace(
        DraftMachine,
        states = mapOf(
            DraftSpace.FRESH to fresh,
            DraftSpace.FRESH_EDITED to freshEdited,
            DraftSpace.OPENED to opened,
            DraftSpace.LOADED to loaded,
            DraftSpace.EDITING to editing,
            DraftSpace.SAVED to saved,
            DraftSpace.FAILED to step(writing, Fact.Failed(plain)),
            DraftSpace.CLEANUP_UNKNOWN to step(writing, Fact.Failed(committed)),
            DraftSpace.REVOKED to step(loaded, Intent.Revoke),
        ),
        inputs = mapOf(
            DraftSpace.EDIT to Intent.Edit(generation),
            DraftSpace.PERSIST to Intent.Persist(generation, 1),
            DraftSpace.CLEAR to Intent.Clear(generation, 1),
            DraftSpace.CLEANUP to Intent.Cleanup(generation),
            DraftSpace.REVOKE to Intent.Revoke,
            // A foreign epoch: observing the same one is not a transition worth a column.
            DraftSpace.RESET_EPOCH to Fact.ResetEpochObserved(9),
            DraftSpace.RESTORED to restored,
            DraftSpace.WRITTEN to Fact.Written(1),
            DraftSpace.CLEARED to Fact.Cleared(1),
            DraftSpace.FAILED_PLAIN to Fact.Failed(plain),
            DraftSpace.FAILED_COMMITTED to Fact.Failed(committed),
            DraftSpace.CLEANUP_RETRIED to Fact.CleanupRetried(committed),
        ),
    )
}
