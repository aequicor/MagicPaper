package io.aequicor.magicpaper.ui

import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.data.storage.*
import io.aequicor.magicpaper.data.planning.*
import io.aequicor.magicpaper.data.docs.EmbeddedDocRepository
import io.aequicor.magicpaper.plugins.PluginRegistry
import kotlinx.serialization.json.*

class ModelSettingsFixture {
    val kv = InMemoryKeyValueStore()
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    val profiles = JsonLlmProfileRepository(kv, json)
    val chats = JsonChatRepository(kv, json)
    val settings = JsonSettingsRepository(kv, json)
    val planning = PlanningStore(JsonPlanningRepository(kv, json))
    val calls = mutableListOf<LlmProfile>()
    val gateway = object : LlmGateway {
        override suspend fun complete(profile: LlmProfile, messages: List<LlmMessage>): String {
            calls += profile
            return """{"strengths":"Работа с кодом и сложными задачами.","limitations":"Длительные ответы при высоком effort.","rating":4}"""
        }
    }
    val search = object : SearchEngine {
        override val provider = SearchProvider.WIKIPEDIA
        override val displayName = "Search"
        override fun isConfigured(settings: AppSettings) = true
        override suspend fun search(query: String, settings: AppSettings, limit: Int) = listOf(SearchHit("Model", "https://example.com/model"))
    }
    suspend fun prepare(codingRuntime: CodingRuntime? = null, codingProjects: CodingProjectRepository? = null): MagicPaperViewModel {
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
        return MagicPaperViewModel(MagicAgent(gateway, search, docs), chats, settings, profiles, docs, PluginRegistry(), bridge, kv, json,
            planning = planning, gateway = gateway, dossierResearcher = DossierResearcher(gateway, search),
            codingRuntime = codingRuntime, codingProjects = codingProjects)
    }
}
