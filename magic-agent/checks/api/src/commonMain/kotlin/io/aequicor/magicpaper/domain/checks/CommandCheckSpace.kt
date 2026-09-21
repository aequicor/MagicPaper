package io.aequicor.magicpaper.domain.checks

import io.aequicor.magicpaper.machine.Branch
import io.aequicor.magicpaper.machine.EffectId
import io.aequicor.magicpaper.machine.InputId
import io.aequicor.magicpaper.machine.InputSpec
import io.aequicor.magicpaper.machine.PhaseId
import io.aequicor.magicpaper.machine.StateSpace
import io.aequicor.magicpaper.machine.acceptance

/**
 * The state space of [CommandCheckMachine], declared so it can be read without running anything.
 *
 * The state is an aggregate of checks, at most one of them not yet finished, and each carries a
 * [CommandCheckMachine.Phase]. Seven phases are not seven positions, though. `STOPPING` waits for
 * three independent pieces of evidence — the process exit, the stop of its process group and the
 * restoring of the permissions it changed — that can arrive in any order, and each input is refused
 * once its own evidence is in. What a `STOPPING` check accepts is therefore a function of what it
 * still *owes*, and the last piece to arrive moves it to `ATTESTING`. That makes seven positions of
 * `STOPPING`, named by what they owe; with one position for all of them `ATTESTING` could not be
 * reached from any representative, and the reducer's real shape would be hidden.
 *
 * [label] ranks an unknown outcome above everything else, as the reducer does, then the check that
 * is not finished, then `finished`.
 *
 * What the declaration cannot express, and leaves to `CommandCheckMachineTest`: refusals of identity
 * — a receipt id, proof or output that does not match the check — the admission rules for a command
 * (policy, output mode, environment, arguments), and a change of payload behind a reused reference.
 * A check stopped before its process was prepared still accepts `NotDispatched`, which the
 * representative, holding a process, does not.
 */
object CommandCheckSpace : StateSpace<CommandCheckMachine.State, CommandCheckMachine.Input, CommandCheckMachine.Effect> {
    val EMPTY = PhaseId("empty")
    val PREPARING = PhaseId("preparing")
    val PREPARED = PhaseId("prepared")
    val RUNNING = PhaseId("running")
    val STOPPING_ALL = PhaseId("stopping-owes-exit-group-authority")
    val STOPPING_GROUP_AUTHORITY = PhaseId("stopping-owes-group-authority")
    val STOPPING_EXIT_AUTHORITY = PhaseId("stopping-owes-exit-authority")
    val STOPPING_EXIT_GROUP = PhaseId("stopping-owes-exit-group")
    val STOPPING_AUTHORITY = PhaseId("stopping-owes-authority")
    val STOPPING_GROUP = PhaseId("stopping-owes-group")
    val STOPPING_EXIT = PhaseId("stopping-owes-exit")
    val ATTESTING = PhaseId("attesting")
    val UNKNOWN = PhaseId("unknown-outcome")
    val FINISHED = PhaseId("finished")
    val PERSISTENCE_UNKNOWN = PhaseId("persistence-unknown")

    val SUBMIT = InputId("Submit")
    val RELEASE = InputId("Release")
    val STOP = InputId("Stop")
    val INSPECT = InputId("Inspect")
    val AUTHORITY_RECORDED = InputId("AuthorityRecorded")
    val PROCESS_PREPARED = InputId("ProcessPrepared")
    val EXITED = InputId("Exited")
    val GROUP_STOPPED = InputId("GroupStopped")
    val AUTHORITY_RESTORED = InputId("AuthorityRestored")
    val ARTIFACTS_COMMITTED = InputId("ArtifactsCommitted")
    val PREPARATION_REJECTED = InputId("PreparationRejected")
    val NOT_DISPATCHED = InputId("NotDispatched")
    val FAILED = InputId("Failed")
    val NEIGHBOUR_MISSING = InputId("NeighbourMissing")
    val COMPLETION_RECOVERED = InputId("CompletionRecovered")
    val RESTORED = InputId("Restored")
    val PERSISTENCE_UNKNOWN_FACT = InputId("PersistenceUnknown")

