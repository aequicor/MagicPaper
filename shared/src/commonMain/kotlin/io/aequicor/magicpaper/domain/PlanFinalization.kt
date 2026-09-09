package io.aequicor.magicpaper.domain

import io.aequicor.magicpaper.util.Id

/** A proposal has a visible endpoint even when the model omits its final commit task. */
internal fun Plan.withFinalization(generateId: () -> String = Id::uuid): Plan {
    if (wizardStep !in setOf(PlanningStep.REVIEW, PlanningStep.STATUS) || tree.isEmpty()) return this
    val graph = DecisionCompiler.compile(this)
    require(graph.valid) { graph.errors.joinToString("\n") }
    if (graph.stageIds.isEmpty()) return this
    val selected = milestones.filter { it.id in graph.stageIds }
    val known = mutableMapOf<String, Set<String>>()
    fun prerequisites(id: String): Set<String> = known.getOrPut(id) {
        graph.dependencies[id].orEmpty().flatMap { setOf(it) + prerequisites(it) }.toSet()
    }
    if (selected.any { it.isFinalization && prerequisites(it.id).containsAll(graph.stageIds - it.id) }) return this

    val root = tree.single { it.kind == DecisionKind.GOAL }
    // Reuse an unstarted endpoint at the root. Started tasks keep their original dependencies.
    val reusableNode = tree.firstOrNull { node -> node.id in root.children && node.kind == DecisionKind.STAGE &&
        selected.any { task -> task.id == (node.stageId ?: node.id) && task.isFinalization && task.status == MilestoneStatus.PENDING && task.attempts.isEmpty() &&
            graph.stageIds.none { other -> other != task.id && task.id in prerequisites(other) } } }
    val reusable = reusableNode?.let { node -> selected.single { it.id == (node.stageId ?: node.id) } }
    val used = (tree.map { it.id } + milestones.map { it.id }).toMutableSet()
    val stageId = reusable?.id ?: uniqueSchedulingId(used, generateId)
    val source = selected.lastOrNull { !it.isFinalization } ?: selected.last()
    val finalization = reusable ?: Milestone(stageId, "Коммит и итог",
        description = """
            Заверши работу после всех выбранных этапов. Просмотри diff и результаты проверок, включая явные пропуски пользователя.
            В Git-репозитории создай один или несколько осмысленных коммитов только с изменениями этой задачи. Сохрани чужие staged, unstaged и untracked изменения; при смешанных правках выдели только относящиеся к задаче части.
            До коммита проверь историю и рабочее дерево: уже созданный коммит повторно не создавай. Не создавай пустой коммит.
            Если пользователь явно запретил коммиты, в папке нет Git или результат уже зафиксирован, заверши итогом с конкретной причиной и подтверждением. Не инициализируй репозиторий автоматически.
            Не выполняй push, публикацию, amend или переписывание истории без отдельного поручения.
            В итоговом ответе укажи, что сделано, какие проверки прошли или пропущены, путь и ветку репозитория, хеши и сообщения коммитов, а также оставшиеся изменения и ограничения. В отдельной рабочей копии явно укажи, где находятся коммиты.
        """.trimIndent(),
        acceptance = "Результат зафиксирован относящимися к задаче коммитами с указанными хешами и сообщениями либо объяснено и подтверждено, почему новый коммит не требуется. Итог содержит изменения, проверки и оставшиеся ограничения; чужая работа сохранена.",
        agentProfileId = source.agentProfileId, agentModelId = source.agentModelId, assignment = source.assignment,
        complexityPoints = 1.0, isFinalization = true)
    val nodeId = reusableNode?.id ?: stageId
    val node = DecisionNode(nodeId, finalization.title, DecisionKind.STAGE, stageId = stageId,
        dependsOn = root.children.filterNot { it == nodeId })
    return copy(milestones = if (reusable == null) milestones + finalization else milestones,
        tree = tree.filterNot { it.id == nodeId }.map { if (it.id == root.id) it.copy(children = (it.children + nodeId).distinct()) else it } + node)
}
