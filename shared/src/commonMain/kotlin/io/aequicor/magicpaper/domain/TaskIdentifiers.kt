package io.aequicor.magicpaper.domain

import io.aequicor.magicpaper.util.Id

/** Models may name draft aliases; permanent task identity is allocated before the proposal is persisted. */
internal fun Plan.allocateTaskIdentifiers(previous: Plan, generate: () -> String = Id::uuid): Plan {
    val used = (previous.milestones.map { it.id } + tree.map { it.id }).toMutableSet()
    val mapping = milestones.filter { task -> previous.milestones.none { it.id == task.id } }.associate { task ->
        task.id to uniqueSchedulingId(used, generate).also { used += it }
    }
    if (mapping.isEmpty()) return this
    val nodes = tree.filter { it.kind == DecisionKind.STAGE && it.id in mapping && (it.stageId ?: it.id) == it.id }
        .associate { it.id to mapping.getValue(it.id) }
    fun task(id: String) = mapping[id] ?: id
    fun node(id: String) = nodes[id] ?: id
    fun dependency(id: String) = nodes[id] ?: mapping[id] ?: id
    return copy(milestones = milestones.map { it.copy(id = task(it.id), dependsOn = it.dependsOn.map(::dependency), continuationOf = it.continuationOf?.let(::task)) },
        tree = tree.map { it.copy(id = node(it.id), children = it.children.map(::node), dependsOn = it.dependsOn.map(::dependency),
            stageId = it.stageId?.let(::task), selectedOptionId = it.selectedOptionId?.let(::node)) },
        dialogue = dialogue.map { it.copy(questionStageIds = it.questionStageIds.map(::task)) })
}
