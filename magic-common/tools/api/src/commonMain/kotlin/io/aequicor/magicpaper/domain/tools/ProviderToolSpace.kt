package io.aequicor.magicpaper.domain.tools

import io.aequicor.magicpaper.machine.Branch
import io.aequicor.magicpaper.machine.EffectId
import io.aequicor.magicpaper.machine.InputId
import io.aequicor.magicpaper.machine.InputSpec
import io.aequicor.magicpaper.machine.PhaseId
import io.aequicor.magicpaper.machine.StateSpace
import io.aequicor.magicpaper.machine.acceptance

/**
 * The state space of [ProviderToolMachine], declared so it can be read without running anything.
 *
 * This machine already has a [ProviderToolMachine.Phase] enum and acceptance is a function of it
 * alone, so the twelve positions map one to one. Where owners without an enum must invent a
 * `label`, this one only names the values it already has.
 *
 * What the declaration cannot express: an attempt id that is not the pending one, a tool call that
 * is not first in the queue, a reply that names an unavailable or repeated call, and the two bounds
 * (`maxTurns`, `maxCalls`). Those are refusals of identity or quantity, not of position, and stay in
 * `ProviderToolMachineTest`. `RequestModel` is accepted from `model-ready` only while a turn is
 * left, and the representative has one.
 */
object ProviderToolSpace : StateSpace<ProviderToolMachine.State, ProviderToolMachine.Input, ProviderToolMachine.Effect> {
    val NEW = PhaseId("new")
    val MODEL_READY = PhaseId("model-ready")
    val MODEL_PENDING = PhaseId("model-pending")
    val TOOLS_READY = PhaseId("tools-ready")
    val TOOL_PENDING = PhaseId("tool-pending")
    val OUTPUT_PENDING = PhaseId("output-pending")
    val OUTPUT_WRITING = PhaseId("output-writing")
    val SUCCEEDED = PhaseId("succeeded")
    val FAILED = PhaseId("failed")
    val CANCELLED = PhaseId("cancelled")
    val INTERRUPTED = PhaseId("interrupted")
    val UNKNOWN = PhaseId("unknown")

    val START = InputId("Start")
    val REQUEST_MODEL = InputId("RequestModel")
    val EXECUTE_TOOL = InputId("ExecuteTool")
    val CANCEL = InputId("Cancel")
    val STORE_OUTPUT = InputId("StoreOutput")
    val MODEL_CALLS = InputId("ModelReturnedCalls")
    val MODEL_TEXT = InputId("ModelReturnedText")
    val TOOL_RETURNED = InputId("ToolReturned")
    val TOOL_UNKNOWN = InputId("ToolReturnedUnknown")
    val OUTPUT_STORED = InputId("OutputStored")
    val FAILED_KNOWN = InputId("FailedKnown")
    val FAILED_UNKNOWN = InputId("FailedUnknown")
    val RESTORED = InputId("Restored")
    val PERSISTENCE_UNKNOWN = InputId("PersistenceUnknown")

    override val phases = listOf(
        NEW, MODEL_READY, MODEL_PENDING, TOOLS_READY, TOOL_PENDING, OUTPUT_PENDING, OUTPUT_WRITING,
        SUCCEEDED, FAILED, CANCELLED, INTERRUPTED, UNKNOWN,
    )

    override val inputs = listOf(
        InputSpec(START, Branch.INTENT),
        InputSpec(REQUEST_MODEL, Branch.INTENT),
        InputSpec(EXECUTE_TOOL, Branch.INTENT),
        InputSpec(CANCEL, Branch.INTENT),
        InputSpec(STORE_OUTPUT, Branch.INTENT),
        InputSpec(MODEL_CALLS, Branch.FACT),
        InputSpec(MODEL_TEXT, Branch.FACT),
        InputSpec(TOOL_RETURNED, Branch.FACT),
        InputSpec(TOOL_UNKNOWN, Branch.FACT),
        InputSpec(OUTPUT_STORED, Branch.FACT),
        InputSpec(FAILED_KNOWN, Branch.FACT),
        InputSpec(FAILED_UNKNOWN, Branch.FACT),
        InputSpec(RESTORED, Branch.FACT),
        InputSpec(PERSISTENCE_UNKNOWN, Branch.FACT),
    )

