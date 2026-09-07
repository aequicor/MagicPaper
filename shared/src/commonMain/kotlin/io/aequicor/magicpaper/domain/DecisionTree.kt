package io.aequicor.magicpaper.domain

import kotlinx.serialization.Serializable

@Serializable enum class DecisionKind { GOAL, GROUP, CHOICE, OPTION, STAGE }
@Serializable enum class ExecutionIntent { RUN, PAUSE, STOP }
@Serializable enum class ExecutionPhase { IDLE, RECOVERING, EXECUTING, VERIFYING, INTEGRATING, APPLYING, WAITING, COMPLETE }
@Serializable enum class AttemptPhase { PREPARED, EXECUTING, VERIFYING, INTEGRATING, COMPLETE, FAILED }
@Serializable enum class IssueKind { TRANSIENT, CONFIGURATION, INVALID_RESPONSE, VERIFICATION, CONFLICT, UNCERTAIN, STORAGE, CANCELLED }

@Serializable data class StageAssignment(
    val profileId: String,
    val modelId: String,
    val effort: EffortSelection = EffortSelection.Default,
    val effectiveEffort: EffortSelection = EffortSelection.Default,
    val explanation: String = "",
    val manual: Boolean = false,
    val displayName: String = "",
    val options: AdvancedLlmOptions? = null,
)

/** 0 means unknown; higher values are better for quality/speed/economy/safety. */
@Serializable data class StageAssessment(
    val quality: Int = 0, val speed: Int = 0, val economy: Int = 0, val safety: Int = 0,
    val complexity: Int = 0, val explanation: String = "",
)
@Serializable data class PlanningPriorities(
    val quality: Int = 1, val speed: Int = 1, val economy: Int = 1, val safety: Int = 1,
) {
    fun score(a: StageAssessment): Double {
        val pairs = listOf(quality to a.quality, speed to a.speed, economy to a.economy, safety to a.safety)
        // Unknown characteristics contribute no evidence, never a fabricated average.
        val weight = pairs.sumOf { it.first.coerceAtLeast(0) }.coerceAtLeast(1)
        return pairs.sumOf { it.first.coerceAtLeast(0) * it.second.coerceIn(0, 3) }.toDouble() / weight
    }
}
@Serializable data class DecisionNode(
    val id: String, val title: String, val kind: DecisionKind,
    val children: List<String> = emptyList(), val selectedOptionId: String? = null,
    val dependsOn: List<String> = emptyList(), val stageId: String? = null,
    val assessment: StageAssessment = StageAssessment(), val explanation: String = "",
    val manualSelection: Boolean = false,
)
@Serializable data class PlanningMessage(val id: String, val role: String, val text: String, val activity: List<CodingStep> = emptyList(), val questions: List<PlanningQuestion> = emptyList())
@Serializable data class PlanningIssue(
    val kind: IssueKind, val message: String, val retryAt: Long = 0,
    val retries: Int = 0, val requiresUser: Boolean = false,
)
@Serializable data class StageAttempt(
    val id: String, val sessionId: String, val assignment: StageAssignment,
    val phase: AttemptPhase = AttemptPhase.PREPARED, val engineSessionId: String = "",
    val path: String = "", val baseCommit: String = "", val resultCommit: String = "",
    val turnIndex: Int = 0, val prompt: String = "", val report: String = "", val activity: String = "", val steps: List<CodingStep> = emptyList(), val error: PlanningIssue? = null,
    val pendingTool: String = "", val pendingToolExternal: Boolean = false,
    val transportRetries: Int = 0, val repairRetries: Int = 0, val mergeRetries: Int = 0,
    val mergeAssignment: StageAssignment? = null, val mergePhase: AttemptPhase? = null,
    val mergeReport: String = "", val mergeEngineSessionId: String = "", val mergePath: String = "",
    val startedAt: Long = 0, val updatedAt: Long = 0,
)
@Serializable data class PlanWorkspace(
    val root: String, val integrationPath: String, val baseCommit: String = "",
    val git: Boolean = false, val applied: Boolean = false,
    val appliedCommit: String = "", val verificationReport: String = "",
)
@Serializable data class PlanJournalEntry(
    val id: String, val at: Long, val stageId: String = "", val attemptId: String = "",
    val operation: String, val detail: String = "",
)
data class CompiledDecisionGraph(
    val stageIds: List<String>, val dependencies: Map<String, Set<String>>, val errors: List<String>,
) { val valid: Boolean get() = errors.isEmpty() }

