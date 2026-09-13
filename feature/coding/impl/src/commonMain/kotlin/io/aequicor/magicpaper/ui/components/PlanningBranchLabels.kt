package io.aequicor.magicpaper.ui.components

import io.aequicor.magicpaper.domain.*

data class PlanningBranchLabel(val choiceId: String, val optionId: String, val title: String)
data class PlanningStageBranches(val labels: List<PlanningBranchLabel>, val shared: Boolean)

/** Membership is independent of selection so unselected and nested routes stay identifiable. */
fun planningStageBranches(plan: Plan): Map<String, PlanningStageBranches> {
    val nodes = plan.tree.associateBy { it.id }
    fun descendants(id: String, skipChoices: Boolean = false, visited: MutableSet<String> = mutableSetOf()): Set<String> {
        if (!visited.add(id)) return emptySet()
        val node = nodes[id] ?: return emptySet()
        if (node.kind == DecisionKind.STAGE) return setOf(node.stageId ?: node.id)
        if (skipChoices && node.kind == DecisionKind.CHOICE) return emptySet()
        return node.children.flatMap { descendants(it, skipChoices, visited) }.toSet()
    }
    val unconditional = plan.tree.filter { it.kind == DecisionKind.GOAL }.flatMap { descendants(it.id, true) }.toSet()
    val labels = mutableMapOf<String, MutableList<PlanningBranchLabel>>()
    plan.tree.filter { it.kind == DecisionKind.CHOICE && it.children.size > 1 }.forEach { choice ->
        choice.children.forEach { id -> nodes[id]?.let { option ->
            descendants(id).forEach { stage ->
                labels.getOrPut(stage) { mutableListOf() }.add(PlanningBranchLabel(choice.id, id, option.title))
            }
        } }
    }
    return labels.mapValues { (stage, values) ->
        PlanningStageBranches(values.distinct(), stage in unconditional || values.groupBy { it.choiceId }.values.any { it.size > 1 })
    }
}
