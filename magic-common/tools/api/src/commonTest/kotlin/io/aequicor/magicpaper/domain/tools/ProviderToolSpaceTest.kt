package io.aequicor.magicpaper.domain.tools

import io.aequicor.magicpaper.domain.tools.ProviderToolMachine.Fact
import io.aequicor.magicpaper.domain.tools.ProviderToolMachine.Intent
import io.aequicor.magicpaper.machine.verifyStateSpace
import kotlin.test.Test

/**
 * The representatives of [ProviderToolSpace], kept here rather than in the api so a shipped binary —
 * the browser bundle included — carries no fixtures.
 *
 * Each one is built by running the machine from `initial`, never by constructing a state, which is
 * what the `internal constructor` on `State` is there to enforce.
 */
class ProviderToolSpaceTest {
    private val call = ProviderToolMachine.Call("call", "tool", "fingerprint")
    private val output = ProviderToolMachine.OutputRef("run", "attempt", "identity", "digest")
    private fun step(state: ProviderToolMachine.State, input: ProviderToolMachine.Input) = ProviderToolMachine.reduce(state, input).state

    private val new = ProviderToolMachine.initial()
    private val modelReady = step(new, Intent.Start("run", "identity", setOf("tool"), maxTurns = 2, maxCalls = 1))
    private val modelPending = step(modelReady, Intent.RequestModel("attempt"))
    private val toolsReady = step(modelPending, Fact.ModelReturned("attempt", listOf(call), hasText = false))
    private val toolPending = step(toolsReady, Intent.ExecuteTool(call.id))
    private val outputPending = step(modelPending, Fact.ModelReturned("attempt", emptyList(), hasText = true, output = output))
    private val outputWriting = step(outputPending, Intent.StoreOutput)

    @Test fun declaredSpaceIsClosedAndMatchesEveryTransition() = verifyStateSpace(
        ProviderToolMachine,
        states = mapOf(
            ProviderToolSpace.NEW to new,
            ProviderToolSpace.MODEL_READY to modelReady,
            ProviderToolSpace.MODEL_PENDING to modelPending,
            ProviderToolSpace.TOOLS_READY to toolsReady,
            ProviderToolSpace.TOOL_PENDING to toolPending,
            ProviderToolSpace.OUTPUT_PENDING to outputPending,
            ProviderToolSpace.OUTPUT_WRITING to outputWriting,
            ProviderToolSpace.SUCCEEDED to step(outputWriting, Fact.OutputStored(output)),
            ProviderToolSpace.FAILED to step(modelReady, Fact.Failed("model", unknown = false)),
            ProviderToolSpace.CANCELLED to step(modelReady, Intent.Cancel),
            // A restart between attempts: nothing external was in flight, so the run can be reviewed.
            ProviderToolSpace.INTERRUPTED to step(modelReady, Fact.Restored),
            // A restart during an attempt: the outcome of the external call was never observed.
            ProviderToolSpace.UNKNOWN to step(toolPending, Fact.Restored),
        ),
        inputs = mapOf(
            ProviderToolSpace.START to Intent.Start("run", "identity", setOf("tool"), maxTurns = 2, maxCalls = 1),
            ProviderToolSpace.REQUEST_MODEL to Intent.RequestModel("attempt"),
            ProviderToolSpace.EXECUTE_TOOL to Intent.ExecuteTool(call.id),
            ProviderToolSpace.CANCEL to Intent.Cancel,
            ProviderToolSpace.STORE_OUTPUT to Intent.StoreOutput,
            ProviderToolSpace.MODEL_CALLS to Fact.ModelReturned("attempt", listOf(call), hasText = false),
            ProviderToolSpace.MODEL_TEXT to Fact.ModelReturned("attempt", emptyList(), hasText = true, output = output),
            ProviderToolSpace.TOOL_RETURNED to Fact.ToolReturned(call.id, ToolPhase.SUCCEEDED),
            ProviderToolSpace.TOOL_UNKNOWN to Fact.ToolReturned(call.id, ToolPhase.UNKNOWN),
            ProviderToolSpace.OUTPUT_STORED to Fact.OutputStored(output),
            ProviderToolSpace.FAILED_KNOWN to Fact.Failed("model", unknown = false),
            ProviderToolSpace.FAILED_UNKNOWN to Fact.Failed("model", unknown = true),
            ProviderToolSpace.RESTORED to Fact.Restored,
            ProviderToolSpace.PERSISTENCE_UNKNOWN to Fact.PersistenceUnknown,
        ),
    )
}
