package io.aequicor.magicpaper.di

import io.aequicor.magicpaper.data.coding.JsonRuntimeQuestionnaireStore
import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.domain.tools.DefaultCustomOrchestration
import io.aequicor.magicpaper.navigation.AppRoute
import io.aequicor.magicpaper.ui.*
import kotlinx.coroutines.CoroutineScope
import org.koin.core.module.Module

/** The desktop composition root alone knows executable agent contracts. */
internal fun Module.nativeRuntimeBindings(
    applicationScope: CoroutineScope,
    runtime: CodingRuntime,
    dirPicker: ProjectDirPicker?,
    planningWorkspace: PlanningWorkspace,
    taskWorkspace: TaskWorkspace,
    integrationChecks: SessionIntegrationCheckRunner?,
    featureFactory: (CodingFeatureDependencies) -> CodingFeature = ::codingFeature,
) {
    dirPicker?.let { picker -> single<ProjectDirPicker> { picker } }
    single<CodingFeature> { featureFactory(CodingFeatureDependencies(
        store = get(), json = get(), settings = get(), settingsCommands = get(), profiles = get(), usage = get(),
        gateway = get(), search = get(), dossier = get(), modelDossiers = get(), drafts = get(), draftBlobs = get(), events = get(),
        media = get(), requestPins = get(), chats = get(),
        toolQuestionnaires = get<RuntimeQuestionnaireFactory>().create("application-tools",
            JsonRuntimeQuestionnaireStore(get(), "tool-questionnaires")),
        toolReceipts = get(), toolSessionFactory = get(), mediaToolFactory = get(), questionnaireToolFactory = get(),
        mediaToolReceipts = get(),
        readResearchPage = get<io.aequicor.magicpaper.data.ResearchPageReader>()::read,
        filePicker = get(), projectSkills = getOrNull(), applicationScope = applicationScope,
        onOpenSession = { project, session -> get<NavigationEvents>().navigate(AppRoute.Projects(project, session)) },
        removePluginDrafts = { project, plans -> get<PluginService>().removeProjectDrafts(project, plans) },
        codingRuntime = runtime, dirPicker = dirPicker, planningWorkspace = planningWorkspace,
        taskWorkspace = taskWorkspace, integrationChecks = integrationChecks,
        taskWorktreeOwner = io.aequicor.magicpaper.data.workspace.DefaultTaskWorktreeOwner(taskWorkspace, get(), get()),
        planningStoreFactory = io.aequicor.magicpaper.data.planning.PlanningStoreFactory { knownSecrets ->
            io.aequicor.magicpaper.data.planning.DefaultPlanningStore(
                io.aequicor.magicpaper.data.planning.JsonPlanningRepository(get(), get()), get(), knownSecrets)
        },
        organismStoreFactory = io.aequicor.magicpaper.data.coding.SessionOrganismStoreFactory { knownSecrets ->
            io.aequicor.magicpaper.data.coding.DefaultSessionOrganismStore(get(), get(), knownSecrets)
        },
        orchestrationFactory = ::DefaultCustomOrchestration,
    )) }
    single<CodingProjectRepository> { get<CodingFeature>().projects }
    single<CodingRuntime> { get<CodingFeature>().runtime }
    single<CodingModelCatalog> { get<CodingFeature>().models }
    single<PlanningRepository> { get<CodingFeature>().planning }
    single<CodingService> { get<CodingFeature>().service }
    factory<CodingComponent.Factory>(FeatureFactoryQualifiers.coding) { get<CodingFeature>().componentFactory }
}

internal class NativeRuntimeExtension(
    private val feature: CodingFeature,
    private val computer: NativeComputerUse,
    private val pauseNative: suspend () -> Unit,
    private val resumeNative: suspend () -> Unit,
    private val discardUnresolvableChecks: suspend () -> Unit = {},
) : RuntimeExtension {
    override val id = "agent"
    // Reset participation is transient host coordination, not restored execution authority.
    // Mark before each call: a failed pause may already have revoked or stopped resources.
    private var featureNeedsResume = false
    private var nativeNeedsResume = false
    private var computerNeedsResume = false
    override suspend fun start() {
        // Recovery must rebuild planning before the service consumes saved run checkpoints.
        feature.start()
        feature.service.start()
    }
    override fun updateConfiguration(state: SettingsState) = feature.service.updateConfiguration(
        state.settings, state.llmProfiles, state.openAiSubscription.available,
        state.openAiSubscription.account?.signedIn == true,
    )
    override suspend fun reload() = feature.service.reload()
    override suspend fun clearProfileOverrides(profileId: String) = feature.service.clearProfileOverrides(profileId)
    override suspend fun prepareForReset() {
        featureNeedsResume = true
        feature.prepareForReset()
    }
    override suspend fun pauseForReset(discardUnresolvable: Boolean) {
        // Before the feature pauses: stopping sessions reconciles and releases workspaces through checks, which an
        // unresolvable check journal would refuse. Admission stays open; nothing here needs a resume.
        if (discardUnresolvable) discardUnresolvableChecks()
        featureNeedsResume = true
        feature.pauseForReset()
        nativeNeedsResume = true
        pauseNative()
        computerNeedsResume = true
        computer.prepareForReset()
    }
    override suspend fun clearForReset() = feature.clearForReset()
    override suspend fun resumeAfterReset() = completeRuntimeCleanup(
        { if (computerNeedsResume) { computer.resumeAfterReset(); computerNeedsResume = false } },
        { if (nativeNeedsResume) { resumeNative(); nativeNeedsResume = false } },
        { if (featureNeedsResume) { feature.resumeAfterReset(); featureNeedsResume = false } },
    )
    override suspend fun close() = completeRuntimeCleanup({ feature.service.close() }, { feature.close() })


}

internal class NativeMediaOwnerPolicy(private val projects: CodingProjectRepository) : MediaOwnerPolicy {
    override fun owns(owner: MediaGenerationOwner) = owner.projectId != null
    override suspend fun exists(owner: MediaGenerationOwner) = session(owner) != null
    override suspend fun allows(owner: MediaGenerationOwner, kind: MediaKind): Boolean =
        session(owner)?.let { it.mediaTools.enabled(kind) && it.runtimeGeneration == owner.runtimeGeneration } == true
    private suspend fun session(owner: MediaGenerationOwner) = owner.projectId?.let { projectId ->
        projects.sessions(projectId).firstOrNull { it.id == owner.sessionId }
    }
}
