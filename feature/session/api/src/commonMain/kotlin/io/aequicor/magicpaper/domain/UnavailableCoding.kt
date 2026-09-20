package io.aequicor.magicpaper.domain

import androidx.compose.runtime.Composable
import com.arkivanov.decompose.ComponentContext
import io.aequicor.magicpaper.data.coding.NoopCodingRuntime
import io.aequicor.magicpaper.data.storage.DraftSession
import io.aequicor.magicpaper.designsystem.PaperText
import io.aequicor.magicpaper.plugins.MagicPlugin
import io.aequicor.magicpaper.ui.CodingAction
import io.aequicor.magicpaper.ui.CodingComponent
import io.aequicor.magicpaper.ui.CodingInput
import io.aequicor.magicpaper.ui.CodingOutput
import io.aequicor.magicpaper.ui.CodingService
import io.aequicor.magicpaper.ui.CodingState
import io.aequicor.magicpaper.ui.SessionCreationStatus
import io.aequicor.magicpaper.ui.components.CodingPresentation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Coding on a host that cannot run an agent: every query answers empty and every command
 * does nothing. A missing binding is not an option — the shell resolves these eagerly, and
 * an absent one would surface as a retry card for an action that can never succeed.
 */

private const val UNAVAILABLE = "Кодинг-сессии доступны только в десктопной версии."

object UnavailableCodingProjectRepository : CodingProjectRepository {
    override suspend fun all(): List<CodingProject> = emptyList()
    override suspend fun save(project: CodingProject) = error(UNAVAILABLE)
    override suspend fun delete(id: String) = Unit
    override suspend fun sessions(projectId: String): List<CodingSession> = emptyList()
    override suspend fun saveSession(session: CodingSession) = error(UNAVAILABLE)
    override suspend fun deleteSession(projectId: String, sessionId: String) = Unit
    override suspend fun messages(projectId: String, sessionId: String): List<CodingMessage> = emptyList()
    override suspend fun saveMessages(projectId: String, sessionId: String, messages: List<CodingMessage>): List<CodingMessage> = error(UNAVAILABLE)
    override suspend fun wipe() = Unit
}

object UnavailablePlanningRepository : PlanningRepository {
    override suspend fun plans(): List<Plan> = emptyList()
    override suspend fun planFor(projectId: String): Plan? = null
    override suspend fun save(plan: Plan) = error(UNAVAILABLE)
    override suspend fun deletePlan(projectId: String) = Unit
    override suspend fun dossiers(): List<ModelDossier> = emptyList()
    override suspend fun saveDossier(dossier: ModelDossier) = error(UNAVAILABLE)
    override suspend fun wipe() = Unit
}

object UnavailableCodingPresentation : CodingPresentation {
    @Composable override fun ComputerUsePanel(
        state: ComputerUseState, sessionId: String, running: Boolean,
        onEnable: (ComputerAccess) -> Unit, onDisable: () -> Unit, onPreview: () -> Unit, onSettings: () -> Unit,
    ) = PaperText(UNAVAILABLE)
}

/** Renders the reason instead of a retry the host could never satisfy. */
object UnsupportedCodingComponentFactory : CodingComponent.Factory {
    override fun create(context: ComponentContext, input: CodingInput, onOutput: (CodingOutput) -> Unit): CodingComponent =
        object : CodingComponent {
            override val state: StateFlow<CodingState> = MutableStateFlow(unavailableCodingState)
            override fun onAction(action: CodingAction) = Unit
            @Composable override fun Content() = PaperText(UNAVAILABLE)
        }
}

private val unavailableCodingState = CodingState(coding = io.aequicor.magicpaper.ui.CodingUi(supported = false))

