package io.aequicor.magicpaper.domain

internal val PlanningIssue.isPlannerAnswerWait: Boolean
    get() = kind == IssueKind.CONFIGURATION && message == "Ожидается ответ планировщику"

/** Only a settled rejection can be superseded by a revised plan. */
internal val Plan.canExtendAfterFinalVerification: Boolean
    get() = phase == ExecutionPhase.WAITING && issue?.kind == IssueKind.VERIFICATION &&
        finalAttempt?.phase == AttemptPhase.VERIFYING && finalAttempt.error?.kind == IssueKind.VERIFICATION &&
        finalAttempt.pendingTool.isBlank() && !finalAttempt.pendingToolExternal && finalAttempt.mergePhase == null &&
        workspace?.applied != true

internal data class PlanningBlocker(
    val planId: String,
    val issue: PlanningIssue,
    val stage: Milestone? = null,
    val attempt: StageAttempt? = null,
) {
    val messageId: String get() = "$planId-blocked-${stage?.id ?: "plan"}-${attempt?.id.orEmpty()}-${attempt?.repairRetries ?: 0}-${issue.hashCode()}"
    val title: String get() = when {
        issue.kind == IssueKind.VERIFICATION && stage != null -> "Этап «${stage.title}» не прошёл проверку"
        issue.kind == IssueKind.VERIFICATION -> "Итоговая проверка не пройдена"
        stage != null -> "Этап «${stage.title}» остановлен"
        else -> "Выполнение плана остановлено"
    }
    val text: String get() = "$title.\n\n${issue.message}" +
        if (issue.kind == IssueKind.VERIFICATION && stage != null && (attempt?.repairRetries ?: 0) >= 2)
            "\n\nАвтоматические попытки исправления исчерпаны (${attempt?.repairRetries})." else ""
}

/** Execution errors remain actionable even when the planner has not asked a question. */
internal fun Plan.blockingIssues(history: List<CodingMessage>): List<PlanningBlocker> {
    if (phase == ExecutionPhase.COMPLETE) return emptyList()
    val blockers = selectedMilestones.filterNot { it.completed }.mapNotNull { stage ->
        val attempt = stage.attempts.lastOrNull()
        attempt?.error?.takeIf { it.requiresUser }?.let { PlanningBlocker(id, it, stage, attempt) }
    }.toMutableList()
    finalAttempt?.error?.takeIf { it.requiresUser }?.let { blockers += PlanningBlocker(id, it, attempt = finalAttempt) }
    issue?.takeIf { it.requiresUser && blockers.none { b -> b.issue.kind == it.kind && b.issue.message == it.message } }
        ?.let { blockers += PlanningBlocker(id, it) }
    val answered = history.mapNotNull { it.planning?.replyTo }.toSet()
    val questions = history.filter { it.id !in answered && it.planning?.planId == id && it.planning.questions.isNotEmpty() }
    return blockers.filter { blocker ->
        !blocker.issue.isPlannerAnswerWait || questions.none {
            it.planning?.sourceStageId == null || it.planning.sourceStageId == blocker.stage?.id
        }
    }
}
