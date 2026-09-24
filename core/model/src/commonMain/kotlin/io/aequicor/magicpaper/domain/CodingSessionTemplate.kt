package io.aequicor.magicpaper.domain

/**
 * The project's conversation whose parameters seed a new session: the one the person has open, otherwise
 * the most recently active. Planning sessions, plan stages and workers run on their own frozen
 * assignments and are never templates.
 */
fun List<CodingSession>.lastConversation(openSessionId: String?): CodingSession? {
    val conversations = filter { it.role == CodingSessionRole.CHAT && !it.planningMode && it.stageId == null }
    return conversations.firstOrNull { it.id == openSessionId }
        ?: conversations.maxByOrNull { maxOf(it.statusChangedAt, it.createdAt) }
}

/**
 * Carries the last conversation's model, effort, mode, worktree, feature flags and media tools into a new
 * session. The native model follows only within the same engine; a profile model that is no longer
 * [usable] leaves the new session on its own default.
 */
fun CodingSession.withParametersOf(template: CodingSession?, usable: (ModelSelection) -> Boolean): CodingSession {
    if (template == null) return this
    val selection = template.modelSelection?.takeIf(usable)
    return copy(
        codingModel = template.codingModel?.takeIf { it.engine == engine } ?: codingModel,
        modelSelection = selection ?: modelSelection,
        llmProfileId = if (selection != null) template.llmProfileId else llmProfileId,
        researchMode = template.researchMode,
        worktreeEnabled = template.worktreeEnabled,
        featureFlags = template.featureFlags,
        mediaTools = template.mediaTools,
    )
}
