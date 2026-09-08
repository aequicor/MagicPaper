package io.aequicor.magicpaper.domain

fun selectPlanningOption(plan: Plan, choiceId: String, optionId: String): Plan {
    require(plan.finalAttempt == null || plan.canExtendAfterFinalVerification) { "Выбор закреплён общей проверкой плана" }
    val choice = plan.tree.firstOrNull { it.id == choiceId && it.kind == DecisionKind.CHOICE }
    require(choice != null && optionId in choice.children && plan.tree.any { it.id == optionId && it.kind == DecisionKind.OPTION }) { "Нет такого варианта решения" }
    val updated = plan.copy(tree = plan.tree.map {
        if (it.id == choiceId) it.copy(selectedOptionId = optionId, manualSelection = true) else it
    })
    DecisionCompiler.validateEdit(plan, updated)
    return updated
}
