package io.aequicor.magicpaper.data.storage

import io.aequicor.magicpaper.data.storage.DraftMachine.Effect
import io.aequicor.magicpaper.data.storage.DraftMachine.Fact
import io.aequicor.magicpaper.data.storage.DraftMachine.Intent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DraftMachineTest {
    private val generation = 7L
    private val epoch = 3L
    private val restored = Fact.Restored(revision = 5, ownerEpoch = 2, resetEpoch = epoch, stored = true)
    private val plain = DraftFailure("save draft", StorageException.Kind.WRITE, committed = false)
    private val committed = DraftFailure("clean draft", StorageException.Kind.CLEANUP, committed = true)

    private fun step(state: DraftMachine.State, input: DraftMachine.Input) = DraftMachine.reduce(state, input).state
    private fun effects(state: DraftMachine.State, input: DraftMachine.Input) = DraftMachine.reduce(state, input).effects
    private fun opened() = step(DraftMachine.initial(generation), Fact.ResetEpochObserved(epoch))
    private fun loaded() = step(opened(), restored)
    private fun edited() = step(loaded(), Intent.Edit(generation))
    private fun writing() = step(edited(), Intent.Persist(generation, 1))
    private fun saved() = step(writing(), Fact.Written(1))

    @Test fun stateInputTableGuardsHydrationOwnershipAndExactVersion() {
        val states = listOf(
            DraftMachine.initial(generation),
            step(DraftMachine.initial(generation), Intent.Edit(generation)),
            opened(), loaded(), edited(), writing(), saved(),
            step(writing(), Fact.Failed(plain)),
            step(writing(), Fact.Failed(committed)),
            step(step(saved(), Intent.Clear(generation, 1)), Fact.Cleared(1)),
            step(loaded(), Intent.Revoke),
        )
        val inputs = listOf(
            Intent.Edit(generation), Intent.Persist(generation, 1), Intent.Clear(generation, 1),
            Intent.Cleanup(generation), Intent.Revoke,
            Fact.ResetEpochObserved(9), restored, Fact.Written(1), Fact.Cleared(1),
            Fact.Failed(plain), Fact.CleanupRetried(committed),
        )
        // Explicit acceptance table: each row is a state, each column an input above.
        val allowed = listOf(
            "10011101111", "10011101111", "10011011111", "11011011111", "11111011111",
            "11111011111", "11111011111", "11111011111", "11111011111", "11011011111",
            "01011011111",
        )
        states.forEachIndexed { row, state ->
            inputs.forEachIndexed { column, input ->
                val transition = DraftMachine.reduce(state, input)
                assertEquals(allowed[row][column] == '1', transition.effects.none { it is Effect.Reject },
                    "row=$row column=$column input=$input")
                if (allowed[row][column] == '0') assertEquals(state, transition.state, "row=$row column=$column")
            }
        }
    }

    @Test fun anotherWriterOfTheSameKeyIsRefusedByGenerationAndByReset() {
        assertTrue(effects(loaded(), Intent.Edit(generation + 1)).any { it is Effect.Reject })
        assertTrue(effects(loaded(), Intent.Clear(generation + 1, 0)).any { it is Effect.Reject })
        // A stale writer must not spend a revision either: refusal leaves the allocation untouched.
        assertEquals(loaded(), DraftMachine.reduce(loaded(), Intent.Persist(generation + 1, 1)).state)
        assertTrue(effects(loaded(), Fact.ResetEpochObserved(epoch + 1)).any { it is Effect.Reject })
        assertTrue(effects(opened(), Fact.Restored(5, 2, epoch + 1, true)).any { it is Effect.Reject })
    }

    @Test fun editBeforeHydrationKeepsItsVersionAndRestoreNeverLowersAnAllocation() {
        val typed = step(DraftMachine.initial(generation), Intent.Edit(generation))
        assertEquals(1, typed.version)
        val hydrated = step(step(typed, Fact.ResetEpochObserved(epoch)), restored)
        assertTrue(hydrated.loaded)
        assertEquals(0, hydrated.savedVersion)
        assertTrue(hydrated.pendingWrite)
        assertEquals(5, hydrated.revision)
        // A retried restore reports the durable revision; an interrupted write already spent more.
        val interrupted = step(writing(), Fact.Failed(plain))
        assertEquals(6, interrupted.revision)
        assertEquals(6, step(interrupted, Fact.Restored(5, 2, epoch, true)).revision)
    }

    @Test fun supersededWriteIsSkippedWhileNewerTextKeepsItsPendingWrite() {
        val write = effects(edited(), Intent.Persist(generation, 1)).filterIsInstance<Effect.Write>().single()
        assertEquals(Effect.Write(version = 1, revision = 6), write)
        val typedAgain = step(writing(), Intent.Edit(generation))
        val acknowledged = step(typedAgain, Fact.Written(1))
        assertEquals(2, acknowledged.version)
        assertEquals(1, acknowledged.savedVersion)
        assertTrue(acknowledged.saving, "the newer edit is still unsaved")
        assertTrue(effects(acknowledged, Intent.Persist(generation, 1)).isEmpty())
        assertEquals(Effect.Write(version = 2, revision = 7),
            effects(acknowledged, Intent.Persist(generation, 2)).filterIsInstance<Effect.Write>().single())
        // A late acknowledgement of an older version cannot retract a newer proven write.
        assertEquals(1, step(acknowledged, Fact.Written(0)).savedVersion)
    }

    @Test fun clearAppliesOnlyToItsOwnVersionAndTextTypedDuringTheDeleteSurvives() {
        val delete = effects(saved(), Intent.Clear(generation, 1)).filterIsInstance<Effect.Delete>().single()
        assertEquals(Effect.Delete(version = 1, revision = 7, ownerEpoch = 2, resetEpoch = epoch), delete)
        val deleting = step(saved(), Intent.Clear(generation, 1))
        val replaced = step(deleting, Fact.Cleared(1))
        assertEquals(2, replaced.version)
        assertEquals(2, replaced.savedVersion)
        assertFalse(replaced.saving)
        val typedDuringDelete = step(deleting, Intent.Edit(generation))
        val kept = step(typedDuringDelete, Fact.Cleared(1))
        assertEquals(2, kept.version)
        assertEquals(1, kept.savedVersion)
        assertTrue(kept.pendingWrite, "the tombstone must not swallow text typed while it was written")
    }

    @Test fun committedCleanupStaysUnknownUntilItsOwnRetryIsAcknowledged() {
        val unknown = step(writing(), Fact.Failed(committed))
        assertTrue(unknown.unknown)
        assertFalse(unknown.saving)
        assertEquals(listOf(Effect.RetryCleanup), effects(unknown, Intent.Cleanup(generation)))
        // A different failure is not evidence that this one was cleaned up.
        assertEquals(unknown, step(unknown, Fact.CleanupRetried(plain)))
        val resolved = step(unknown, Fact.CleanupRetried(committed))
        assertFalse(resolved.unknown)
        assertNull(resolved.failure)
        assertTrue(effects(resolved, Intent.Cleanup(generation)).isEmpty())
        // An ordinary failure has a known outcome and never asks for cleanup.
        assertTrue(effects(step(writing(), Fact.Failed(plain)), Intent.Cleanup(generation)).isEmpty())
    }

    @Test fun anUnknownCleanupOutlivesTheKeystrokeThatTakesItsNotice() {
        val typedAfter = step(step(writing(), Fact.Failed(committed)), Intent.Edit(generation))
        assertNull(typedAfter.failure, "the notice belongs to the edit the user is making now")
        assertTrue(typedAfter.unknown, "the durable outcome is still unknown")
        assertEquals(listOf(Effect.RetryCleanup), effects(typedAfter, Intent.Cleanup(generation)))
        // A proven write of the newer text is not evidence that the earlier cleanup ran.
        val written = step(step(typedAfter, Intent.Persist(generation, 2)), Fact.Written(2))
        assertTrue(written.unknown)
        assertEquals(listOf(Effect.RetryCleanup), effects(written, Intent.Cleanup(generation)))
        val settled = step(written, Fact.CleanupRetried(committed))
        assertFalse(settled.unknown)
        assertFalse(settled.saving, "an acknowledged cleanup does not revive a stale saving indicator")
        assertEquals(written.savedVersion, settled.savedVersion)
    }

    @Test fun revokedWriterNeitherEditsNorClearsNorSpendsARevision() {
        val revoked = step(saved(), Intent.Revoke)
        assertTrue(effects(revoked, Intent.Edit(generation)).any { it is Effect.Reject })
        assertTrue(effects(revoked, Intent.Clear(generation, 1)).any { it is Effect.Reject })
        assertEquals(revoked, DraftMachine.reduce(revoked, Intent.Persist(generation, 2)).state)
        assertEquals(saved().revision, revoked.revision)
    }
}
