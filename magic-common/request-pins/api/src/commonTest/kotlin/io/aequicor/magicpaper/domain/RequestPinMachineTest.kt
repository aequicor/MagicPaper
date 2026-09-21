package io.aequicor.magicpaper.domain

import io.aequicor.magicpaper.domain.RequestPinMachine.State
import io.aequicor.magicpaper.domain.RequestPinMachine.Input
import io.aequicor.magicpaper.domain.RequestPinMachine.Intent
import io.aequicor.magicpaper.domain.RequestPinMachine.Fact
import io.aequicor.magicpaper.domain.RequestPinMachine.Effect
import io.aequicor.magicpaper.domain.RequestPinMachine.Failure
import kotlin.test.*

class RequestPinMachineTest {
    private val source = PinMessage("input", "User request", true)
    private val initialized = reduce(initial(), Fact.Initialized(emptyList())).state
    private val pending = reduce(initialized, Intent.Sync(listOf(source), "profile-token", true)).state
    private val active = reduce(pending, Intent.Analyse("attempt")).state
    private val unknown = reduce(active, Fact.Restored).state
    private fun reduce(state: State, input: Input) = RequestPinMachine.reduce(state, input)
    private fun initial() = RequestPinMachine.initial()

    @Test fun stateAndInputMatrixOnlyAllowsExecutionAfterAnExplicitAvailableAnalysisIntent() {
        data class Case(val label: String, val state: State, val allowed: Set<String>)
        val transitions = mapOf<String, Input>(
            "init" to Fact.Initialized(emptyList()),
            "sync" to Intent.Sync(listOf(source), "profile-token", true),
            "analyse" to Intent.Analyse("attempt"),
            "complete" to Fact.Completed("attempt", "Summary", true),
            "fail" to Fact.Failed("attempt", unknown = true),
            "restore" to Fact.Restored,
            "remove" to Intent.Remove,
            "persistence" to Fact.PersistenceUnknown,
        )
        val cases = listOf(
            Case("new", initial(), setOf("init", "persistence")),
            Case("idle", initialized, setOf("sync", "restore", "remove", "persistence")),
            Case("pending", pending, setOf("sync", "analyse", "restore", "remove", "persistence")),
            Case("active", active, setOf("sync", "complete", "fail", "restore", "remove", "persistence")),
            Case("unknown", unknown, setOf("sync", "restore", "remove", "persistence")),
            Case("removed", reduce(pending, Intent.Remove).state, setOf("restore", "persistence")),
            Case("persistence unknown", reduce(active, Fact.PersistenceUnknown).state, setOf("persistence")),
        )
        for (case in cases) for ((label, input) in transitions) {
            val result = reduce(case.state, input)
            assertEquals(label in case.allowed, result.effects.none { it is Effect.Reject }, "${case.label} × $label")
            assertEquals(case.label == "pending" && label == "analyse", result.effects.any { it is Effect.Analyse }, "${case.label} × $label")
            if (label !in case.allowed) assertEquals(case.state, result.state)
        }
    }

    @Test fun restoredActiveRequestCannotBeRetriedByReopenOrProfileChangeButEditedTextIsANewInput() {
        assertTrue(source in unknown.unknown)
        assertNull(unknown.active)
        assertEquals(Failure.UNKNOWN_OUTCOME, unknown.failure)
        for (reopened in listOf(false, true)) for (token in listOf("profile-token", "new-profile")) {
            val restored = reduce(unknown, Intent.Sync(listOf(source), token, true, reopened)).state
            assertFalse(RequestPinMachine.pending(restored))
            assertTrue(reduce(restored, Intent.Analyse("new-attempt")).effects.single() is Effect.Reject)
        }
        val edited = reduce(unknown, Intent.Sync(listOf(source.copy(text = "An explicit edited request")), "profile-token", true)).state
        assertTrue(RequestPinMachine.pending(edited))
        assertEquals("An explicit edited request", reduce(edited, Intent.Analyse("edited")).effects.filterIsInstance<Effect.Analyse>().single().source.text)
    }

    @Test fun lateSummaryAfterEditCannotOverwriteNewTextOrPreserveAnOldActiveAttempt() {
        val edited = reduce(active, Intent.Sync(listOf(source.copy(text = "Edited")), "profile-token", true)).state
        val late = reduce(edited, Fact.Completed("attempt", "Old answer", true))
        assertNull(late.state.active)
        assertEquals("Edited", late.state.records.single().summary)
        assertTrue(late.effects.isEmpty(), "Stale model output must never checkpoint a summary")
        assertTrue(RequestPinMachine.pending(late.state))
    }

    @Test fun failedKnownResponseMayRetryOnReopenButUnknownResultRemainsFenced() {
        val known = reduce(active, Fact.Failed("attempt", unknown = false)).state
        assertFalse(RequestPinMachine.pending(known))
        assertTrue(RequestPinMachine.pending(reduce(known, Intent.Sync(listOf(source), "profile-token", true, reopened = true)).state))
        val ambiguous = reduce(active, Fact.Failed("attempt", unknown = true)).state
        assertFalse(RequestPinMachine.pending(reduce(ambiguous, Intent.Sync(listOf(source), "changed", true, reopened = true)).state))
    }

    @Test fun completionPreservesForcedClarificationAndBoundsSummaryWithoutTouchingSource() {
        val clarification = source.copy(id = "clarification", clarification = true)
        var state = reduce(initialized, Intent.Sync(listOf(source, clarification), "profile-token", true)).state
        state = reduce(state, Intent.Analyse("first")).state
        state = reduce(state, Fact.Completed("first", "First", true)).state
        state = reduce(state, Intent.Analyse("second")).state
        state = reduce(state, Fact.Completed("second", "Long\n\n summary ".repeat(50), true)).state
        assertFalse(state.records.last().newRequest)
        assertEquals(180, state.records.last().summary.length)
        assertEquals(clarification, state.records.last().source)
        assertEquals(1, state.records.pinGroups().size)
    }

    @Test fun completingAnotherSummaryKeepsAnUnresolvedUnknownSourceVisible() {
        val second = PinMessage("second", "A different request", true)
        var state = reduce(unknown, Intent.Sync(listOf(source, second), "profile-token", true)).state
        state = reduce(state, Intent.Analyse("second-attempt")).state
        state = reduce(state, Fact.Completed("second-attempt", "Second summary", true)).state
        assertEquals(Failure.UNKNOWN_OUTCOME, state.failure)
        assertFalse(state.records.first().analysed)
        assertTrue(state.records.last().analysed)
        assertFalse(RequestPinMachine.pending(state))
    }
}
