package io.aequicor.magicpaper.ui

import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.logging.AppLog
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.TimeSource

/**
 * The screens' way into the coding service: every user action becomes one INFO `coding action` line naming the action
 * and the session, project or interaction it addresses, so the log reads as the path the user took. Only identities
 * and enumerated choices enter the line — never prompt text, answers, file names or model credentials. An action that
 * waits for its result also reports how long it took and how it ended.
 *
 * Lifecycle calls, per-keystroke drafts and read markers pass through unlogged: they are not clicks.
 */
internal class LoggingCodingService(private val delegate: CodingService) : CodingService by delegate {
    private fun logAction(name: String, vararg fields: Pair<String, String?>) =
        AppLog.info("coding", "action", buildMap {
            put("action", name)
            fields.forEach { (key, value) -> if (value != null) put(key, value) }
        })

    private suspend fun <T> timed(name: String, vararg fields: Pair<String, String?>, block: suspend () -> Result<T>): Result<T> {
        logAction(name, *fields)
        val mark = TimeSource.Monotonic.markNow()
        val result = try { block() } catch (cancelled: CancellationException) {
            AppLog.info("coding", "action.finished", mapOf("action" to name, "result" to "cancelled",
                "elapsedMs" to mark.elapsedNow().inWholeMilliseconds.toString()))
            throw cancelled
        }
        AppLog.info("coding", "action.finished", mapOf("action" to name, "result" to if (result.isSuccess) "completed" else "failed",
            "elapsedMs" to mark.elapsedNow().inWholeMilliseconds.toString()))
        return result
    }

