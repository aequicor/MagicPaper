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
    val runId: String = "",
) {
    private val acceptance: AcceptanceRecord? get() = attempt?.acceptanceRecord.takeIf { issue.kind == IssueKind.VERIFICATION }
    val needsWorker: Boolean get() = issue.kind == IssueKind.VERIFICATION && (acceptance == null || acceptance?.canRetryWithWorker == true)
    val canSkipVerification: Boolean get() = issue.kind == IssueKind.VERIFICATION && acceptance?.canSkipByUser == true &&
        attempt?.phase == AttemptPhase.VERIFYING && attempt.pendingTool.isBlank() && !attempt.pendingToolExternal && attempt.mergePhase == null
    val messageId: String get() = "$planId-blocked-$runId-${stage?.id ?: "plan"}-${attempt?.id.orEmpty()}-${attempt?.sessionGeneration ?: 0}-${attempt?.turnIndex ?: 0}-${attempt?.repairRetries ?: 0}-${issue.hashCode()}"
    val title: String get() = when {
        acceptance?.status == AcceptanceStatus.PARTIAL && stage != null -> "Этап «${stage.title}»: не хватает подтверждений"
        acceptance?.status == AcceptanceStatus.PARTIAL -> "Итоговая проверка: не хватает подтверждений"
        acceptance?.status == AcceptanceStatus.STALE -> "Результат нужно проверить заново"
        issue.kind == IssueKind.VERIFICATION && stage != null -> "Этап «${stage.title}» не прошёл проверку"
        issue.kind == IssueKind.VERIFICATION -> "Итоговая проверка не пройдена"
        stage != null -> "Этап «${stage.title}» остановлен"
        else -> "Выполнение плана остановлено"
    }
    val text: String get() = "$title.\n\n${acceptance?.userSummary() ?: issue.message}"
}

internal fun List<PlanningBlocker>.recoveryActionLabel(): String = when {
    isNotEmpty() && all { it.canSkipVerification } -> "Проверить автоматически"
    any { it.stage == null && it.issue.kind == IssueKind.VERIFICATION } -> "Доработать план"
    any { it.needsWorker } -> "Исправить и проверить"
    any { it.issue.kind == IssueKind.VERIFICATION } -> "Повторить проверку"
    else -> "Повторить запуск"
}

/** Older versions skipped only missing findings, then could block the same user decision on STALE.
 * Recover that explicit whole-stage decision, without restarting stopped plans or waiving new criteria. */
internal fun Plan.restoreSkippedVerification(): Plan {
    if (intent != ExecutionIntent.RUN || acceptanceWaivers.isEmpty() || journal.none { it.operation == "user-skip-verification" }) return this
    val blockers = blockingIssues(emptyList())
    if (blockers.isEmpty() || blockers.any { !it.canSkipVerification }) return this
    val restored = mutableListOf<AcceptanceWaiver>()
    for (blocker in blockers) {
        val record = blocker.attempt!!.acceptanceRecord!!
        val criteria = blocker.stage?.criteria() ?: acceptanceCriteria()
        if (record.runId != runId || record.criteria != criteria) return this
        val decision = acceptanceWaivers.firstOrNull {
            it.runId == runId && it.attemptId == record.attemptId && it.criterion in criteria
        } ?: return this
        restored += criteria.map { decision.copy(criterion = it) }
    }
    val attemptIds = blockers.map { it.attempt!!.id }.toSet()
    fun resume(attempt: StageAttempt) = if (attempt.id in attemptIds) attempt.copy(error = null, verificationSnapshot = null) else attempt
    return copy(acceptanceWaivers = (acceptanceWaivers + restored).distinctBy { it.runId to it.criterion },
        issue = null, phase = ExecutionPhase.RECOVERING, status = PlanStatus.RUNNING,
        milestones = milestones.map { it.copy(attempts = it.attempts.map(::resume)) }, finalAttempt = finalAttempt?.let(::resume))
}

/** Execution errors remain actionable even when the planner has not asked a question. */
internal fun Plan.blockingIssues(history: List<CodingMessage>): List<PlanningBlocker> {
    if (phase == ExecutionPhase.COMPLETE) return emptyList()
    val blockers = selectedMilestones.filterNot { it.completed }.mapNotNull { stage ->
        val attempt = stage.attempts.lastOrNull()
        attempt?.error?.takeIf { it.requiresUser }?.let { PlanningBlocker(id, it, stage, attempt, runId) }
    }.toMutableList()
    finalAttempt?.error?.takeIf { it.requiresUser }?.let { blockers += PlanningBlocker(id, it, attempt = finalAttempt, runId = runId) }
    issue?.takeIf { it.requiresUser && blockers.none { b -> b.issue.kind == it.kind && b.issue.message == it.message } }
        ?.let { blockers += PlanningBlocker(id, it, runId = runId) }
    val answered = history.mapNotNull { it.planning?.replyTo }.toSet()
    val questions = history.filter { it.id !in answered && it.planning?.planId == id && it.planning.questions.isNotEmpty() }
    return blockers.filter { blocker ->
        !blocker.issue.isPlannerAnswerWait || questions.none {
            it.planning?.sourceStageId == null || it.planning.sourceStageId == blocker.stage?.id
        }
    }
}
