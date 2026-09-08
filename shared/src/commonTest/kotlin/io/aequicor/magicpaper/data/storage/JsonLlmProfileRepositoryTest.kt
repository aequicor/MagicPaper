package io.aequicor.magicpaper.data.storage

import io.aequicor.magicpaper.domain.EffortSelection
import io.aequicor.magicpaper.domain.ReasoningEffort
import io.aequicor.magicpaper.domain.LlmProfile
import io.aequicor.magicpaper.domain.ProviderType
import io.aequicor.magicpaper.domain.AdvancedLlmOptions
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertNull

class JsonLlmProfileRepositoryTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun repo() = JsonLlmProfileRepository(InMemoryKeyValueStore(), json)

    @Test
    fun roundTrip() = runTest {
        val repository = repo()
        assertTrue(repository.load().isEmpty())
        val profile = LlmProfile(
            id = "p1",
            name = "Ollama (локально)",
            provider = ProviderType.OPENAI_COMPATIBLE,
            baseUrl = "http://localhost:11434/v1",
            modelId = "llama3.2",
        )
        repository.save(profile)
        assertEquals(listOf(profile), repository.load())
    }

    @Test
    fun saveSameIdReplaces() = runTest {
        val repository = repo()
        repository.save(LlmProfile(id = "p1", name = "старый", baseUrl = "http://x/v1", modelId = "m"))
        repository.save(LlmProfile(id = "p1", name = "новый", baseUrl = "http://x/v1", modelId = "m"))
        val all = repository.load()
        assertEquals(1, all.size)
        assertEquals("новый", all.single().name)
    }

    @Test
    fun deleteAndWipe() = runTest {
        val repository = repo()
        repository.save(LlmProfile(id = "p1", name = "a", baseUrl = "http://x/v1", modelId = "m"))
        repository.save(LlmProfile(id = "p2", name = "b", baseUrl = "http://y/v1", modelId = "n"))
        repository.delete("p1")
        assertEquals(listOf("p2"), repository.load().map { it.id })
        repository.replaceAll(emptyList())
        assertTrue(repository.load().isEmpty())
    }

    @Test
    fun corruptedDataFallsBackToEmpty() = runTest {
        val store = InMemoryKeyValueStore()
        store.write("llm_profiles", "{broken")
        assertTrue(JsonLlmProfileRepository(store, json).load().isEmpty())
    }

    @Test
    fun legacyEnumEffortMigratesToScale() = runTest {
        // Старые версии хранили усилие строкой перечисления — профиль не должен потеряться.
        val store = InMemoryKeyValueStore()
        store.write(
            "llm_profiles",
            """[{"id":"p1","name":"старый","provider":"OPENAI_COMPATIBLE","baseUrl":"http://x/v1","apiKey":"","modelId":"m","effort":"HIGH","advanced":{"timeoutSeconds":60,"systemPromptOverride":"","contextMessages":8},"createdAt":0}]""",
        )
        val profile = JsonLlmProfileRepository(store, json).load().single()
        assertEquals(EffortSelection.of(ReasoningEffort.HIGH), profile.effort)
        assertTrue(profile.favoriteModels.isEmpty())
    }

    @Test
    fun legacyNullTokenLimitDoesNotHideProfilesOrLoseThemOnSave() = runTest {
        val store = InMemoryKeyValueStore()
        val raw = """[{"id":"legacy","name":"Мой сервер","baseUrl":"http://test/v1","apiKey":"test-key","modelId":"m","effort":"MEDIUM","advanced":{"temperature":null,"topP":null,"maxTokens":null,"timeoutSeconds":60,"contextMessages":8}},
            {"id":"other","name":"Other","baseUrl":"http://other/v1","modelId":"n","advanced":{"maxTokens":16384}}]"""
        store.write("llm_profiles", raw)
        val repository = JsonLlmProfileRepository(store, json)
        val loaded = repository.load()
        assertEquals(listOf("legacy", "other"), loaded.map { it.id })
        val legacy = loaded.first()
        assertTrue(legacy.configured)
        assertEquals("test-key", legacy.apiKey)
        assertEquals(EffortSelection.of(ReasoningEffort.MEDIUM), legacy.effort)
        assertEquals(AdvancedLlmOptions().maxTokens, legacy.advanced.maxTokens)
        assertNull(legacy.advanced.temperature)
        assertNull(legacy.advanced.topP)
        assertEquals(60, legacy.advanced.timeoutSeconds)
        assertEquals(16384, loaded.last().advanced.maxTokens)
        assertEquals(raw, store.read("llm_profiles"))

        repository.save(loaded.last().copy(name = "Renamed"))
        assertEquals(listOf(legacy, loaded.last().copy(name = "Renamed")), repository.load())
    }

    @Test
    fun favoriteModelsRoundTrip() = runTest {
        val repository = repo()
        val profile = LlmProfile(
            id = "p1",
            name = "OpenRouter",
            baseUrl = "https://openrouter.ai/api/v1",
            modelId = "anthropic/claude-sonnet-4",
            effort = EffortSelection.of(ReasoningEffort.HIGH),
            favoriteModels = listOf("anthropic/claude-sonnet-4", "openai/gpt-5"),
        )
        repository.save(profile)
        val loaded = repository.load().single()
        assertEquals(EffortSelection.of(ReasoningEffort.HIGH), loaded.effort)
        assertEquals(profile.favoriteModels, loaded.favoriteModels)
    }
}
