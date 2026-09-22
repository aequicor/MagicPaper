package io.aequicor.magicpaper.domain.checks

import io.aequicor.magicpaper.domain.checks.CommandCheckMachine.State
import io.aequicor.magicpaper.domain.checks.CommandCheckMachine.Input
import io.aequicor.magicpaper.domain.checks.CommandCheckMachine.Phase
import io.aequicor.magicpaper.domain.checks.CommandCheckMachine.Effect
import io.aequicor.magicpaper.domain.checks.CommandCheckMachine.Reason
import kotlin.test.*

class CommandCheckMachineTest {
    private val ref = CheckRef(CheckScope("project", "session", "request", 0), "call", 0)
    private val command = CheckCommand(ref, "/workspace", listOf("check", "--private"))
    private val receipt = CheckProcessReceipt("receipt", "group", 123)
    private val result = CheckResult("output", 0)
    private val proof = CheckCompletionProof(receipt.id, "group-proof", "authority-proof", "artifact-proof")
    private fun apply(state: State, input: Input): State = CommandCheckMachine.reduce(state, input).also {
        assertTrue(it.effects.none { effect -> effect is Effect.Reject }, "$input: ${it.effects}")
    }.state
    private fun states(): Map<Phase, State> {
        val preparing = apply(CommandCheckMachine.initial(command.workspace), Input.Intent.Submit(command))
        val prepared = apply(preparing, Input.Fact.ProcessPrepared(ref, receipt))
        val running = apply(prepared, Input.Intent.Release(ref, receipt.id))
        val stopping = apply(running, Input.Intent.Stop(ref))
        val exited = apply(stopping, Input.Fact.Exited(ref, receipt.id, result))
        val stopped = apply(exited, Input.Fact.GroupStopped(ref, receipt.id, proof.groupStopped))
        val attesting = apply(stopped, Input.Fact.AuthorityRestored(ref, receipt.id, proof.authorityRestored))
        val finished = apply(attesting, Input.Fact.ArtifactsCommitted(ref, receipt.id, proof.artifactsCommitted))
        val unknown = apply(running, Input.Fact.Restored)
        return mapOf(Phase.PREPARING to preparing, Phase.PREPARED to prepared, Phase.RUNNING to running,
            Phase.STOPPING to stopping, Phase.ATTESTING to attesting, Phase.UNKNOWN to unknown, Phase.FINISHED to finished)
    }

    @Test fun transitionTableCoversEveryPhaseAndInputKind() {
        val all = Phase.entries.toSet()
        val table: List<Pair<Input, Set<Phase>>> = listOf(
            Input.Intent.Submit(command) to setOf(Phase.FINISHED),
            Input.Intent.Release(ref, receipt.id) to setOf(Phase.PREPARED),
            Input.Intent.Stop(ref) to all,
            Input.Intent.Inspect(ref) to setOf(Phase.UNKNOWN, Phase.FINISHED),
            Input.Fact.AuthorityRecorded(ref, "acl") to setOf(Phase.PREPARING),
            Input.Fact.ProcessPrepared(ref, receipt) to setOf(Phase.PREPARING),
            Input.Fact.Exited(ref, receipt.id, result) to setOf(Phase.RUNNING, Phase.STOPPING),
            Input.Fact.GroupStopped(ref, receipt.id, proof.groupStopped) to setOf(Phase.STOPPING),
            Input.Fact.AuthorityRestored(ref, receipt.id, proof.authorityRestored) to setOf(Phase.STOPPING),
            Input.Fact.ArtifactsCommitted(ref, receipt.id, proof.artifactsCommitted) to setOf(Phase.ATTESTING),
            Input.Fact.PreparationRejected(command, CheckResult("", null, "unavailable")) to emptySet(),
            Input.Fact.NotDispatched(ref, null, CheckResult("", null, "unavailable")) to setOf(Phase.PREPARING),
            Input.Fact.Failed(ref) to all - Phase.FINISHED,
            Input.Fact.NeighbourMissing(ref) to all - Phase.FINISHED,
            Input.Fact.CompletionRecovered(ref, proof, result) to setOf(Phase.UNKNOWN),
            Input.Fact.Restored to all,
            Input.Fact.PersistenceUnknown to all,
        )
        assertEquals(17, table.size)
        states().forEach { (phase, state) -> table.forEach { (input, accepted) ->
            val transition = CommandCheckMachine.reduce(state, input)
            val rejected = transition.effects.any { it is Effect.Reject }
            assertEquals(phase !in accepted, rejected, "$phase × $input")
            if (rejected) assertEquals(state, transition.state)
        } }
    }

