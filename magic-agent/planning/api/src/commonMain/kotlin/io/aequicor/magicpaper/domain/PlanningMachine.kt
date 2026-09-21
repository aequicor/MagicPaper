package io.aequicor.magicpaper.domain

import io.aequicor.magicpaper.domain.planning.*
import io.aequicor.magicpaper.machine.Machine
import io.aequicor.magicpaper.machine.MachineId
import io.aequicor.magicpaper.machine.Step
import kotlinx.serialization.Serializable

/** One plan owns its durable admission and all accepted checkpoints. Replaying never admits work. */
object PlanningMachine : Machine<PlanningMachine.State, PlanningMachine.Input, PlanningMachine.Effect> {
    override val id = MachineId("planning")
    override val space get() = PlanningSpace
    /** Bridge to the owner's own reducer: [Transition] and [reduce] keep every call site. */
    override fun step(state: State, input: Input) = reduce(state, input).let { Step(it.state, it.effects) }

    @Serializable data class Stamp(val id: String, val at: Long)
    @Serializable data class RunRef(val planId: String, val runId: String, val admissionId: String, val generation: Long)
    @Serializable data class AttemptRef(val id: String, val sessionId: String, val sessionGeneration: Long,
        val turnIndex: Int, val phase: AttemptPhase, val mergePhase: AttemptPhase?,
        val mergeRetries: Int, val transportRetries: Int, val repairRetries: Int, val interrupted: Boolean) {
        companion object {
            fun from(attempt: StageAttempt) = AttemptRef(attempt.id, attempt.sessionId, attempt.sessionGeneration,
                attempt.turnIndex, attempt.phase, attempt.mergePhase, attempt.mergeRetries, attempt.transportRetries, attempt.repairRetries, attempt.interrupted)
        }
    }
    @Serializable enum class RunPhase { RUNNING, PAUSED, STOPPING, STOPPED, INTERRUPTED, UNKNOWN, COMPLETE }
    @Serializable data class Run(val ref: RunRef, val phase: RunPhase)
    @ConsistentCopyVisibility
    data class State internal constructor(val id: String, val plan: Plan? = null, val run: Run? = null,
        val nativeRecovery: PlanningNativeRecoveryState = PlanningNativeRecoveryState(),
        val generation: Long = 0, val pendingOperations: Set<Long> = emptySet(),
        val admissions: Set<String> = emptySet(), val stopId: String? = null, val persistenceUnknown: Boolean = false, val deleted: Boolean = false,
        val refinement: RefinementRequest? = null)
    @Serializable sealed interface Input { val stamp: Stamp }
    @Serializable sealed interface Intent : Input {
        @Serializable data class ConfirmNativeRecovery(val value: PlanningNativeRecoveryDecision, override val stamp: Stamp) : Intent
        @Serializable data class Create(val plan: Plan, override val stamp: Stamp) : Intent
        @Serializable data class Edit(val expectedRevision: Long, val proposal: Plan, override val stamp: Stamp) : Intent
        @Serializable data class Revise(val events: List<PlanEvent>, override val stamp: Stamp) : Intent
        @Serializable data class Start(val runId: String, val rules: PlanningRulesSnapshot, override val stamp: Stamp) : Intent
        @Serializable data class Resume(val ref: RunRef, override val stamp: Stamp) : Intent
        @Serializable data class Pause(override val stamp: Stamp) : Intent
        @Serializable data class Stop(override val stamp: Stamp) : Intent
        @Serializable data class Retry(val expected: Plan, val authorizations: Map<String, PlanAttemptRetryAuthorization>,
            val runId: String, val rules: PlanningRulesSnapshot, override val stamp: Stamp) : Intent
        @Serializable data class SkipVerification(val blockers: Set<String>, val runId: String,
            val rules: PlanningRulesSnapshot, override val stamp: Stamp,
            val proofs: Set<PlanningSkipProof>? = null) : Intent
        @Serializable data class AssignStage(val stageId: String, val assignment: StageAssignment, override val stamp: Stamp) : Intent
        @Serializable data class Navigate(val step: PlanningStep, override val stamp: Stamp) : Intent
        @Serializable data class RefineRequested(val message: PlanningMessage, override val stamp: Stamp) : Intent
        @Serializable data class BeginRefinement(val message: PlanningMessage, val nodeId: String?,
            val requireApproval: Boolean, val selection: ModelSelection?, val search: SearchProvider,
            override val stamp: Stamp) : Intent
        @Serializable data class CancelRefinement(val ref: RefinementRef, override val stamp: Stamp) : Intent
        @Serializable data class DiscardLegacyRefinement(val requestId: String, override val stamp: Stamp) : Intent
        @Serializable data class RecoverAssignments(val recovery: AssignmentRecovery, override val stamp: Stamp) : Intent
        @Serializable data class Schedule(val expectedRunId: String, val commands: List<ScheduleCommand>, val origin: String,
            val author: String, val questionIds: Set<String>, val waitingTask: String?, override val stamp: Stamp) : Intent
        @Serializable data class Delete(override val stamp: Stamp) : Intent
    }
    @Serializable sealed interface Fact : Input {
        @Serializable data class RefinementCompleted(val ref: RefinementRef, val result: RefinementResult,
            override val stamp: Stamp) : Fact
        @Serializable data class NativeObserved(val value: PlanningNativeFact, override val stamp: Stamp) : Fact
        /** The version-one journal remains readable; this input is accepted only for an empty owner. */
        @Serializable data class LegacyImported(val plan: Plan, val pending: Set<Long>, override val stamp: Stamp) : Fact
        @Serializable data class LegacyCheckpoint(val plan: Plan, override val stamp: Stamp) : Fact
        @Serializable data class Restored(override val stamp: Stamp) : Fact
        @Serializable data class PersistenceUnknown(override val stamp: Stamp) : Fact
        @Serializable data class RecoveryConfirmed(override val stamp: Stamp,
            val native: List<PlanningNativeRecoveryRelease> = emptyList()) : Fact
        @Serializable data class EvidenceObserved(val pending: Set<Long>, override val stamp: Stamp) : Fact
        @Serializable data class OperationUnknown(val intentSeq: Long, override val stamp: Stamp) : Fact
        @Serializable data class StrategySelected(val selection: PlanStrategySelection, val retryLimit: Int?, override val stamp: Stamp) : Fact
        @Serializable data class SkippedVerificationRestored(val ref: RunRef, override val stamp: Stamp) : Fact
        @Serializable data class VerificationObserved(val ref: RunRef, val stageId: String, val expected: AttemptRef,
            val passed: Boolean, val note: String, override val stamp: Stamp) : Fact
        @Serializable data class RulesBound(val rules: PlanningRulesSnapshot, override val stamp: Stamp) : Fact
        @Serializable data class WorkspaceSelected(val ref: RunRef, val enabled: Boolean, override val stamp: Stamp) : Fact
        @Serializable data class WorkspacePrepared(val ref: RunRef, val workspace: PlanWorkspace, override val stamp: Stamp) : Fact
        @Serializable data class PhaseObserved(val ref: RunRef, val phase: ExecutionPhase, override val stamp: Stamp) : Fact
        @Serializable data class IssueObserved(val ref: RunRef?, val issue: PlanningIssue?, val retries: Int? = null, override val stamp: Stamp) : Fact
        @Serializable data class FinalAttemptCreated(val ref: RunRef, val id: String, val sessionId: String,
            val assignment: StageAssignment, val path: String, val engine: CodingEngine, val startedAt: Long, override val stamp: Stamp) : Fact
        @Serializable data class FinalTransitioned(val ref: RunRef, val expected: AttemptRef,
            val mutation: FinalAttemptMutation, override val stamp: Stamp) : Fact
        @Serializable data class StageCreated(val ref: RunRef, val stageId: String, val id: String, val sessionId: String,
            val assignment: StageAssignment, val startedAt: Long, override val stamp: Stamp) : Fact
        @Serializable data class StageTransitioned(val ref: RunRef, val stageId: String, val expected: AttemptRef,
            val mutation: StageMutation, val progress: StageProgress? = null, override val stamp: Stamp) : Fact
        @Serializable data class StageProgressObserved(val ref: RunRef, val stageId: String, val expected: AttemptRef,
            val progress: StageProgress, override val stamp: Stamp) : Fact
        @Serializable data class AttemptRecorded(val ref: RunRef, val stageId: String?, val attempt: StageAttempt,
            val phase: ExecutionPhase? = null, override val stamp: Stamp) : Fact
        @Serializable data class FinalAttemptCleared(val ref: RunRef, val expected: StageAttempt, override val stamp: Stamp) : Fact
        @Serializable data class Applied(val ref: RunRef, val workspace: PlanWorkspace, override val stamp: Stamp) : Fact
        @Serializable data class StopConfirmed(override val stamp: Stamp, val expectedStopId: String? = null) : Fact
        @Serializable data class StopUnknown(override val stamp: Stamp, val expectedStopId: String? = null) : Fact
        @Serializable data class AcceptanceRechecked(val ref: RunRef, val record: AcceptanceRecord, val snapshot: String?, override val stamp: Stamp) : Fact
        @Serializable data class MergeAcceptanceRecorded(val ref: RunRef, val expected: AttemptRef, val record: AcceptanceRecord, override val stamp: Stamp) : Fact
        @Serializable data class ProjectionConfirmed(val marker: String, override val stamp: Stamp) : Fact
        @Serializable data class ProjectionFailed(val marker: String, val stageId: String, val attemptId: String, override val stamp: Stamp) : Fact
        @Serializable data class JournalObserved(val operation: PlanJournalOperation, val stageId: String = "", val attemptId: String = "",
            val detail: String = "", val ref: RunRef? = null, override val stamp: Stamp) : Fact
        @Serializable data class ScheduleAdvanced(val ref: RunRef, override val stamp: Stamp) : Fact
        @Serializable data class ScheduleDelivered(val ref: RunRef, val ruleId: String, override val stamp: Stamp) : Fact
        @Serializable data class ScheduleFailed(val ref: RunRef, val ruleId: String, val receiptExists: Boolean, override val stamp: Stamp) : Fact
    }
    sealed interface Effect {
        data class RunRequested(val ref: RunRef) : Effect
        data class StageDecision(val stageId: String, val transition: StageTransition) : Effect
        data class StopRequested(val ref: RunRef?) : Effect
        data class Reject(val reason: String) : Effect
    }
    data class Transition(val state: State, val effects: List<Effect> = emptyList()) {
        val rejection get() = effects.filterIsInstance<Effect.Reject>().singleOrNull()
    }
    fun initial(id: String): State { require(id.isNotBlank()); return State(id) }
    fun reduce(state: State, input: Input): Transition = try {
        require(input.stamp.id.isNotBlank() && input.stamp.at >= 0) { "Некорректная запись планирования" }
        if (input is Fact.PersistenceUnknown) return Transition(state.copy(persistenceUnknown = true,
            run = state.run?.copy(phase = RunPhase.UNKNOWN)))
        require(!state.persistenceUnknown) { "Результат сохранения неизвестен; требуется восстановление" }
        require(!state.deleted) { "План удалён" }
        if (input is Fact.LegacyImported || input is Intent.Create) {
            val candidate = when (input) { is Fact.LegacyImported -> input.plan; is Intent.Create -> input.plan; else -> error("input") }
            require(state.plan == null && candidate.id == state.id && candidate.projectId.isNotBlank()) { "План уже существует или принадлежит другому владельцу" }
            val plan = DecisionCompiler.migrate(candidate)
            return Transition(state.copy(plan = plan, pendingOperations = (input as? Fact.LegacyImported)?.pending.orEmpty()))
        }
        val plan = requireNotNull(state.plan) { "План не найден" }
        var nativeRecovery = state.nativeRecovery
        var next = plan
        var run = state.run
        var pending = state.pendingOperations
        var deleted = false
        var stopId = state.stopId
        var refinement = state.refinement
        val effects = mutableListOf<Effect>()
        fun requireRun(ref: RunRef) {
            require(ref.planId == state.id && ref.runId == plan.runId && state.run?.ref == ref &&
                state.run.phase != RunPhase.COMPLETE) { "Разрешение запуска устарело или исход неизвестен" }
        }
        fun admit(runId: String, rules: PlanningRulesSnapshot) {
            require(pending.isEmpty() && run?.phase != RunPhase.UNKNOWN) { "Исход операции неизвестен; повтор запрещён" }
            require(run?.phase != RunPhase.RUNNING) { "Запуск уже разрешён" }
            require(!plan.stopping && plan.phase != ExecutionPhase.COMPLETE) { "План остановлен или уже завершён" }
            require(input.stamp.id !in state.admissions && state.generation < Long.MAX_VALUE) { "Разрешение уже использовано" }
            val graph = DecisionCompiler.compile(next)
            require(graph.valid && graph.stageIds.isNotEmpty()) { "В плане нет допустимых этапов" }
            val ref = RunRef(state.id, next.runId.ifBlank { runId.also { require(it.isNotBlank()) } }, input.stamp.id, state.generation + 1)
            run = Run(ref, RunPhase.RUNNING)
            stopId = null
            next = next.copy(intent = ExecutionIntent.RUN, phase = ExecutionPhase.RECOVERING, wizardStep = PlanningStep.STATUS,
                runId = ref.runId, planningRulesSnapshot = next.planningRulesSnapshot ?: rules, issue = null, status = PlanStatus.RUNNING)
            effects += Effect.RunRequested(ref)
        }
        fun progress(attempt: StageAttempt, value: StageProgress): StageAttempt {
            require(attempt.engineSessionId.isBlank() || value.engineSessionId == attempt.engineSessionId) { "Идентичность исполнителя изменилась" }
            require(attempt.mergeEngineSessionId.isBlank() || value.mergeEngineSessionId == attempt.mergeEngineSessionId) { "Идентичность исполнителя слияния изменилась" }
            require(!attempt.interrupted && run?.phase != RunPhase.STOPPED) { "Исполнитель уже остановлен" }
            require(run?.phase != RunPhase.UNKNOWN || !attempt.pendingToolExternal ||
                (value.pendingToolExternal && value.pendingTool == attempt.pendingTool)) { "Неизвестный исход команды нельзя заменить снимком прогресса" }
            return value.applyTo(attempt)
        }
        fun recordStage(stageId: String, value: StageAttempt) {
            val stage = next.milestones.single { it.id == stageId }
            require(stage.attempts.lastOrNull()?.id.let { it == null || it == value.id }) { "Попытка этапа изменилась" }
            val attempt = value.copy(updatedAt = input.stamp.at)
            val marker = attempt.takeIf { it.phase == AttemptPhase.COMPLETE && it.sessionGeneration > 0 }?.let { "${it.id}:${it.sessionGeneration}" }
            next = next.copy(phase = ExecutionPhase.EXECUTING, pendingSessionProjections = next.pendingSessionProjections + listOfNotNull(marker),
                milestones = next.milestones.map { if(it.id != stageId) it else it.copy(
                    status = when { attempt.phase == AttemptPhase.COMPLETE -> MilestoneStatus.DONE; attempt.error?.requiresUser == true -> MilestoneStatus.FAILED; else -> MilestoneStatus.ACTIVE },
                    report = attempt.report, attempts = if(it.attempts.isEmpty()) listOf(attempt) else it.attempts.dropLast(1) + attempt) })
        }
        fun note(operation: PlanJournalOperation, stageId: String = "", attemptId: String = "", detail: String = "", id: String = input.stamp.id) {
            if (next.journal.none { it.id == id }) next = next.copy(journal = next.journal + PlanJournalEntry(id,
                input.stamp.at, operation, stageId, attemptId, detail))
        }
        when (input) {
            is Intent.Create, is Fact.LegacyImported, is Fact.PersistenceUnknown -> error("handled")
            is Intent.ConfirmNativeRecovery -> nativeRecovery = planningNativeDecision(state, input.value)
            is Fact.NativeObserved -> nativeRecovery = planningNativeObserved(state, input.value)
            is Intent.Edit -> {
                val proposed = input.proposal
                require(plan.revision == input.expectedRevision) { "План изменился; повторите правку" }
                require(proposed == plan.copy(goal = proposed.goal, tree = proposed.tree, milestones = proposed.milestones,
                    priorities = proposed.priorities, parallelism = proposed.parallelism, wizardStep = proposed.wizardStep,
                    plannerSelection = proposed.plannerSelection, searchProvider = proposed.searchProvider)) { "Правка не может менять полномочия исполнения" }
                require(proposed.milestones.all { next -> plan.milestones.firstOrNull { it.id == next.id }?.let { old ->
                    next.attempts == old.attempts && next.status == old.status && next.report == old.report && next.checkNote == old.checkNote
                } ?: (next.attempts.isEmpty() && next.status == MilestoneStatus.PENDING && next.report.isEmpty()) }) { "Правка не может менять результат этапа" }
                next = validatePlanRevision(plan, proposed)
            }
            is Intent.Revise -> input.events.forEach { next = io.aequicor.magicpaper.domain.planning.reduce(next, it) }
            is Intent.RecoverAssignments -> next = plan.applyAssignmentRecovery(input.recovery, state.generation)
            is Intent.BeginRefinement -> {
                require(input.message.id.isNotBlank() && input.message.role == "user") { "Некорректный запрос доработки" }
                require(input.nodeId == null || plan.tree.any { it.id == input.nodeId }) { "Узел доработки отсутствует" }
                val previous = plan.dialogue.firstOrNull { it.id == input.message.id }
                require(previous == null || previous == input.message) { "Идентификатор сообщения уже использован" }
                next = plan.copy(pendingRequest = input.message.text, requestId = input.message.id,
                    pendingRecalculationNodeId = input.nodeId,
                    dialogue = if (previous == null) plan.dialogue + input.message else plan.dialogue)
                refinement = next.captureRefinement(input.message.id, input.stamp.id, input.requireApproval,
                    input.selection, input.search).copy(baseRevision = plan.revision + if (next != plan) 1 else 0)
            }
            is Fact.RefinementCompleted -> {
                val captured = requireNotNull(refinement) { "Запрос доработки уже завершён" }
                require(captured.ref == input.ref) { "Ответ относится к другой попытке доработки" }
                next = plan.finishRefinement(captured, input.result, input.stamp.at)
                refinement = null
            }
            is Intent.CancelRefinement -> {
                if (refinement?.ref == input.ref) {
                    next = plan.copy(pendingRequest = "", requestId = "", pendingRecalculationNodeId = null)
                    refinement = null
                }
            }
            is Intent.DiscardLegacyRefinement -> {
                require(refinement == null && input.requestId.isNotBlank() && plan.requestId == input.requestId) { "Запрос доработки изменился" }
                next = plan.copy(pendingRequest = "", requestId = "", pendingRecalculationNodeId = null)
            }
            is Intent.Start -> admit(input.runId, input.rules)
            is Intent.Resume -> {
                requireRun(input.ref)
                require(run?.phase == RunPhase.PAUSED && pending.isEmpty() && !plan.stopping && plan.phase != ExecutionPhase.COMPLETE) { "Продолжение текущего запуска недоступно" }
                next = plan.copy(intent = ExecutionIntent.RUN)
                run = requireNotNull(run).copy(phase = RunPhase.RUNNING)
                effects += Effect.RunRequested(input.ref)
            }
            is Intent.Pause -> { require(!plan.stopping && run?.phase != RunPhase.UNKNOWN) { "Исход остановки ещё не подтверждён" }; next = plan.copy(intent = ExecutionIntent.PAUSE); run = run?.copy(phase = RunPhase.PAUSED) }
            is Intent.Stop -> { stopId = input.stamp.id; next = plan.copy(intent = ExecutionIntent.STOP, stopping = true); run = run?.copy(phase = RunPhase.STOPPING); note(PlanJournalOperation.STOP_INTENT); effects += Effect.StopRequested(run?.ref) }
            is Intent.Retry -> {
                require(plan == input.expected) { "Состояние плана изменилось; повторите действие" }
                require(plan.blockingIssues(emptyList()).none { it.issue.retryBlocked }) { "Повтор не устраняет причину остановки" }
                fun resumed(attempt: StageAttempt) = attempt.retryAfterUserAction().let { updated ->
                    input.authorizations[attempt.id]?.let { updated.copy(retryAuthorization = it) } ?: updated
                }.let { if (plan.issue?.kind == IssueKind.UNCERTAIN) it.copy(pendingToolExternal = false, pendingTool = "") else it }
                next = plan.copy(finalAttempt = plan.finalAttempt?.let(::resumed), milestones = plan.milestones.map { it.copy(attempts = it.attempts.map(::resumed)) })
                admit(input.runId, input.rules)
            }
            is Intent.SkipVerification -> {
                val blockers = plan.blockingIssues(emptyList())
                require(blockers.isNotEmpty() && blockers.map { it.messageId }.toSet() == input.blockers && blockers.all { it.canSkipVerification }) { "Пропуск проверки недоступен" }
                val proofs = blockers.map { requireNotNull(it.verificationProof) }.toSet()
                require(proofs.size == blockers.size && input.proofs == proofs) { "Подтверждение проверки изменилось" }
                val waivers = blockers.flatMap { blocker ->
                    val attempt = requireNotNull(blocker.attempt)
                    val record = requireNotNull(attempt.acceptanceRecord)
                    require(record.runId == plan.runId && record.attemptId == attempt.id &&
                        record.criteria == (blocker.stage?.criteria() ?: plan.acceptanceCriteria())) { "Условия проверки изменились" }
                    record.criteria.map { AcceptanceWaiver(plan.runId, it, record.attemptId, record.snapshotId, input.stamp.at) }
                }
                val attempts = blockers.map { it.attempt!!.id }.toSet()
                fun resume(attempt: StageAttempt) = if (attempt.id in attempts) attempt.copy(error = null, verificationSnapshot = null) else attempt
                next = plan.copy(acceptanceWaivers = (plan.acceptanceWaivers + waivers).distinctBy { it.runId to it.criterion },
                    milestones = plan.milestones.map { it.copy(attempts = it.attempts.map(::resume)) }, finalAttempt = plan.finalAttempt?.let(::resume))
                admit(input.runId, input.rules); note(PlanJournalOperation.USER_SKIP_VERIFICATION)
            }
            is Intent.AssignStage -> {
                require(plan.milestones.any { it.id == input.stageId }) { "Этап не найден" }
                next = plan.copy(milestones = plan.milestones.map { if(it.id != input.stageId) it else it.copy(
                    agentProfileId = input.assignment.profileId, agentModelId = input.assignment.modelId, assignment = input.assignment) })
            }
            is Intent.Navigate -> next = plan.copy(wizardStep = input.step)
            is Intent.RefineRequested -> next = plan.copy(wizardStep = PlanningStep.CLARIFY, dialogue = plan.dialogue + input.message)
            is Intent.Schedule -> {
                require(plan.runId == input.expectedRunId) { "Запуск изменился; команда не применена" }
                var number = 0L
                val allocated = plan.scheduledMessages.flatMap { listOf(it.id, it.deliveryId) }.toMutableSet()
                next = plan.applyScheduleCommands(input.commands, input.origin, input.author, input.questionIds, input.stamp.at, input.waitingTask) {
                    var id: String
                    do { id = "${input.stamp.id}:schedule:${number++}" } while(!allocated.add(id))
                    id
                }
            }
            is Intent.Delete -> { require(run?.phase !in setOf(RunPhase.RUNNING, RunPhase.STOPPING, RunPhase.UNKNOWN) && pending.isEmpty()) { "Сначала подтвердите остановку" }; deleted = true }
            is Fact.LegacyCheckpoint -> {
                require(input.plan.id == plan.id && input.plan.projectId == plan.projectId && input.plan.revision == plan.revision + 1) { "Повреждена последовательность checkpoint" }
                return Transition(state.copy(plan = input.plan, run = run?.takeIf { it.ref.runId == input.plan.runId }?.let {
                    if(input.plan.intent == ExecutionIntent.RUN) it else it.copy(phase = if(input.plan.stopping) RunPhase.STOPPING else RunPhase.PAUSED)
                }))
            }
            is Fact.Restored -> run = run?.copy(phase = if (pending.isNotEmpty()) RunPhase.UNKNOWN else when(run!!.phase) {
                RunPhase.RUNNING, RunPhase.STOPPING -> RunPhase.INTERRUPTED; else -> run!!.phase })
            is Fact.RecoveryConfirmed -> {
                require(pending.isEmpty()) { "Исход операции ещё неизвестен" }
                val released = planningNativeReleased(state, input.native)
                next = released.first
                nativeRecovery = released.second
                if(PlanningRecoveryIssues.owns(next.issue)) next = next.copy(issue = null,
                    status = if(next.phase == ExecutionPhase.COMPLETE) PlanStatus.DONE else next.status)
            }
            is Fact.EvidenceObserved -> {
                pending = input.pending
                run = run?.let { it.copy(phase = if(pending.isNotEmpty()) RunPhase.UNKNOWN else if(it.phase == RunPhase.UNKNOWN) RunPhase.INTERRUPTED else it.phase) }
                if (pending.isEmpty()) {
                    // Recovery proof was committed before closing the intent. Replay only finishes this projection.
                    val ready = planningNativeReadyReleases(state.copy(pendingOperations = pending))
                    val released = planningNativeReleased(state.copy(pendingOperations = pending), ready)
                    next = released.first
                    nativeRecovery = released.second
                }
            }
            is Fact.OperationUnknown -> { require(input.intentSeq > 0); pending = pending + input.intentSeq; run = run?.copy(phase = RunPhase.UNKNOWN) }
            is Fact.StrategySelected -> { require(input.selection.strategy == selectPlanStrategy(plan, input.selection.cause, input.selection.status, input.retryLimit)); next = applyPlanStrategy(plan, input.selection) }
            is Fact.SkippedVerificationRestored -> { requireRun(input.ref); next = plan.restoreSkippedVerification() }
            is Fact.VerificationObserved -> { requireRun(input.ref); require(plan.milestones.single { it.id == input.stageId }.attempts.lastOrNull()?.let(AttemptRef::from) == input.expected) { "Проверяемая попытка изменилась" }; next = plan.copy(
                milestones = plan.milestones.map { if(it.id == input.stageId) it.copy(checkNote = input.note) else it },
                coordination = plan.coordination.map { if(it.id == "${input.expected.id}-turn-${input.expected.turnIndex - 1}") it.copy(verification = StageVerification(input.passed, input.note)) else it }) }
            is Fact.RulesBound -> next = plan.copy(planningRulesSnapshot = plan.planningRulesSnapshot ?: input.rules)
            is Fact.WorkspaceSelected -> { requireRun(input.ref); next = plan.copy(worktreeEnabled = input.enabled, sharedWorkspace = !input.enabled) }
            is Fact.WorkspacePrepared -> { requireRun(input.ref); next = plan.copy(workspace = input.workspace, issue = null, phase = ExecutionPhase.EXECUTING) }
            is Fact.PhaseObserved -> {
                requireRun(input.ref)
                require(input.phase in setOf(ExecutionPhase.WAITING, ExecutionPhase.APPLYING)) { "Фаза требует собственного факта результата" }
                if(input.phase == ExecutionPhase.APPLYING) require(run?.phase == RunPhase.RUNNING && pending.isEmpty()) { "Применение не разрешено" }
                next = plan.copy(phase = input.phase)
            }
            is Fact.IssueObserved -> {
                input.ref?.let(::requireRun)
                next = plan.copy(issue = input.issue, transportRetries = input.retries ?: plan.transportRetries,
                    phase = if(plan.phase == ExecutionPhase.COMPLETE) plan.phase else if (input.issue == null) ExecutionPhase.RECOVERING else ExecutionPhase.WAITING,
                    status = if (input.issue?.requiresUser == true) PlanStatus.FAILED else PlanStatus.RUNNING)
                if(input.issue?.requiresUser == true && run?.phase == RunPhase.RUNNING) run = run?.copy(phase = RunPhase.PAUSED)
            }
            is Fact.FinalAttemptCreated -> {
                requireRun(input.ref)
                require(run?.phase == RunPhase.RUNNING && pending.isEmpty() && plan.finalAttempt == null && input.id.isNotBlank() && input.sessionId.isNotBlank()) { "Итоговая попытка уже существует или запуск не разрешён" }
                require(plan.finalAttemptHistory.none { it.id == input.id }) { "Идентификатор итоговой попытки закрыт" }
                next = plan.copy(finalAttempt = StageAttempt(input.id, input.sessionId, input.assignment, path = input.path,
                    engine = input.engine, startedAt = input.startedAt, updatedAt = input.stamp.at), phase = ExecutionPhase.VERIFYING)
            }
            is Fact.FinalTransitioned -> {
                requireRun(input.ref)
                val attempt = requireNotNull(plan.finalAttempt) { "Итоговая попытка не найдена" }
                require(AttemptRef.from(attempt) == input.expected) { "Итоговая попытка изменилась" }
                when(val mutation = input.mutation) {
                    is FinalAttemptMutation.VerificationStarted, is FinalAttemptMutation.DeliveryStarted, is FinalAttemptMutation.DeliveryRequested -> require(run?.phase == RunPhase.RUNNING && pending.isEmpty()) { "Новый ход не разрешён" }
                    is FinalAttemptMutation.DeliveryReviewed -> require(mutation.record.runId == plan.runId && mutation.record.attemptId == attempt.id && mutation.record.criteria == plan.acceptanceCriteria()) { "Проверка переноса относится к другому запуску" }
                    FinalAttemptMutation.WaiversApplied -> require(plan.acceptanceCriteria().all { criterion -> plan.acceptanceWaivers.any { it.runId == plan.runId && it.criterion == criterion } }) { "Проверки не пропущены пользователем" }
                    is FinalAttemptMutation.ProgressObserved -> progress(attempt, mutation.progress)
                    is FinalAttemptMutation.AcceptanceRecorded -> require(mutation.record.runId == plan.runId && mutation.record.attemptId == attempt.id && mutation.record.criteria == plan.acceptanceCriteria()) { "Итоговая проверка относится к другому запуску" }
                    else -> Unit
                }
                val result = finalAttemptTransition(attempt, input.mutation)
                next = plan.copy(finalAttempt = result.attempt.copy(updatedAt = input.stamp.at), phase = result.phase)
            }
            is Fact.StageCreated -> {
                requireRun(input.ref)
                require(run?.phase == RunPhase.RUNNING && pending.isEmpty()) { "Создание попытки не разрешено" }
                require(plan.milestones.single { it.id == input.stageId }.attempts.isEmpty() && input.id.isNotBlank() && input.sessionId.isNotBlank()) { "Попытка уже существует" }
                require(plan.milestones.flatMap { it.attempts }.none { it.id == input.id || it.sessionId == input.sessionId }) { "Идентификатор попытки занят" }
                recordStage(input.stageId, StageAttempt(input.id, input.sessionId, input.assignment, startedAt = input.startedAt))
            }
            is Fact.StageProgressObserved -> {
                requireRun(input.ref)
                val attempt = plan.milestones.single { it.id == input.stageId }.attempts.last()
                require(AttemptRef.from(attempt) == input.expected && attempt.phase != AttemptPhase.COMPLETE) { "Результат попытки устарел" }
                recordStage(input.stageId, progress(attempt, input.progress))
            }
            is Fact.StageTransitioned -> {
                requireRun(input.ref)
                val attempt = plan.milestones.single { it.id == input.stageId }.attempts.last()
                require(AttemptRef.from(attempt) == input.expected) { "Попытка этапа изменилась" }
                require(attempt.phase != AttemptPhase.COMPLETE || input.mutation is StageEvent.Interrupted) { "Этап уже завершён" }
                val live = input.progress?.takeIf { it != StageProgress.from(attempt) }?.let { progress(attempt, it) } ?: attempt
                when(val event = input.mutation) {
                    is StageEvent.WorkerStarting -> require(run?.phase == RunPhase.RUNNING && pending.isEmpty() && attempt.phase in setOf(AttemptPhase.PREPARED, AttemptPhase.EXECUTING, AttemptPhase.FAILED)) { "Новый ход не разрешён" }
                    is StageEvent.EngineResolved -> require(attempt.engine == null) { "Движок уже выбран" }
                    is StageEvent.WorkspacePrepared -> require(event.prepared == live.copy(path = event.prepared.path, baseCommit = event.prepared.baseCommit)) { "Рабочая папка изменила полномочия попытки" }
                    is StageEvent.WorkerAdmitted -> require(event.admitted == live.copy(sessionGeneration = event.admitted.sessionGeneration) && event.admitted.sessionGeneration >= live.sessionGeneration) { "Допуск изменил попытку" }
                    is StageEvent.AcceptanceRecorded -> require(event.record.runId == plan.runId && event.record.attemptId == attempt.id && event.record.criteria == plan.milestones.single { it.id == input.stageId }.criteria()) { "Проверка принадлежит другой попытке" }
                    is StageEvent.Captured -> require(attempt.phase == AttemptPhase.VERIFYING && attempt.acceptanceRecord?.permitsProgress == true) { "Приёмка этапа не подтверждена" }
                    StageEvent.Completed -> require(attempt.phase == AttemptPhase.INTEGRATING) { "Объединение этапа не подтверждено" }
                    else -> Unit
                }
                val transition = io.aequicor.magicpaper.domain.planning.reduce(live.toState(), input.mutation)
                val beginsWorker = transition.effects.any { it in setOf(StageEffect.RunWorker, StageEffect.PrepareWorker, StageEffect.RunConflictAgent, StageEffect.Coordinate) }
                val beginsCompletion = transition.effects.any { it in setOf(StageEffect.RunVerifier, StageEffect.RunMerge, StageEffect.Capture, StageEffect.RunMergeVerifier) }
                require(!beginsWorker || (run?.phase == RunPhase.RUNNING && pending.isEmpty())) { "Новый ход не разрешён" }
                require(!beginsCompletion || (run?.phase in setOf(RunPhase.RUNNING, RunPhase.PAUSED) && pending.isEmpty())) { "Продолжение проверки не разрешено" }
                recordStage(input.stageId, transition.state.attempt)
                effects += Effect.StageDecision(input.stageId, transition.copy(state = next.milestones.single { it.id == input.stageId }.attempts.last().toState()))
            }
            is Fact.AttemptRecorded -> {
                requireRun(input.ref)
                val previous = if (input.stageId == null) plan.finalAttempt else plan.milestones.single { it.id == input.stageId }.attempts.lastOrNull()
                previous?.let { require(it.id == input.attempt.id && it.sessionId == input.attempt.sessionId && it.turnIndex <= input.attempt.turnIndex &&
                    it.sessionGeneration <= input.attempt.sessionGeneration && (it.phase != AttemptPhase.COMPLETE || it == input.attempt)) { "Checkpoint попытки устарел" } }
                val attempt = input.attempt.copy(updatedAt = input.stamp.at)
                val marker = attempt.takeIf { it.phase == AttemptPhase.COMPLETE && it.sessionGeneration > 0 }?.let { "${it.id}:${it.sessionGeneration}" }
                next = if (input.stageId == null) plan.copy(finalAttempt = attempt, phase = input.phase ?: plan.phase)
                else plan.copy(phase = ExecutionPhase.EXECUTING,
                    pendingSessionProjections = plan.pendingSessionProjections + listOfNotNull(marker),
                    milestones = plan.milestones.map { stage -> if (stage.id != input.stageId) stage else stage.copy(
                        status = when { attempt.phase == AttemptPhase.COMPLETE -> MilestoneStatus.DONE; attempt.error?.requiresUser == true -> MilestoneStatus.FAILED; else -> MilestoneStatus.ACTIVE },
                        report = attempt.report, attempts = stage.attempts.filterNot { it.id == attempt.id } + attempt) })
            }
            is Fact.FinalAttemptCleared -> { requireRun(input.ref); require(plan.finalAttempt == input.expected) { "Итоговая попытка изменилась" }; next = plan.copy(finalAttempt = null, finalAttemptHistory = plan.finalAttemptHistory + listOfNotNull(plan.finalAttempt), phase = ExecutionPhase.EXECUTING) }
            is Fact.Applied -> {
                requireRun(input.ref)
                require(run?.phase == RunPhase.RUNNING && pending.isEmpty() && plan.intent == ExecutionIntent.RUN) { "Исход исполнения не подтверждён" }
                require(plan.issue == null && plan.finalAttempt?.acceptanceRecord?.permitsProgress == true && plan.finalAttempt?.acceptanceRecord?.criteria == plan.acceptanceCriteria()) { "Приёмка изменилась" }
                val acceptedFinal = requireNotNull(plan.finalAttempt) { "Итоговая попытка не подтверждена" }
                require(acceptedFinal.phase == AttemptPhase.COMPLETE && acceptedFinal.acceptanceRecord?.runId == plan.runId &&
                    acceptedFinal.acceptanceRecord?.attemptId == acceptedFinal.id) { "Итоговая попытка не подтверждена" }
                require(input.workspace.applied && plan.selectedMilestones.isNotEmpty() && plan.selectedMilestones.all { it.completed }) { "Результат плана ещё не применён" }
                val workspace = requireNotNull(plan.workspace) { "Рабочая папка не подготовлена" }
                require(input.workspace.root == workspace.root && input.workspace.integrationPath == workspace.integrationPath &&
                    input.workspace.baseCommit == workspace.baseCommit && input.workspace.git == workspace.git) { "Рабочая папка результата изменилась" }
                next = plan.copy(workspace = input.workspace, phase = ExecutionPhase.COMPLETE, status = PlanStatus.DONE, issue = null)
                run = run?.copy(phase = RunPhase.COMPLETE)
                note(PlanJournalOperation.APPLY_COMPLETE)
            }
            is Fact.StopConfirmed -> { require(plan.intent == ExecutionIntent.STOP && plan.stopping); require(input.expectedStopId == null || input.expectedStopId == stopId) { "Запрос остановки устарел" }; next = plan.copy(stopping = false, status = PlanStatus.STOPPED,
                issue = if(pending.isNotEmpty() && plan.issue?.requiresUser != true) PlanningRecoveryIssues.stoppedWithUnconfirmedEffects else plan.issue)
                run = run?.copy(phase = if(pending.isEmpty()) RunPhase.STOPPED else RunPhase.UNKNOWN); note(PlanJournalOperation.STOP_CONFIRMED) }
            is Fact.StopUnknown -> { require(input.expectedStopId == null || (input.expectedStopId == stopId && plan.intent == ExecutionIntent.STOP && plan.stopping)) { "Запрос остановки устарел" }; next = plan.copy(stopping = true, phase = ExecutionPhase.WAITING, issue = PlanningIssue(IssueKind.UNCERTAIN, "Остановка не подтверждена", requiresUser = true)); run = run?.copy(phase = RunPhase.UNKNOWN) }
            is Fact.AcceptanceRechecked -> {
                requireRun(input.ref)
                if (plan.runId == input.record.runId && plan.finalAttempt?.id == input.record.attemptId && plan.finalAttempt?.acceptanceRecord == input.record) {
                    val checked = AcceptanceGate.evaluate(input.record, plan.acceptanceCriteria(), input.snapshot)
                    next = plan.copy(finalAttempt = requireNotNull(plan.finalAttempt).copy(acceptanceRecord = checked),
                        issue = if(checked.permitsProgress) plan.issue else PlanningIssue(IssueKind.VERIFICATION, checked.summary(), requiresUser = true),
                        status = if(checked.permitsProgress) plan.status else PlanStatus.FAILED)
                }
            }
            is Fact.MergeAcceptanceRecorded -> {
                requireRun(input.ref)
                val attempt = requireNotNull(plan.finalAttempt)
                require(AttemptRef.from(attempt) == input.expected && input.record.runId == plan.runId && input.record.attemptId == attempt.id && input.record.criteria == plan.acceptanceCriteria()) { "Проверка переноса устарела" }
                next = plan.copy(finalAttempt = attempt.copy(mergeAcceptanceRecord = input.record))
            }
            is Fact.ProjectionConfirmed -> next = plan.copy(pendingSessionProjections = plan.pendingSessionProjections - input.marker)
            is Fact.ProjectionFailed -> note(PlanJournalOperation.SESSION_PROJECTION_PENDING, input.stageId, input.attemptId, "Синхронизация сессии ожидает повторной попытки", "${input.marker}-session-projection")
            is Fact.JournalObserved -> {
                if(input.operation.kind == JournalEntryKind.INTENT) {
                    requireRun(requireNotNull(input.ref))
                    val admittedCompletion = run?.phase == RunPhase.PAUSED && input.operation in setOf(PlanJournalOperation.CAPTURE_INTENT, PlanJournalOperation.MERGE_INTENT) &&
                        plan.milestones.any { stage -> stage.id == input.stageId && stage.attempts.any { it.id == input.attemptId && it.phase in setOf(AttemptPhase.VERIFYING, AttemptPhase.INTEGRATING) } }
                    require(pending.isEmpty() && (run?.phase == RunPhase.RUNNING || admittedCompletion)) { "Исполнение приостановлено или его исход неизвестен" }
                }
                note(input.operation, input.stageId, input.attemptId, input.detail)
            }
            is Fact.ScheduleAdvanced -> { requireRun(input.ref); next = plan.advanceScheduledMessages(input.stamp.at) }
            is Fact.ScheduleDelivered -> { requireRun(input.ref); next = plan.copy(scheduledMessages = plan.scheduledMessages.map { if(it.id == input.ruleId && it.status == ScheduledMessageStatus.READY) it.copy(status = ScheduledMessageStatus.QUEUED) else it }) }
            is Fact.ScheduleFailed -> { requireRun(input.ref); next = plan.copy(scheduledMessages = plan.scheduledMessages.map { if(it.id == input.ruleId) it.copy(status = if(input.receiptExists) ScheduledMessageStatus.QUEUED else ScheduledMessageStatus.ERROR, reason = if(input.receiptExists) it.reason else "Не удалось доставить сообщение") else it }) }
        }
        require(next.id == plan.id && next.projectId == plan.projectId) { "Идентичность плана изменилась" }
        if (next.runId != plan.runId && effects.none { it is Effect.RunRequested }) run = null
        if(next.intent != ExecutionIntent.RUN && run?.phase == RunPhase.RUNNING) run = run?.copy(phase = if(next.stopping) RunPhase.STOPPING else RunPhase.PAUSED)
        val admitted = effects.filterIsInstance<Effect.RunRequested>().singleOrNull()?.ref
        if (next != plan) {
            require(plan.revision < Long.MAX_VALUE)
            var number = 0
            next = DecisionCompiler.migrate(next).checkpointMessageEvents(plan, input.stamp.at) { "${input.stamp.id}:message:${number++}" }
                .copy(revision = plan.revision + 1, updatedAt = input.stamp.at)
        }
        Transition(state.copy(plan = next, run = run, nativeRecovery = nativeRecovery, pendingOperations = pending, deleted = deleted, stopId = stopId, refinement = refinement,
            generation = admitted?.generation ?: state.generation, admissions = state.admissions + listOfNotNull(admitted?.admissionId)), effects)
    } catch (failure: IllegalArgumentException) { Transition(state, listOf(Effect.Reject(failure.message ?: "Переход недоступен"))) }
      catch (failure: IllegalStateException) { Transition(state, listOf(Effect.Reject(failure.message ?: "Переход недоступен"))) }
}
