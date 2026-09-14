package io.aequicor.magicpaper.ui

import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.data.storage.*
import io.aequicor.magicpaper.data.planning.*
import io.aequicor.magicpaper.data.docs.EmbeddedDocRepository
import io.aequicor.magicpaper.plugins.PluginRegistry
import kotlinx.serialization.json.*
import kotlinx.coroutines.launch
import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.resume

class ModelSettingsFixture {
    val kv = InMemoryKeyValueStore()
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    val profiles = JsonLlmProfileRepository(kv, json)
    val chats = JsonChatRepository(kv, json)
    val settings = JsonSettingsRepository(kv, json)
    val planning = PlanningStore(JsonPlanningRepository(kv, json))
    val calls = mutableListOf<LlmProfile>()
    var gatewayFailure: Exception? = null
    var searchHits = listOf(SearchHit("Model", "https://example.com/model", "Current model capabilities"))
    val gateway = object : LlmGateway {
        override suspend fun complete(profile: LlmProfile, messages: List<LlmMessage>): String {
            calls += profile
            gatewayFailure?.let { throw it }
            if (messages.firstOrNull()?.content?.contains("\"newRequest\"") == true)
                return """{"summary":"Краткий запрос","newRequest":true}"""
            return """{"strengths":"Работа с кодом и сложными задачами.","limitations":"Длительные ответы при высоком effort.","rating":4}"""
        }
    }
    val search = object : SearchEngine {
        override val provider = SearchProvider.WIKIPEDIA
        override val displayName = "Search"
        override fun isConfigured(settings: AppSettings) = true
        override suspend fun search(query: String, settings: AppSettings, limit: Int) = searchHits
    }
    private var seeded = false
    suspend fun seed() {
        if (seeded) return
        seeded = true
        val declaration = DeclaredReasoning(efforts = setOf(ReasoningEffort.LOW, ReasoningEffort.MEDIUM, ReasoningEffort.HIGH, ReasoningEffort.XHIGH))
        val p = LlmProfile("openai", "OpenAI", baseUrl = "https://example.com/v1", modelId = "gpt-5.4",
            modelLibraryVersion = 1, favoriteModels = listOf("gpt-5.4"),
            modelCatalog = listOf(ProviderModel("gpt-5.4", "GPT-5.4", contextWindow = 200000, maxOutputTokens = 32000, reasoning = declaration)),
            variants = listOf(ModelVariant("variant:precise", "GPT-5.4 · точные ответы", "gpt-5.4", AdvancedLlmOptions(temperature = .2))))
        profiles.save(p)
        profiles.save(LlmProfile("anthropic", "Anthropic", baseUrl = "https://example.com", modelId = "claude-sonnet-4-6", provider = ProviderType.ANTHROPIC,
            favoriteModels = listOf("claude-sonnet-4-6"), modelLibraryVersion = 1,
            modelCatalog = listOf(ProviderModel("claude-sonnet-4-6", "Claude Sonnet", contextWindow = 200000))))
        settings.save(AppSettings(activeLlmProfileId = "openai", defaultModel = ModelSelection("openai", "gpt-5.4"), onboardingDone = true))
        chats.save(ChatSession("first", "Первый", 1, 1, modelSelection = ModelSelection("openai", "gpt-5.4")))
        planning.saveDossier(ModelDossier("d", "openai", "gpt-5.4", strengths = "Анализ сложных задач, программирование и работа с документами.", limitations = "Высокое усилие увеличивает время ответа.", rating = 4))
        val docs = EmbeddedDocRepository()
        val bridge = object : ProfileBridge {
            override val supportsFilePicker = false
            override suspend fun export(json: String) = false
            override suspend fun import(): String? = null
        }
    }

    val usage = DefaultUsageLedger(JsonUsageRepository(kv, json))
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate)
    val draftRepository = InMemoryDraftRepository()
    val draftBlobs = InMemoryDraftBlobStore()
    fun pins(repo: RequestPinRepository?): RequestPinService? = repo?.let { DefaultRequestPinService(it, gateway, scope, json) }
    val bridge = object : ProfileBridge {
        override val supportsFilePicker = false
        override suspend fun export(json: String) = false
        override suspend fun import(): String? = null
    }

    suspend fun prepareCoding(codingRuntime: CodingRuntime? = null, codingProjects: CodingProjectRepository? = null,
        requestPinRepository: RequestPinRepository? = null,
        workerDispatcher: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.Main,
        activate: Boolean = true): DefaultCodingService {
        seed()
        lateinit var service: DefaultCodingService
        service = DefaultCodingService(settings, profiles, kv, json, codingRuntime, codingProjects,
            gateway = gateway, requestPins = pins(requestPinRepository), usage = usage, workerDispatcher = workerDispatcher,
            draftRepository = draftRepository, draftBlobs = draftBlobs,
            onOpenSession = { project, session -> scope.launch { service.activate(project, session) } })
        service.start()
        if (activate) {
            val project = service.state.value.coding.projects.firstOrNull()
            service.activate(project?.id, service.state.value.coding.sessions.firstOrNull { it.session.projectId == project?.id }?.session?.id)
        }
        return service
    }
    suspend fun prepareChat(requestPinRepository: RequestPinRepository? = null): DefaultChatService {
        seed()
        lateinit var service: DefaultChatService
        service = DefaultChatService(GatewaySessionRuntime(gateway, search, EmbeddedDocRepository()), chats, settings, profiles,
            pins(requestPinRepository), workerDispatcher = kotlinx.coroutines.Dispatchers.Main,
            draftRepository = draftRepository, draftBlobs = draftBlobs,
            onOpenSession = { service.activate(it) })
        service.start()
        service.activate("first")
        return service
    }
    suspend fun prepareSettings(): DefaultSettingsService {
        seed()
        return DefaultSettingsService(settings, profiles, chats, bridge, kv, json, planning = planning,
            gateway = gateway, dossierResearcher = DefaultDossierResearcher(gateway, search), usage = usage,
            draftRepository = draftRepository).also { it.start() }
    }
    suspend fun prepareSettingsComponent(input: SettingsInput = SettingsInput()): DefaultSettingsComponent {
        val service = prepareSettings()
        val coding = prepareCoding()
        val plugins = DefaultPluginService(PluginRegistry(), settings).also { it.start() }
        val lifecycle = LifecycleRegistry().apply { resume() }
        return DefaultSettingsComponentFactory(service, coding, plugins, draftRepository, draftBlobs)
            .create(DefaultComponentContext(lifecycle), input) {} as DefaultSettingsComponent
    }
    fun prepareCodingComponent(service: DefaultCodingService, input: CodingInput = CodingInput(
        service.state.value.coding.currentSession?.session?.projectId, service.state.value.coding.currentSession?.session?.id)): DefaultCodingComponent {
        val lifecycle = LifecycleRegistry().apply { resume() }
        return DefaultCodingComponentFactory(service, NoopFilePicker)
            .create(DefaultComponentContext(lifecycle), input) {} as DefaultCodingComponent
    }

}
