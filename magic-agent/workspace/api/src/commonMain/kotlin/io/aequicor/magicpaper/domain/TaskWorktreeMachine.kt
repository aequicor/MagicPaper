package io.aequicor.magicpaper.domain

import io.aequicor.magicpaper.machine.Machine
import io.aequicor.magicpaper.machine.MachineId
import io.aequicor.magicpaper.machine.Step
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Git authority is independent of the native attempt and of the parent session cache. */
object TaskWorktreeMachine : Machine<TaskWorktreeMachine.State, TaskWorktreeMachine.Input, TaskWorktreeMachine.Effect> {
    override val id = MachineId("task-worktree")
    override val space get() = TaskWorktreeSpace
    /** Bridge to the owner's own reducer: [Transition] and [reduce] keep every call site. */
    override fun step(state: State, input: Input) = reduce(state, input).let { Step(it.state, it.effects) }

    @Serializable
    enum class Operation { OPEN, REFRESH, CAPTURE, INTEGRATE, VERIFY, DELIVER }

    @Serializable
    data class Pending(val id: String, val kind: Operation, val taskId: String, val generation: Long,
        val before: TaskWorktree?)

    enum class Stage { EMPTY, IDLE, EXECUTING, UNKNOWN, COMPLETE }

    @ConsistentCopyVisibility
    data class State internal constructor(
        val owner: TaskWorktreeOwnerId,
        val record: TaskWorktree? = null,
        val generation: Long = 0,
        val pending: Pending? = null,
        val unknown: Boolean = false,
        val persistenceUnknown: Boolean = false,
        val completedOperations: Set<String> = emptySet(),
        val retiredTasks: Set<String> = emptySet(),
        val verifiedCommit: String = "",
        val acceptedCommit: String = "",
        val verificationFailed: Boolean = false,
        val initialized: Boolean = false,
    ) {
        val stage: Stage get() = when {
            unknown || persistenceUnknown -> Stage.UNKNOWN
            pending != null -> Stage.EXECUTING
            record == null -> Stage.EMPTY
            record.phase == TaskWorktreePhase.COMPLETE -> Stage.COMPLETE
            else -> Stage.IDLE
        }
    }

    @Serializable
    sealed interface Input {
        @Serializable
        sealed interface Intent : Input {
            @Serializable @SerialName("io.aequicor.magicpaper.domain.TaskWorktreeMachine.Input.Intent.Prepare") data class Prepare(val record: TaskWorktree, val generation: Long, val operationId: String) : Intent
            @Serializable @SerialName("io.aequicor.magicpaper.domain.TaskWorktreeMachine.Input.Intent.BindRun") data class BindRun(val taskId: String, val generation: Long) : Intent
            @Serializable @SerialName("io.aequicor.magicpaper.domain.TaskWorktreeMachine.Input.Intent.Handoff") data class Handoff(val taskId: String, val generation: Long, val ready: Boolean,
                val checks: List<List<String>>) : Intent
            @Serializable @SerialName("io.aequicor.magicpaper.domain.TaskWorktreeMachine.Input.Intent.RevokeHandoff") data class RevokeHandoff(val taskId: String, val generation: Long) : Intent
            @Serializable @SerialName("io.aequicor.magicpaper.domain.TaskWorktreeMachine.Input.Intent.ReturnForRepair") data class ReturnForRepair(val taskId: String, val generation: Long) : Intent
            @Serializable @SerialName("io.aequicor.magicpaper.domain.TaskWorktreeMachine.Input.Intent.RetryVerification") data class RetryVerification(val taskId: String, val generation: Long) : Intent
            @Serializable @SerialName("io.aequicor.magicpaper.domain.TaskWorktreeMachine.Input.Intent.AttachResponse") data class AttachResponse(val taskId: String, val generation: Long, val response: CodingMessage) : Intent
            @Serializable @SerialName("io.aequicor.magicpaper.domain.TaskWorktreeMachine.Input.Intent.Refresh") data class Refresh(val taskId: String, val generation: Long, val operationId: String) : Intent
            @Serializable @SerialName("io.aequicor.magicpaper.domain.TaskWorktreeMachine.Input.Intent.Capture") data class Capture(val taskId: String, val generation: Long, val operationId: String,
                val planAccepted: Boolean = false) : Intent
            @Serializable @SerialName("io.aequicor.magicpaper.domain.TaskWorktreeMachine.Input.Intent.Integrate") data class Integrate(val taskId: String, val generation: Long, val operationId: String,
                val targetCommit: String, val planAccepted: Boolean = false) : Intent
            @Serializable @SerialName("io.aequicor.magicpaper.domain.TaskWorktreeMachine.Input.Intent.Verify") data class Verify(val taskId: String, val generation: Long, val operationId: String) : Intent
            @Serializable @SerialName("io.aequicor.magicpaper.domain.TaskWorktreeMachine.Input.Intent.AcceptMerge") data class AcceptMerge(val taskId: String, val generation: Long, val mergeCommit: String) : Intent
            @Serializable @SerialName("io.aequicor.magicpaper.domain.TaskWorktreeMachine.Input.Intent.Deliver") data class Deliver(val taskId: String, val generation: Long, val operationId: String) : Intent
            @Serializable @SerialName("io.aequicor.magicpaper.domain.TaskWorktreeMachine.Input.Intent.Inspect") data class Inspect(val taskId: String) : Intent
            @Serializable @SerialName("io.aequicor.magicpaper.domain.TaskWorktreeMachine.Input.Intent.NoteFailure") data class NoteFailure(val taskId: String, val message: String) : Intent
        }

