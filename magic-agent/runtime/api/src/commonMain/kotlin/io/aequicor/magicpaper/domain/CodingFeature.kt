package io.aequicor.magicpaper.domain

import io.aequicor.magicpaper.data.storage.DraftBlobStore
import io.aequicor.magicpaper.data.storage.DraftRepository
import io.aequicor.magicpaper.data.storage.EventJournal
import io.aequicor.magicpaper.data.storage.KeyValueStore
import io.aequicor.magicpaper.domain.tools.ToolReceiptStore
import io.aequicor.magicpaper.domain.tools.ToolSession
import io.aequicor.magicpaper.domain.tools.MediaToolReceiptOwner
import io.aequicor.magicpaper.domain.tools.MediaToolCommands
import io.aequicor.magicpaper.domain.tools.QuestionnaireToolCommands
import io.aequicor.magicpaper.domain.tools.CustomOrchestration
import io.aequicor.magicpaper.domain.tools.OrchestrationActions
import io.aequicor.magicpaper.plugins.MagicPlugin
import io.aequicor.magicpaper.ui.CodingComponent
import io.aequicor.magicpaper.ui.CodingService
import io.aequicor.magicpaper.ui.components.CodingPresentation
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.Json

/**
 * What the coding half of a session needs from the application shell.
 *
 * The shell owns storage, settings, model access and the chat side; it knows nothing about
 * plans, worktrees or agent processes. Platform capabilities that only some hosts can supply
 * ([codingRuntime], [dirPicker], [planningWorkspace], [taskWorkspace], [integrationChecks])
 * arrive already resolved, so the feature never asks which platform it is running on.
 */
class CodingFeatureDependencies(
    val store: KeyValueStore,
    val json: Json,
    val settings: SettingsRepository,
    val settingsCommands: SettingsCommands,
    val profiles: LlmProfileRepository,
    val usage: UsageLedger,
    val gateway: LlmGateway,
    val search: SearchEngine,
    val dossier: DossierResearcher,
    val modelDossiers: ModelDossierRepository,
    val drafts: DraftRepository,
    val draftBlobs: DraftBlobStore,
    /** Append-only evidence for planning; the same reset clears it and the plans. */
    val events: EventJournal,
    val media: MediaGenerationService,
    val requestPins: RequestPinService,
    /** Media policy for a research chat reads the owning conversation, not a project. */
    val chats: ChatRepository,
    /**
     * Reads a research page when the planner verifies that a search hit is readable.
     * The capability is passed, not the checker: the checker's own read limit then bounds
     * planning separately from chat instead of the two contending for one budget.
     */
    val readResearchPage: (suspend (String) -> String)?,
    val filePicker: FilePicker,
    val projectSkills: ProjectSkills?,
    val applicationScope: CoroutineScope,
    val onOpenSession: (projectId: String?, sessionId: String?) -> Unit,
    val removePluginDrafts: suspend (projectId: String, planIds: Set<String>?) -> Unit,
    val codingRuntime: CodingRuntime,
    val dirPicker: ProjectDirPicker?,
    val planningWorkspace: PlanningWorkspace,
    val taskWorkspace: TaskWorkspace,
    val taskWorktreeOwner: TaskWorktreeOwner,
    val integrationChecks: SessionIntegrationCheckRunner?,
    val orchestrationFactory: (OrchestrationActions) -> CustomOrchestration,
    val toolQuestionnaires: RuntimeQuestionnaireService,
    val toolReceipts: ToolReceiptStore,
    val toolSessionFactory: ToolSession.Factory,
    val mediaToolFactory: MediaToolCommands.Factory,
    val mediaToolReceipts: MediaToolReceiptOwner,
    val questionnaireToolFactory: QuestionnaireToolCommands.Factory,
    val planningStoreFactory: io.aequicor.magicpaper.data.planning.PlanningStoreFactory,
    val organismStoreFactory: io.aequicor.magicpaper.data.coding.SessionOrganismStoreFactory,
)

/**
 * Everything the application binds for the coding half of a session, assembled by the
 * desktop host. This contract is compiled only for JVM; other hosts have no executable
 * coding binding. Persisted session identities remain platform-independent values.
 */
interface CodingFeature {
    val projects: CodingProjectRepository
    val planning: PlanningRepository
    val runtime: CodingRuntime
    /** Нативные каталоги моделей движков; опрос запускает потребитель через `refresh`, не сборка фичи. */
    val models: CodingModelCatalog
    val service: CodingService
    val componentFactory: CodingComponent.Factory
    val presentation: CodingPresentation
    val plugins: List<MagicPlugin>

    /**
     * Recovers orchestration and child-session projections. `AppRuntime` calls this before
     * `CodingService.start()` consumes durable run checkpoints; the order is load-bearing.
     */
    suspend fun start() = Unit

    /**
     * Execution teardown only. The service closes under its own name so one failing owner
     * cannot stop the other, exactly as before this contract existed.
     */
    suspend fun close() = Unit

    /**
     * Reset runs in a fixed order across owners: every owner prepares, then execution pauses,
     * then storage is wiped. These stay separate members because other owners run between them.
     */
    suspend fun prepareForReset() = Unit
    suspend fun pauseForReset() = Unit
    suspend fun clearForReset() = Unit
    suspend fun resumeAfterReset() = Unit
}