    @Test fun restoreNeverEmitsEffectsAndUnknownOutranksNewCalls() {
        states().forEach { (phase, state) ->
            val restored = CommandCheckMachine.reduce(state, Input.Fact.Restored)
            assertTrue(restored.effects.isEmpty())
            assertEquals(phase != Phase.FINISHED, restored.state.unknown)
            if (phase != Phase.FINISHED) {
                val fresh = command.copy(ref = ref.copy(callId = "new"))
                assertEquals(listOf(Effect.Reject(Reason.UNKNOWN)), CommandCheckMachine.reduce(restored.state, Input.Intent.Submit(fresh)).effects)
            }
        }
    }

    @Test fun staleProbeCrashArtifactsDoNotFenceAFreshProbeSubmit() {
        val probeRef = CheckRef(CheckScope(CommandCheckMachine.SANDBOX_PROBE_PROJECT, CommandCheckMachine.SANDBOX_PROBE_PROJECT, "old-visit", 0), "probe")
        val probeCommand = CheckCommand(probeRef, "/checks/sandbox-probe", listOf("probe-script"))
        val admitted = CommandCheckMachine.reduce(CommandCheckMachine.initial(probeCommand.workspace), Input.Intent.Submit(probeCommand)).state
        val crashed = CommandCheckMachine.reduce(admitted, Input.Fact.Restored).state
        assertEquals(Phase.UNKNOWN, crashed.checks.getValue(probeRef).phase)
        assertTrue(crashed.unknown)
        val fresh = CheckCommand(probeRef.copy(callId = "new-visit"), probeCommand.workspace, listOf("probe-script"))
        assertEquals(listOf(Effect.Prepare(fresh)), CommandCheckMachine.reduce(crashed, Input.Intent.Submit(fresh)).effects)
    }

    @Test fun commandIdentityCannotBeReusedForDifferentArgumentsPolicyOrGeneration() {
        val finished = states().getValue(Phase.FINISHED)
        listOf(command.copy(arguments = listOf("other")), command.copy(policy = CheckPolicy.MANAGED_WORKTREE),
            command.copy(subdirectory = "child")).forEach { changed ->
            assertEquals(listOf(Effect.Reject(Reason.PAYLOAD_CHANGED)), CommandCheckMachine.reduce(finished, Input.Intent.Submit(changed)).effects)
        }
        val fresh = command.copy(ref = ref.copy(scope = ref.scope.copy(generation = 1)))
        assertIs<Effect.Prepare>(CommandCheckMachine.reduce(finished, Input.Intent.Submit(fresh)).effects.single())
        assertTrue(CommandCheckMachine.reduce(finished, Input.Intent.Submit(command)).effects.isEmpty())
    }

