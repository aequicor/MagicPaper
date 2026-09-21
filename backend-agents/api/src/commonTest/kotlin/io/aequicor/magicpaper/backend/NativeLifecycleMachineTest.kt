package io.aequicor.magicpaper.backend

import kotlin.test.*
import kotlinx.serialization.json.Json

class NativeLifecycleMachineTest {
    private val run = NativeRunRef("session", "request")
    private val attempt = NativeAttemptRef(run, 0)
    private val process = NativeProcessIdentity("session", 42, 123)
    private fun state(vararg inputs: NativeLifecycleMachine.Input) = inputs.fold(NativeLifecycleMachine.initial()) { s, input ->
        NativeLifecycleMachine.reduce(s, input).also { assertTrue(it.effects.none { it is NativeLifecycleMachine.Effect.Reject }) }.state
    }
    private fun delivered() = state(NativeLifecycleMachine.Intent.Begin(run), NativeLifecycleMachine.Fact.LaunchRequested(run),
        NativeLifecycleMachine.Fact.Attached(attempt, process), NativeLifecycleMachine.Fact.DeliveryRequested(attempt, NativeDelivery.PI_STDIN))
    private fun rejected(s: NativeLifecycleMachine.State, input: NativeLifecycleMachine.Input) {
        val next = NativeLifecycleMachine.reduce(s, input)
        assertEquals(s, next.state)
        assertIs<NativeLifecycleMachine.Effect.Reject>(next.effects.single())
    }
    @Test fun deliveryIsUnknownBeforeAnyBytesAreSent() {
        val next = delivered()
        assertEquals(NativeOutcome.UNKNOWN, next.runs.getValue(run).attempts.single().outcome)
        rejected(next, NativeLifecycleMachine.Fact.DeliveryRequested(attempt, NativeDelivery.PI_STDIN))
    }
    @Test fun processStopCannotConfirmExternalOutcome() {
        val next = NativeLifecycleMachine.reduce(delivered(), NativeLifecycleMachine.Fact.Stopped(attempt)).state
        assertEquals(NativeOutcome.UNKNOWN, next.runs.getValue(run).attempts.single().outcome)
        rejected(next, NativeLifecycleMachine.Intent.Begin(run.copy(requestId = "new")))
    }
    @Test fun restoreNeverEmitsNativeExecutionEffects() {
        val restored = NativeLifecycleMachine.reduce(delivered(), NativeLifecycleMachine.Fact.Restored)
        assertTrue(restored.effects.isEmpty())
        assertEquals(NativeTermination.UNKNOWN, restored.state.runs.getValue(run).attempts.single().termination)
        rejected(restored.state, NativeLifecycleMachine.Intent.Begin(run))
        rejected(restored.state, NativeLifecycleMachine.Fact.DeliveryRequested(attempt, NativeDelivery.CODEX_TURN))
    }
    @Test fun continuationNeedsTerminalEvidenceAndCleanup() {
        val terminal = NativeLifecycleMachine.reduce(delivered(), NativeLifecycleMachine.Fact.Terminal(attempt, NativeOutcome.SUCCEEDED)).state
        rejected(terminal, NativeLifecycleMachine.Fact.LaunchRequested(run))
        val stopped = NativeLifecycleMachine.reduce(terminal, NativeLifecycleMachine.Fact.Stopped(attempt)).state
        val next = NativeLifecycleMachine.reduce(stopped, NativeLifecycleMachine.Fact.LaunchRequested(run))
        assertEquals(NativeAttemptRef(run, 1), assertIs<NativeLifecycleMachine.Effect.Launch>(next.effects.single()).attempt)
    }
    @Test fun explicitAcknowledgementKeepsUnknownAndRequiresFreshIdentity() {
        var current = NativeLifecycleMachine.reduce(delivered(), NativeLifecycleMachine.Fact.Stopped(attempt)).state
        current = NativeLifecycleMachine.reduce(current, NativeLifecycleMachine.Fact.RunFinished(run)).state
        val ack = NativeRecoveryAcknowledgement("ack", attempt, "parent-abandon")
        current = NativeLifecycleMachine.reduce(current, NativeLifecycleMachine.Intent.Acknowledge(ack)).state
        assertEquals(NativeOutcome.UNKNOWN, current.runs.getValue(run).attempts.single().outcome)
        rejected(current, NativeLifecycleMachine.Intent.Begin(run, ack))
        rejected(current, NativeLifecycleMachine.Intent.Begin(run.copy(requestId = "new")))
        val next = NativeLifecycleMachine.reduce(current, NativeLifecycleMachine.Intent.Begin(run.copy(requestId = "new"), ack))
        assertIs<NativeLifecycleMachine.Effect.Execute>(next.effects.single())
        rejected(next.state, NativeLifecycleMachine.Fact.Terminal(attempt, NativeOutcome.SUCCEEDED))
        val completed = NativeLifecycleMachine.reduce(next.state, NativeLifecycleMachine.Fact.RunFinished(run.copy(requestId = "new"))).state
        val later = NativeLifecycleMachine.reduce(completed, NativeLifecycleMachine.Intent.Begin(run.copy(requestId = "later")))
        assertIs<NativeLifecycleMachine.Effect.Execute>(later.effects.single())
        rejected(completed, NativeLifecycleMachine.Intent.Begin(run.copy(requestId = "reuse-ack"), ack))
    }
    @Test fun persistenceUnknownPrecedesEveryOtherAdmissionCheck() {
        val unknown = NativeLifecycleMachine.reduce(delivered(), NativeLifecycleMachine.Fact.PersistenceUnknown).state
        val result = NativeLifecycleMachine.reduce(unknown, NativeLifecycleMachine.Intent.Begin(NativeRunRef("", "")))
        assertContains(assertIs<NativeLifecycleMachine.Effect.Reject>(result.effects.single()).reason, "журнала")
        rejected(unknown, NativeLifecycleMachine.Fact.Restored)
    }
    @Test fun parentMayAcknowledgeAnExactStoppedPredecessorWithoutChangingItsKnownOutcome() {
        for (outcome in listOf(NativeOutcome.NOT_DISPATCHED, NativeOutcome.SUCCEEDED, NativeOutcome.FAILED)) {
            var current = if (outcome == NativeOutcome.NOT_DISPATCHED)
                state(NativeLifecycleMachine.Intent.Begin(run), NativeLifecycleMachine.Fact.LaunchRequested(run))
            else NativeLifecycleMachine.reduce(delivered(), NativeLifecycleMachine.Fact.Terminal(attempt, outcome)).state
            current = NativeLifecycleMachine.reduce(current, NativeLifecycleMachine.Fact.Stopped(attempt)).state
            current = NativeLifecycleMachine.reduce(current, NativeLifecycleMachine.Fact.RunFinished(run)).state
            val ack = NativeRecoveryAcknowledgement("ack-$outcome", attempt, "parent-workspace-abandon")
            current = NativeLifecycleMachine.reduce(current, NativeLifecycleMachine.Intent.Acknowledge(ack)).also {
                assertTrue(it.effects.none { effect -> effect is NativeLifecycleMachine.Effect.Reject })
            }.state
            assertEquals(outcome, current.runs.getValue(run).attempts.single().outcome)
            rejected(current, NativeLifecycleMachine.Intent.Begin(run.copy(requestId = "fresh")))
            val admitted = NativeLifecycleMachine.reduce(current, NativeLifecycleMachine.Intent.Begin(run.copy(requestId = "fresh"), ack))
            assertIs<NativeLifecycleMachine.Effect.Execute>(admitted.effects.single())
            val finished = NativeLifecycleMachine.reduce(admitted.state, NativeLifecycleMachine.Fact.RunFinished(run.copy(requestId = "fresh"))).state
            rejected(finished, NativeLifecycleMachine.Intent.Begin(run.copy(requestId = "another"), ack))
        }
    }
    @Test fun inputsRoundTripWithoutRequestPayload() {
        val entries = listOf<NativeLifecycleMachine.Input>(NativeLifecycleMachine.Intent.Begin(run),
            NativeLifecycleMachine.Fact.Attached(attempt, process), NativeLifecycleMachine.Fact.DeliveryRequested(attempt, NativeDelivery.PI_STDIN),
            NativeLifecycleMachine.Fact.Terminal(attempt, NativeOutcome.SUCCEEDED), NativeLifecycleMachine.Fact.Restored)
        entries.forEach {
            val text = Json.encodeToString(NativeLifecycleMachine.Input.serializer(), it)
            assertEquals(it, Json.decodeFromString(NativeLifecycleMachine.Input.serializer(), text))
            assertFalse(text.contains("prompt")); assertFalse(text.contains("token"))
        }
    }