    override val effects = listOf(EffectId("InvokeModel"), EffectId("InvokeTool"), EffectId("PersistOutput"), EffectId("Reject"))

    // Rows follow `phases`, columns follow `inputs`. Everything is refused from `unknown` except
    // `Restored` and `PersistenceUnknown`; the four terminal rows refuse everything else, cancel and
    // failure included.
    override val accepts = acceptance(phases, inputs, listOf(
        //                    St Rm Ex Ca So Mc Mt Tr Tu Os Fk Fu Rs Pu
        /* new             */ "10010000001111",
        /* model-ready     */ "01010000001111",
        /* model-pending   */ "00010110001111",
        /* tools-ready     */ "00110000001111",
        /* tool-pending    */ "00010001101111",
        /* output-pending  */ "00011000001111",
        /* output-writing  */ "00010000011111",
        /* succeeded       */ "00000000000011",
        /* failed          */ "00000000000011",
        /* cancelled       */ "00000000000011",
        /* interrupted     */ "00000000000011",
        /* unknown         */ "00000000000011",
    ))

    override fun label(state: ProviderToolMachine.State): PhaseId = when (state.phase) {
        ProviderToolMachine.Phase.NEW -> NEW
        ProviderToolMachine.Phase.MODEL_READY -> MODEL_READY
        ProviderToolMachine.Phase.MODEL_PENDING -> MODEL_PENDING
        ProviderToolMachine.Phase.TOOLS_READY -> TOOLS_READY
        ProviderToolMachine.Phase.TOOL_PENDING -> TOOL_PENDING
        ProviderToolMachine.Phase.OUTPUT_PENDING -> OUTPUT_PENDING
        ProviderToolMachine.Phase.OUTPUT_WRITING -> OUTPUT_WRITING
        ProviderToolMachine.Phase.SUCCEEDED -> SUCCEEDED
        ProviderToolMachine.Phase.FAILED -> FAILED
        ProviderToolMachine.Phase.CANCELLED -> CANCELLED
        ProviderToolMachine.Phase.INTERRUPTED -> INTERRUPTED
        ProviderToolMachine.Phase.UNKNOWN -> UNKNOWN
    }

    override fun name(input: ProviderToolMachine.Input): InputId = when (input) {
        is ProviderToolMachine.Intent.Start -> START
        is ProviderToolMachine.Intent.RequestModel -> REQUEST_MODEL
        is ProviderToolMachine.Intent.ExecuteTool -> EXECUTE_TOOL
        ProviderToolMachine.Intent.Cancel -> CANCEL
        ProviderToolMachine.Intent.StoreOutput -> STORE_OUTPUT
        // One fact, two inputs: a reply with tool calls queues them, a reply with text alone is the
        // final answer and moves to writing it. Only the second can reach `output-pending`.
        is ProviderToolMachine.Fact.ModelReturned -> if (input.calls.isEmpty()) MODEL_TEXT else MODEL_CALLS
        // An unknown tool outcome is the one that fences every later action, so it is its own input.
        is ProviderToolMachine.Fact.ToolReturned -> if (input.phase == ToolPhase.UNKNOWN) TOOL_UNKNOWN else TOOL_RETURNED
        is ProviderToolMachine.Fact.OutputStored -> OUTPUT_STORED
        is ProviderToolMachine.Fact.Failed -> if (input.unknown) FAILED_UNKNOWN else FAILED_KNOWN
        ProviderToolMachine.Fact.Restored -> RESTORED
        ProviderToolMachine.Fact.PersistenceUnknown -> PERSISTENCE_UNKNOWN
    }

    override fun name(effect: ProviderToolMachine.Effect): EffectId = when (effect) {
        is ProviderToolMachine.Effect.InvokeModel -> EffectId("InvokeModel")
        is ProviderToolMachine.Effect.InvokeTool -> EffectId("InvokeTool")
        is ProviderToolMachine.Effect.PersistOutput -> EffectId("PersistOutput")
        is ProviderToolMachine.Effect.Reject -> EffectId("Reject")
    }

    override fun unknown(state: ProviderToolMachine.State) = state.phase == ProviderToolMachine.Phase.UNKNOWN

    override fun rejected(effect: ProviderToolMachine.Effect) = effect is ProviderToolMachine.Effect.Reject
}