        @Serializable
        sealed interface Fact : Input {
            @Serializable @SerialName("io.aequicor.magicpaper.domain.TaskWorktreeMachine.Input.Fact.Imported") data class Imported(val record: TaskWorktree?, val generation: Long) : Fact
            @Serializable @SerialName("io.aequicor.magicpaper.domain.TaskWorktreeMachine.Input.Fact.Opened") data class Opened(val operationId: String) : Fact
            @Serializable @SerialName("io.aequicor.magicpaper.domain.TaskWorktreeMachine.Input.Fact.Refreshed") data class Refreshed(val operationId: String, val behind: Int, val target: String,
                val updated: Boolean, val note: String?, val pendingTransfer: Boolean) : Fact
            @Serializable @SerialName("io.aequicor.magicpaper.domain.TaskWorktreeMachine.Input.Fact.Captured") data class Captured(val operationId: String, val commit: String) : Fact
            @Serializable @SerialName("io.aequicor.magicpaper.domain.TaskWorktreeMachine.Input.Fact.Integrated") data class Integrated(val operationId: String, val commit: String?) : Fact
            @Serializable @SerialName("io.aequicor.magicpaper.domain.TaskWorktreeMachine.Input.Fact.Verified") data class Verified(val operationId: String) : Fact
            @Serializable @SerialName("io.aequicor.magicpaper.domain.TaskWorktreeMachine.Input.Fact.VerificationFailed") data class VerificationFailed(val operationId: String, val message: String) : Fact
            @Serializable @SerialName("io.aequicor.magicpaper.domain.TaskWorktreeMachine.Input.Fact.Delivered") data class Delivered(val operationId: String) : Fact
            @Serializable @SerialName("io.aequicor.magicpaper.domain.TaskWorktreeMachine.Input.Fact.Failed") data class Failed(val operationId: String, val beforeEffect: Boolean) : Fact
            @Serializable @SerialName("io.aequicor.magicpaper.domain.TaskWorktreeMachine.Input.Fact.Inspected") data class Inspected(val proof: TaskWorktreeProof) : Fact
            @Serializable @SerialName("io.aequicor.magicpaper.domain.TaskWorktreeMachine.Input.Fact.InspectionUnknown") data class InspectionUnknown(val operationId: String) : Fact
            /**
             * Read-only inspection found the operation's postcondition absent. Only an operation that resumes from any
             * partial state it leaves ([RETRYABLE]) may then be forgotten: the record returns to its state before it.
             */
            @Serializable @SerialName("io.aequicor.magicpaper.domain.TaskWorktreeMachine.Input.Fact.InspectedUnapplied") data class InspectedUnapplied(
                val operationId: String, val taskId: String, val kind: Operation) : Fact
            /** Exact immutable completion artifact observed by the executor, never a Git postcondition guess. */
            @Serializable @SerialName("io.aequicor.magicpaper.domain.TaskWorktreeMachine.Input.Fact.OutcomeRecovered") data class OutcomeRecovered(val outcome: Fact) : Fact
            @Serializable @SerialName("io.aequicor.magicpaper.domain.TaskWorktreeMachine.Input.Fact.NeighbourMissing") data class NeighbourMissing(val taskId: String) : Fact
            @Serializable @SerialName("io.aequicor.magicpaper.domain.TaskWorktreeMachine.Input.Fact.Restored") data object Restored : Fact
            @Serializable @SerialName("io.aequicor.magicpaper.domain.TaskWorktreeMachine.Input.Fact.PersistenceUnknown") data object PersistenceUnknown : Fact
        }
    }

