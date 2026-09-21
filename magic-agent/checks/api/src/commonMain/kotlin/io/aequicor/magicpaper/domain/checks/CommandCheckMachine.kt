package io.aequicor.magicpaper.domain.checks

import io.aequicor.magicpaper.machine.Machine
import io.aequicor.magicpaper.machine.MachineId
import io.aequicor.magicpaper.machine.Step
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** One workspace owns admission, process identity, cleanup and attested output as a single aggregate. */
object CommandCheckMachine : Machine<CommandCheckMachine.State, CommandCheckMachine.Input, CommandCheckMachine.Effect> {
    override val id = MachineId("command-check")
    override val space get() = CommandCheckSpace
    /** Bridge to the owner's own reducer: [Transition] and [reduce] keep every call site. */
    override fun step(state: State, input: Input) = reduce(state, input).let { Step(it.state, it.effects) }

    enum class Phase { PREPARING, PREPARED, RUNNING, STOPPING, ATTESTING, UNKNOWN, FINISHED }
    @ConsistentCopyVisibility
    data class Check internal constructor(
        val command: CheckCommand,
        val phase: Phase = Phase.PREPARING,
        val authorityReceipt: String? = null,
        val process: CheckProcessReceipt? = null,
        val result: CheckResult? = null,
        val groupStopped: String? = null,
        val authorityRestored: String? = null,
        val completion: CheckCompletionProof? = null,
    )
    @ConsistentCopyVisibility
    data class State internal constructor(val workspace: String,
        val checks: Map<CheckRef, Check> = emptyMap(), val persistenceUnknown: Boolean = false) {
        val unknown: Boolean get() = persistenceUnknown || checks.values.any { it.phase == Phase.UNKNOWN }
    }
    @Serializable
    sealed interface Input {
        @Serializable sealed interface Intent : Input {
            @Serializable @SerialName("io.aequicor.magicpaper.domain.checks.CommandCheckMachine.Input.Intent.Submit") data class Submit(val command: CheckCommand) : Intent
            @Serializable @SerialName("io.aequicor.magicpaper.domain.checks.CommandCheckMachine.Input.Intent.Release") data class Release(val ref: CheckRef, val receiptId: String) : Intent
            @Serializable @SerialName("io.aequicor.magicpaper.domain.checks.CommandCheckMachine.Input.Intent.Stop") data class Stop(val ref: CheckRef) : Intent
            @Serializable @SerialName("io.aequicor.magicpaper.domain.checks.CommandCheckMachine.Input.Intent.Inspect") data class Inspect(val ref: CheckRef) : Intent
        }
        @Serializable sealed interface Fact : Input {
            /** Saved before any OS permission change; the referenced private receipt contains the old ACL. */
            @Serializable @SerialName("io.aequicor.magicpaper.domain.checks.CommandCheckMachine.Input.Fact.AuthorityRecorded") data class AuthorityRecorded(val ref: CheckRef, val receipt: String) : Fact
            @Serializable @SerialName("io.aequicor.magicpaper.domain.checks.CommandCheckMachine.Input.Fact.ProcessPrepared") data class ProcessPrepared(val ref: CheckRef, val receipt: CheckProcessReceipt) : Fact
            @Serializable @SerialName("io.aequicor.magicpaper.domain.checks.CommandCheckMachine.Input.Fact.Exited") data class Exited(val ref: CheckRef, val receiptId: String, val result: CheckResult) : Fact
            @Serializable @SerialName("io.aequicor.magicpaper.domain.checks.CommandCheckMachine.Input.Fact.GroupStopped") data class GroupStopped(val ref: CheckRef, val receiptId: String, val proof: String) : Fact
            @Serializable @SerialName("io.aequicor.magicpaper.domain.checks.CommandCheckMachine.Input.Fact.AuthorityRestored") data class AuthorityRestored(val ref: CheckRef, val receiptId: String, val proof: String) : Fact
            @Serializable @SerialName("io.aequicor.magicpaper.domain.checks.CommandCheckMachine.Input.Fact.ArtifactsCommitted") data class ArtifactsCommitted(val ref: CheckRef, val receiptId: String, val proof: String) : Fact
            /** Live interpreter rejected preparation before attempting this command's Submit. Never inferred during restore. */
            @Serializable @SerialName("io.aequicor.magicpaper.domain.checks.CommandCheckMachine.Input.Fact.PreparationRejected") data class PreparationRejected(val command: CheckCommand, val result: CheckResult) : Fact
            /** Executor proved no command was released and every acquired permission was restored. */
            @Serializable @SerialName("io.aequicor.magicpaper.domain.checks.CommandCheckMachine.Input.Fact.NotDispatched") data class NotDispatched(val ref: CheckRef, val authorityRestored: String?, val result: CheckResult) : Fact
            @Serializable @SerialName("io.aequicor.magicpaper.domain.checks.CommandCheckMachine.Input.Fact.Failed") data class Failed(val ref: CheckRef) : Fact
            @Serializable @SerialName("io.aequicor.magicpaper.domain.checks.CommandCheckMachine.Input.Fact.NeighbourMissing") data class NeighbourMissing(val ref: CheckRef) : Fact
            /** Validated immutable completion, only in response to explicit inspection. */
            @Serializable @SerialName("io.aequicor.magicpaper.domain.checks.CommandCheckMachine.Input.Fact.CompletionRecovered") data class CompletionRecovered(val ref: CheckRef, val proof: CheckCompletionProof, val result: CheckResult) : Fact
            @Serializable @SerialName("io.aequicor.magicpaper.domain.checks.CommandCheckMachine.Input.Fact.Restored") data object Restored : Fact
            @Serializable @SerialName("io.aequicor.magicpaper.domain.checks.CommandCheckMachine.Input.Fact.PersistenceUnknown") data object PersistenceUnknown : Fact
        }
    }
    sealed interface Effect {
        data class Prepare(val command: CheckCommand) : Effect
        data class Release(val ref: CheckRef, val receipt: CheckProcessReceipt) : Effect
        data class Stop(val ref: CheckRef, val receipt: CheckProcessReceipt?) : Effect
        data class Inspect(val ref: CheckRef, val receipt: CheckProcessReceipt?) : Effect
        data class Attest(val ref: CheckRef, val receipt: CheckProcessReceipt) : Effect
        data class Reject(val reason: Reason) : Effect
    }
    enum class Reason { INVALID, MISSING, BUSY, UNKNOWN, STALE, PAYLOAD_CHANGED, NOT_READY }
    data class Transition(val state: State, val effects: List<Effect> = emptyList())
    fun initial(workspace: String): State = State(workspace)

