package io.aequicor.magicpaper.data.storage

import io.aequicor.magicpaper.domain.LlmProfile
import io.aequicor.magicpaper.domain.ProviderType
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JsonLlmProfileRepositoryTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun repo() = JsonLlmProfileRepository(InMemoryKeyValueStore(), json)

    @Test
    fun roundTrip() = runTest {
        val repository = repo()
        assertTrue(repository.all().isEmpty())
        val profile = LlmProfile(
            id = "p1",
            name = "Ollama (локально)",
            provider = ProviderType.OPENAI_COMPATIBLE,
            baseUrl = "http://localhost:11434/v1",
            modelId = "llama3.2",
        )
        repository.save(profile)
        assertEquals(listOf(profile), repository.all())
    }

    @Test
    fun saveSameIdReplaces() = runTest {
        val repository = repo()
        repository.save(LlmProfile(id = "p1", name = "старый", baseUrl = "http://x/v1", modelId = "m"))
        repository.save(LlmProfile(id = "p1", name = "новый", baseUrl = "http://x/v1", modelId = "m"))
        val all = repository.all()
        assertEquals(1, all.size)
        assertEquals("новый", all.single().name)
    }

    @Test
    fun deleteAndWipe() = runTest {
        val repository = repo()
        repository.save(LlmProfile(id = "p1", name = "a", baseUrl = "http://x/v1", modelId = "m"))
        repository.save(LlmProfile(id = "p2", name = "b", baseUrl = "http://y/v1", modelId = "n"))
        repository.delete("p1")
        assertEquals(listOf("p2"), repository.all().map { it.id })
        repository.wipe()
        assertTrue(repository.all().isEmpty())
    }

    @Test
    fun corruptedDataFallsBackToEmpty() = runTest {
        val store = InMemoryKeyValueStore()
        store.write("llm_profiles", "{broken")
        assertTrue(JsonLlmProfileRepository(store, json).all().isEmpty())
    }
}