    @Test fun exitAloneCannotAttestAndWrongReceiptCannotComplete() {
        var state = states().getValue(Phase.RUNNING)
        state = apply(state, Input.Fact.Exited(ref, receipt.id, result))
        assertEquals(Phase.STOPPING, state.checks.getValue(ref).phase)
        val wrong = Input.Fact.GroupStopped(ref, "other", "proof")
        assertIs<Effect.Reject>(CommandCheckMachine.reduce(state, wrong).effects.single())
        state = apply(state, Input.Fact.GroupStopped(ref, receipt.id, proof.groupStopped))
        assertEquals(Phase.STOPPING, state.checks.getValue(ref).phase)
        val transition = CommandCheckMachine.reduce(state, Input.Fact.AuthorityRestored(ref, receipt.id, proof.authorityRestored))
        assertIs<Effect.Attest>(transition.effects.single())
        assertEquals(Phase.ATTESTING, transition.state.checks.getValue(ref).phase)
        assertNull(transition.state.checks.getValue(ref).completion)
    }

    @Test fun savedAuthorityMustBeRestoredBeforeNoDispatchCanCloseAdmission() {
        var state = states().getValue(Phase.PREPARING)
        state = apply(state, Input.Fact.AuthorityRecorded(ref, "old-acl"))
        val blocked = CheckResult("", null, "preflight unavailable")
        assertIs<Effect.Reject>(CommandCheckMachine.reduce(state, Input.Fact.NotDispatched(ref, null, blocked)).effects.single())
        assertIs<Effect.Reject>(CommandCheckMachine.reduce(state, Input.Fact.ProcessPrepared(ref, receipt)).effects.single())
        state = apply(state, Input.Fact.NotDispatched(ref, "restored-acl", blocked))
        assertEquals(Phase.FINISHED, state.checks.getValue(ref).phase)
    }

    @Test fun persistenceUncertaintyRejectsEvenStoredCompletionAndExactInspection() {
        val state = apply(states().getValue(Phase.FINISHED), Input.Fact.PersistenceUnknown)
        listOf(Input.Intent.Submit(command), Input.Intent.Inspect(ref), Input.Fact.CompletionRecovered(ref, proof, result)).forEach {
            assertEquals(listOf(Effect.Reject(Reason.UNKNOWN)), CommandCheckMachine.reduce(state, it).effects)
        }
    }

    @Test fun delayedForeignCompletionCannotResolveUnknownAttempt() {
        val state = states().getValue(Phase.UNKNOWN)
        listOf(Input.Fact.CompletionRecovered(ref.copy(attempt = 1), proof, result),
            Input.Fact.CompletionRecovered(ref, proof.copy(receiptId = "foreign"), result),
            Input.Fact.CompletionRecovered(ref, proof.copy(groupStopped = ""), result)).forEach {
            assertIs<Effect.Reject>(CommandCheckMachine.reduce(state, it).effects.single())
        }
        val recovered = CommandCheckMachine.reduce(state, Input.Fact.CompletionRecovered(ref, proof, result))
        assertTrue(recovered.effects.isEmpty())
        assertEquals(Phase.FINISHED, recovered.state.checks.getValue(ref).phase)
    }

    @Test fun recoveredCompletionCannotContradictAnyAlreadyRecordedFact() {
        var state = states().getValue(Phase.RUNNING)
        state = apply(state, Input.Fact.Exited(ref, receipt.id, result.copy(exitCode = 7)))
        state = apply(state, Input.Fact.GroupStopped(ref, receipt.id, "original-group"))
        state = apply(state, Input.Fact.AuthorityRestored(ref, receipt.id, "original-authority"))
        state = apply(state, Input.Fact.Failed(ref))
        val exact = proof.copy(groupStopped = "original-group", authorityRestored = "original-authority")
        listOf(Input.Fact.CompletionRecovered(ref, exact, result),
            Input.Fact.CompletionRecovered(ref, exact.copy(groupStopped = "different"), result.copy(exitCode = 7)),
            Input.Fact.CompletionRecovered(ref, exact.copy(authorityRestored = "different"), result.copy(exitCode = 7))).forEach { fact ->
            val transition = CommandCheckMachine.reduce(state, fact)
            assertIs<Effect.Reject>(transition.effects.single())
            assertEquals(state, transition.state)
        }
        apply(state, Input.Fact.CompletionRecovered(ref, exact, result.copy(exitCode = 7)))
    }
}