    fun reduce(state: State, input: Input): Transition {
        fun reject(reason: Reason) = Transition(state, listOf(Effect.Reject(reason)))
        if (input == Input.Fact.PersistenceUnknown) return Transition(state.copy(persistenceUnknown = true))
        if (input == Input.Fact.Restored) return Transition(state.copy(checks = state.checks.mapValues { (_, check) ->
            if (check.phase == Phase.FINISHED) check else check.copy(phase = Phase.UNKNOWN)
        }))
        if (state.workspace.isBlank()) return reject(Reason.INVALID)
        // Persistence uncertainty outranks even an apparently completed check or a duplicate call.
        if (state.persistenceUnknown) return reject(Reason.UNKNOWN)
        if (input is Input.Intent.Submit) {
            val command = input.command.copy(arguments = input.command.arguments.toList(), environment = input.command.environment.toMap(), affectedResources = input.command.affectedResources.toSet())
            val ref = command.ref
            if (!valid(command, state.workspace)) return reject(Reason.INVALID)
            if (state.unknown) return reject(Reason.UNKNOWN)
            val prior = state.checks[ref]
            if (prior != null) return when {
                prior.command != command -> reject(Reason.PAYLOAD_CHANGED)
                prior.phase != Phase.FINISHED -> reject(Reason.BUSY)
                else -> Transition(state)
            }
            if (state.checks.values.any { it.phase != Phase.FINISHED }) return reject(Reason.BUSY)
            return Transition(state.copy(checks = state.checks + (ref to Check(command))), listOf(Effect.Prepare(command)))
        }
        if (input is Input.Fact.PreparationRejected) {
            val command = input.command.copy(arguments = input.command.arguments.toList(),
                environment = input.command.environment.toMap(), affectedResources = input.command.affectedResources.toSet())
            if (!valid(command, state.workspace) || input.result.exitCode != null ||
                input.result.blockedReason.isNullOrBlank() || input.result.output.isNotEmpty() || input.result.binaryOutput != null)
                return reject(Reason.INVALID)
            // A preparation failure cannot replace any existing admission, even a finished one.
            if (command.ref in state.checks) return reject(Reason.STALE)
            return Transition(state.copy(checks = state.checks + (command.ref to
                Check(command, phase = Phase.FINISHED, result = input.result))))
        }
        val ref = when (input) {
            is Input.Intent.Release -> input.ref
            is Input.Intent.Stop -> input.ref
            is Input.Intent.Inspect -> input.ref
            is Input.Fact.AuthorityRecorded -> input.ref
            is Input.Fact.ProcessPrepared -> input.ref
            is Input.Fact.Exited -> input.ref
            is Input.Fact.GroupStopped -> input.ref
            is Input.Fact.AuthorityRestored -> input.ref
            is Input.Fact.ArtifactsCommitted -> input.ref
            is Input.Fact.NotDispatched -> input.ref
            is Input.Fact.Failed -> input.ref
            is Input.Fact.NeighbourMissing -> input.ref
            is Input.Fact.CompletionRecovered -> input.ref
            else -> return reject(Reason.INVALID)
        }
        val check = state.checks[ref] ?: return reject(Reason.MISSING)
        fun next(value: Check, effects: List<Effect> = emptyList()) = Transition(state.copy(checks = state.checks + (ref to value)), effects)
        fun exact(receipt: String) = receipt.isNotBlank() && check.process?.id == receipt
        fun validOutput(result: CheckResult, receiptId: String): Boolean {
            val output = result.binaryOutput
            if (output == null) return check.command.outputMode == CheckOutputMode.TEXT ||
                result.exitCode == null && !result.blockedReason.isNullOrBlank()
            return check.command.outputMode == CheckOutputMode.BINARY_STDOUT && result.exitCode != null && result.blockedReason == null &&
                output.id == receiptId && output.bytes in 0..CheckOutputRef.MAX_BYTES &&
                output.digest.length == 64 && output.digest.all { it in '0'..'9' || it in 'a'..'f' }
        }
        fun cleanup(value: Check): Transition = if (value.groupStopped != null && value.authorityRestored != null && value.result != null) {
            next(value.copy(phase = Phase.ATTESTING), listOf(Effect.Attest(ref, checkNotNull(value.process))))
        } else next(value)
        if (check.phase == Phase.UNKNOWN && input !is Input.Intent.Inspect && input !is Input.Intent.Stop &&
            input !is Input.Fact.CompletionRecovered && input !is Input.Fact.Failed && input !is Input.Fact.NeighbourMissing)
            return reject(Reason.UNKNOWN)
        return when (input) {
            is Input.Intent.Release -> if (check.phase != Phase.PREPARED || !exact(input.receiptId)) reject(Reason.NOT_READY)
                else next(check.copy(phase = Phase.RUNNING), listOf(Effect.Release(ref, checkNotNull(check.process))))
            is Input.Intent.Stop -> if (check.phase == Phase.FINISHED || check.phase == Phase.ATTESTING) Transition(state)
                else next(check.copy(phase = if (check.phase == Phase.UNKNOWN) Phase.UNKNOWN else Phase.STOPPING), listOf(Effect.Stop(ref, check.process)))
            is Input.Intent.Inspect -> if (check.phase == Phase.FINISHED) Transition(state)
                else if (check.phase != Phase.UNKNOWN) reject(Reason.BUSY)
                else Transition(state, listOf(Effect.Inspect(ref, check.process)))
            is Input.Fact.AuthorityRecorded -> if (check.phase != Phase.PREPARING || check.authorityReceipt != null || input.receipt.isBlank()) reject(Reason.STALE)
                else next(check.copy(authorityReceipt = input.receipt))
            is Input.Fact.ProcessPrepared -> if (check.phase != Phase.PREPARING || input.receipt.id.isBlank() || input.receipt.pid <= 0 ||
                input.receipt.kind.isBlank() || input.receipt.authorityReceipt != check.authorityReceipt) reject(Reason.STALE)
                else next(check.copy(phase = Phase.PREPARED, process = input.receipt))
            is Input.Fact.Exited -> if (check.phase !in setOf(Phase.RUNNING, Phase.STOPPING) || !exact(input.receiptId) || check.result != null ||
                !validOutput(input.result, input.receiptId)) reject(Reason.STALE)
                else if (check.groupStopped != null && check.authorityRestored != null) cleanup(check.copy(result = input.result))
                else next(check.copy(phase = Phase.STOPPING, result = input.result), listOf(Effect.Stop(ref, check.process)))
            is Input.Fact.GroupStopped -> if (check.phase != Phase.STOPPING || !exact(input.receiptId) || input.proof.isBlank() || check.groupStopped != null) reject(Reason.STALE)
                else cleanup(check.copy(groupStopped = input.proof))
            is Input.Fact.AuthorityRestored -> if (check.phase != Phase.STOPPING || !exact(input.receiptId) || input.proof.isBlank() || check.authorityRestored != null) reject(Reason.STALE)
                else cleanup(check.copy(authorityRestored = input.proof))
            is Input.Fact.ArtifactsCommitted -> if (check.phase != Phase.ATTESTING || !exact(input.receiptId) || input.proof.isBlank()) reject(Reason.STALE)
                else next(check.copy(phase = Phase.FINISHED, completion = CheckCompletionProof(input.receiptId,
                    checkNotNull(check.groupStopped), checkNotNull(check.authorityRestored), input.proof)))
            is Input.Fact.NotDispatched -> if (check.phase !in setOf(Phase.PREPARING, Phase.PREPARED, Phase.STOPPING) || check.result != null ||
                check.process != null || check.authorityReceipt != null && input.authorityRestored.isNullOrBlank() ||
                input.result.exitCode != null || input.result.blockedReason.isNullOrBlank() || input.result.binaryOutput != null) reject(Reason.STALE)
                else next(check.copy(phase = Phase.FINISHED, result = input.result, authorityRestored = input.authorityRestored))
            is Input.Fact.Failed, is Input.Fact.NeighbourMissing -> if (check.phase == Phase.FINISHED) reject(Reason.STALE)
                else next(check.copy(phase = Phase.UNKNOWN))
            is Input.Fact.CompletionRecovered -> if (check.phase != Phase.UNKNOWN || !exact(input.proof.receiptId) ||
                !validOutput(input.result, input.proof.receiptId) ||
                input.proof.groupStopped.isBlank() || input.proof.authorityRestored.isBlank() || input.proof.artifactsCommitted.isBlank() ||
                check.result != null && check.result != input.result ||
                check.groupStopped != null && check.groupStopped != input.proof.groupStopped ||
                check.authorityRestored != null && check.authorityRestored != input.proof.authorityRestored) reject(Reason.STALE)
                else next(check.copy(phase = Phase.FINISHED, result = input.result, completion = input.proof,
                    groupStopped = input.proof.groupStopped, authorityRestored = input.proof.authorityRestored))
            else -> reject(Reason.INVALID)
        }
    }
    private fun valid(command: CheckCommand, workspace: String): Boolean {
        val ref = command.ref
        if (command.resource != workspace || command.workspace.isBlank() || !valid(ref) || command.arguments.isEmpty() ||
            command.arguments.size > 128 || command.arguments.first().isBlank() ||
            command.affectedResources.size > 32 || command.affectedResources.any { it.isBlank() || it.length > 16_384 || '\u0000' in it } ||
            command.arguments.any { it.length > 16_384 || '\u0000' in it } || command.subdirectory.isBlank())
            return false
        val metadata = command.metadataSource
        if (command.policy == CheckPolicy.METADATA_READ_ONLY) {
            if (metadata == null || !valid(metadata.parent) || metadata.parent == ref || metadata.parent.scope != ref.scope ||
                command.protectedResource != workspace || command.workspace != workspace || command.subdirectory != "." ||
                command.arguments != metadata.query.arguments() || command.outputMode != CheckOutputMode.BINARY_STDOUT ||
                command.environment.isNotEmpty()) return false
        } else if (metadata != null) return false
        if (command.policy == CheckPolicy.GIT_READ_ONLY && (command.subdirectory != "." ||
            !isCheckGitReadArguments(command.arguments) ||
            command.environment.isNotEmpty() || command.outputMode != CheckOutputMode.BINARY_STDOUT)) return false
        if ((command.outputMode != CheckOutputMode.TEXT || command.environment.isNotEmpty()) &&
            command.policy !in setOf(CheckPolicy.MANAGED_WORKTREE, CheckPolicy.METADATA_READ_ONLY, CheckPolicy.GIT_READ_ONLY) ||
            command.environment.any { (key, value) -> when (key) {
                "GIT_INDEX_FILE" -> value.isBlank() || value.length > 16_384 || '\u0000' in value
                "GIT_TERMINAL_PROMPT" -> value != "0"
                else -> true
            } }) return false
        return true
    }
    private fun valid(ref: CheckRef) = ref.callId.isNotBlank() && ref.attempt >= 0 && ref.scope.projectId.isNotBlank() &&
        ref.scope.sessionId.isNotBlank() && ref.scope.requestId.isNotBlank() && ref.scope.generation >= 0
}