    @Test fun noDispatchProofRequiresAnExistingClosedRunWithNoAdmittedAttempt() {
        val proof = NativeNoDispatchProof(run, "proof", "epoch")
        val confirm = NativeLifecycleMachine.Fact.NoDispatchConfirmed(proof)
        rejected(NativeLifecycleMachine.initial(), confirm)
        rejected(state(NativeLifecycleMachine.Intent.Begin(run)), confirm)
        rejected(state(NativeLifecycleMachine.Intent.Begin(run), NativeLifecycleMachine.Fact.LaunchRequested(run),
            NativeLifecycleMachine.Fact.RunFinished(run)), confirm)
        val closed = state(NativeLifecycleMachine.Intent.Begin(run), NativeLifecycleMachine.Fact.RunFinished(run))
        val proved = NativeLifecycleMachine.reduce(closed, confirm)
        assertEquals(proof, proved.state.runs.getValue(run).noDispatchProof)
        assertTrue(proved.state.runs.getValue(run).attempts.isEmpty())
        assertTrue(proved.effects.isEmpty())
        rejected(proved.state, NativeLifecycleMachine.Fact.LaunchRequested(run))
        rejected(proved.state, NativeLifecycleMachine.Fact.NoDispatchConfirmed(proof.copy(proofId = "changed")))
        rejected(proved.state, NativeLifecycleMachine.Intent.Begin(run.copy(requestId = "fresh")))
    }