object DecisionCompiler {
    fun compile(plan: Plan): CompiledDecisionGraph {
        val errors = mutableListOf<String>()
        val stages = plan.milestones.associateBy { it.id }
        if (stages.size != plan.milestones.size) errors += "Повторяющиеся идентификаторы этапов"
        if (plan.tree.isEmpty()) return validate(stages.keys.toList(), stages.mapValues { it.value.dependsOn.toSet() }, errors)
        val nodes = plan.tree.associateBy { it.id }
        if (nodes.size != plan.tree.size) errors += "Повторяющиеся идентификаторы узлов"
        val roots = plan.tree.filter { it.kind == DecisionKind.GOAL }
        if (roots.size != 1) errors += "Дерево должно иметь одну цель"
        val selected = linkedSetOf<String>()
        val active = linkedSetOf<String>()
        val descendants = mutableMapOf<String, Set<String>>()
        val visiting = mutableSetOf<String>()
        fun visit(id: String): Set<String> {
            descendants[id]?.let { return it }
            val node = nodes[id] ?: run { errors += "Нет узла $id"; return emptySet() }
            if (!visiting.add(id)) { errors += "Цикл дерева: ${node.title}"; return emptySet() }
            active += id
            val result = when (node.kind) {
                DecisionKind.STAGE -> {
                    val stage = node.stageId ?: node.id
                    if (stage !in stages) errors += "Нет этапа ${node.title}"
                    selected += stage
                    setOf(stage)
                }
                DecisionKind.CHOICE -> {
                    if (node.children.isEmpty() || node.children.any { nodes[it]?.kind != DecisionKind.OPTION })
                        errors += "У выбора ${node.title} должны быть варианты"
                    if (node.selectedOptionId !in node.children) { errors += "Выберите вариант: ${node.title}"; emptySet() }
                    else visit(node.selectedOptionId!!)
                }
                else -> node.children.flatMap { visit(it) }.toSet()
            }
            visiting -= id
            descendants[id] = result
            return result
        }
        // Validate even inactive branches for structural cycles/dangling references.
        val structuralDone = mutableSetOf<String>()
        val structuralPath = mutableSetOf<String>()
        fun structure(id: String) {
            if (id in structuralDone) return
            val n = nodes[id] ?: run { errors += "Нет узла $id"; return }
            if (!structuralPath.add(id)) { errors += "Цикл дерева: ${n.title}"; return }
            n.children.forEach { structure(it) }
            structuralPath -= id; structuralDone += id
        }
        plan.tree.forEach { structure(it.id) }
        roots.forEach { visit(it.id) }
        val deps = selected.associateWith { stages[it]?.dependsOn.orEmpty().toMutableSet() }.toMutableMap()
        active.forEach { id ->
            val node = nodes.getValue(id)
            node.dependsOn.forEach { dep ->
                val prerequisite = descendants[dep]
                if (prerequisite == null) errors += "Зависимость ${node.title} ведёт в неактивный или отсутствующий узел $dep"
                else descendants[id].orEmpty().forEach { stage -> deps.getValue(stage).addAll(prerequisite) }
            }
        }
        return validate(selected.toList(), deps, errors)
    }