    sealed interface Effect {
        data class Execute(val pending: Pending, val record: TaskWorktree, val previous: TaskWorktree? = null) : Effect
        data class Inspect(val pending: Pending, val record: TaskWorktree) : Effect
        data class Reject(val reason: Reason, val message: String? = null) : Effect
    }
    enum class Reason { INVALID, STALE, BUSY, UNKNOWN, NOT_READY, ALREADY_USED, MISSING }
    data class Transition(val state: State, val effects: List<Effect> = emptyList())

    fun initial(owner: TaskWorktreeOwnerId): State = State(owner)

    fun reduce(state: State, input: Input): Transition {
        fun reject(reason: Reason, message: String? = null) = Transition(state, listOf(Effect.Reject(reason, message)))
        if (state.owner.projectId.isBlank() || state.owner.sessionId.isBlank()) return reject(Reason.INVALID)
        if (input == Input.Fact.PersistenceUnknown) return Transition(state.copy(persistenceUnknown = true, unknown = true))
        if (input == Input.Fact.Restored) return Transition(state.copy(unknown = state.unknown || state.pending != null))
        if (input is Input.Fact.Imported) {
            if (state.initialized || input.generation < 0 || input.record?.let { !validRecord(it) } == true) return reject(Reason.INVALID)
            val record = input.record
            val operation = when (record?.phase) {
                TaskWorktreePhase.PREPARING -> Operation.OPEN
                TaskWorktreePhase.CAPTURING -> Operation.CAPTURE
                TaskWorktreePhase.MERGING, TaskWorktreePhase.CONFLICT -> Operation.INTEGRATE
                TaskWorktreePhase.DELIVERING -> Operation.DELIVER
                // Old refresh had no dedicated durable phase. A running legacy task needs read-only inspection.
                TaskWorktreePhase.RUNNING -> Operation.REFRESH
                else -> null
            }
            return Transition(state.copy(record = record, generation = input.generation, initialized = true,
                pending = operation?.let { Pending("legacy-${record!!.taskId}-${it.name}", it, record.taskId, input.generation, record) },
                unknown = operation != null))
        }
        if (!state.initialized) return reject(Reason.INVALID)
        if (input is Input.Fact.NeighbourMissing) {
            if (input.taskId != state.record?.taskId) return reject(Reason.STALE)
            return Transition(state.copy(unknown = true, record = state.record?.copy(error = MISSING_NOTICE)))
        }
        if (input is Input.Intent.Inspect) {
            if (input.taskId != state.record?.taskId) return reject(Reason.STALE)
            if (state.persistenceUnknown) return reject(Reason.UNKNOWN)
            val pending = state.pending ?: return Transition(state)
            if (!state.unknown) return reject(Reason.BUSY)
            return Transition(state, listOf(Effect.Inspect(pending, checkNotNull(state.record))))
        }
        if (input is Input.Fact.Inspected) return inspected(state, input.proof)
        if (input is Input.Fact.OutcomeRecovered) {
            if (!state.unknown || state.persistenceUnknown) return reject(Reason.UNKNOWN)
            if (input.outcome !is Input.Fact.Opened && input.outcome !is Input.Fact.Refreshed &&
                input.outcome !is Input.Fact.Captured && input.outcome !is Input.Fact.Integrated &&
                input.outcome !is Input.Fact.Verified && input.outcome !is Input.Fact.VerificationFailed &&
                input.outcome !is Input.Fact.Delivered) return reject(Reason.INVALID)
            val recovered = completed(state.copy(unknown = false), input.outcome)
            return if (recovered.effects.any { it is Effect.Reject }) reject(Reason.STALE)
                else if (input.outcome is Input.Fact.VerificationFailed) recovered
                else recovered.copy(state = recovered.state.copy(record = recovered.state.record?.copy(error = null)))
        }
        if (input is Input.Fact.InspectionUnknown) {
            if (input.operationId != state.pending?.id) return reject(Reason.STALE)
            return Transition(state.copy(unknown = true, record = state.record?.copy(error = UNKNOWN_NOTICE)))
        }
        if (input is Input.Fact.InspectedUnapplied) return unapplied(state, input)
        if (input is Input.Intent.NoteFailure) {
            if (input.taskId != state.record?.taskId || input.message.isBlank()) return reject(Reason.INVALID)
            return Transition(state.copy(record = state.record?.copy(error = input.message)))
        }
        if (input is Input.Fact) return completed(state, input)
        // Unknown always wins over freshness, handoff and completion. It cannot be cleared by a new native generation.
        if (state.stage == Stage.UNKNOWN) return reject(Reason.UNKNOWN)
        if (state.pending != null) return reject(Reason.BUSY)
        if (input is Input.Intent.Prepare) {
            val record = input.record
            if (!validRecord(record) || record.phase != TaskWorktreePhase.PREPARING || input.generation < 0) return reject(Reason.INVALID)
            if (record.taskId in state.retiredTasks || record.taskId == state.record?.taskId) return reject(Reason.ALREADY_USED)
            if (state.record?.phase != null && state.record.phase != TaskWorktreePhase.COMPLETE) return reject(Reason.NOT_READY)
            if (state.record != null && (record.reuseBranch != state.record.branch || record.reuseCommit != state.record.mergeCommit)) return reject(Reason.STALE)
            val next = state.copy(record = record, generation = input.generation, verifiedCommit = "", acceptedCommit = "", verificationFailed = false,
                retiredTasks = state.retiredTasks + listOfNotNull(state.record?.taskId))
            return begin(next, input.operationId, Operation.OPEN, state.record, before = state.record)
        }
        val record = state.record ?: return reject(Reason.MISSING)
        if (input is Input.Intent.BindRun) {
            if (input.taskId != record.taskId || input.generation < state.generation) return reject(Reason.STALE)
            if (input.generation == state.generation) return Transition(state)
            return Transition(state.copy(generation = input.generation, record = record.copy(handoffGeneration = null,
                phase = if (record.phase == TaskWorktreePhase.READY) TaskWorktreePhase.RUNNING else record.phase)))
        }
        val identity = when (input) {
            is Input.Intent.Handoff -> input.taskId to input.generation
            is Input.Intent.RevokeHandoff -> input.taskId to input.generation
            is Input.Intent.ReturnForRepair -> input.taskId to input.generation
            is Input.Intent.RetryVerification -> input.taskId to input.generation
            is Input.Intent.AttachResponse -> input.taskId to input.generation
            is Input.Intent.Refresh -> input.taskId to input.generation
            is Input.Intent.Capture -> input.taskId to input.generation
            is Input.Intent.Integrate -> input.taskId to input.generation
            is Input.Intent.Verify -> input.taskId to input.generation
            is Input.Intent.AcceptMerge -> input.taskId to input.generation
            is Input.Intent.Deliver -> input.taskId to input.generation
            else -> return reject(Reason.INVALID)
        }
        if (identity != (record.taskId to state.generation)) return reject(Reason.STALE)
        return when (input) {
            is Input.Intent.Handoff -> {
                if (record.phase !in ACTIVE_PHASES || input.checks.any { it.isEmpty() || it.any { a -> a.isBlank() || '\u0000' in a } }) reject(Reason.NOT_READY)
                else Transition(state.copy(record = record.copy(
                    phase = if (record.phase == TaskWorktreePhase.CONFLICT) record.phase else if (input.ready) TaskWorktreePhase.READY else TaskWorktreePhase.RUNNING,
                    handoffGeneration = if (input.ready) state.generation else null, checks = input.checks,
                    error = if (input.ready) null else "Задача заблокирована агентом. Уточните запрос и продолжите")))
            }
            is Input.Intent.RevokeHandoff -> {
                if (record.phase !in ACTIVE_PHASES) reject(Reason.NOT_READY)
                else Transition(state.copy(record = record.copy(phase = if (record.phase == TaskWorktreePhase.CONFLICT) record.phase else TaskWorktreePhase.RUNNING,
                    handoffGeneration = null, error = null)))
            }
            is Input.Intent.ReturnForRepair -> if (record.phase != TaskWorktreePhase.MERGING || !state.verificationFailed) reject(Reason.NOT_READY)
                else Transition(state.copy(record = record.copy(phase = TaskWorktreePhase.RUNNING, handoffGeneration = null, error = null),
                    verifiedCommit = "", acceptedCommit = "", verificationFailed = false))
            is Input.Intent.RetryVerification -> if (record.phase != TaskWorktreePhase.MERGING || !state.verificationFailed) reject(Reason.NOT_READY)
                else Transition(state.copy(record = record.copy(error = null), verifiedCommit = "", acceptedCommit = "", verificationFailed = false))
            is Input.Intent.AttachResponse -> {
                if (input.response.id.isBlank() || record.phase == TaskWorktreePhase.COMPLETE) reject(Reason.INVALID)
                else Transition(state.copy(record = record.copy(executionResponse = input.response)))
            }
            is Input.Intent.Refresh -> if (record.phase !in setOf(TaskWorktreePhase.RUNNING, TaskWorktreePhase.READY)) reject(Reason.NOT_READY)
                else begin(state, input.operationId, Operation.REFRESH)
            is Input.Intent.Capture -> if (record.phase !in ACTIVE_PHASES) reject(Reason.NOT_READY, record.error)
                // A blocked handoff already says why; a running task without one ended its answer without a result.
                else if (!input.planAccepted && (record.phase != TaskWorktreePhase.READY || record.handoffGeneration != state.generation))
                    reject(Reason.NOT_READY, record.error ?: NO_HANDOFF_NOTICE.takeIf { record.phase == TaskWorktreePhase.RUNNING })
                else begin(state.copy(record = record.copy(phase = TaskWorktreePhase.CAPTURING, error = null), verifiedCommit = "", acceptedCommit = ""), input.operationId, Operation.CAPTURE, before = record)
            is Input.Intent.Integrate -> if (state.verificationFailed || record.phase !in setOf(TaskWorktreePhase.MERGING, TaskWorktreePhase.CONFLICT) || record.resultCommit.isBlank() || input.targetCommit.isBlank() ||
                (record.phase == TaskWorktreePhase.CONFLICT && record.handoffGeneration != state.generation && !input.planAccepted)) reject(Reason.NOT_READY)
                else begin(state.copy(record = record.copy(targetCommit = input.targetCommit, error = null), verifiedCommit = "", acceptedCommit = ""), input.operationId, Operation.INTEGRATE, before = record)
            is Input.Intent.Verify -> if (state.verificationFailed || record.phase != TaskWorktreePhase.MERGING || record.mergeCommit.isBlank()) reject(Reason.NOT_READY)
                else begin(state.copy(verificationFailed = false), input.operationId, Operation.VERIFY)
            is Input.Intent.AcceptMerge -> if (record.phase != TaskWorktreePhase.MERGING || input.mergeCommit.isBlank() || input.mergeCommit != record.mergeCommit || state.verifiedCommit != record.mergeCommit) reject(Reason.NOT_READY)
                else Transition(state.copy(acceptedCommit = input.mergeCommit))
            is Input.Intent.Deliver -> if (record.phase != TaskWorktreePhase.MERGING || record.mergeCommit.isBlank() ||
                state.verifiedCommit != record.mergeCommit || state.acceptedCommit != record.mergeCommit) reject(Reason.NOT_READY)
                else begin(state.copy(record = record.copy(phase = TaskWorktreePhase.DELIVERING)), input.operationId, Operation.DELIVER, before = record)
            else -> reject(Reason.INVALID)
        }
    }

