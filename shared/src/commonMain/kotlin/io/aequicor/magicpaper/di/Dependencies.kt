package io.aequicor.magicpaper.di

import io.aequicor.magicpaper.domain.tools.*
import io.aequicor.magicpaper.domain.OrchestrationService
import io.aequicor.magicpaper.data.coding.JsonCodingProjectRepository
import io.aequicor.magicpaper.data.coding.BackgroundCodingProjectRepository
import io.aequicor.magicpaper.data.coding.NoopCodingRuntime
import io.aequicor.magicpaper.data.docs.EmbeddedDocRepository
import io.aequicor.magicpaper.data.llm.AnthropicGateway
import io.aequicor.magicpaper.data.llm.AnthropicModelDirectory
import io.aequicor.magicpaper.data.llm.GoogleGateway
import io.aequicor.magicpaper.data.llm.GoogleModelDirectory
import io.aequicor.magicpaper.data.llm.OpenAiCompatibleGateway
import io.aequicor.magicpaper.data.llm.OpenAiModelDirectory
import io.aequicor.magicpaper.data.llm.RoutingLlmGateway
import io.aequicor.magicpaper.data.llm.RoutingModelDirectory
import io.aequicor.magicpaper.data.planning.JsonPlanningRepository
import io.aequicor.magicpaper.data.planning.PlanningStore
import io.aequicor.magicpaper.data.search.CompositeSearchEngine
import io.aequicor.magicpaper.data.search.GoogleSearchEngine
import io.aequicor.magicpaper.data.search.QueritSearchEngine
import io.aequicor.magicpaper.data.search.WikipediaSearchEngine
import io.aequicor.magicpaper.data.skills.EmbeddedSkillCatalog
import io.aequicor.magicpaper.data.skills.JsonSkillRepository
import io.aequicor.magicpaper.data.skills.SkillStore
import io.aequicor.magicpaper.data.storage.JsonChatRepository
import io.aequicor.magicpaper.data.storage.JsonRequestPinRepository
import io.aequicor.magicpaper.data.storage.JsonLlmProfileRepository
import io.aequicor.magicpaper.data.storage.JsonSettingsRepository
import io.aequicor.magicpaper.data.storage.KeyValueStore
import io.aequicor.magicpaper.data.storage.NoopFilePicker
import io.aequicor.magicpaper.domain.CodingProjectRepository
import io.aequicor.magicpaper.domain.CodingRuntime
import io.aequicor.magicpaper.domain.DossierResearcher
import io.aequicor.magicpaper.domain.FilePicker
import io.aequicor.magicpaper.domain.LlmMilestoneVerifier
import io.aequicor.magicpaper.domain.MagicAgent
import io.aequicor.magicpaper.domain.OpenAiSubscriptionService
import io.aequicor.magicpaper.domain.PlanComposer
import io.aequicor.magicpaper.domain.PlanRunner
import io.aequicor.magicpaper.domain.ProfileBridge
import io.aequicor.magicpaper.domain.ProjectDirPicker
import io.aequicor.magicpaper.domain.ProviderType
import io.aequicor.magicpaper.domain.SkillEducator
import io.aequicor.magicpaper.domain.SkillInstaller
import io.aequicor.magicpaper.plugins.PluginRegistry
import io.aequicor.magicpaper.plugins.builtin.CalcPlugin
import io.aequicor.magicpaper.plugins.builtin.CodingPlanningPlugin
import io.aequicor.magicpaper.plugins.builtin.FocusPlugin
import io.aequicor.magicpaper.plugins.builtin.NotesPlugin
import io.aequicor.magicpaper.plugins.builtin.SelfEducationPlugin
import io.aequicor.magicpaper.plugins.builtin.SkillsRepositoryPlugin
import io.aequicor.magicpaper.ui.MagicPaperViewModel
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import kotlinx.serialization.json.Json

/** Корень композиции: всё приложение собирается в одном месте. */
class MagicPaperDependencies(val viewModel: MagicPaperViewModel, val planning: io.aequicor.magicpaper.domain.PlanningExecutionService)

/** Платформы поставляют хранилище и мост профиля. */
expect fun createMagicPaperDependencies(): MagicPaperDependencies

