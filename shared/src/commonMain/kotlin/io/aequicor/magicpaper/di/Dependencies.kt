package io.aequicor.magicpaper.di

import io.aequicor.magicpaper.domain.PlanningChatService
import io.aequicor.magicpaper.data.coding.JsonCodingProjectRepository
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
import kotlinx.serialization.json.Json

/** Корень композиции: всё приложение собирается в одном месте. */
class MagicPaperDependencies(val viewModel: MagicPaperViewModel, val planning: io.aequicor.magicpaper.domain.PlanningExecutionService)

/** Платформы поставляют хранилище и мост профиля. */
expect fun createMagicPaperDependencies(): MagicPaperDependencies

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
): MagicPaperDependencies {
    val json = appJson
    val client = HttpClient()
    val settingsRepo = JsonSettingsRepository(store, json)
    val chatRepo = JsonChatRepository(store, json)
    val profileRepo = JsonLlmProfileRepository(store, json)
    val docs = EmbeddedDocRepository()
    val search = CompositeSearchEngine(
        listOf(
            WikipediaSearchEngine(client, json),
            QueritSearchEngine(client, json),
            GoogleSearchEngine(client, json),
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
    val agent = MagicAgent(gateway, search, docs, skillLibrary = skillStore)
    // Планирование: свой стор поверх того же хранилища (как у навыков);
    // исполнитель — поверх кодинг-рантайма, проверка — моделью через шлюз.
    val planningStore = PlanningStore(JsonPlanningRepository(store, json))
    val planningExecution = io.aequicor.magicpaper.domain.PlanningExecutionService(
        planningStore, codingRuntime ?: NoopCodingRuntime, codingProjects, profileRepo, settingsRepo,
        LlmMilestoneVerifier(gateway, json), planningWorkspace,
    )
    val planner = CodingPlanningPlugin(
        store = planningStore,
        composer = PlanComposer(gateway, json, search),
        researcher = DossierResearcher(gateway, search, json),
        execution = planningExecution,
        runtime = codingRuntime ?: NoopCodingRuntime,
        projectsRepo = codingProjects,
        profileRepo = profileRepo,
        settingsRepo = settingsRepo,
    )
    val registry = PluginRegistry()
        .register(NotesPlugin)
        .register(FocusPlugin)
        .register(CalcPlugin)
        .register(SkillsRepositoryPlugin(EmbeddedSkillCatalog(), installer, skillStore))
        .register(SelfEducationPlugin(SkillEducator(gateway, json), installer, skillStore, chatRepo, settingsRepo, profileRepo))
        .register(planner)
    val planningChat = codingProjects?.let { PlanningChatService(planningStore, planningExecution, it, profileRepo, settingsRepo, PlanComposer(gateway, json, search), gateway) }
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
        codingRuntime = codingRuntime,
        codingProjects = codingProjects,
        dirPicker = dirPicker,
        modelDirectory = modelDirectory,
        gateway = gateway,
        dossierResearcher = DossierResearcher(gateway, search, json),
        searchConnectionChecker = io.aequicor.magicpaper.data.search.HttpSearchConnectionChecker(client, json),
        filePicker = filePicker,
        openAiSubscription = openAiSubscription,
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
fun codingProjectRepository(store: KeyValueStore, json: Json): CodingProjectRepository =
    JsonCodingProjectRepository(store, json) { session, project ->
        val profiles = JsonLlmProfileRepository(store, json).load()
        val settings = JsonSettingsRepository(store, json).load()
        io.aequicor.magicpaper.domain.legacyCodingEngine(io.aequicor.magicpaper.domain.ProfileResolver.coding(session, project, settings, profiles))
    }
