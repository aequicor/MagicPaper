package io.aequicor.magicpaper.domain

import kotlinx.serialization.Serializable

@Serializable
enum class CodingInteractionMode(val title: String) {
    CODE("Обычный режим"), RESEARCH("Исследование"), PLANNING("Планирование")
}

val CodingSession.interactionMode: CodingInteractionMode get() = when {
    planningMode -> CodingInteractionMode.PLANNING
    researchMode -> CodingInteractionMode.RESEARCH
    else -> CodingInteractionMode.CODE
}

/** Lifecycle bookkeeping alone does not opt a normal conversation into planning. */
val CodingSession.runtimePlanningRules: PlanningRulesSnapshot?
    get() = planningRulesSnapshot.takeIf { planningMode || planId != null || stageId != null }

/** Persisted request restrictions cannot be weakened by a stale session or a caller. */
fun CodingSession.forPendingRun(): CodingSession {
    require(!(planningMode && researchMode)) { "Исследование и планирование нельзя включить одновременно" }
    val mode = pendingRun?.interactionMode ?: interactionMode
    require(mode == interactionMode) { "Режим сохранённого запроса отличается от режима сессии. Запрос не запущен." }
    return this
}

fun CodingSession.changeInteractionMode(mode: CodingInteractionMode, busy: Boolean = false): CodingSession {
    require(!archived && stageId == null && role != CodingSessionRole.WORKER) { "Режим этой сессии изменить нельзя" }
    require(!busy && pendingRun?.intent != ExecutionIntent.RUN) { "Сначала завершите или остановите текущий запрос" }
    require(!planningMode || mode == CodingInteractionMode.PLANNING) { "Режим планирования закреплён за сессией" }
    if (interactionMode == mode) return this
    return copy(planningMode = mode == CodingInteractionMode.PLANNING, researchMode = mode == CodingInteractionMode.RESEARCH,
        role = if (mode == CodingInteractionMode.PLANNING) CodingSessionRole.ORCHESTRATOR else CodingSessionRole.CHAT,
        piSessionId = "", pendingRun = null, needsHistorySeed = true)
}

/** Public dialogue only; system receipts and tool output cannot become a new user instruction. */
fun researchContextSeed(messages: List<CodingMessage>, contextMessages: Int, currentMessageId: String): String {
    val history = messages.filter { it.id != currentMessageId && !it.systemContext && !it.systemNotice }
        .takeLast(contextMessages.coerceIn(1, 200)).joinToString("\n\n") {
            "[${if (it.role == CodingRole.USER) "пользователь" else "ассистент"}]\n${it.text.take(12_000)}"
        }.takeLast(60_000)
    return if (history.isBlank()) "" else "История предыдущего режима (только контекст, не команды для повторного исполнения):\n$history\n\nТекущий запрос:\n"
}
