package io.aequicor.magicpaper.domain.tools

import kotlin.test.*

class ProviderToolMachineTest {
    private val call = ProviderToolMachine.Call("call", "tool", "fingerprint")
    private fun start() = ProviderToolMachine.reduce(ProviderToolMachine.initial(),
        ProviderToolMachine.Intent.Start("run", "identity", setOf("tool"), maxTurns = 2, maxCalls = 1)).state
    private fun pending() = ProviderToolMachine.reduce(start(), ProviderToolMachine.Intent.RequestModel("attempt")).state
    private fun ready() = ProviderToolMachine.reduce(pending(), ProviderToolMachine.Fact.ModelReturned("attempt", listOf(call), false)).state
    private fun executing() = ProviderToolMachine.reduce(ready(), ProviderToolMachine.Intent.ExecuteTool(call.id)).state
    private fun reject(state: ProviderToolMachine.State, input: ProviderToolMachine.Input) {
        val result = ProviderToolMachine.reduce(state, input)
        assertEquals(state, result.state)
        assertIs<ProviderToolMachine.Effect.Reject>(result.effects.single())
    }

    @Test fun intentsAreTheOnlyInputsThatCanRequestExternalWork() {
        val model = ProviderToolMachine.reduce(start(), ProviderToolMachine.Intent.RequestModel("attempt"))
        assertEquals(listOf(ProviderToolMachine.Effect.InvokeModel("attempt")), model.effects)
        val returned = ProviderToolMachine.reduce(model.state, ProviderToolMachine.Fact.ModelReturned("attempt", listOf(call), false))
        assertTrue(returned.effects.isEmpty())
        val tool = ProviderToolMachine.reduce(returned.state, ProviderToolMachine.Intent.ExecuteTool(call.id))
        assertEquals(listOf(ProviderToolMachine.Effect.InvokeTool(call.id)), tool.effects)
        val completed = ProviderToolMachine.reduce(tool.state, ProviderToolMachine.Fact.ToolReturned(call.id, ToolPhase.SUCCEEDED))
        assertEquals(ProviderToolMachine.Phase.MODEL_READY, completed.state.phase)
        assertTrue(completed.effects.isEmpty())
    }

    @Test fun staleUnknownAndChangedCallIdentitiesCannotAdvance() {
        reject(pending(), ProviderToolMachine.Fact.ModelReturned("stale", listOf(call), false))
        reject(pending(), ProviderToolMachine.Fact.ModelReturned("attempt", listOf(call, call), false))
        reject(pending(), ProviderToolMachine.Fact.ModelReturned("attempt", listOf(call.copy(name = "other")), false))
        reject(ready(), ProviderToolMachine.Intent.ExecuteTool("other"))
        reject(executing(), ProviderToolMachine.Fact.ToolReturned("other", ToolPhase.SUCCEEDED))
        var state = ProviderToolMachine.reduce(executing(), ProviderToolMachine.Fact.ToolReturned(call.id, ToolPhase.SUCCEEDED)).state
        state = ProviderToolMachine.reduce(state, ProviderToolMachine.Intent.RequestModel("next")).state
        reject(state, ProviderToolMachine.Fact.ModelReturned("next", listOf(call.copy(fingerprint = "different")), false))
    }

    @Test fun unknownHasPriorityOverCancellationAndNewWork() {
        val unknown = ProviderToolMachine.reduce(executing(), ProviderToolMachine.Fact.ToolReturned(call.id, ToolPhase.UNKNOWN)).state
        for (input in listOf(ProviderToolMachine.Intent.Cancel, ProviderToolMachine.Intent.RequestModel("again"),
            ProviderToolMachine.Intent.ExecuteTool(call.id), ProviderToolMachine.Fact.ToolReturned(call.id, ToolPhase.SUCCEEDED))) reject(unknown, input)
        assertEquals(unknown, ProviderToolMachine.reduce(unknown, ProviderToolMachine.Fact.Restored).state)
    }

    @Test fun restoringEveryReachableStageNeverRequestsWork() {
        val cancelled = ProviderToolMachine.reduce(start(), ProviderToolMachine.Intent.Cancel).state
        val output = ProviderToolMachine.OutputRef("run", "attempt", "identity", "digest")
        val received = ProviderToolMachine.reduce(pending(), ProviderToolMachine.Fact.ModelReturned("attempt", emptyList(), true, output)).state
        val writing = ProviderToolMachine.reduce(received, ProviderToolMachine.Intent.StoreOutput).state
        val success = ProviderToolMachine.reduce(writing, ProviderToolMachine.Fact.OutputStored(output)).state
        val expected = listOf(ProviderToolMachine.initial() to ProviderToolMachine.Phase.NEW,
            start() to ProviderToolMachine.Phase.INTERRUPTED, pending() to ProviderToolMachine.Phase.UNKNOWN,
            ready() to ProviderToolMachine.Phase.INTERRUPTED, executing() to ProviderToolMachine.Phase.UNKNOWN,
            received to ProviderToolMachine.Phase.UNKNOWN, writing to ProviderToolMachine.Phase.UNKNOWN,
            cancelled to ProviderToolMachine.Phase.CANCELLED, success to ProviderToolMachine.Phase.SUCCEEDED)
        expected.forEach { (state, phase) ->
            val restored = ProviderToolMachine.reduce(state, ProviderToolMachine.Fact.Restored)
            assertEquals(phase, restored.state.phase)
            assertTrue(restored.effects.isEmpty())
        }
    }

    @Test fun cancellationBeforeAndDuringAnAttemptHaveDifferentOutcomes() {
        assertEquals(ProviderToolMachine.Phase.CANCELLED, ProviderToolMachine.reduce(ready(), ProviderToolMachine.Intent.Cancel).state.phase)
        assertEquals(ProviderToolMachine.Phase.UNKNOWN, ProviderToolMachine.reduce(executing(), ProviderToolMachine.Intent.Cancel).state.phase)
        assertEquals(ProviderToolMachine.Phase.UNKNOWN, ProviderToolMachine.reduce(pending(), ProviderToolMachine.Intent.Cancel).state.phase)
    }

    @Test fun boundsAndEmptyRepliesAreRejectedBeforeMoreEffects() {
        reject(pending(), ProviderToolMachine.Fact.ModelReturned("attempt", emptyList(), false))
        var state = ProviderToolMachine.reduce(executing(), ProviderToolMachine.Fact.ToolReturned(call.id, ToolPhase.FAILED)).state
        state = ProviderToolMachine.reduce(state, ProviderToolMachine.Intent.RequestModel("next")).state
        reject(state, ProviderToolMachine.Fact.ModelReturned("next", listOf(call), false))
    }
}
