package io.aequicor.magicpaper.di

import io.aequicor.magicpaper.data.docs.EmbeddedDocRepository
import io.aequicor.magicpaper.data.coding.JsonRuntimeQuestionnaireStore
import io.aequicor.magicpaper.data.llm.*
import io.aequicor.magicpaper.data.media.HttpMediaGenerationGateway
import io.aequicor.magicpaper.data.search.*
import io.aequicor.magicpaper.data.skills.*
import io.aequicor.magicpaper.data.storage.*
import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.domain.tools.*
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
    filePicker: FilePicker = NoopFilePicker,
    openAiSubscription: OpenAiSubscriptionService? = null,
    platformPlugins: List<MagicPlugin> = emptyList(),
    projectSkills: ProjectSkills? = null,
    packageInstructions: SkillInstructionSource? = null,
    experiencePlugin: ((LlmGateway, LlmProfileRepository) -> MagicPlugin)? = null,
    onPlatformStarted: () -> Unit = {},
    onPlatformClosed: suspend () -> Unit = {},
    modelLimits: ModelLimitCatalog? = null,
    researchPageBrowser: ResearchPageBrowser? = null,
    mediaStore: MediaStore = UnavailableMediaStore,
    questionnaireFactory: RuntimeQuestionnaireFactory = DefaultRuntimeQuestionnaireFactory(persistence.events),
    settingsContributions: org.koin.core.scope.Scope.() -> SettingsContributions = { SettingsContributions() },
    appContributions: org.koin.core.scope.Scope.() -> AppContributions = { AppContributions() },
    runtimeExtensions: org.koin.core.scope.Scope.() -> List<RuntimeExtension> = { emptyList() },
    mediaOwnerPolicies: org.koin.core.scope.Scope.() -> List<MediaOwnerPolicy> = { emptyList() },
    featurePlugins: org.koin.core.scope.Scope.() -> List<MagicPlugin> = { emptyList() },
    responseExtensions: org.koin.core.scope.Scope.() -> List<ChatResponseExtension> = { emptyList() },
    settingsRuntime: org.koin.core.scope.Scope.() -> SettingsRuntimeParticipant? = { null },
    platformDefinitions: org.koin.core.module.Module.(CoroutineScope) -> Unit = {},
): MagicPaperRuntime = MagicPaperRuntime(navigationSession, onPlatformStarted, onPlatformClosed) { applicationScope ->
    var resetting = false
    val resetOwners = mutableSetOf<RuntimeExtension>()
    var resetMedia = false
    var resetChat = false
    var resetPlugins = false
    var resetSkills = false
    var eraseResetFiles = false
    module {
        single { appJson }
        single<KeyValueStore> { store }
        single { persistence }
        single<SecretStore> { persistence.secrets }
        single<DraftRepository> { persistence.drafts }
        single<DraftBlobStore> { persistence.blobs }
        single<EventJournal> { persistence.events }
        single<RuntimeQuestionnaireFactory> { questionnaireFactory }
        single<ToolReceiptStore> { StoredToolReceipts(get()) }
        single<MediaToolReceiptOwner> { DefaultMediaToolReceiptOwner(get(), get<MediaStore>()::localPath) }
        single<ToolSession.Factory> { DefaultToolSessionFactory() }
        single<MediaToolCommands.Factory> { DefaultMediaToolCommandsFactory() }
        single<QuestionnaireToolCommands.Factory> { DefaultQuestionnaireToolCommandsFactory() }
        single<MediaStore> { mediaStore }
        single<NavigationSnapshotStore> { persistence.navigation }
        single<CoroutineScope> { applicationScope }
        single<ProfileBridge> { bridge }
        single<FilePicker> { filePicker }
        single { NavigationEvents() }
        projectSkills?.let { renderer -> single<ProjectSkills> { renderer } }
        openAiSubscription?.let { subscription -> single<OpenAiSubscriptionService> { subscription } }
        single { DefaultSettingsConfiguration(get(), get(), get(), get(), modelLimits = modelLimits,
            directory = get(), researcher = get(), runtime = settingsRuntime()) }
        single<SettingsCommands> { get<DefaultSettingsConfiguration>() }
        single<SettingsRepository> { val owner = get<DefaultSettingsConfiguration>()
            object : SettingsRepository { override suspend fun load() = owner.settings() } }
        single<LlmProfileRepository> { val owner = get<DefaultSettingsConfiguration>()
            object : LlmProfileRepository { override suspend fun load() = owner.profiles() } }
        single<ModelDossierRepository> { val owner = get<DefaultSettingsConfiguration>()
            object : ModelDossierRepository { override suspend fun dossiers() = owner.dossiers() } }
        single<SettingsContributions> { settingsContributions() }
        single<AppContributions> { appContributions() }
        single { RuntimeExtensions(runtimeExtensions()) }
        single { MediaOwnerPolicies(mediaOwnerPolicies()) }
        single<ChatCheckpointStore> { JsonChatRepository(get(), get()) }
        single<ChatPayloadStore> { StoredChatPayloads(get(), get()) }
        single { ChatJournalStore(get(), get(), get(), get()) }
        single<ChatRepository> { get<ChatJournalStore>() }
        single<ChatHistoryCommands> { get<DefaultChatService>() }
        single<RequestPinRepository> { JsonRequestPinRepository(get(), get()) }
        single<UsageLedger> { DefaultUsageLedger(JsonUsageRepository(get(), get()), get(), get(), get()) }
        single { appHttpClient() } onClose { it?.close() }
        single<MediaGenerationGateway> { HttpMediaGenerationGateway(get(), get()) }
        single { DefaultMediaGenerationService(get<SettingsRepository>()::load, get<LlmProfileRepository>()::load,
            get(), get(), get(), get(), applicationScope,
            ownerExists = { owner ->
                val projectId = owner.projectId
                if (projectId == null) get<ChatRepository>().session(owner.sessionId) != null
                else get<MediaOwnerPolicies>().resolve(owner)?.exists(owner) == true
            },
            onTerminal = { owner, operation, media -> get<MediaToolReceiptOwner>().reconcileCompletion(owner, operation, media) },
            authorizeSubmission = { owner, kind ->
                    val projectId = owner.projectId
                    if (projectId == null) {
                        val chats = get<ChatRepository>()
                        val session = chats.session(owner.sessionId)
                        val parentId = session?.researchParentId
                        val root = if (parentId == null) session else chats.session(parentId)
                        root?.mediaTools?.enabled(kind) == true &&
                            session?.pendingRun?.let { it.runId == owner.requestId && !it.stoppedByUser } == true
                    } else {
                        get<MediaOwnerPolicies>().resolve(owner)?.allows(owner, kind) == true
                    }
                }) }
        single<MediaGenerationService> { get<DefaultMediaGenerationService>() }
        single { ResearchSiteIcons(get()) }
        single { io.aequicor.magicpaper.data.ResearchPageReader(get()) }
        single { ResearchSourceAccess(get<io.aequicor.magicpaper.data.ResearchPageReader>()::read) }
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
        single { SkillStore(get(), get(), get()) }
        single<SkillRepository> { get<SkillStore>() }
        single<SkillLibrary> { get<SkillStore>() }
        single<SkillCommands> { get<SkillStore>() }
        single<SkillCatalog> { EmbeddedSkillCatalog() }
        single { SkillInstaller(get<SkillStore>()) }
        packageInstructions?.let { source ->
            single<SkillInstructionRuntime> { DefaultSkillInstructionRuntime(source, get()) }
        }
        single<ProviderToolOutputs> { StoredProviderToolOutputs(get()) }
        single<ProviderToolLoop> { DefaultProviderToolLoop(get(), get(), get()) }
        single<ChatToolSessions> {
            val docs = get<DocRepository>()
            DefaultChatToolSessions(get(), docs::articles, { docs.search(it) }, get<SkillRepository>()::all,
                get(), get<RuntimeQuestionnaireFactory>().create("application-tools",
                    JsonRuntimeQuestionnaireStore(get(), "tool-questionnaires")), get(), get(),
                readResearchPage = get<io.aequicor.magicpaper.data.ResearchPageReader>()::read,
                allowMedia = { captured, kind ->
                    val repository = get<ChatRepository>()
                    val session = repository.session(captured.id)
                    val root = session?.researchParentId?.let { repository.session(it) } ?: session
                    root?.mediaTools?.enabled(kind) == true
                })
        }
        single { val settings = get<SettingsRepository>()
            GatewaySessionRuntime(get(), get(), get(), get(), skillLibrary = get(), packageRuntime = getOrNull(),
                settings = { settings.load() },
                readResearchPage = get<io.aequicor.magicpaper.data.ResearchPageReader>()::read) }
        single { DefaultRequestPinService(get(), get(), applicationScope, get(), get()) }
        single<RequestPinService> { get<DefaultRequestPinService>() }
        single<PluginRegistry> {
            PluginRegistry().register(NotesPlugin(get(), applicationScope)).register(FocusPlugin(get(), applicationScope)).register(CalcPlugin(get(), applicationScope))
                .register(SkillsRepositoryPlugin(get(), get(), get<SkillStore>()))
                .apply { experiencePlugin?.let { register(it(get(), get())) } }
                .apply { featurePlugins().forEach(::register) }
                .apply { platformPlugins.forEach(::register) }
        }
        factory<ChatComponent.Factory>(FeatureFactoryQualifiers.chat) { DefaultChatComponentFactory(get(), get(), get()) }
        factory<SettingsComponent.Factory>(FeatureFactoryQualifiers.settings) { DefaultSettingsComponentFactory(get(), get(), get(), get(), get()) }
        factory<DocsComponent.Factory>(FeatureFactoryQualifiers.docs) { DefaultDocsComponentFactory(get()) }
        factory<PluginsComponent.Factory>(FeatureFactoryQualifiers.plugins) { DefaultPluginsComponentFactory(get()) }
        factory<SkillsComponent.Factory>(FeatureFactoryQualifiers.skills) { DefaultSkillsComponentFactory(get()) }
        single<ChatService> { get<DefaultChatService>() }
        single<SettingsService> { get<DefaultSettingsService>() }
        single<PluginService> { DefaultPluginService(get(), get(), get(), get()) }
        single<ChatBackend> { get<GatewaySessionRuntime>() }
        single { DefaultChatService(get(), get(), get(), get(), get(),
            onOpenSession = { get<NavigationEvents>().navigate(AppRoute.Chat(it)) },
            draftRepository = get(), draftBlobs = get(),
            researchSearch = get(), usage = get(), sourceAccess = get(), sourceBrowser = researchPageBrowser,
            mediaGeneration = get(),
            responseExtensions = responseExtensions()) }
        single { DefaultSettingsService(get(), get(), get(), get(), get(), chatHistory = get(), pluginPreferences = get<PluginService>(),
            skills = get(), skillCommands = get(), modelDirectory = get(), gateway = get(), dossierResearcher = get(),
            openAiSubscription = openAiSubscription, searchConnectionChecker = get(), usage = get(), draftRepository = get(),
            mediaGeneration = get(), mediaStore = get(),
            clearCodingOverrides = { id -> get<RuntimeExtensions>().owners.forEach { it.clearProfileOverrides(id) } },
            onDataChanged = { get<ChatService>().start(); get<RuntimeExtensions>().owners.forEach { it.reload() }; get<PluginService>().start() },
            onProfileSaved = { get<NavigationEvents>().back() },
            clearApplicationData = { erase ->
                get<NavigationEvents>().reset()
                resetting = true
                get<DefaultSettingsService>().prepareForReset()
                resetChat = true
                get<DefaultChatService>().prepareForReset()
                get<RuntimeExtensions>().owners.forEach { resetOwners += it; it.prepareForReset() }
                get<DefaultRequestPinService>().resetForWipe()
                resetPlugins = true
                get<PluginService>().prepareForReset()
                resetSkills = true
                get<SkillCommands>().prepareForReset()
                // Only a user-confirmed erase reaches here, and its confirmation covers evidence nothing can resolve.
                get<RuntimeExtensions>().owners.forEach { it.pauseForReset(discardUnresolvable = true) }
                resetMedia = true
                get<MediaGenerationService>().prepareForReset()
                // The subscription sign-in is configuration: only erasing everything signs out.
                if (erase == ApplicationDataReset.ALL) openAiSubscription?.logout()
                get<RuntimeExtensions>().owners.forEach { it.clearForReset() }
                get<RuntimeQuestionnaireFactory>().clearForReset()
                when (erase) {
                    ApplicationDataReset.ALL -> { persistence.clearOwnedData(); mediaStore.clear(); store.clear() }
                    ApplicationDataReset.SESSIONS -> clearSessionData(persistence, store, mediaStore)
                }
                eraseResetFiles = true
            },
            finishApplicationReset = {
                if (resetting) {
                    resetting = false
                    val resumed = mutableSetOf<RuntimeExtension>()
                    val actions = buildList<suspend () -> Unit> {
                        // Only once no record refers to them, and before any owner resumes work in them. A failure
                        // does not skip the resumes below; the next reset finishes the deletion.
                        if (eraseResetFiles) add { eraseResetFiles = false; get<RuntimeExtensions>().owners.forEach { it.eraseFilesForReset() } }
                        resetOwners.toList().forEach { owner -> add { owner.resumeAfterReset(); resumed += owner; resetOwners -= owner } }
                        if (resetMedia) add { get<MediaGenerationService>().resumeAfterReset(); resetMedia = false }
                        if (resetSkills) add { get<SkillCommands>().finishReset(); resetSkills = false }
                        if (resetChat) add { get<ChatService>().start(); resetChat = false }
                        get<RuntimeExtensions>().owners.forEach { owner -> add { if (owner in resumed) owner.reload() } }
                        if (resetPlugins) add { get<PluginService>().start(); resetPlugins = false }
                        add { get<NavigationEvents>().resetComplete() }
                    }
                    completeRuntimeCleanup(*actions.toTypedArray())
                }
            }) }
        platformDefinitions(applicationScope)
    }
}
