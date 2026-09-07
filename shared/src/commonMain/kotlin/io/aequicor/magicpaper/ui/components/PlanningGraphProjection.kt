package io.aequicor.magicpaper.ui.components

import io.aequicor.magicpaper.domain.*

/** Service operations are visible nodes; they never become inputs to the execution compiler. */
fun planningGraphProjection(plan: Plan): Plan {
    val operations = buildList {
        fun addOperation(id: String, title: String, a: StageAttempt, deps: List<String>, conflict: Boolean) {
            val phase = if (conflict) a.mergePhase ?: AttemptPhase.PREPARED else a.phase
            val assignment = if (conflict) a.mergeAssignment ?: a.assignment else a.assignment
            val report = if (conflict) a.mergeReport else a.report
            add(Milestone(id, title, status = when {
                phase == AttemptPhase.COMPLETE -> MilestoneStatus.DONE
                a.error?.requiresUser == true || phase == AttemptPhase.FAILED -> MilestoneStatus.FAILED
                else -> MilestoneStatus.ACTIVE
            }, assignment = assignment, dependsOn = deps, report = report, attempts = listOf(a.copy(
                phase = phase, assignment = assignment, report = report, path = if (conflict) a.mergePath else a.path,
            ))))
        }
        plan.milestones.forEach { m -> m.attempts.lastOrNull()?.takeIf { it.mergePhase != null }?.let {
            addOperation("service-merge-${it.id}", "Объединение: ${m.title}", it, listOf(m.id), true)
        } }
        plan.finalAttempt?.let { a ->
            addOperation("service-final-${a.id}", "Общая проверка", a, plan.selectedMilestones.map { it.id }, false)
            if (a.mergePhase != null) addOperation("service-delivery-${a.id}", "Конфликт переноса", a, listOf("service-final-${a.id}"), true)
        }
    }
    if (operations.isEmpty()) return plan
    return plan.copy(milestones = plan.milestones + operations, tree = plan.tree.map {
        if (it.kind == DecisionKind.GOAL) it.copy(children = it.children + operations.map { m -> m.id }) else it
    } + operations.map { DecisionNode(it.id, it.title, DecisionKind.STAGE, stageId = it.id) })
}
