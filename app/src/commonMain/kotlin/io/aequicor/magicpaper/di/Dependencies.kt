package io.aequicor.magicpaper.di

import io.aequicor.magicpaper.data.coding.NoopCodingRuntime
import io.aequicor.magicpaper.data.docs.EmbeddedDocRepository
import io.aequicor.magicpaper.data.llm.*
import io.aequicor.magicpaper.data.search.*
import io.aequicor.magicpaper.data.skills.*
import io.aequicor.magicpaper.data.storage.*
import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.navigation.AppRoute
import io.aequicor.magicpaper.navigation.DialogRoute
import io.aequicor.magicpaper.plugins.*
import io.aequicor.magicpaper.plugins.builtin.*
import io.aequicor.magicpaper.ui.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import org.koin.dsl.module
import org.koin.dsl.onClose

expect fun createMagicPaperRuntime(navigationSession: NavigationSessionConfig = NavigationSessionConfig()): MagicPaperRuntime

internal val appJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

internal fun buildRuntime(
    store: KeyValueStore,
    persistence: PersistenceStores,
    bridge: ProfileBridge,
    navigationSession: NavigationSessionConfig,
    codingRuntime: CodingRuntime = NoopCodingRuntime,
    dirPicker: ProjectDirPicker? = null,
    filePicker: FilePicker = NoopFilePicker,
    openAiSubscription: OpenAiSubscriptionService? = null,
    planningWorkspace: PlanningWorkspace = LocalPlanningWorkspace(),
    taskWorkspace: TaskWorkspace = UnavailableTaskWorkspace,
    integrationChecks: SessionIntegrationCheckRunner? = null,
    platformPlugins: List<MagicPlugin> = emptyList(),
    projectSkills: ProjectSkills? = null,
    packageInstructions: SkillInstructionSource? = null,
    experiencePlugin: ((LlmGateway, LlmProfileRepository) -> MagicPlugin)? = null,
    onPlatformStarted: () -> Unit = {},
    onPlatformClosed: suspend () -> Unit = {},
    layoutEditor: LayoutEditor = UnavailableLayoutEditor,
    modelLimits: ModelLimitCatalog? = null,
): MagicPaperRuntime = MagicPaperRuntime(navigationSession, onPlatformStarted, onPlatformClosed) { applicationScope ->
    var resetting = false
    module {
        single { appJson }
        single<KeyValueStore> { store }
        single { persistence }
        single<SecretStore> { persistence.secrets }
        single<DraftRepository> { persistence.drafts }
        single<DraftBlobStore> { persistence.blobs }
        single<NavigationSnapshotStore> { persistence.navigation }
        single<CoroutineScope> { applicationScope }
        single<ProfileBridge> { bridge }
        single<FilePicker> { filePicker }
        single { NavigationEvents() }
        dirPicker?.let { picker -> single<ProjectDirPicker> { picker } }
        projectSkills?.let { renderer -> single<ProjectSkills> { renderer } }
        openAiSubscription?.let { subscription -> single<OpenAiSubscriptionService> { subscription } }
        single<SettingsRepository> { JsonSettingsRepository(get(), get(), secrets = get()) }
        single<LlmProfileRepository> { JsonLlmProfileRepository(get(), get(), secrets = get(), modelLimits = modelLimits) }
        single<ChatRepository> { JsonChatRepository(get(), get()) }
        single<RequestPinRepository> { JsonRequestPinRepository(get(), get()) }
        single<UsageLedger> { DefaultUsageLedger(JsonUsageRepository(get(), get())) }
        single { appHttpClient() } onClose { it?.close() }
        single<SearchEngine> { CompositeSearchEngine(listOf(
            WikipediaSearchEngine(get(), get(), get()),
            QueritSearchEngine(get(), get(), get()),
            GoogleSearchEngine(get(), get(), get()),
        )) }
        single<LlmGateway> { RoutingLlmGateway(buildMap {
            openAiSubscription?.let { put(ProviderType.OPENAI_SUBSCRIPTION, it) }
            put(ProviderType.OPENAI_COMPATIBLE, OpenAiCompatibleGateway(get(), get()))
            put(ProviderType.OPENROUTER, OpenAiCompatibleGateway(get(), get()))
            put(ProviderType.ANTHROPIC, AnthropicGateway(get(), get()))
            put(ProviderType.GOOGLE, GoogleGateway(get(), get()))
        }, usage = get()) }
        single<ModelDirectory> {
            val providers = RoutingModelDirectory(buildMap {
                openAiSubscription?.let { put(ProviderType.OPENAI_SUBSCRIPTION, it) }
                put(ProviderType.OPENAI_COMPATIBLE, OpenAiModelDirectory(get(), get()))
                put(ProviderType.OPENROUTER, OpenAiModelDirectory(get(), get()))
                put(ProviderType.ANTHROPIC, AnthropicModelDirectory(get(), get()))
                put(ProviderType.GOOGLE, GoogleModelDirectory(get(), get()))
            })
            // Эндпоинты, отдающие только id, дополняются фактами каталога движка.
            if (modelLimits == null) providers else DeclaredLimitsModelDirectory(providers, modelLimits)
        }
        single<SearchConnectionChecker> { HttpSearchConnectionChecker(get(), get(), get()) }
        single<DossierResearcher> { DefaultDossierResearcher(get(), get(), get()) }
        single<DocRepository> { EmbeddedDocRepository() }
        single { SkillStore(JsonSkillRepository(get(), get())) }
        single<SkillRepository> { get<SkillStore>() }
        single<SkillLibrary> { get<SkillStore>() }
        single<SkillCatalog> { EmbeddedSkillCatalog() }
        single { SkillInstaller(get<SkillStore>()) }
        packageInstructions?.let { source ->
            single<SkillInstructionRuntime> { DefaultSkillInstructionRuntime(source, get()) }
        }
        single { val settings = get<SettingsRepository>()
            GatewaySessionRuntime(get(), get(), get(), skillLibrary = get(), packageRuntime = getOrNull(),
                layoutEditor = layoutEditor, settings = { settings.load() },
                readResearchPage = io.aequicor.magicpaper.data.ResearchPageReader(get())::read) }
        single<CodingProjectRepository> { codingProjectRepository(store, get(), get(), get(), codingRuntime) }
        single { CodingRuntimeGraph(store, get(), get(), get(), get(), codingRuntime,
            planningWorkspace, integrationChecks, get(), get(), get(), draftRepository = get(), taskWorkspace = taskWorkspace).also { graph ->
                graph.toolHost.orchestration = io.aequicor.magicpaper.domain.tools.DefaultCustomOrchestration(
                    io.aequicor.magicpaper.domain.tools.OrchestrationActions { context, operation, tool, arguments ->
                        graph.toolHost.receiver(context, operation, tool, arguments)
                    })
            } }
        single<CodingRuntime> { get<CodingRuntimeGraph>().runtime ?: codingRuntime }
        single<PlanningRepository> { get<CodingRuntimeGraph>().planningStore }
        single { DefaultRequestPinService(get(), get(), applicationScope, get()) }
        single<RequestPinService> { get<DefaultRequestPinService>() }
        single<PluginRegistry> {
            val graph = get<CodingRuntimeGraph>()
            PluginRegistry().register(NotesPlugin(get(), applicationScope)).register(FocusPlugin(get(), applicationScope)).register(CalcPlugin(get(), applicationScope))
                .register(SkillsRepositoryPlugin(get(), get(), get<SkillStore>()))
                .apply { experiencePlugin?.let { register(it(get(), get())) } }
                .register(CodingPlanningPlugin(graph.planningStore, graph.planComposer, get(),
                    graph.planningExecution, graph.runtime ?: codingRuntime, get(), get(), get(),
                    draftRepository = get(), applicationScope = applicationScope))
                .apply { platformPlugins.forEach(::register) }
        }
        factory<ChatComponent.Factory>(FeatureFactoryQualifiers.chat) { DefaultChatComponentFactory(get(), get()) }
        factory<CodingComponent.Factory>(FeatureFactoryQualifiers.coding) { DefaultCodingComponentFactory(get(), get(), getOrNull()) }
        factory<SettingsComponent.Factory>(FeatureFactoryQualifiers.settings) { DefaultSettingsComponentFactory(get(), get(), get(), get(), get()) }
        factory<DocsComponent.Factory>(FeatureFactoryQualifiers.docs) { DefaultDocsComponentFactory(get()) }
        factory<PluginsComponent.Factory>(FeatureFactoryQualifiers.plugins) { DefaultPluginsComponentFactory(get()) }
        factory<SkillsComponent.Factory>(FeatureFactoryQualifiers.skills) { DefaultSkillsComponentFactory(get()) }
        single<ChatService> { get<DefaultChatService>() }
        single<CodingService> { get<DefaultCodingService>() }
        single<SettingsService> { get<DefaultSettingsService>() }
        single<PluginService> { DefaultPluginService(get(), get()) }
        single { DefaultChatService(if (codingRuntime.supported) get<CodingRuntime>() else get<GatewaySessionRuntime>(), get(), get(), get(), get(),
            layoutAgent = LayoutChatAgent(get(), layoutEditor),
            onOpenSession = { get<NavigationEvents>().navigate(AppRoute.Chat(it)) },
            draftRepository = get(), draftBlobs = get(),
            layoutProject = { boundId ->
                val coding = get<CodingService>().state.value.coding
                if (boundId == null) coding.current ?: coding.projects.singleOrNull() else coding.projects.firstOrNull { it.id == boundId }
            }) }
        single { DefaultCodingService(get(), get(), get(), get(), get(), get(), dirPicker,
            get(), get<CodingRuntimeGraph>().planningChat, get(), get(),
            onOpenSession = { project, session -> get<NavigationEvents>().navigate(AppRoute.Projects(project, session)) },
            draftRepository = get(), draftBlobs = get(),
            taskWorktrees = get<CodingRuntimeGraph>().taskWorktrees,
            removePluginDrafts = { project, plans -> get<PluginService>().removeProjectDrafts(project, plans) }) }
        single { DefaultSettingsService(get(), get(), get(), get(), get(), get(),
            skills = get(), planning = get(), modelDirectory = get(), gateway = get(), dossierResearcher = get(),
            openAiSubscription = openAiSubscription, searchConnectionChecker = get(), usage = get(), draftRepository = get(),
            applyRuntimeSettings = { get<CodingService>().applySettings(it) },
            clearCodingOverrides = { get<CodingService>().clearProfileOverrides(it) },
            onDataChanged = { get<ChatService>().start(); get<CodingService>().reload(); get<PluginService>().start() },
            onProfileSaved = { get<NavigationEvents>().back() },
            clearApplicationData = {
                get<NavigationEvents>().reset()
                resetting = true
                get<DefaultSettingsService>().prepareForReset()
                get<DefaultChatService>().prepareForReset()
                get<DefaultCodingService>().prepareForReset()
                get<DefaultRequestPinService>().resetForWipe()
                get<PluginService>().prepareForReset()
                get<CodingRuntimeGraph>().pauseForReset()
                openAiSubscription?.logout()
                get<CodingProjectRepository>().wipe()
                get<CodingRuntimeGraph>().clearForReset()
                persistence.clearOwnedData()
                store.clear()
            },
            finishApplicationReset = {
                if (resetting) {
                    resetting = false
                    try {
                        get<CodingRuntimeGraph>().resumeAfterReset()
                        get<ChatService>().start()
                        get<CodingService>().reload()
                        get<PluginService>().start()
                    } finally { get<NavigationEvents>().resetComplete() }
                }
            }) }
    }
}