    override val phases = listOf(
        EMPTY, PREPARING, PREPARED, RUNNING,
        STOPPING_ALL, STOPPING_GROUP_AUTHORITY, STOPPING_EXIT_AUTHORITY, STOPPING_EXIT_GROUP,
        STOPPING_AUTHORITY, STOPPING_GROUP, STOPPING_EXIT,
        ATTESTING, UNKNOWN, FINISHED, PERSISTENCE_UNKNOWN,
    )

    override val inputs = listOf(
        InputSpec(SUBMIT, Branch.INTENT),
        InputSpec(RELEASE, Branch.INTENT),
        InputSpec(STOP, Branch.INTENT),
        InputSpec(INSPECT, Branch.INTENT),
        InputSpec(AUTHORITY_RECORDED, Branch.FACT),
        InputSpec(PROCESS_PREPARED, Branch.FACT),
        InputSpec(EXITED, Branch.FACT),
        InputSpec(GROUP_STOPPED, Branch.FACT),
        InputSpec(AUTHORITY_RESTORED, Branch.FACT),
        InputSpec(ARTIFACTS_COMMITTED, Branch.FACT),
        InputSpec(PREPARATION_REJECTED, Branch.FACT),
        InputSpec(NOT_DISPATCHED, Branch.FACT),
        InputSpec(FAILED, Branch.FACT),
        InputSpec(NEIGHBOUR_MISSING, Branch.FACT),
        InputSpec(COMPLETION_RECOVERED, Branch.FACT),
        InputSpec(RESTORED, Branch.FACT),
        InputSpec(PERSISTENCE_UNKNOWN_FACT, Branch.FACT),
    )

    override val effects = listOf(
        EffectId("Prepare"), EffectId("Release"), EffectId("Stop"), EffectId("Inspect"), EffectId("Attest"), EffectId("Reject"),
    )

    // Rows follow `phases`, columns follow `inputs`. `Submit` is accepted from `empty` as a new
    // admission and from `finished` as a repeat of the same command, which changes nothing. Only an
    // empty store accepts `PreparationRejected`: it may not replace an admission, even a finished one.
    // `Stop` of a finished or attesting check is accepted and does nothing.
    override val accepts = acceptance(phases, inputs, listOf(
        //                                        Su Re St In Ar Pp Ex Gs Au Ac Pj Nd Fa Nm Cr Rt Pu
        /* empty                              */ "10000000001000011",
        /* preparing                          */ "00101100000111011",
        /* prepared                           */ "01100000000011011",
        /* running                            */ "00100010000011011",
        /* stopping-owes-exit-group-authority */ "00100011100011011",
        /* stopping-owes-group-authority      */ "00100001100011011",
        /* stopping-owes-exit-authority       */ "00100010100011011",
        /* stopping-owes-exit-group           */ "00100011000011011",
        /* stopping-owes-authority            */ "00100000100011011",
        /* stopping-owes-group                */ "00100001000011011",
        /* stopping-owes-exit                 */ "00100010000011011",
        /* attesting                          */ "00100000010011011",
        /* unknown-outcome                    */ "00110000000011111",
        /* finished                           */ "10110000000000011",
        /* persistence-unknown                */ "00000000000000011",
    ))

    private fun stopping(check: CommandCheckMachine.Check): PhaseId? {
        val exit = check.result == null
        val group = check.groupStopped == null
        val authority = check.authorityRestored == null
        return when {
            exit && group && authority -> STOPPING_ALL
            !exit && group && authority -> STOPPING_GROUP_AUTHORITY
            exit && !group && authority -> STOPPING_EXIT_AUTHORITY
            exit && group && !authority -> STOPPING_EXIT_GROUP
            !exit && !group && authority -> STOPPING_AUTHORITY
            !exit && group && !authority -> STOPPING_GROUP
            exit && !group && !authority -> STOPPING_EXIT
            // Owing nothing is `ATTESTING`: the reducer moves there the moment the last piece arrives.
            else -> null
        }
    }

