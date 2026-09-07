package io.aequicor.magicpaper.domain

import kotlin.math.abs

data class StageTiming(val earlyStart: Double, val earlyFinish: Double, val lateStart: Double, val lateFinish: Double) {
    val slackPoints: Double get() = (lateStart - earlyStart).coerceAtLeast(0.0)
    val critical: Boolean get() = abs(lateStart - earlyStart) < 0.000001
}

data class CriticalPathSchedule(
    val order: List<String>,
    val dependencies: Map<String, Set<String>>,
    val timings: Map<String, StageTiming>,
    val complexityPoints: Double?,
    val errors: List<String>,
)

/** Longest dependency chain weighted by relative complexity; these values are not dates. */
fun criticalPathSchedule(plan: Plan): CriticalPathSchedule {
    val graph = DecisionCompiler.compile(plan)
    if (!graph.valid) return CriticalPathSchedule(emptyList(), graph.dependencies, emptyMap(), null, graph.errors)
    val remaining = graph.stageIds.toMutableSet()
    val order = mutableListOf<String>()
    while (remaining.isNotEmpty()) {
        val ready = remaining.filter { graph.dependencies[it].orEmpty().all { dep -> dep in order } }
        if (ready.isEmpty()) break // Compiler has already rejected cycles and dangling dependencies.
        order += ready
        remaining.removeAll(ready.toSet())
    }
    val durations = plan.milestones.associate { it.id to (it.complexityPoints ?: it.assessment.complexity.takeIf { value -> value > 0 }?.toDouble()) }
    if (order.isEmpty() || order.any { durations[it]?.let { d -> d.isFinite() && d > 0 } != true })
        return CriticalPathSchedule(order, graph.dependencies, emptyMap(), null, emptyList())
    val finishes = mutableMapOf<String, Double>()
    order.forEach { id -> finishes[id] = (graph.dependencies[id].orEmpty().maxOfOrNull { finishes.getValue(it) } ?: 0.0) + durations.getValue(id)!! }
    val duration = finishes.values.maxOrNull()!!
    val successors = order.associateWith { mutableListOf<String>() }
    graph.dependencies.forEach { (id, deps) -> deps.forEach { successors.getValue(it).add(id) } }
    val timings = mutableMapOf<String, StageTiming>()
    order.asReversed().forEach { id ->
        val latestFinish = successors.getValue(id).minOfOrNull { timings.getValue(it).lateStart } ?: duration
        val d = durations.getValue(id)!!
        timings[id] = StageTiming(finishes.getValue(id) - d, finishes.getValue(id), latestFinish - d, latestFinish)
    }
    return CriticalPathSchedule(order, graph.dependencies, timings, duration, emptyList())
}
