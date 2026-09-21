package io.aequicor.magicpaper.domain.checks

import io.aequicor.magicpaper.domain.checks.CommandCheckMachine.Input.Fact
import io.aequicor.magicpaper.domain.checks.CommandCheckMachine.Input.Intent
import io.aequicor.magicpaper.machine.verifyStateSpace
import kotlin.test.Test

/**
 * The representatives of [CommandCheckSpace], kept here rather than in the api so a shipped binary
 * carries no fixtures.
 *
 * Each one is built by running the machine from `initial`, never by constructing a state, which is
 * what the `internal constructor` on `State` is there to enforce. The seven `STOPPING` positions are
 * reached by delivering the three pieces of evidence in the order that leaves exactly one of them owed.
 */
class CommandCheckSpaceTest {
    private val ref = CheckRef(CheckScope("project", "session", "request", 0), "call", 0)
    private val command = CheckCommand(ref, "/workspace", listOf("check", "--private"))
    private val receipt = CheckProcessReceipt("receipt", "group", 123)
    private val result = CheckResult("output", 0)
    private val proof = CheckCompletionProof(receipt.id, "group-proof", "authority-proof", "artifact-proof")
    private val exited = Fact.Exited(ref, receipt.id, result)
    private val groupStopped = Fact.GroupStopped(ref, receipt.id, proof.groupStopped)
    private val authorityRestored = Fact.AuthorityRestored(ref, receipt.id, proof.authorityRestored)
    private val notDispatched = Fact.NotDispatched(ref, null, CheckResult("", null, "unavailable"))
    private fun step(state: CommandCheckMachine.State, vararg inputs: CommandCheckMachine.Input) =
        inputs.fold(state) { current, input -> CommandCheckMachine.reduce(current, input).state }

    private val empty = CommandCheckMachine.initial(command.workspace)
    private val preparing = step(empty, Intent.Submit(command))
    private val prepared = step(preparing, Fact.ProcessPrepared(ref, receipt))
    private val running = step(prepared, Intent.Release(ref, receipt.id))
    private val stopping = step(running, Intent.Stop(ref))

    @Test fun declaredSpaceIsClosedAndMatchesEveryTransition() = verifyStateSpace(
        CommandCheckMachine,
        states = mapOf(
            CommandCheckSpace.EMPTY to empty,
            CommandCheckSpace.PREPARING to preparing,
            CommandCheckSpace.PREPARED to prepared,
            CommandCheckSpace.RUNNING to running,
            CommandCheckSpace.STOPPING_ALL to stopping,
            CommandCheckSpace.STOPPING_GROUP_AUTHORITY to step(stopping, exited),
            CommandCheckSpace.STOPPING_EXIT_AUTHORITY to step(stopping, groupStopped),
            CommandCheckSpace.STOPPING_EXIT_GROUP to step(stopping, authorityRestored),
            CommandCheckSpace.STOPPING_AUTHORITY to step(stopping, exited, groupStopped),
            CommandCheckSpace.STOPPING_GROUP to step(stopping, exited, authorityRestored),
            CommandCheckSpace.STOPPING_EXIT to step(stopping, groupStopped, authorityRestored),
            CommandCheckSpace.ATTESTING to step(stopping, exited, groupStopped, authorityRestored),
            // A restart with the process in flight: whether it ran, and to what end, was never observed.
            CommandCheckSpace.UNKNOWN to step(running, Fact.Restored),
            CommandCheckSpace.FINISHED to step(stopping, exited, groupStopped, authorityRestored,
                Fact.ArtifactsCommitted(ref, receipt.id, proof.artifactsCommitted)),
            CommandCheckSpace.PERSISTENCE_UNKNOWN to step(running, Fact.PersistenceUnknown),
        ),
        inputs = mapOf(
            CommandCheckSpace.SUBMIT to Intent.Submit(command),
            CommandCheckSpace.RELEASE to Intent.Release(ref, receipt.id),
            CommandCheckSpace.STOP to Intent.Stop(ref),
            CommandCheckSpace.INSPECT to Intent.Inspect(ref),
            CommandCheckSpace.AUTHORITY_RECORDED to Fact.AuthorityRecorded(ref, "acl"),
            CommandCheckSpace.PROCESS_PREPARED to Fact.ProcessPrepared(ref, receipt),
            CommandCheckSpace.EXITED to exited,
            CommandCheckSpace.GROUP_STOPPED to groupStopped,
            CommandCheckSpace.AUTHORITY_RESTORED to authorityRestored,
            CommandCheckSpace.ARTIFACTS_COMMITTED to Fact.ArtifactsCommitted(ref, receipt.id, proof.artifactsCommitted),
            CommandCheckSpace.PREPARATION_REJECTED to Fact.PreparationRejected(command, CheckResult("", null, "unavailable")),
            CommandCheckSpace.NOT_DISPATCHED to notDispatched,
            CommandCheckSpace.FAILED to Fact.Failed(ref),
            CommandCheckSpace.NEIGHBOUR_MISSING to Fact.NeighbourMissing(ref),
            CommandCheckSpace.COMPLETION_RECOVERED to Fact.CompletionRecovered(ref, proof, result),
            CommandCheckSpace.RESTORED to Fact.Restored,
            CommandCheckSpace.PERSISTENCE_UNKNOWN_FACT to Fact.PersistenceUnknown,
        ),
    )
}