    @Test fun noDispatchDecisionIsExactAndConsumedByOneFreshRunWithoutChangingOldJournalInputs() {
        val proof = NativeNoDispatchProof(run, "proof", "epoch")
        val ack = NativeNoDispatchAcknowledgement("ack", proof, "decision")
        val proved = state(NativeLifecycleMachine.Intent.Begin(run), NativeLifecycleMachine.Fact.RunFinished(run),
            NativeLifecycleMachine.Fact.NoDispatchConfirmed(proof))
        for (wrong in listOf(ack.copy(proof = proof.copy(journalGeneration = "other")),
            ack.copy(proof = proof.copy(run = run.copy(sessionId = "other"))),
            ack.copy(proof = proof.copy(proofId = "other")), ack.copy(parentDecisionId = ""))) {
            rejected(proved, NativeLifecycleMachine.Intent.AcknowledgeNoDispatch(wrong))
        }
        val acknowledged = NativeLifecycleMachine.reduce(proved, NativeLifecycleMachine.Intent.AcknowledgeNoDispatch(ack)).state
        rejected(acknowledged, NativeLifecycleMachine.Intent.Begin(run, noDispatchAcknowledgement = ack))
        rejected(acknowledged, NativeLifecycleMachine.Intent.Begin(run.copy(requestId = "fresh")))
        rejected(acknowledged, NativeLifecycleMachine.Intent.Begin(run.copy(requestId = "fresh"),
            NativeRecoveryAcknowledgement("attempt", attempt, "decision"), ack))
        val fresh = run.copy(requestId = "fresh")
        val admitted = NativeLifecycleMachine.reduce(acknowledged, NativeLifecycleMachine.Intent.Begin(fresh, noDispatchAcknowledgement = ack))
        assertEquals(ack, admitted.state.runs.getValue(fresh).previousNoDispatchAcknowledgement)
        assertIs<NativeLifecycleMachine.Effect.Execute>(admitted.effects.single())
        val ended = NativeLifecycleMachine.reduce(admitted.state, NativeLifecycleMachine.Fact.RunFinished(fresh)).state
        rejected(ended, NativeLifecycleMachine.Intent.Begin(run.copy(requestId = "reuse"), noDispatchAcknowledgement = ack))
        listOf<NativeLifecycleMachine.Input>(NativeLifecycleMachine.Fact.NoDispatchConfirmed(proof),
            NativeLifecycleMachine.Intent.AcknowledgeNoDispatch(ack), NativeLifecycleMachine.Intent.Begin(fresh, noDispatchAcknowledgement = ack)).forEach { input ->
            assertEquals(input, Json.decodeFromString(NativeLifecycleMachine.Input.serializer(),
                Json.encodeToString(NativeLifecycleMachine.Input.serializer(), input)))
        }
        // The additive optional field must not change the meaning of existing Begin records.
        assertEquals(NativeLifecycleMachine.Intent.Begin(run), Json.decodeFromString(NativeLifecycleMachine.Input.serializer(),
            """{"type":"io.aequicor.magicpaper.backend.NativeLifecycleMachine.Intent.Begin","run":{"sessionId":"session","requestId":"request"}}"""))
    }
}
