package io.aequicor.magicpaper.domain

import io.aequicor.magicpaper.machine.Branch
import io.aequicor.magicpaper.machine.EffectId
import io.aequicor.magicpaper.machine.InputId
import io.aequicor.magicpaper.machine.InputSpec
import io.aequicor.magicpaper.machine.PhaseId
import io.aequicor.magicpaper.machine.StateSpace
import io.aequicor.magicpaper.machine.acceptance

/**
 * The state space of [MediaGenerationMachine], declared so it can be read without running anything.
 *
 * The machine has a [MediaGenerationMachine.Stage] enum, but it is not the position. `UNKNOWN`
 * stands for three situations the reducer treats differently — a submission whose outcome was never
 * seen, a job that is known and can be polled, and a produced output that can still be downloaded —
 * and `persistenceUnknown` is a flag beside the stage that refuses everything but `Restored`. So
 * fourteen positions are named where the enum lists eleven.
 *
 * What the declaration cannot express: an input whose payload decides the outcome. `Download` from
 * an output with no URL is accepted only when the interpreter still holds the bytes, an operation
 * with a raw prompt or inline bytes is refused at `Create`, and a blank job id or asset id is
 * refused. Those stay in `MediaGenerationMachineTest`. The `unknown-output` row assumes a known job
 * id, which is what the representative carries; an imported operation with an output and no job
 * would refuse `Poll` from the same position.
 */
object MediaGenerationSpace : StateSpace<MediaGenerationMachine.State, MediaGenerationMachine.Input, MediaGenerationMachine.Effect> {
    val NEW = PhaseId("new")
    val CREATED = PhaseId("created")
    val SUBMITTING = PhaseId("submitting")
    val WAITING = PhaseId("waiting")
    val POLLING = PhaseId("polling")
    val OUTPUT_AVAILABLE = PhaseId("output-available")
    val DOWNLOADING = PhaseId("downloading")
    val READY = PhaseId("ready")
    val FAILED = PhaseId("failed")
    val UNKNOWN_SUBMIT = PhaseId("unknown-submission")
    val UNKNOWN_JOB = PhaseId("unknown-job")
    val UNKNOWN_OUTPUT = PhaseId("unknown-output")
    val DELETED = PhaseId("deleted")
    val PERSISTENCE_UNKNOWN = PhaseId("persistence-unknown")

    val CREATE = InputId("Create")
    val SUBMIT = InputId("Submit")
    val POLL = InputId("Poll")
    val DOWNLOAD = InputId("Download")
    val DELETE = InputId("Delete")
    val IMPORT = InputId("Import")
    val ACCEPTED = InputId("Accepted")
    val PENDING = InputId("Pending")
    val OUTPUT = InputId("Output")
    val ASSET = InputId("Asset")
    val FAILURE_CONFIRMED = InputId("FailureConfirmed")
    val FAILURE_UNCONFIRMED = InputId("FailureUnconfirmed")
    val RESTORED = InputId("Restored")
    val PERSISTENCE_UNKNOWN_FACT = InputId("PersistenceUnknown")

    override val phases = listOf(
        NEW, CREATED, SUBMITTING, WAITING, POLLING, OUTPUT_AVAILABLE, DOWNLOADING, READY, FAILED,
        UNKNOWN_SUBMIT, UNKNOWN_JOB, UNKNOWN_OUTPUT, DELETED, PERSISTENCE_UNKNOWN,
    )

    override val inputs = listOf(
        InputSpec(CREATE, Branch.INTENT),
        InputSpec(SUBMIT, Branch.INTENT),
        InputSpec(POLL, Branch.INTENT),
        InputSpec(DOWNLOAD, Branch.INTENT),
        InputSpec(DELETE, Branch.INTENT),
        InputSpec(IMPORT, Branch.FACT),
        InputSpec(ACCEPTED, Branch.FACT),
        InputSpec(PENDING, Branch.FACT),
        InputSpec(OUTPUT, Branch.FACT),
        InputSpec(ASSET, Branch.FACT),
        InputSpec(FAILURE_CONFIRMED, Branch.FACT),
        InputSpec(FAILURE_UNCONFIRMED, Branch.FACT),
        InputSpec(RESTORED, Branch.FACT),
        InputSpec(PERSISTENCE_UNKNOWN_FACT, Branch.FACT),
    )

    override val effects = listOf(EffectId("Submit"), EffectId("Poll"), EffectId("Download"), EffectId("Reject"))