/**
 * Общий HTTP-клиент приложения.
 *
 * Движок CIO (jvm/android) по умолчанию обрывает любой запрос через 15 секунд
 * (`CIOEngineConfig.requestTimeout`), если запрос не несёт `HttpTimeoutCapability`:
 * медленные рассуждающие модели (Qwen/DashScope, DeepSeek-R1…) не укладывались
 * в этот потолок и падали с «Request timeout has expired … request_timeout=unknown ms»,
 * хотя в профиле стоял больший `timeoutSeconds`.
 *
 * Установленный `HttpTimeout` выставляет capability на каждый запрос — движок свой
 * 15-секундный потолок отключает, а ограничителем становится значение плагина.
 * 30 с — разумный предел для коротких вызовов (поиск, каталоги); LLM-транспорт
 * (`postJson`/`getText`) снимает его per-request и живёт по `timeoutSeconds`
 * профиля через `withTimeout` (0 = без ограничения, как и раньше).
 */
internal fun appHttpClient(): HttpClient = HttpClient {
    install(HttpTimeout) {
        requestTimeoutMillis = 30_000
        connectTimeoutMillis = 10_000
    }
}

internal val appJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

internal fun buildDependencies(
    store: KeyValueStore,
    bridge: ProfileBridge,
    codingRuntime: CodingRuntime? = null,
    codingProjects: CodingProjectRepository? = null,
    dirPicker: ProjectDirPicker? = null,
    filePicker: FilePicker = NoopFilePicker,
    openAiSubscription: OpenAiSubscriptionService? = null,
    planningWorkspace: io.aequicor.magicpaper.domain.PlanningWorkspace = io.aequicor.magicpaper.domain.LocalPlanningWorkspace(),
    integrationChecks: io.aequicor.magicpaper.domain.SessionIntegrationCheckRunner? = null,
    platformPlugins: List<io.aequicor.magicpaper.plugins.MagicPlugin> = emptyList(),
    packageInstructions: io.aequicor.magicpaper.domain.SkillInstructionSource? = null,
    experiencePlugin: ((io.aequicor.magicpaper.domain.LlmGateway, io.aequicor.magicpaper.domain.LlmProfileRepository) -> io.aequicor.magicpaper.plugins.MagicPlugin)? = null,
): MagicPaperDependencies {
    val json = appJson
    val usageLedger = io.aequicor.magicpaper.domain.UsageLedger(io.aequicor.magicpaper.data.storage.JsonUsageRepository(store, json))
    val settingsRepo = JsonSettingsRepository(store, json)
    val profileRepo = JsonLlmProfileRepository(store, json)
    val toolHost = ToolHost(StoredToolReceipts(store), io.aequicor.magicpaper.domain.RuntimeQuestionnaires(
        io.aequicor.magicpaper.data.coding.JsonRuntimeQuestionnaireStore(store, "tool-questionnaires")))
    val organisms = codingProjects?.let { io.aequicor.magicpaper.domain.SessionOrganismService(
        io.aequicor.magicpaper.data.coding.SessionOrganismStore(store), it, settingsRepo,
        sourceSnapshot = { project -> planningWorkspace.verificationSnapshot(project.path) }) }
    val sessionTree = organisms?.let { io.aequicor.magicpaper.domain.SessionTreeRuntime(it, codingProjects!!, profileRepo, settingsRepo, planningWorkspace = planningWorkspace) }
    organisms?.integrationWorkspaces = integrationChecks?.let { io.aequicor.magicpaper.domain.SessionIntegrationWorkspaces(planningWorkspace, it) { toolHost.knownSecrets() } }
    val runtime = codingRuntime?.let { io.aequicor.magicpaper.data.coding.MeteredCodingRuntime(ToolEnabledCodingRuntime(it, toolHost, sessionTree), usageLedger) }
    sessionTree?.runtime = runtime
    if (runtime != null && organisms != null) {
        val recovery = io.aequicor.magicpaper.domain.PlanRetryNativeRecovery(runtime, toolHost.receipts) { toolHost.knownSecrets() }
        organisms.reconcilePlanRetry = recovery::reconcile
    }
    sessionTree?.cancelQuestions = { sessionId ->
        try { toolHost.questions.revoke(sessionId) }
        finally { organisms?.reconcileIntegrationsForSession(sessionId) }
    }
    toolHost.contextDefaults = { context ->
        val organism = organisms?.store?.organisms?.value?.values?.firstOrNull {
            it.projectId == context.projectId && context.ownerSessionId in it.sessions
        }
        val node = organism?.sessions?.get(context.ownerSessionId)
        if (node == null) context else context.copy(organismId = organism.id, runtimeGeneration = node.generation,
            planningRulesSnapshot = node.rules)
    }
    toolHost.authorizeReceipt = { context, definition ->
        val organism = organisms?.store?.organisms?.value?.values?.firstOrNull {
            it.projectId == context.projectId && context.ownerSessionId in it.sessions
        }
        if (organism != null) {
            val node = organisms!!.store.get(organism.id).sessions.getValue(context.ownerSessionId)
            require(node.generation == context.runtimeGeneration && definition.allowsAuthorityMode(context, node.mode)) { "Полномочия запуска отозваны" }
        }
    }
    toolHost.authorizeTool = { context, definition ->
        toolHost.authorizeReceipt(context, definition)
        val organism = organisms?.store?.organisms?.value?.values?.firstOrNull {
            it.projectId == context.projectId && context.ownerSessionId in it.sessions
        }
        // Per-command CAS belongs to dispatch; a long-lived tool session keeps only its generation fence.
        if (organism != null && definition.mutating && definition.id != "immunity.signal")
            organisms!!.store.check(organisms.authority(context.copy(stateVersion = null), organism))
    }
    toolHost.unknownOutcome = { context, receipt ->
        try {
            val session = codingProjects?.sessions(context.projectId)?.firstOrNull { it.id == context.ownerSessionId }
            if (session != null && organisms != null) {
                val organism = organisms.ensure(session)
                organisms.project(organisms.store.quarantine(organism.id, session.id, context.runtimeGeneration,
                    receipt.operationId.ifBlank { receipt.id }, "Неизвестный исход ${receipt.toolId}: ${receipt.error.ifBlank { receipt.result.toString() }}"))
            }
        } finally {
            // A native provider can execute file/shell tools without re-entering ToolExecutor.
            // Revoke that running connection as well; its owner joins/reconciles asynchronously.
            codingRuntime?.abort(context.sessionId)
        }
    }
    val client = appHttpClient()
    val chatRepo = JsonChatRepository(store, json)
    val docs = EmbeddedDocRepository()
    val search = CompositeSearchEngine(
        listOf(
            WikipediaSearchEngine(client, json, usageLedger),
            QueritSearchEngine(client, json, usageLedger),
            GoogleSearchEngine(client, json, usageLedger),
        )
    )
    // Шлюз-роутер: формат запроса выбирается по типу провайдера в профиле.
    // Новый провайдер = новый транспорт + запись в карте (OCP).
    val gateway = RoutingLlmGateway(
        buildMap {
            openAiSubscription?.let { put(ProviderType.OPENAI_SUBSCRIPTION, it) }
            putAll(
                mapOf(
                    ProviderType.OPENAI_COMPATIBLE to OpenAiCompatibleGateway(client, json),
                    ProviderType.OPENROUTER to OpenAiCompatibleGateway(client, json),
                    ProviderType.ANTHROPIC to AnthropicGateway(client, json),
                    ProviderType.GOOGLE to GoogleGateway(client, json),
                ),
            )
        },
        usage = usageLedger,
    )
    // Каталог моделей у провайдеров — тем же роутером.
    val modelDirectory = RoutingModelDirectory(
        buildMap {
            openAiSubscription?.let { put(ProviderType.OPENAI_SUBSCRIPTION, it) }
            putAll(
                mapOf(
                    ProviderType.OPENAI_COMPATIBLE to OpenAiModelDirectory(client, json),
                    // OpenRouter — тот же OpenAI-совместимый /models, но с объявлениями
                    // об уровнях мышления (supported_parameters / reasoning).
                    ProviderType.OPENROUTER to OpenAiModelDirectory(client, json),
                    ProviderType.ANTHROPIC to AnthropicModelDirectory(client, json),
                    ProviderType.GOOGLE to GoogleModelDirectory(client, json),
                ),
            )
        },
    )
    // Система навыков: библиотека (порт агента) и каталог (лавка) — одно хранилище,
    // за которым наблюдают оба плагина.
    val skillStore = SkillStore(JsonSkillRepository(store, json))
    val installer = SkillInstaller(skillStore)
    val agent = MagicAgent(gateway, search, docs, skillLibrary = skillStore,
        packageRuntime = packageInstructions?.let { io.aequicor.magicpaper.domain.SkillInstructionRuntime(it, gateway) })
    // Планирование: свой стор поверх того же хранилища (как у навыков);
    // исполнитель — поверх кодинг-рантайма, проверка — моделью через шлюз.
    val planningStore = PlanningStore(JsonPlanningRepository(store, json))
    val acceptanceChecks = io.aequicor.magicpaper.domain.AcceptanceChecks()
    val planComposer = PlanComposer(gateway, json, search,
        io.aequicor.magicpaper.domain.RuntimePlanningGateway(runtime ?: NoopCodingRuntime),
        projectLookup = { id -> codingProjects?.all()?.firstOrNull { it.id == id } }, acceptanceChecks = acceptanceChecks, toolHost = toolHost,
        retryLimit = { settingsRepo.load().agentLimits.retries })
    val planningExecution = io.aequicor.magicpaper.domain.PlanningExecutionService(
        planningStore, runtime ?: NoopCodingRuntime, codingProjects, profileRepo, settingsRepo,
        LlmMilestoneVerifier(gateway, json, retryLimit = { settingsRepo.load().agentLimits.retries }), planningWorkspace, acceptanceChecks = acceptanceChecks,
    )
    val planner = CodingPlanningPlugin(
        store = planningStore,
        composer = planComposer,
        researcher = DossierResearcher(gateway, search, json),
        execution = planningExecution,
        runtime = runtime ?: NoopCodingRuntime,
        projectsRepo = codingProjects,
        profileRepo = profileRepo,
        settingsRepo = settingsRepo,
    )
    val registry = PluginRegistry()
        .register(NotesPlugin)
        .register(FocusPlugin)
        .register(CalcPlugin)
        .register(SkillsRepositoryPlugin(EmbeddedSkillCatalog(), installer, skillStore))
        .apply { experiencePlugin?.let { register(it(gateway, profileRepo)) } }
        .register(planner)
    platformPlugins.forEach(registry::register)
    val planningChat = codingProjects?.let { OrchestrationService(planningStore, planningExecution, it, profileRepo, settingsRepo, planComposer, gateway,
        toolHost = toolHost, organisms = organisms, sessionTree = sessionTree) }
    toolHost.search = { context, query ->
        val saved = settingsRepo.load()
        val plan = context.planId?.let { planningStore.planFor(it) }
        val effectiveSettings = if (plan == null) saved else saved.copy(searchProvider = plan.searchProvider)
        val result = search.searchWithDiagnostics(query, effectiveSettings, 5)
        if (result.hits.isEmpty()) {
            val diagnosis = result.issues.ifEmpty { listOf("Поиск не нашёл результатов по запросу «$query».") }
            error(diagnosis.joinToString("; "))
        }
        kotlinx.serialization.json.buildJsonArray { result.hits.forEach { hit -> add(kotlinx.serialization.json.buildJsonObject {
            put("title", kotlinx.serialization.json.JsonPrimitive(hit.title))
            put("snippet", kotlinx.serialization.json.JsonPrimitive(hit.snippet))
            put("url", kotlinx.serialization.json.JsonPrimitive(hit.url))
        }) } }
    }
    val viewModel = MagicPaperViewModel(
        agent = agent,
        chats = chatRepo,
        settingsRepo = settingsRepo,
        profileRepo = profileRepo,
        docs = docs,
        registry = registry,
        bridge = bridge,
        store = store,
        json = json,
        skills = skillStore,
        planning = planningStore,
        planningChat = planningChat,
        codingRuntime = runtime,
        codingProjects = codingProjects,
        dirPicker = dirPicker,
        modelDirectory = modelDirectory,
        gateway = gateway,
        dossierResearcher = DossierResearcher(gateway, search, json),
        searchConnectionChecker = io.aequicor.magicpaper.data.search.HttpSearchConnectionChecker(client, json, usageLedger),
        filePicker = filePicker,
        openAiSubscription = openAiSubscription,
        requestPinRepository = JsonRequestPinRepository(store, json),
        usage = usageLedger,
    )
    planningChat?.bootstrap()
    planningExecution.bootstrap()
    return MagicPaperDependencies(viewModel, planningExecution)
}

