package io.aequicor.magicpaper.ui.components

import io.aequicor.magicpaper.domain.*

enum class PlanningEdgeKind { STRUCTURE, ALTERNATIVE, DEPENDENCY }
data class PlanningGraphEdge(val from: String, val to: String, val kind: PlanningEdgeKind)

/** Keep every alternative, plus the effective dependencies of the selected route. */
fun planningGraphEdges(plan: Plan): List<PlanningGraphEdge> {
    val nodes = plan.tree.associateBy { it.id }
    val stageNodes = plan.tree.filter { it.kind == DecisionKind.STAGE }.groupBy { it.stageId ?: it.id }
    fun stages(id: String, visiting: Set<String> = emptySet()): Set<String> {
        val node = nodes[id] ?: return emptySet()
        if (id in visiting) return emptySet()
        return if (node.kind == DecisionKind.STAGE) setOf(id) else node.children.flatMap { stages(it, visiting + id) }.toSet()
    }
    val dependencies = buildList {
        plan.tree.forEach { node ->
            node.dependsOn.forEach { dep ->
                val from = stages(dep).ifEmpty { setOf(dep) }
                val to = stages(node.id).ifEmpty { setOf(node.id) }
                from.forEach { predecessor -> to.forEach { successor -> add(PlanningGraphEdge(predecessor, successor, PlanningEdgeKind.DEPENDENCY)) } }
            }
            if (node.kind == DecisionKind.STAGE) plan.milestones.firstOrNull { it.id == (node.stageId ?: node.id) }?.dependsOn.orEmpty().forEach { dep ->
                stageNodes[dep].orEmpty().forEach { add(PlanningGraphEdge(it.id, node.id, PlanningEdgeKind.DEPENDENCY)) }
            }
        }
    }.distinct()
    // A dependency chain already connects its downstream tasks to their branch.
    // Avoid drawing composition arrows across those tasks as well.
    val downstream = dependencies.map { it.to }.toSet()
    return buildList {
        plan.tree.forEach { node -> node.children.forEach { child ->
            if (nodes[child]?.kind != DecisionKind.STAGE || child !in downstream)
                add(PlanningGraphEdge(node.id, child, if (node.kind == DecisionKind.CHOICE) PlanningEdgeKind.ALTERNATIVE else PlanningEdgeKind.STRUCTURE))
        } }
        addAll(dependencies)
    }.distinct()
}

data class PlanningNetworkLayout(
    val positions: Map<String, GraphPosition>,
    val edges: List<PlanningGraphEdge>,
    val sources: Set<String>,
    val sinks: Set<String>,
    val start: GraphPosition,
    val finish: GraphPosition,
    val cyclic: Boolean,
)

/** Activity-on-node ranks: only prerequisite edges consume a column. */
fun planningNetworkLayout(plan: Plan): PlanningNetworkLayout {
    val stages = plan.tree.filter { it.kind == DecisionKind.STAGE }.distinctBy { it.stageId ?: it.id }
    val canonical = stages.associate { (it.stageId ?: it.id) to it.id }
    val aliases = plan.tree.filter { it.kind == DecisionKind.STAGE }.associate { it.id to canonical.getValue(it.stageId ?: it.id) }
    val ids = stages.map { it.id }.toSet()
    val edges = planningGraphEdges(plan).filter { it.kind == PlanningEdgeKind.DEPENDENCY && it.from in aliases && it.to in aliases }
        .map { it.copy(from = aliases.getValue(it.from), to = aliases.getValue(it.to)) }.distinct()
    val predecessors = edges.groupBy { it.to }
    val ranks = mutableMapOf<String, Int>()
    val remaining = ids.toMutableSet()
    var cyclic = false
    while (remaining.isNotEmpty()) {
        val ready = remaining.filter { id -> predecessors[id].orEmpty().all { it.from in ranks } }
        if (ready.isEmpty()) {
            cyclic = true
            val lastRank = (ranks.values.maxOrNull() ?: 0) + 1
            remaining.forEach { ranks[it] = lastRank }
            break
        }
        ready.forEach { id -> ranks[id] = (predecessors[id].orEmpty().maxOfOrNull { ranks.getValue(it.from) } ?: 0) + 1 }
        remaining.removeAll(ready.toSet())
    }
    val rows = mutableMapOf<Int, Int>()
    val positions = stages.associate { node ->
        val rank = ranks.getValue(node.id)
        val row = rows[rank] ?: 0
        rows[rank] = row + 1
        node.id to GraphPosition(rank * 280f + 16, row * 180f + 16)
    }
    val sources = ids - edges.map { it.to }.toSet()
    val sinks = ids - edges.map { it.from }.toSet()
    fun terminalY(adjacent: Set<String>): Float = adjacent.mapNotNull { positions[it]?.y }.average()
        .takeIf { it.isFinite() }?.toFloat()?.plus(30f) ?: 54f
    return PlanningNetworkLayout(positions, edges, sources, sinks,
        GraphPosition(16f, terminalY(sources)),
        GraphPosition(((ranks.values.maxOrNull() ?: 0) + 1) * 280f + 16, terminalY(sinks)), cyclic)
}

/** Retained for callers of the earlier layout API; decision containers do not occupy work columns. */
fun planningAlternativePositions(plan: Plan, collapsed: Set<String>): Map<String, GraphPosition> {
    val nodes = plan.tree.associateBy { it.id }
    val visible = mutableSetOf<String>()
    fun visit(id: String) {
        if (!visible.add(id) || id in collapsed) return
        nodes[id]?.children?.forEach(::visit)
    }
    plan.tree.filter { it.kind == DecisionKind.GOAL }.forEach { visit(it.id) }
    return planningNetworkLayout(plan.copy(tree = plan.tree.filter { it.id in visible })).positions
}