    // Rows follow `phases`, columns follow `inputs`. A confirmed failure is refused from every
    // unknown position — an unconfirmed outcome cannot be declared a rejection — while an
    // unconfirmed one is accepted anywhere an operation exists and is not already final.
    override val accepts = acceptance(phases, inputs, listOf(
        //                       Cr Su Po Dl De Im Ac Pe Ou As Fc Fu Rs Pu
        /* new                */ "10000100000011",
        /* created            */ "01001000001111",
        /* submitting         */ "00001010101111",
        /* waiting            */ "00101000001111",
        /* polling            */ "00001001101111",
        /* output-available   */ "00011000001111",
        /* downloading        */ "00001000011111",
        /* ready              */ "00001000000011",
        /* failed             */ "00001000000011",
        /* unknown-submission */ "00001000000111",
        /* unknown-job        */ "00101000000111",
        /* unknown-output     */ "00111000000111",
        /* deleted            */ "00001000000011",
        /* persistence        */ "00000000000011",
    ))

    override fun label(state: MediaGenerationMachine.State): PhaseId = when {
        // The flag sits beside the stage, not in it, and it outranks every stage: while it is set
        // the reducer refuses everything except `Restored`.
        state.persistenceUnknown -> PERSISTENCE_UNKNOWN
        else -> when (state.stage) {
            MediaGenerationMachine.Stage.NEW -> NEW
            MediaGenerationMachine.Stage.CREATED -> CREATED
            MediaGenerationMachine.Stage.SUBMITTING -> SUBMITTING
            MediaGenerationMachine.Stage.WAITING -> WAITING
            MediaGenerationMachine.Stage.POLLING -> POLLING
            MediaGenerationMachine.Stage.OUTPUT_AVAILABLE -> OUTPUT_AVAILABLE
            MediaGenerationMachine.Stage.DOWNLOADING -> DOWNLOADING
            MediaGenerationMachine.Stage.READY -> READY
            MediaGenerationMachine.Stage.FAILED -> FAILED
            MediaGenerationMachine.Stage.DELETED -> DELETED
            // Ranked by what can still be done: an output can be downloaded, a job can be polled,
            // and a submission with neither can only be observed after a restart.
            MediaGenerationMachine.Stage.UNKNOWN -> when {
                state.operation?.output != null -> UNKNOWN_OUTPUT
                state.operation?.jobId != null -> UNKNOWN_JOB
                else -> UNKNOWN_SUBMIT
            }
        }
    }

    override fun name(input: MediaGenerationMachine.Input): InputId = when (input) {
        is MediaGenerationMachine.Intent.Create -> CREATE
        MediaGenerationMachine.Intent.Submit -> SUBMIT
        MediaGenerationMachine.Intent.Poll -> POLL
        is MediaGenerationMachine.Intent.Download -> DOWNLOAD
        MediaGenerationMachine.Intent.Delete -> DELETE
        is MediaGenerationMachine.Fact.Import -> IMPORT
        is MediaGenerationMachine.Fact.Accepted -> ACCEPTED
        MediaGenerationMachine.Fact.Pending -> PENDING
        is MediaGenerationMachine.Fact.Output -> OUTPUT
        is MediaGenerationMachine.Fact.Asset -> ASSET
        // Same branch, two inputs: only the unconfirmed one may open an unknown outcome, and only
        // the confirmed one is refused from inside one.
        is MediaGenerationMachine.Fact.Failure -> if (input.confirmed) FAILURE_CONFIRMED else FAILURE_UNCONFIRMED
        MediaGenerationMachine.Fact.Restored -> RESTORED
        MediaGenerationMachine.Fact.PersistenceUnknown -> PERSISTENCE_UNKNOWN_FACT
    }

    override fun name(effect: MediaGenerationMachine.Effect): EffectId = when (effect) {
        is MediaGenerationMachine.Effect.Submit -> EffectId("Submit")
        is MediaGenerationMachine.Effect.Poll -> EffectId("Poll")
        is MediaGenerationMachine.Effect.Download -> EffectId("Download")
        is MediaGenerationMachine.Effect.Reject -> EffectId("Reject")
    }

    // `persistenceUnknown` always carries stage UNKNOWN, so the stage alone answers this.
    override fun unknown(state: MediaGenerationMachine.State) = state.stage == MediaGenerationMachine.Stage.UNKNOWN

    override fun rejected(effect: MediaGenerationMachine.Effect) = effect is MediaGenerationMachine.Effect.Reject
}
