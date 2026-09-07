package io.aequicor.magicpaper.domain

import io.aequicor.magicpaper.data.coding.PiModelOptions
import io.aequicor.magicpaper.data.coding.PiModelsConfig
import io.aequicor.magicpaper.data.llm.LlmPayloads
import kotlinx.serialization.json.*
import kotlin.test.*

class ModelLibraryTest {
    private val fact = ProviderModel("m", contextWindow = 200000, maxOutputTokens = 50000,
        defaultParameters = mapOf("temperature" to JsonPrimitive(.9), "top_p" to JsonPrimitive(.8)),
        supportedParameters = setOf("temperature", "top_p", "max_tokens"),
        reasoning = DeclaredReasoning(efforts = setOf(ReasoningEffort.LOW, ReasoningEffort.HIGH)))
    private val source = LlmProfile("p", "Provider", baseUrl = "https://test/v1", modelId = "operational",
        favoriteModels = listOf("m"), modelCatalog = listOf(fact), modelLibraryVersion = 1,
        advanced = AdvancedLlmOptions(temperature = .1, contextLimit = 32000))
    private val custom = ModelVariant("variant:1", "Precise", "m", AdvancedLlmOptions(temperature = .2, topP = .7, maxTokens = 7000))

    @Test fun favoritesContainOnlyMarkedModelsAndVariants() {
        val p = source.copy(variants = listOf(custom), codingModelId = "not-favorite")
        assertEquals(listOf("m", custom.id), p.displayModels)
        assertTrue(p.isFavoriteModel(custom.id))
        assertEquals(listOf(custom.id), p.withFavoriteModel("m").displayModels)
    }

    @Test fun ordinaryFavoriteUsesProviderDefaultsAndDeclaredContext() {
        val request = source.forModel("m")
        assertEquals(.9, request.advanced.temperature)
        assertEquals(.8, request.advanced.topP)
        assertEquals(200000, request.advanced.contextLimit)
        assertFalse(request.advanced.sendMaxTokens)
        assertFalse("max_tokens" in LlmPayloads.openAi(request, emptyList()))
        assertNull(source.forModel("unknown").advanced.temperature)
    }

    @Test fun forkKeepsIdentityButSendsProviderModelAndCustomOptions() {
        val p = source.copy(variants = listOf(custom))
        val request = p.forModel(custom.id, EffortSelection.of(ReasoningEffort.HIGH))
        assertEquals("m", request.modelId)
        assertEquals(custom.id, request.selectionKey)
        assertEquals(custom.options, request.advanced)
        assertEquals(request, request.forCoding())
        assertEquals(setOf(ReasoningEffort.LOW, ReasoningEffort.HIGH), ModelDefaults.capability(request).selectableLevels.toSet())
        assertEquals(7000, PiModelsConfig.maxTokens(request))
        assertEquals(7000, PiModelOptions.parameters(request)["max_tokens"]?.jsonPrimitive?.int)
        assertEquals(ReasoningEffort.HIGH, request.effortSelectionFor().level)
        assertEquals(.9, p.forModel("m").advanced.temperature)
    }

    @Test fun chatEffortAndModelNeverChangeOperationalDefaultOrOtherChat() {
        val profiles = listOf(source.copy(variants = listOf(custom)))
        val settings = AppSettings(defaultModel = ModelSelection("p", custom.id, EffortSelection.of(ReasoningEffort.HIGH)))
        val first = ChatSession("1", "A", 0, 0, modelSelection = ModelSelection("p", "m", EffortSelection.of(ReasoningEffort.LOW)))
        val second = first.copy(id = "2", modelSelection = first.modelSelection!!.copy(effort = EffortSelection.of(ReasoningEffort.HIGH)))
        assertEquals(ReasoningEffort.LOW, ProfileResolver.resolve(first, settings, profiles)?.effort?.level)
        assertEquals(ReasoningEffort.HIGH, ProfileResolver.resolve(second, settings, profiles)?.effort?.level)
        assertEquals(custom.id, ProfileResolver.resolve(null as ChatSession?, settings, profiles)?.selectionKey)
        assertEquals(.1, source.advanced.temperature)
    }