    private fun begin(state: State, id: String, kind: Operation, previous: TaskWorktree? = null,
        before: TaskWorktree? = state.record): Transition {
        if (id.isBlank() || id in state.completedOperations) return rejected(state, Reason.ALREADY_USED)
        val record = checkNotNull(state.record)
        val pending = Pending(id, kind, record.taskId, state.generation, before)
        return Transition(state.copy(pending = pending), listOf(Effect.Execute(pending, record, previous)))
    }

    private fun completed(state: State, input: Input.Fact): Transition {
        val pending = state.pending ?: return rejected(state, Reason.STALE)
        val record = state.record ?: return rejected(state, Reason.MISSING)
        val id = when (input) {
            is Input.Fact.Opened -> input.operationId
            is Input.Fact.Refreshed -> input.operationId
            is Input.Fact.Captured -> input.operationId
            is Input.Fact.Integrated -> input.operationId
            is Input.Fact.Verified -> input.operationId
            is Input.Fact.VerificationFailed -> input.operationId
            is Input.Fact.Delivered -> input.operationId
            is Input.Fact.Failed -> input.operationId
            else -> return rejected(state, Reason.INVALID)
        }
        if (id != pending.id || pending.taskId != record.taskId || pending.generation != state.generation) return rejected(state, Reason.STALE)
        if (state.unknown || state.persistenceUnknown) return rejected(state, Reason.UNKNOWN)
        if (input is Input.Fact.Failed) return if (input.beforeEffect) Transition(state.copy(record = pending.before,
            pending = null, completedOperations = state.completedOperations + id))
            else Transition(state.copy(unknown = true, record = record.copy(error = UNKNOWN_NOTICE)))
        val next = when (input) {
            is Input.Fact.Opened -> if (pending.kind == Operation.OPEN) record.copy(phase = TaskWorktreePhase.RUNNING) else null
            is Input.Fact.Refreshed -> if (pending.kind == Operation.REFRESH && input.behind >= 0 &&
                (!(input.updated || input.pendingTransfer) || input.target.isNotBlank())) record.copy(behindCommits = input.behind, refreshNote = input.note,
                pendingTransfer = input.pendingTransfer, integratedCommit = if (input.updated || input.pendingTransfer) input.target else record.integratedCommit) else null
            is Input.Fact.Captured -> if (pending.kind == Operation.CAPTURE && input.commit.isNotBlank()) record.copy(resultCommit = input.commit, phase = TaskWorktreePhase.MERGING) else null
            is Input.Fact.Integrated -> if (pending.kind != Operation.INTEGRATE || input.commit?.isBlank() == true) null
                else if (input.commit == null) record.copy(phase = TaskWorktreePhase.CONFLICT, handoffGeneration = null)
                else record.copy(mergeCommit = input.commit, integratedCommit = record.targetCommit, behindCommits = 0,
                    refreshNote = null, pendingTransfer = false, phase = TaskWorktreePhase.MERGING)
            is Input.Fact.Verified -> if (pending.kind == Operation.VERIFY) record else null
            is Input.Fact.VerificationFailed -> if (pending.kind == Operation.VERIFY && input.message.isNotBlank()) record.copy(error = input.message) else null
            is Input.Fact.Delivered -> if (pending.kind == Operation.DELIVER) record.copy(phase = TaskWorktreePhase.COMPLETE, error = null) else null
            else -> null
        } ?: return rejected(state, Reason.INVALID)
        return Transition(state.copy(record = next, pending = null, completedOperations = state.completedOperations + id,
            verificationFailed = input is Input.Fact.VerificationFailed || (state.verificationFailed && input !is Input.Fact.Verified),
            verifiedCommit = if (input is Input.Fact.Verified) record.mergeCommit else if (input is Input.Fact.VerificationFailed) "" else state.verifiedCommit))
    }