/**
 * Хранилище проектов кодинг-агента (веб/десктоп): строится поверх того же
 * key-value хранилища, что и чаты. Платформы без кодинг-бэкенда могут не
 * передавать его в [buildDependencies].
 */
fun codingProjectRepository(store: KeyValueStore, json: Json, runtime: CodingRuntime? = null): CodingProjectRepository =
    BackgroundCodingProjectRepository(JsonCodingProjectRepository(store, json, initialContext = { session, project ->
        val profiles = JsonLlmProfileRepository(store, json).load()
        val settings = JsonSettingsRepository(store, json).load()
        val profile = if (session.planningMode) session.modelSelection?.let {
            io.aequicor.magicpaper.domain.ProfileResolver.selection(it, profiles)
        } ?: io.aequicor.magicpaper.domain.ProfileResolver.resolve(null as io.aequicor.magicpaper.domain.ChatSession?, settings, profiles)
        else io.aequicor.magicpaper.domain.ProfileResolver.coding(session, project, settings, profiles)
        runtime?.sessionContext(project, session, profile)
    }) { session, project ->
        val profiles = JsonLlmProfileRepository(store, json).load()
        val settings = JsonSettingsRepository(store, json).load()
        io.aequicor.magicpaper.domain.legacyCodingEngine(io.aequicor.magicpaper.domain.ProfileResolver.coding(session, project, settings, profiles))
    })
