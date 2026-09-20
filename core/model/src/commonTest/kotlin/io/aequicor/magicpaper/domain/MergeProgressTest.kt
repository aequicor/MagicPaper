package io.aequicor.magicpaper.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MergeProgressTest {
    private val attempt = StageAttempt("a", "s", StageAssignment("profile", "model"))

    private fun stored(phase: AttemptPhase?) = attempt.copy(mergePhase = phase).mergeProgress

    private val everyProgress = listOf(MergeProgress.Untouched, MergeProgress.Admitted, MergeProgress.Running,
        MergeProgress.AwaitingVerdict, MergeProgress.Settled, MergeProgress.Rejected)

    @Test fun theStoredFormIsUnchangedAndRoundTrips() {
        // The field stays `AttemptPhase?` on disk: this slice renames the values, it does not
        // migrate them. Every name must write back exactly the phase it was read from.
        assertEquals(listOf(null, AttemptPhase.PREPARED, AttemptPhase.EXECUTING,
            AttemptPhase.VERIFYING, AttemptPhase.COMPLETE, AttemptPhase.FAILED),
            everyProgress.map { attempt.merging(it).mergePhase })
        everyProgress.forEach { assertEquals(it, attempt.merging(it).mergeProgress, "$it") }
    }

    @Test fun anUnusedPhaseFromAnOlderPlanRunsItsResolverAgain() {
        // INTEGRATING was never a merge phase any producer wrote. Reading it as anything
        // settled would accept a conflict resolution that no verifier ever saw.
        assertEquals(MergeProgress.Running, stored(AttemptPhase.INTEGRATING))
        assertTrue(stored(AttemptPhase.INTEGRATING).needsTurn)
        assertFalse(stored(AttemptPhase.INTEGRATING).needsResolver)
    }

    @Test fun onlyAnAbsentOrRejectedResolutionAdmitsAFreshResolver() {
        everyProgress.forEach {
            val expected = it == MergeProgress.Untouched || it == MergeProgress.Rejected
            assertEquals(expected, it.needsResolver, "$it")
        }
    }

    @Test fun onlyTheResolversOwnReportEndsItsTurn() {
        // Not a verdict and not a settled conflict: a merge that is settled yet still fails to
        // integrate must admit a resolver again, never ask a verifier to re-read an old report.
        everyProgress.forEach { assertEquals(it != MergeProgress.AwaitingVerdict, it.needsTurn, "$it") }
    }

    @Test fun aConflictIsUnresolvedBetweenItsFirstRecordAndItsAcceptance() {
        assertFalse(MergeProgress.Untouched.unresolved, "Конфликта не было")
        assertFalse(MergeProgress.Settled.unresolved, "Разрешение принято")
        listOf(MergeProgress.Admitted, MergeProgress.Running, MergeProgress.AwaitingVerdict, MergeProgress.Rejected)
            .forEach { assertTrue(it.unresolved, "$it") }
    }

    @Test fun aStartedMergeGovernsTheRunEvenAfterItSettles() {
        // The merge assignment, not the work assignment, resolves the model from here on.
        assertFalse(MergeProgress.Untouched.started)
        everyProgress.filter { it != MergeProgress.Untouched }.forEach { assertTrue(it.started, "$it") }
    }

    @Test fun theFullResolutionWalksFromUntouchedToSettled() {
        var walking = attempt
        assertEquals(MergeProgress.Untouched, walking.mergeProgress)
        assertTrue(walking.mergeProgress.needsResolver)

        walking = walking.merging(MergeProgress.Admitted)
        assertTrue(walking.mergeProgress.needsTurn)
        assertFalse(walking.mergeProgress.needsResolver, "Допущенный исполнитель не допускается второй раз")

        walking = walking.merging(MergeProgress.Running).merging(MergeProgress.AwaitingVerdict)
        assertFalse(walking.mergeProgress.needsTurn, "Отчёт получен; дальше решает судья")
        assertTrue(walking.mergeProgress.unresolved)

        walking = walking.merging(MergeProgress.Settled)
        assertFalse(walking.mergeProgress.unresolved)
        assertTrue(walking.mergeProgress.started)
    }

    @Test fun aRejectedVerdictReturnsToAdmittingAResolver() {
        val rejected = attempt.merging(MergeProgress.AwaitingVerdict).merging(MergeProgress.Rejected)
        assertTrue(rejected.mergeProgress.needsResolver)
        assertTrue(rejected.mergeProgress.unresolved, "Отклонённое разрешение не закрывает конфликт")
    }
}
