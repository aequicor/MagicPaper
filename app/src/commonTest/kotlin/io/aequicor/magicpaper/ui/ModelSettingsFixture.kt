package io.aequicor.magicpaper.ui

import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.data.storage.*
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
    val chatJournal = InMemoryEventJournal()
    var beforeChatInput: suspend (ChatMachine.Input) -> Unit = {}
    private fun chatPayloads(): ChatPayloadStore {
        val durable = StoredChatPayloads(kv, json, kotlinx.coroutines.Dispatchers.Main)
        return object : ChatPayloadStore by durable {
            override suspend fun save(notebookId: String, inputId: String, input: ChatMachine.Input): ChatInputRef {
                beforeChatInput(input)
                return durable.save(notebookId, inputId, input)
            }
        }
    }
    fun newChatStore(checkpoints: ChatCheckpointStore = chats) = ChatJournalStore(checkpoints, chatJournal,
        chatPayloads(), json, kotlinx.coroutines.Dispatchers.Main)
    val chatStore by lazy { newChatStore() }
    val chatHistory = object : ChatHistoryCommands {
        override suspend fun importNotebooks(sessions: List<ChatSession>) {
            for ((id, notebook) in sessions.groupBy { it.researchChatId }) chatStore.dispatch(id, ChatMachine.Intent.ImportNotebook(notebook))
        }
        override suspend fun unlinkProfile(profileId: String) {
            chatStore.start()
            chatStore.states.value.filterValues { !it.deleted }.keys.forEach { chatStore.dispatch(it, ChatMachine.Intent.UnlinkProfile(profileId)) }
        }
        override suspend fun wipeHistory() { chatStore.wipe() }
    }
    val plugins = DefaultPluginService(PluginRegistry(), kv, InMemoryEventJournal(), json, storageDispatcher = kotlinx.coroutines.Dispatchers.Main)
    val settings = JsonSettingsRepository(kv, json)
    val modelDossiers = JsonModelDossierRepository(kv, json)
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
        modelDossiers.saveDossier(ModelDossier("d", "openai", "gpt-5.4", strengths = "Анализ сложных задач, программирование и работа с документами.", limitations = "Высокое усилие увеличивает время ответа.", rating = 4))
        val docs = EmbeddedDocRepository()
        val bridge = object : ProfileBridge {
            override val supportsFilePicker = false
            override suspend fun export(json: String) = false
            override suspend fun import(): String? = null
        }
    }

    val usage = DefaultUsageLedger(JsonUsageRepository(kv, json), InMemoryEventJournal(), kv, json,
        storageDispatcher = kotlinx.coroutines.Dispatchers.Main.immediate)
    internal val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate)
    val draftRepository = InMemoryDraftRepository()
    val draftBlobs = InMemoryDraftBlobStore()
    private val pinJournal = io.aequicor.magicpaper.data.storage.InMemoryEventJournal()
    fun pins(repo: RequestPinRepository?): RequestPinService? = repo?.let {
        DefaultRequestPinService(it, gateway, scope, pinJournal, json,
            storageDispatcher = scope.coroutineContext[kotlin.coroutines.ContinuationInterceptor] as kotlinx.coroutines.CoroutineDispatcher)
    }
    val bridge = object : ProfileBridge {
        override val supportsFilePicker = false
        override suspend fun export(json: String) = false
        override suspend fun import(): String? = null
    }

    suspend fun prepareChat(requestPinRepository: RequestPinRepository? = null): DefaultChatService {
        seed()
        lateinit var service: DefaultChatService
        service = DefaultChatService(testGatewayRuntime(gateway, search, EmbeddedDocRepository()), chatStore, settings, profiles,
            pins(requestPinRepository), workerDispatcher = kotlinx.coroutines.Dispatchers.Main,
            draftRepository = draftRepository, draftBlobs = draftBlobs,
            onOpenSession = { service.activate(it) })
        service.start()
        service.activate("first")
        return service
    }
    val configuration by lazy { DefaultSettingsConfiguration(kv, InMemoryEventJournal(), kv.secrets, json,
        researcher = DefaultDossierResearcher(gateway, search), dispatcher = kotlinx.coroutines.Dispatchers.Main) }
    suspend fun prepareSettings(): DefaultSettingsService {
        seed()
        return DefaultSettingsService(configuration, chatStore, bridge, kv, json, chatHistory = chatHistory, pluginPreferences = plugins,
            gateway = gateway, dossierResearcher = DefaultDossierResearcher(gateway, search), usage = usage,
            draftRepository = draftRepository).also { it.start() }
    }
    suspend fun prepareSettingsComponent(input: SettingsInput = SettingsInput()): DefaultSettingsComponent {
        val service = prepareSettings()
        val plugins = this.plugins.also { it.start() }
        val lifecycle = LifecycleRegistry().apply { resume() }
        return DefaultSettingsComponentFactory(service, plugins, draftRepository, draftBlobs)
            .create(DefaultComponentContext(lifecycle), input) {} as DefaultSettingsComponent
    }


}