    private fun validate(ids: List<String>, deps: Map<String, Set<String>>, errors: MutableList<String>): CompiledDecisionGraph {
        deps.forEach { (id, values) -> if (values.any { it !in ids || it == id }) errors += "Некорректная зависимость этапа $id" }
        val remaining = ids.toMutableSet()
        val sorted = mutableListOf<String>()
        while (remaining.isNotEmpty()) {
            val ready = remaining.filter { id -> deps[id].orEmpty().all { it in sorted } }
            if (ready.isEmpty()) { errors += "Цикл или недоступная зависимость графа"; break }
            sorted += ready; remaining.removeAll(ready.toSet())
        }
        return CompiledDecisionGraph(ids, deps, errors.distinct())
    }

    fun migrate(plan: Plan): Plan {
        if (plan.tree.isNotEmpty()) return plan
        val option = "${plan.id}-option"
        val choice = "${plan.id}-choice"
        val legacyRunning = plan.status == PlanStatus.RUNNING && plan.runId.isBlank() && plan.intent == ExecutionIntent.STOP
        return plan.copy(
            intent = if (legacyRunning) ExecutionIntent.RUN else plan.intent,
            issue = if (legacyRunning && plan.milestones.any { it.status == MilestoneStatus.ACTIVE && it.attempts.isEmpty() })
                PlanningIssue(IssueKind.UNCERTAIN, "План старой версии был прерван без журнала попыток. Проверьте результат перед продолжением.", requiresUser = true) else plan.issue,
            tree = listOf(
            DecisionNode("${plan.id}-root", plan.goal, DecisionKind.GOAL, listOf(choice)),
            DecisionNode(choice, "Способ достижения", DecisionKind.CHOICE, listOf(option), option),
            DecisionNode(option, "Исходный план", DecisionKind.OPTION, plan.milestones.map { it.id }),
        ) + plan.milestones.map { DecisionNode(it.id, it.title, DecisionKind.STAGE, stageId = it.id) })
    }

    fun validateEdit(old: Plan, updated: Plan) {
        require(old.id == updated.id && old.projectId == updated.projectId) { "Нельзя изменить принадлежность плана" }
        val graph = compile(updated)
        require(graph.valid) { graph.errors.joinToString("\n") }
        val oldGraph = compile(old)
        if (old.milestones.any { it.attempts.isNotEmpty() || it.status != MilestoneStatus.PENDING })
            require(old.goal == updated.goal) { "Цель уже запущенного плана закреплена" }
        old.milestones.filter { it.attempts.isNotEmpty() || it.status != MilestoneStatus.PENDING }.forEach { m ->
            require(m.id in graph.stageIds && updated.milestones.firstOrNull { it.id == m.id } == m &&
                graph.dependencies[m.id] == oldGraph.dependencies[m.id]) { "Этап «${m.title}» уже начат" }
        }
    }

    fun removeNode(plan: Plan, nodeId: String): Plan {
        require(plan.tree.none { it.id == nodeId && it.kind == DecisionKind.GOAL }) { "Нельзя удалить цель" }
        var nodes = plan.tree.filterNot { it.id == nodeId }.map { n ->
            val children = n.children - nodeId
            n.copy(children = children, selectedOptionId = if (n.selectedOptionId == nodeId) children.firstOrNull() else n.selectedOptionId)
        }
        val emptyChoices = nodes.filter { it.kind == DecisionKind.CHOICE && it.children.isEmpty() }.map { it.id }.toSet()
        nodes = nodes.filterNot { it.id in emptyChoices }.map { it.copy(children = it.children.filterNot { id -> id in emptyChoices }) }
        val reachable = mutableSetOf<String>()
        val byId = nodes.associateBy { it.id }
        fun visit(id: String) { if (reachable.add(id)) byId[id]?.children?.forEach(::visit) }
        nodes.filter { it.kind == DecisionKind.GOAL }.forEach { visit(it.id) }
        nodes = nodes.filter { it.id in reachable }
        val stages = nodes.filter { it.kind == DecisionKind.STAGE }.map { it.stageId ?: it.id }.toSet()
        return plan.copy(tree = nodes, milestones = plan.milestones.filter { it.id in stages })
    }
}