    private fun inspected(state: State, proof: TaskWorktreeProof): Transition {
        val pending = state.pending ?: return rejected(state, Reason.STALE)
        val record = state.record ?: return rejected(state, Reason.MISSING)
        if (!state.unknown || state.persistenceUnknown || proof.operationId != pending.id || proof.taskId != record.taskId || proof.kind != pending.kind)
            return rejected(state, Reason.STALE)
        val fact: Input.Fact = when (pending.kind) {
            Operation.OPEN -> Input.Fact.Opened(pending.id)
            Operation.CAPTURE -> Input.Fact.Captured(pending.id, proof.resultCommit)
            Operation.INTEGRATE -> {
                if (proof.targetCommit != record.targetCommit) return rejected(state, Reason.STALE)
                Input.Fact.Integrated(pending.id, proof.mergeCommit)
            }
            Operation.DELIVER -> {
                if (proof.mergeCommit.isBlank() || proof.mergeCommit != record.mergeCommit) return rejected(state, Reason.STALE)
                Input.Fact.Delivered(pending.id)
            }
            Operation.REFRESH -> Input.Fact.Refreshed(pending.id, proof.behindCommits, proof.integratedCommit,
                proof.integratedCommit.isNotBlank(), null, proof.pendingTransfer)
            // A clean checkout does not prove that arbitrary verification commands finished successfully.
            Operation.VERIFY -> return rejected(state, Reason.UNKNOWN)
        }
        return completed(state.copy(unknown = false), fact).let { transition ->
            if (transition.effects.any { it is Effect.Reject }) rejected(state, Reason.INVALID)
            else transition.copy(state = transition.state.copy(record = transition.state.record?.copy(error = null)))
        }
    }

