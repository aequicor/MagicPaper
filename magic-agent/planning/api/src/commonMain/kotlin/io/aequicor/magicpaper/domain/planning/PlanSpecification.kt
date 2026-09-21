package io.aequicor.magicpaper.domain.planning

import io.aequicor.magicpaper.domain.*
import kotlinx.serialization.Serializable

/** Editable definition only. Attempts, results, display numbering and execution authority stay with the owner. */
@Serializable
data class StageSpecification(
    val id: String,
    val title: String,
    val description: String = "",
    val agentProfileId: String = "",
    val agentModelId: String = "",
    val assignment: StageAssignment? = null,
    val assessment: StageAssessment = StageAssessment(),
    val acceptance: String = "",
    val dependsOn: List<String> = emptyList(),
    val durationHours: Double? = null,
    val complexityPoints: Double? = null,
    val continuationOf: String? = null,
    val acceptanceCriteria: List<AcceptanceCriterion> = emptyList(),
    val isFinalization: Boolean = false,
) {
    fun milestone(): Milestone = Milestone(id = id, title = title, description = description,
        agentProfileId = agentProfileId, agentModelId = agentModelId, assignment = assignment,
        assessment = assessment, acceptance = acceptance, dependsOn = dependsOn,
        durationHours = durationHours, complexityPoints = complexityPoints, continuationOf = continuationOf,
        acceptanceCriteria = acceptanceCriteria, isFinalization = isFinalization)

    companion object {
        fun from(stage: Milestone) = StageSpecification(stage.id, stage.title, stage.description,
            stage.agentProfileId, stage.agentModelId, stage.assignment, stage.assessment, stage.acceptance,
            stage.dependsOn, stage.durationHours, stage.complexityPoints, stage.continuationOf,
            stage.acceptanceCriteria, stage.isFinalization)
    }
}

@Serializable
data class PlanSpecification(
    val goal: String,
    val tree: List<DecisionNode>,
    val stages: List<StageSpecification>,
    val priorities: PlanningPriorities = PlanningPriorities(),
    val parallelism: Int = 2,
) {
    companion object {
        fun from(plan: Plan) = PlanSpecification(plan.goal, plan.tree,
            plan.milestones.map(StageSpecification::from), plan.priorities, plan.parallelism)
    }
}

/** Applies definitions over the latest telemetry, then repeats the existing edit/final-review policy. */
fun Plan.applySpecification(specification: PlanSpecification): Plan =
    validatePlanRevision(this, specificationCandidate(specification))

internal fun Plan.specificationCandidate(specification: PlanSpecification): Plan {
    require(specification.goal.isNotBlank() && specification.parallelism > 0) { "Некорректная спецификация плана" }
    require(specification.stages.all { it.id.isNotBlank() } &&
        specification.stages.map { it.id }.distinct().size == specification.stages.size &&
        specification.tree.all { it.id.isNotBlank() } &&
        specification.tree.map { it.id }.distinct().size == specification.tree.size) { "Идентификаторы узлов и этапов должны быть уникальны" }
    val next = copy(goal = specification.goal, tree = specification.tree,
        priorities = specification.priorities, parallelism = specification.parallelism,
        milestones = specification.stages.map { definition ->
            val previous = milestones.firstOrNull { it.id == definition.id }
            if (previous != null && (previous.attempts.isNotEmpty() || previous.status != MilestoneStatus.PENDING)) {
                require(StageSpecification.from(previous) == definition) { "Начатый этап нельзя изменить" }
                previous
            } else definition.milestone().copy(displayNumber = previous?.displayNumber,
                displayName = previous?.displayName, updatedAt = previous?.updatedAt ?: 0)
        })
    DecisionCompiler.validateEdit(this, next)
    return next
}