    @Test fun projectDefaultAndSessionOverrideAreIndependent() {
        val project = CodingProject("p", "P", "/tmp", 0, modelSelection = ModelSelection("p", "m", EffortSelection.of(ReasoningEffort.LOW)))
        val session = CodingSession("s", "p", "S", 0)
        assertEquals(ReasoningEffort.LOW, ProfileResolver.coding(session, project, AppSettings(), listOf(source))?.effort?.level)
        assertEquals(ReasoningEffort.HIGH, ProfileResolver.coding(session.copy(modelSelection = project.modelSelection!!.copy(effort = EffortSelection.of(ReasoningEffort.HIGH))), project, AppSettings(), listOf(source))?.effort?.level)
    }

    @Test fun legacyMigrationKeepsCustomOptionsAsForksAndIsIdempotent() {
        val old = source.copy(modelLibraryVersion = 0, modelId = "m")
        val migrated = old.migrateModelLibrary()
        assertEquals(migrated, migrated.migrateModelLibrary())
        assertEquals(old.advanced, migrated.forModel().advanced)
        assertEquals("m", migrated.forModel().modelId)
        assertEquals(listOf("m"), migrated.favoriteModels)
        assertTrue(migrated.modelId.startsWith("variant:"))
        assertEquals(.9, migrated.forModel("m").advanced.temperature)
    }

    @Test fun catalogRefreshKeepsFavoriteVariantsAndCustomSettings() {
        val p = source.copy(variants = listOf(custom))
        val refreshed = p.withCatalog(ModelDefaults.discover(source.provider, listOf("m", "new")))
        assertEquals(p.displayModels, refreshed.displayModels)
        assertEquals(p.variants, refreshed.variants)
        assertEquals(custom.options, refreshed.forModel(custom.id).advanced)
    }

    @Test fun selectionsCatalogsVariantsAndDescriptionsRoundTrip() {
        val profile = source.copy(variants = listOf(custom))
        val bundle = ProfileBundle(exportedAt = 0, settings = AppSettings(defaultModel = ModelSelection("p", custom.id)),
            plugins = emptyList(), sessions = emptyList(), llmProfiles = listOf(profile),
            modelDescriptions = listOf(ModelDossier("d", "p", "m", strengths = "Coding", limitations = "Unknown price", rating = 4)))
        assertEquals(bundle, Json.decodeFromString<ProfileBundle>(Json.encodeToString(ProfileBundle.serializer(), bundle)))
    }

    @Test fun deletedSelectionDoesNotSilentlyRunAnotherModel() {
        val session = ChatSession("s", "S", 0, 0, modelSelection = ModelSelection("p", "removed"))
        assertNull(ProfileResolver.resolve(session, AppSettings(), listOf(source)))
    }

    @Test fun codingAdaptersUseNativeProviderFormats() {
        for ((provider, api) in listOf(ProviderType.ANTHROPIC to "anthropic-messages", ProviderType.GOOGLE to "google-generative-ai", ProviderType.OPENROUTER to "openai-completions")) {
            val request = source.copy(provider = provider).forModel("m", EffortSelection.of(ReasoningEffort.HIGH))
            assertEquals(api, PiModelsConfig.root(request)["providers"]!!.jsonObject["magicpaper"]!!.jsonObject["api"]!!.jsonPrimitive.content)
            val params = PiModelOptions.parameters(request)
            assertFalse("messages" in params)
            assertFalse("model" in params)
            if (provider == ProviderType.OPENROUTER) assertEquals("high", params["reasoning"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
        }
    }
    @Test fun thinkingBudgetAndCustomOutputRespectTheProviderCeiling() {
        val p = source.copy(provider = ProviderType.ANTHROPIC, variants = listOf(custom.copy(options = custom.options.copy(maxTokens = 90000))))
        val request = p.forModel(custom.id, EffortSelection.of(ReasoningEffort.HIGH))
        assertEquals(50000, request.advanced.maxTokens)
        val body = LlmPayloads.anthropic(request, emptyList(), ReasoningPresets.ANTHROPIC_BUDGET)
        assertEquals(50000, body["max_tokens"]!!.jsonPrimitive.int)
        assertTrue(body["thinking"]!!.jsonObject["budget_tokens"]!!.jsonPrimitive.int < 50000)
    }

}
