package io.aequicor.magicpaper.domain.planning

import io.aequicor.magicpaper.domain.*
import kotlinx.serialization.Serializable

/** A selection repair belongs to the captured plan generation, including before any native admission. */
@Serializable data class AssignmentRecoveryRef(val planId: String, val projectId: String,
    val runId: String, val generation: Long)
@Serializable data class PlannerSelectionChange(val before: ModelSelection?, val after: ModelSelection)
@Serializable data class StageAssignmentChange(val stageId: String,
    val before: StageAssignment, val after: StageAssignment)
@Serializable data class AttemptAssignmentChange(val stageId: String?, val expected: PlanningMachine.AttemptRef,
    val before: StageAssignment, val after: StageAssignment,
    val beforeMerge: StageAssignment?, val afterMerge: StageAssignment?)
@Serializable data class AssignmentRecovery(val ref: AssignmentRecoveryRef,
    val planner: PlannerSelectionChange? = null,
    val stages: List<StageAssignmentChange> = emptyList(),
    val attempts: List<AttemptAssignmentChange> = emptyList())

/** Compare-and-replace only the captured bindings. Concurrent choices and completed attempts win. */
fun Plan.applyAssignmentRecovery(recovery: AssignmentRecovery, generation: Long): Plan {
    require(recovery.ref.planId == id && recovery.ref.projectId == projectId && recovery.ref.generation >= 0) {
        "Восстановление назначений относится к другому плану"
    }
    require(recovery.stages.all { it.stageId.isNotBlank() } &&
        recovery.stages.map { it.stageId }.distinct().size == recovery.stages.size &&
        recovery.attempts.all { it.expected.id.isNotBlank() && (it.stageId == null || it.stageId.isNotBlank()) } &&
        recovery.attempts.map { it.expected.id }.distinct().size == recovery.attempts.size) {
        "Назначение указано повторно или без идентичности"
    }
    require(recovery.attempts.all { (it.beforeMerge == null) == (it.afterMerge == null) }) {
        "Восстановление не может создавать или удалять назначение объединения"
    }
    if (recovery.ref.runId != runId || recovery.ref.generation != generation) return this
    fun StageAttempt.restore(change: AttemptAssignmentChange?): StageAttempt {
        if (change == null || phase == AttemptPhase.COMPLETE || PlanningMachine.AttemptRef.from(this) != change.expected ||
            assignment != change.before || mergeAssignment != change.beforeMerge) return this
        return copy(assignment = change.after, mergeAssignment = change.afterMerge)
    }
    return copy(plannerSelection = recovery.planner?.takeIf { plannerSelection == it.before }?.after ?: plannerSelection,
        milestones = milestones.map { stage ->
            if (stage.completed) stage else {
                val change = recovery.stages.firstOrNull { it.stageId == stage.id }
                stage.copy(assignment = change?.takeIf { stage.assignment == it.before }?.after ?: stage.assignment,
                    attempts = stage.attempts.map { attempt -> attempt.restore(recovery.attempts.firstOrNull {
                        it.stageId == stage.id && it.expected.id == attempt.id
                    }) })
            }
        }, finalAttempt = finalAttempt?.let { attempt -> attempt.restore(recovery.attempts.firstOrNull {
            it.stageId == null && it.expected.id == attempt.id
        }) })
}