    private fun position(check: CommandCheckMachine.Check): PhaseId? = when (check.phase) {
        CommandCheckMachine.Phase.PREPARING -> PREPARING
        CommandCheckMachine.Phase.PREPARED -> PREPARED
        CommandCheckMachine.Phase.RUNNING -> RUNNING
        CommandCheckMachine.Phase.STOPPING -> stopping(check)
        CommandCheckMachine.Phase.ATTESTING -> ATTESTING
        CommandCheckMachine.Phase.UNKNOWN -> UNKNOWN
        CommandCheckMachine.Phase.FINISHED -> FINISHED
    }

    override fun label(state: CommandCheckMachine.State): PhaseId? = when {
        state.persistenceUnknown -> PERSISTENCE_UNKNOWN
        state.checks.isEmpty() -> EMPTY
        state.checks.values.any { it.phase == CommandCheckMachine.Phase.UNKNOWN } -> UNKNOWN
        else -> state.checks.values.firstOrNull { it.phase != CommandCheckMachine.Phase.FINISHED }?.let(::position) ?: FINISHED
    }

    override fun name(input: CommandCheckMachine.Input): InputId = when (input) {
        is CommandCheckMachine.Input.Intent.Submit -> SUBMIT
        is CommandCheckMachine.Input.Intent.Release -> RELEASE
        is CommandCheckMachine.Input.Intent.Stop -> STOP
        is CommandCheckMachine.Input.Intent.Inspect -> INSPECT
        is CommandCheckMachine.Input.Fact.AuthorityRecorded -> AUTHORITY_RECORDED
        is CommandCheckMachine.Input.Fact.ProcessPrepared -> PROCESS_PREPARED
        is CommandCheckMachine.Input.Fact.Exited -> EXITED
        is CommandCheckMachine.Input.Fact.GroupStopped -> GROUP_STOPPED
        is CommandCheckMachine.Input.Fact.AuthorityRestored -> AUTHORITY_RESTORED
        is CommandCheckMachine.Input.Fact.ArtifactsCommitted -> ARTIFACTS_COMMITTED
        is CommandCheckMachine.Input.Fact.PreparationRejected -> PREPARATION_REJECTED
        is CommandCheckMachine.Input.Fact.NotDispatched -> NOT_DISPATCHED
        is CommandCheckMachine.Input.Fact.Failed -> FAILED
        is CommandCheckMachine.Input.Fact.NeighbourMissing -> NEIGHBOUR_MISSING
        is CommandCheckMachine.Input.Fact.CompletionRecovered -> COMPLETION_RECOVERED
        CommandCheckMachine.Input.Fact.Restored -> RESTORED
        CommandCheckMachine.Input.Fact.PersistenceUnknown -> PERSISTENCE_UNKNOWN_FACT
    }

    override fun name(effect: CommandCheckMachine.Effect): EffectId = when (effect) {
        is CommandCheckMachine.Effect.Prepare -> EffectId("Prepare")
        is CommandCheckMachine.Effect.Release -> EffectId("Release")
        is CommandCheckMachine.Effect.Stop -> EffectId("Stop")
        is CommandCheckMachine.Effect.Inspect -> EffectId("Inspect")
        is CommandCheckMachine.Effect.Attest -> EffectId("Attest")
        is CommandCheckMachine.Effect.Reject -> EffectId("Reject")
    }

    override fun unknown(state: CommandCheckMachine.State) = state.unknown

    override fun rejected(effect: CommandCheckMachine.Effect) = effect is CommandCheckMachine.Effect.Reject
}
