package io.aequicor.magicpaper.di

import io.aequicor.magicpaper.data.docs.EmbeddedDocRepository
import io.aequicor.magicpaper.data.llm.OpenAiCompatibleGateway
import io.aequicor.magicpaper.data.search.CompositeSearchEngine
import io.aequicor.magicpaper.data.search.GoogleSearchEngine
import io.aequicor.magicpaper.data.search.QueritSearchEngine
import io.aequicor.magicpaper.data.search.WikipediaSearchEngine
import io.aequicor.magicpaper.data.storage.JsonChatRepository
import io.aequicor.magicpaper.data.storage.JsonSettingsRepository
import io.aequicor.magicpaper.data.storage.KeyValueStore
import io.aequicor.magicpaper.domain.MagicAgent
import io.aequicor.magicpaper.domain.ProfileBridge
import io.aequicor.magicpaper.plugins.PluginRegistry
import io.aequicor.magicpaper.plugins.builtin.CalcPlugin
import io.aequicor.magicpaper.plugins.builtin.FocusPlugin
import io.aequicor.magicpaper.plugins.builtin.NotesPlugin
import io.aequicor.magicpaper.ui.MagicPaperViewModel
import io.ktor.client.HttpClient
import kotlinx.serialization.json.Json

/** Корень композиции: всё приложение собирается в одном месте. */
class MagicPaperDependencies(val viewModel: MagicPaperViewModel)

/** Платформы поставляют хранилище и мост профиля. */
expect fun createMagicPaperDependencies(): MagicPaperDependencies

internal val appJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

internal fun buildDependencies(store: KeyValueStore, bridge: ProfileBridge): MagicPaperDependencies {
    val json = appJson
    val client = HttpClient()
    val settingsRepo = JsonSettingsRepository(store, json)
    val chatRepo = JsonChatRepository(store, json)
    val docs = EmbeddedDocRepository()
    val search = CompositeSearchEngine(
        listOf(
            WikipediaSearchEngine(client, json),
            QueritSearchEngine(client, json),
            GoogleSearchEngine(client, json),
        )
    )
    val gateway = OpenAiCompatibleGateway(client, json)
    val agent = MagicAgent(gateway, search, docs)
    val registry = PluginRegistry()
        .register(NotesPlugin)
        .register(FocusPlugin)
        .register(CalcPlugin)
    val viewModel = MagicPaperViewModel(
        agent = agent,
        chats = chatRepo,
        settingsRepo = settingsRepo,
        docs = docs,
        registry = registry,
        bridge = bridge,
        store = store,
        json = json,
    )
    return MagicPaperDependencies(viewModel)
}