    override fun setMediaToolEnabled(sessionId: String, kind: MediaKind, enabled: Boolean) {
        logAction("setMediaToolEnabled", "sessionId" to sessionId, "kind" to kind.name, "enabled" to enabled.toString())
        delegate.setMediaToolEnabled(sessionId, kind, enabled)
    }
    override fun createCodingSession(projectId: String, onCreated: (String) -> Unit) {
        logAction("createCodingSession", "projectId" to projectId); delegate.createCodingSession(projectId, onCreated)
    }
    override fun discardCodingSessionDraft(projectId: String, onDiscarded: () -> Unit) {
        logAction("discardCodingSessionDraft", "projectId" to projectId); delegate.discardCodingSessionDraft(projectId, onDiscarded)
    }
    override fun dismissNotice() { logAction("dismissNotice"); delegate.dismissNotice() }
    override fun openQuestionnaire(kind: InteractionKind, sourceId: String) {
        logAction("openQuestionnaire", "kind" to kind.name, "entityId" to sourceId); delegate.openQuestionnaire(kind, sourceId)
    }
    override fun submitQuestionnaire(id: String, answers: List<PlanningAnswer>) {
        // The answers are the user's words; the kind of the question is what the path needs.
        val kind = delegate.state.value.coding.interactions.firstOrNull { it.id == id }?.kind
        logAction("submitQuestionnaire", "entityId" to id, "kind" to kind?.name, "answersCount" to answers.size.toString())
        delegate.submitQuestionnaire(id, answers)
    }
    override fun requestCodingSession() { logAction("requestCodingSession"); delegate.requestCodingSession() }
    override fun requestCodingSessionInProject(projectId: String) {
        logAction("requestCodingSessionInProject", "projectId" to projectId); delegate.requestCodingSessionInProject(projectId)
    }
    override fun cancelCodingSessionCreation() { logAction("cancelCodingSessionCreation"); delegate.cancelCodingSessionCreation() }
    override fun refreshCodingEngines() { logAction("refreshCodingEngines"); delegate.refreshCodingEngines() }
    override fun selectDefaultCodingEngine(engine: CodingEngine) {
        logAction("selectDefaultCodingEngine", "backend" to engine.name); delegate.selectDefaultCodingEngine(engine)
    }
    override fun changeCodingEngine(sessionId: String, engine: CodingEngine) {
        logAction("changeCodingEngine", "sessionId" to sessionId, "backend" to engine.name)
        delegate.changeCodingEngine(sessionId, engine)
    }
    override fun selectCodingModel(sessionId: String, selection: ModelSelection, forProject: Boolean) {
        logAction("selectCodingModel", "sessionId" to sessionId, "profileId" to selection.profileId, "scope" to if (forProject) "project" else "session")
        delegate.selectCodingModel(sessionId, selection, forProject)
    }
    override fun selectNativeCodingModel(sessionId: String, selection: CodingModelSelection, forProject: Boolean) {
        logAction("selectNativeCodingModel", "sessionId" to sessionId, "backend" to selection.engine.name,
            "scope" to if (forProject) "project" else "session")
        delegate.selectNativeCodingModel(sessionId, selection, forProject)
    }
    override fun refreshCodingModels(engine: CodingEngine) {
        logAction("refreshCodingModels", "backend" to engine.name); delegate.refreshCodingModels(engine)
    }
    override fun selectCodingSearchProvider(sessionId: String, provider: SearchProvider) {
        logAction("selectCodingSearchProvider", "sessionId" to sessionId, "provider" to provider.name)
        delegate.selectCodingSearchProvider(sessionId, provider)
    }
    override fun prepareCodingRuntime(engine: CodingEngine) {
        logAction("prepareCodingRuntime", "backend" to engine.name); delegate.prepareCodingRuntime(engine)
    }
    override fun uninstallCodingRuntime(engine: CodingEngine) {
        logAction("uninstallCodingRuntime", "backend" to engine.name); delegate.uninstallCodingRuntime(engine)
    }
    override fun recover(recovery: CodingRecovery) {
        logAction("recover", *recovery.logFields()); delegate.recover(recovery)
    }
    override fun cancelRecovery(recovery: CodingRecovery) {
        logAction("cancelRecovery", *recovery.logFields()); delegate.cancelRecovery(recovery)
    }
    private fun CodingRecovery.logFields() = when (this) {
        is CodingRecovery.SignIn -> arrayOf("kind" to "sign_in", "backend" to engine.name)
    }
    override fun signOutEngine(engine: CodingEngine) { logAction("signOutEngine", "backend" to engine.name); delegate.signOutEngine(engine) }
    override fun addCodingProject() { logAction("addCodingProject"); delegate.addCodingProject() }
    override fun selectCodingProject(id: String) { logAction("selectCodingProject", "projectId" to id); delegate.selectCodingProject(id) }
    override fun approveImmunityIntervention(organismId: String, proposalId: String, action: ImmunityAction, deleteConfirmed: Boolean) {
        logAction("approveImmunityIntervention", "entityId" to proposalId, "kind" to action.name, "enabled" to deleteConfirmed.toString())
        delegate.approveImmunityIntervention(organismId, proposalId, action, deleteConfirmed)
    }
    override fun dismissImmunityIntervention(organismId: String, proposalId: String) {
        logAction("dismissImmunityIntervention", "entityId" to proposalId); delegate.dismissImmunityIntervention(organismId, proposalId)
    }
    override fun reconcileCodingQuarantine(sessionId: String, confirmed: Boolean) {
        logAction("reconcileCodingQuarantine", "sessionId" to sessionId, "enabled" to confirmed.toString())
        delegate.reconcileCodingQuarantine(sessionId, confirmed)
    }
    override fun deleteCodingProject(id: String) { logAction("deleteCodingProject", "projectId" to id); delegate.deleteCodingProject(id) }
    override fun addCodingSession(engine: CodingEngine) { logAction("addCodingSession", "backend" to engine.name); delegate.addCodingSession(engine) }
    override fun openPlanningChat() { logAction("openPlanningChat"); delegate.openPlanningChat() }
    override fun selectCodingSession(id: String) { logAction("selectCodingSession", "sessionId" to id); delegate.selectCodingSession(id) }
    override fun setSessionManuallyVerified(sessionId: String, responseId: String, verified: Boolean) {
        logAction("setSessionManuallyVerified", "sessionId" to sessionId, "enabled" to verified.toString())
        delegate.setSessionManuallyVerified(sessionId, responseId, verified)
    }
    override fun deleteAllCodingSessions(projectId: String) {
        logAction("deleteAllCodingSessions", "projectId" to projectId); delegate.deleteAllCodingSessions(projectId)
    }
    override fun archiveCodingSession(id: String) { logAction("archiveCodingSession", "sessionId" to id); delegate.archiveCodingSession(id) }
    override fun restoreCodingSession(id: String) { logAction("restoreCodingSession", "sessionId" to id); delegate.restoreCodingSession(id) }
    override fun deleteCodingSession(id: String) { logAction("deleteCodingSession", "sessionId" to id); delegate.deleteCodingSession(id) }
    override suspend fun editMessage(sessionId: String, messageId: String, text: String): Result<Unit> =
        timed("editMessage", "sessionId" to sessionId, "entityId" to messageId) { delegate.editMessage(sessionId, messageId, text) }
    override suspend fun deleteMessage(sessionId: String, messageId: String): Result<Unit> =
        timed("deleteMessage", "sessionId" to sessionId, "entityId" to messageId) { delegate.deleteMessage(sessionId, messageId) }
    override suspend fun forkSession(sessionId: String, throughMessageId: String?): Result<String> =
        timed("forkSession", "sessionId" to sessionId, "entityId" to throughMessageId) { delegate.forkSession(sessionId, throughMessageId) }
    override fun changeCodingInteractionMode(sessionId: String, mode: CodingInteractionMode) {
        logAction("changeCodingInteractionMode", "sessionId" to sessionId, "mode" to mode.name); delegate.changeCodingInteractionMode(sessionId, mode)
    }
    override fun toggleSessionFeatureFlag(sessionId: String, flag: FeatureFlag) {
        logAction("toggleSessionFeatureFlag", "sessionId" to sessionId, "kind" to flag.name); delegate.toggleSessionFeatureFlag(sessionId, flag)
    }
    override fun toggleWorktree(sessionId: String) { logAction("toggleWorktree", "sessionId" to sessionId); delegate.toggleWorktree(sessionId) }
    override fun sendCodingPrompt(text: String) { logAction("sendCodingPrompt"); delegate.sendCodingPrompt(text) }
    override fun sendCodingPromptTo(sessionId: String, text: String, attachments: List<Attachment>) {
        logAction("sendCodingPromptTo", "sessionId" to sessionId, "attachmentsCount" to attachments.size.toString())
        delegate.sendCodingPromptTo(sessionId, text, attachments)
    }
    override fun clarifyCodingSession(sessionId: String, text: String, attachments: List<Attachment>) {
        logAction("clarifyCodingSession", "sessionId" to sessionId, "attachmentsCount" to attachments.size.toString())
        delegate.clarifyCodingSession(sessionId, text, attachments)
    }
    override fun resumeCodingSession(sessionId: String, text: String, attachments: List<Attachment>, fromQuestionnaire: Boolean) {
        logAction("resumeCodingSession", "sessionId" to sessionId, "attachmentsCount" to attachments.size.toString(),
            "source" to if (fromQuestionnaire) "questionnaire" else "composer")
        delegate.resumeCodingSession(sessionId, text, attachments, fromQuestionnaire)
    }
    override fun abortCodingRun() { logAction("abortCodingRun"); delegate.abortCodingRun() }
    override fun abortCodingSession(sessionId: String) { logAction("abortCodingSession", "sessionId" to sessionId); delegate.abortCodingSession(sessionId) }
    override fun respondCodingApproval(id: String, decision: CodingApprovalDecision) {
        logAction("respondCodingApproval", "entityId" to id, "outcome" to decision.name); delegate.respondCodingApproval(id, decision)
    }
    override fun enableComputerUse(sessionId: String, access: ComputerAccess) {
        logAction("enableComputerUse", "sessionId" to sessionId, "capability" to access.name); delegate.enableComputerUse(sessionId, access)
    }
    override fun disableComputerUse(sessionId: String) { logAction("disableComputerUse", "sessionId" to sessionId); delegate.disableComputerUse(sessionId) }
    override fun previewComputerUse(sessionId: String) { logAction("previewComputerUse", "sessionId" to sessionId); delegate.previewComputerUse(sessionId) }
    override fun openComputerSystemSettings() { logAction("openComputerSystemSettings"); delegate.openComputerSystemSettings() }
}