    /**
     * The next explicit continuation repeats the operation from what it left. A legacy capture has no recorded handoff
     * to return to, so its task returns to the agent like any running task.
     */
    private fun unapplied(state: State, input: Input.Fact.InspectedUnapplied): Transition {
        val pending = state.pending ?: return rejected(state, Reason.STALE)
        val record = state.record ?: return rejected(state, Reason.MISSING)
        if (!state.unknown || state.persistenceUnknown || input.operationId != pending.id || input.taskId != record.taskId ||
            input.kind != pending.kind || pending.taskId != record.taskId) return rejected(state, Reason.STALE)
        if (pending.kind !in RETRYABLE) return rejected(state, Reason.UNKNOWN)
        val before = pending.before ?: return rejected(state, Reason.MISSING)
        if (before.taskId != record.taskId) return rejected(state, Reason.STALE)
        val restored = if (before.phase == TaskWorktreePhase.CAPTURING) before.copy(phase = TaskWorktreePhase.RUNNING, handoffGeneration = null) else before
        return Transition(state.copy(record = restored.copy(error = null), pending = null, unknown = false,
            completedOperations = state.completedOperations + pending.id))
    }

    private fun validRecord(record: TaskWorktree) = record.taskId.isNotBlank() && record.sourcePath.isNotBlank() &&
        record.path.isNotBlank() && record.path != record.sourcePath && record.branch.isNotBlank() &&
        record.targetBranch.isNotBlank() && record.baseCommit.isNotBlank()
    private fun rejected(state: State, reason: Reason) = Transition(state, listOf(Effect.Reject(reason)))
    private val ACTIVE_PHASES = setOf(TaskWorktreePhase.RUNNING, TaskWorktreePhase.READY, TaskWorktreePhase.CONFLICT)
    /**
     * Operations that finish whatever an interrupted attempt left in the task copy, so a repeat is the same operation.
     * Opening names a branch anew, verification runs arbitrary commands and delivery writes the user's folder: those
     * are recovered only by proof of their outcome.
     */
    val RETRYABLE = setOf(Operation.REFRESH, Operation.CAPTURE, Operation.INTEGRATE)
    private const val UNKNOWN_NOTICE = "Исход операции с рабочей копией неизвестен. Проверьте сохранённый результат"
    private const val MISSING_NOTICE = "Рабочая копия недоступна. Проверьте папку задачи"
    private const val NO_HANDOFF_NOTICE = "Агент завершил ответ, не передав результат задачи, поэтому изменения не влиты. Уточните запрос и продолжите"
}
