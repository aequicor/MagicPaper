package io.aequicor.magicpaper.domain

import kotlinx.serialization.Serializable

/** Only the effective, editable plan belongs in a refinement request, never its persisted checkpoints. */
@Serializable
internal data class PlanningRequestContext(
    val id: String,
    val goal: String,
    val revision: Long,
    val confirmedRevision: Long?,
    val status: PlanStatus,
    val phase: ExecutionPhase,
    val intent: ExecutionIntent,
    val sharedWorkspace: Boolean,
    val parallelism: Int,
    val priorities: PlanningPriorities,
    val tree: List<DecisionNode>,
    val milestones: List<Milestone>,
    val dialogue: List<PlanningMessage>,
) {
    companion object {
        fun from(plan: Plan) = PlanningRequestContext(
            plan.id, plan.goal, plan.revision, plan.confirmedRevision, plan.status, plan.phase, plan.intent,
            plan.sharedWorkspace, plan.parallelism, plan.priorities, plan.tree,
            plan.milestones.map { it.copy(attempts = emptyList(), report = "") },
            plan.dialogue.map { it.copy(activity = emptyList()) },
        )
    }
}

// Leave room for transport role labels and separators below the provider's 1,048,576-character limit.
internal fun requirePlanningRequestSize(messages: List<LlmMessage>) {
    val size = messages.sumOf { it.content.length.toLong() }
    require(size <= 1_000_000) {
        "Контекст планирования слишком большой ($size символов). Обработка остановлена до отправки модели. " +
            "Сократите длинные сообщения или разделите задачу на отдельные планы. Сохранённый план и результаты этапов не изменены."
    }
}

internal fun planningFailureMessage(message: String): String =
    if (message.contains("Input exceeds the maximum length", ignoreCase = true))
        "Контекст запроса превысил допустимый размер. Обработка сообщения остановлена; план и результаты этапов сохранены. " +
            "Повторите обработку. Если ошибка повторится, сократите длинные сообщения или разделите задачу на отдельные планы."
    else message