object UnavailableCodingService : CodingService {
    override val state: StateFlow<CodingState> = MutableStateFlow(unavailableCodingState)
    override val sessionCreationStatus: StateFlow<Map<String, SessionCreationStatus>> = MutableStateFlow(emptyMap())
    override fun setMediaToolEnabled(sessionId: String, kind: MediaKind, enabled: Boolean) = Unit
    override fun sessionCreationDraft(projectId: String): DraftSession<CodingEngine>? = null
    override fun createCodingSession(projectId: String, onCreated: (String) -> Unit) = Unit
    override fun discardCodingSessionDraft(projectId: String, onDiscarded: () -> Unit) = onDiscarded()
    override fun updateConfiguration(settings: AppSettings, profiles: List<LlmProfile>, subscriptionAvailable: Boolean, subscriptionSignedIn: Boolean) = Unit
    override fun setVisible(visible: Boolean) = Unit
    override fun dismissNotice() = Unit
    override fun updateQuestionnaireDraft(id: String, draft: QuestionnaireDraft) = Unit
    override fun openQuestionnaire(kind: InteractionKind, sourceId: String) = Unit
    override fun submitQuestionnaire(id: String, answers: List<PlanningAnswer>) = Unit
    override suspend fun start() = Unit
    override suspend fun reload() = Unit
    override fun codingProfileOf(session: CodingSession, plan: Plan?): LlmProfile? = null
    override fun requestCodingSession() = Unit
    override fun requestCodingSessionInProject(projectId: String) = Unit
    override fun cancelCodingSessionCreation() = Unit
    override fun refreshCodingEngines() = Unit
    override fun selectDefaultCodingEngine(engine: CodingEngine) = Unit
    override fun selectCodingModel(sessionId: String, selection: ModelSelection, forProject: Boolean) = Unit
    override fun selectCodingSearchProvider(sessionId: String, provider: SearchProvider) = Unit
    override fun prepareCodingRuntime(engine: CodingEngine) = Unit
    override fun uninstallCodingRuntime(engine: CodingEngine) = Unit
    override fun addCodingProject() = Unit
    override fun selectCodingProject(id: String) = Unit
    override fun approveImmunityIntervention(organismId: String, proposalId: String, action: ImmunityAction, deleteConfirmed: Boolean) = Unit
    override fun dismissImmunityIntervention(organismId: String, proposalId: String) = Unit
    override fun reconcileCodingQuarantine(sessionId: String, confirmed: Boolean) = Unit
    override fun deleteCodingProject(id: String) = Unit
    override fun addCodingSession(engine: CodingEngine) = Unit
    override fun openPlanningChat() = Unit
    override fun selectCodingSession(id: String) = Unit
    override fun markSessionRead(sessionId: String, messageId: String) = Unit
    override fun setSessionManuallyVerified(sessionId: String, responseId: String, verified: Boolean) = Unit
    override fun deleteAllCodingSessions(projectId: String) = Unit
    override fun archiveCodingSession(id: String) = Unit
    override fun restoreCodingSession(id: String) = Unit
    override fun deleteCodingSession(id: String) = Unit
    override suspend fun editMessage(sessionId: String, messageId: String, text: String): Result<Unit> = Result.failure(IllegalStateException(UNAVAILABLE))
    override suspend fun deleteMessage(sessionId: String, messageId: String): Result<Unit> = Result.failure(IllegalStateException(UNAVAILABLE))
    override suspend fun forkSession(sessionId: String, throughMessageId: String?): Result<String> = Result.failure(IllegalStateException(UNAVAILABLE))
    override fun changeCodingInteractionMode(sessionId: String, mode: CodingInteractionMode) = Unit
    override fun toggleSessionFeatureFlag(sessionId: String, flag: FeatureFlag) = Unit
    override fun toggleWorktree(sessionId: String) = Unit
    override fun sendCodingPrompt(text: String) = Unit
    override fun sendCodingPromptTo(sessionId: String, text: String, attachments: List<Attachment>) = Unit
    override fun clarifyCodingSession(sessionId: String, text: String, attachments: List<Attachment>) = Unit
    override fun resumeCodingSession(sessionId: String, text: String, attachments: List<Attachment>, fromQuestionnaire: Boolean) = Unit
    override fun abortCodingRun() = Unit
    override fun abortCodingSession(sessionId: String) = Unit
    override suspend fun shutdownCoding() = Unit
    override fun respondCodingApproval(id: String, decision: CodingApprovalDecision) = Unit
    override fun enableComputerUse(sessionId: String, access: ComputerAccess) = Unit
    override fun disableComputerUse(sessionId: String) = Unit
    override fun previewComputerUse(sessionId: String) = Unit
    override fun openComputerSystemSettings() = Unit
    override suspend fun activate(projectId: String?, sessionId: String?) = Unit
    override suspend fun applySettings(settings: AppSettings): Result<Unit> = Result.success(Unit)
    override suspend fun clearProfileOverrides(id: String) = Unit
    override suspend fun close() = Unit
}

object UnavailableCodingFeature : CodingFeature {
    override val projects: CodingProjectRepository = UnavailableCodingProjectRepository
    override val planning: PlanningRepository = UnavailablePlanningRepository
    override val runtime: CodingRuntime = NoopCodingRuntime
    override val service: CodingService = UnavailableCodingService
    override val componentFactory: CodingComponent.Factory = UnsupportedCodingComponentFactory
    override val presentation: CodingPresentation = UnavailableCodingPresentation
    override val plugins: List<MagicPlugin> = emptyList()
}
