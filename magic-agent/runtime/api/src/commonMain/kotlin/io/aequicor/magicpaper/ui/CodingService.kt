package io.aequicor.magicpaper.ui

import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.data.storage.DraftSession
import kotlinx.coroutines.flow.StateFlow

data class SessionCreationStatus(val busy: Boolean = false, val error: String? = null)

interface CodingService {
    val state: StateFlow<CodingState>
    val mediaGeneration: MediaGenerationService? get() = null
    fun setMediaToolEnabled(sessionId: String, kind: MediaKind, enabled: Boolean)
    val computerPermissions: ComputerPermissions? get() = null
    val sessionCreationStatus: StateFlow<Map<String, SessionCreationStatus>>
    fun sessionCreationDraft(projectId: String): DraftSession<CodingEngine>?
    fun createCodingSession(projectId: String, onCreated: (String) -> Unit)
    fun discardCodingSessionDraft(projectId: String, onDiscarded: () -> Unit)
    fun updateConfiguration(settings: AppSettings, profiles: List<LlmProfile>, subscriptionAvailable: Boolean, subscriptionSignedIn: Boolean = false)
    fun setVisible(visible: Boolean)
    fun dismissNotice()
    fun updateQuestionnaireDraft(id: String, draft: QuestionnaireDraft)
    fun openQuestionnaire(kind: InteractionKind, sourceId: String)
    fun submitQuestionnaire(id: String, answers: List<PlanningAnswer>)
    suspend fun start()
    suspend fun reload()
    fun codingProfileOf(session: CodingSession, plan: Plan? = null): LlmProfile?
    fun requestCodingSession()
    fun requestCodingSessionInProject(projectId: String)
    fun cancelCodingSessionCreation()
    fun refreshCodingEngines()
    fun selectDefaultCodingEngine(engine: CodingEngine)
    fun selectCodingModel(sessionId: String, selection: ModelSelection, forProject: Boolean = false)
    fun selectCodingSearchProvider(sessionId: String, provider: SearchProvider)
    fun prepareCodingRuntime(engine: CodingEngine = CodingEngine.PI)
    fun uninstallCodingRuntime(engine: CodingEngine = CodingEngine.PI)
    fun addCodingProject()
    fun selectCodingProject(id: String)
    fun approveImmunityIntervention(organismId: String, proposalId: String, action: ImmunityAction, deleteConfirmed: Boolean = false)
    fun dismissImmunityIntervention(organismId: String, proposalId: String)
    /** Явная сверка прерванных операций; [confirmed] означает подтверждение фактического исхода человеком. */
    fun reconcileCodingQuarantine(sessionId: String, confirmed: Boolean = false)
    fun deleteCodingProject(id: String)
    fun addCodingSession(engine: CodingEngine = CodingEngine.PI)
    fun openPlanningChat()
    fun selectCodingSession(id: String)
    /** Mark the session as read up to its last visible agent message. */
    fun markSessionRead(sessionId: String, messageId: String)
    fun setSessionManuallyVerified(sessionId: String, responseId: String, verified: Boolean)
    fun deleteAllCodingSessions(projectId: String)
    fun archiveCodingSession(id: String)
    /** Return to the list without restarting execution. */
    fun restoreCodingSession(id: String)
    fun deleteCodingSession(id: String)
    suspend fun editMessage(sessionId: String, messageId: String, text: String): Result<Unit>
    suspend fun deleteMessage(sessionId: String, messageId: String): Result<Unit>
    suspend fun forkSession(sessionId: String, throughMessageId: String? = null): Result<String>
    fun changeCodingInteractionMode(sessionId: String, mode: CodingInteractionMode)
    /** Переключить per-session фича-флаг; пустой session override наследует глобальные из настроек. */
    fun toggleSessionFeatureFlag(sessionId: String, flag: FeatureFlag)
    fun toggleWorktree(sessionId: String)
    fun sendCodingPrompt(text: String)
    fun sendCodingPromptTo(sessionId: String, text: String, attachments: List<Attachment> = emptyList())
    fun clarifyCodingSession(sessionId: String, text: String, attachments: List<Attachment> = emptyList())
    fun resumeCodingSession(sessionId: String, text: String = "", attachments: List<Attachment> = emptyList(), fromQuestionnaire: Boolean = false)
    fun abortCodingRun()
    fun abortCodingSession(sessionId: String)
    suspend fun shutdownCoding()
    fun respondCodingApproval(id: String, decision: io.aequicor.magicpaper.domain.CodingApprovalDecision)
    fun enableComputerUse(sessionId: String, access: io.aequicor.magicpaper.domain.ComputerAccess)
    fun disableComputerUse(sessionId: String)
    fun previewComputerUse(sessionId: String)
    fun openComputerSystemSettings()
    suspend fun activate(projectId: String?, sessionId: String?)
    suspend fun prepareSettings(previous: AppSettings, next: AppSettings)
    suspend fun applySettings(settings: AppSettings): Result<Unit>
    suspend fun clearProfileOverrides(id: String)
    suspend fun close()
}
