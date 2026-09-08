package io.aequicor.magicpaper.data.llm

import io.aequicor.magicpaper.data.search.*
import io.aequicor.magicpaper.domain.*
import io.ktor.client.HttpClient
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Opt-in only: uses existing MagicPaper connections, saves no settings or descriptions. */
class ModelDescriptionLiveIntegrationTest {
    @Test fun configuredSearchAndDefaultModelProduceDescriptionWithSources() = probe(false)
    @Test fun subscriptionProducesDescriptionWithSources() = probe(true)

    private fun probe(useSubscription: Boolean) = runBlocking {
        if (System.getenv("MAGICPAPER_DESCRIPTION_IT") != "true") return@runBlocking
        val root = File(System.getProperty("user.home"), ".MagicPaper")
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val settings = json.decodeFromString(AppSettings.serializer(), root.resolve("settings.json").readText())
        val profiles = json.decodeFromString(ListSerializer(LlmProfile.serializer()), root.resolve("llm_profiles.json").readText()).map { it.migrateModelLibrary() }
        val default = requireNotNull(ProfileResolver.resolve(null as ChatSession?, settings, profiles))
        val judge = if (useSubscription) requireNotNull(profiles.firstOrNull { it.provider == ProviderType.OPENAI_SUBSCRIPTION }).forModel() else default
        val client = HttpClient()
        val codex = CodexAppServerOpenAiSubscription(json)
        try {
            val gateway = RoutingLlmGateway(mapOf(
                ProviderType.OPENAI_COMPATIBLE to OpenAiCompatibleGateway(client, json),
                ProviderType.OPENROUTER to OpenAiCompatibleGateway(client, json),
                ProviderType.OPENAI_SUBSCRIPTION to codex,
                ProviderType.ANTHROPIC to AnthropicGateway(client, json),
                ProviderType.GOOGLE to GoogleGateway(client, json),
            ))
            val search = CompositeSearchEngine(listOf(WikipediaSearchEngine(client, json), QueritSearchEngine(client, json), GoogleSearchEngine(client, json)))
            val researcher = DossierResearcher(gateway, search, json)
            val output = File("build/reports/models/live-description-${if (useSubscription) "subscription" else "default"}.txt").apply { parentFile.mkdirs(); writeText("") }
            val result = researcher.research(default, judge, settings)
            // Only public model descriptions and source URLs; never connection credentials.
            output.appendText("${judge.shortLabel}\n${result.source}\n${result.strengths}\n${result.limitations}\n${result.references.joinToString("\n")}\n\n")
            assertEquals(DossierSource.WEB, result.source, result.note)
            assertTrue(result.references.isNotEmpty())
        } finally { client.close(); codex.close() }
    }
}
