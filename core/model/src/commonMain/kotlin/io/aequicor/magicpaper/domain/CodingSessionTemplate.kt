package io.aequicor.magicpaper.domain

/**
 * An ordinary conversation, whose parameters a new session may take over. Planning sessions, plan stages and
 * workers run on their own frozen assignments and are never templates.
 */
val CodingSession.isConversation: Boolean
    get() = role == CodingSessionRole.CHAT && !planningMode && stageId == null

/**
 * The project's conversation whose parameters seed a new session: the one the person last worked with
 * ([lastUsedId]: configured, sent a task to or created), otherwise the most recently created. Neither the open
 * route nor a status change says which session the person set up: navigating to a project clears the open
 * session, and statuses change whenever any background run starts or ends.
 */
fun List<CodingSession>.lastConversation(lastUsedId: String?): CodingSession? {
    val conversations = filter { it.isConversation }
    return conversations.firstOrNull { it.id == lastUsedId } ?: conversations.maxByOrNull { it.createdAt }
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
