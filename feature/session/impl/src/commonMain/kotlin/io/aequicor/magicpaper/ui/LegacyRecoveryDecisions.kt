package io.aequicor.magicpaper.ui

import io.aequicor.magicpaper.domain.*

/** Legacy IDs omitted the run/generation/turn. Alias only an unchanged, confirmed user stop. */
internal fun legacyRecoveryDecisionAliases(ui: CodingUi, plans: List<Plan>, dismissed: Set<String>): Map<String, String> = buildMap {
    if (dismissed.none { it.startsWith("blocker:") }) return@buildMap
    for (plan in plans) {
        if (plan.intent != ExecutionIntent.STOP || plan.stopping || plan.status != PlanStatus.STOPPED ||
            plan.updatedAt <= 0 || plan.runId.isBlank() || plan.runHistory.isNotEmpty()) continue
        val blockers = plan.blockingIssues(emptyList())
        if (blockers.isEmpty()) continue
        // Preserve this format for migration matching only; all current requests keep the new identity.
        val legacyId = "blocker:" + blockers.map { blocker ->
            "${blocker.planId}-blocked-${blocker.stage?.id ?: "plan"}-${blocker.attempt?.id.orEmpty()}-${blocker.attempt?.repairRetries ?: 0}-${blocker.issue.hashCode()}"
        }.sorted().joinToString(":")
        if (legacyId !in dismissed) continue
        val parent = ui.sessions.singleOrNull { it.session.id == plan.parentSessionId && it.session.projectId == plan.projectId } ?: continue
        if (parent.running || parent.session.archived || plan.blockingIssues(parent.messages) != blockers) continue
        val decision = parent.messages.singleOrNull { it.id == "$legacyId-left" && it.role == CodingRole.USER } ?: continue
        if (decision.createdAt <= plan.updatedAt || plan.journal.none { it.operation == "stop-confirmed" && it.at > 0 }) continue
        if (parent.messages.any { it.id != decision.id && (it.createdAt <= 0 || it.createdAt >= decision.createdAt) }) continue
        val attempts = plan.milestones.flatMap { it.attempts } + listOfNotNull(plan.finalAttempt) + plan.finalAttemptHistory
        if (attempts.any { it.startedAt <= 0 || it.updatedAt <= 0 }) continue
        val times = listOf(plan.createdAt, plan.updatedAt) + plan.journal.map { it.at } +
            plan.milestones.map { it.updatedAt } + attempts.flatMap { attempt ->
                listOf(attempt.startedAt, attempt.updatedAt) + attempt.chatTurns.flatMap { listOf(it.startedAt, it.completedAt) }
            }
        if (times.any { it >= decision.createdAt }) continue
        // Older history has no generation binding. Do not infer a dismissal for a repeated turn.
        if (blockers.mapNotNull { it.attempt }.any { attempt ->
                val worker = ui.sessions.singleOrNull { it.session.id == attempt.sessionId && it.session.projectId == plan.projectId }
                attempt.sessionGeneration !in 0L..1L || attempt.turnIndex != 0 || attempt.retryAuthorization != null ||
                    worker == null || worker.running || worker.session.runtimeGeneration != attempt.sessionGeneration ||
                    worker.messages.any { it.createdAt <= 0 || it.createdAt >= decision.createdAt }
            }) continue
        put("blocker:${blockers.map { it.messageId }.sorted().joinToString(":")}", legacyId)
    }
}

internal fun Set<String>.withLegacyRecoveryDecisions(aliases: Map<String, String>): Set<String> =
    this + aliases.filterValues { it in this }.keys

internal fun MutableSet<String>.reopenInteractionDecisions(ids: Collection<String>, aliases: Map<String, String>) {
    removeAll(ids.toSet() + ids.mapNotNull { aliases[it] })
}
