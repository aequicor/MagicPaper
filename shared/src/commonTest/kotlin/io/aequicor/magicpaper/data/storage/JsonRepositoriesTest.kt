package io.aequicor.magicpaper.data.storage

import io.aequicor.magicpaper.domain.AppSettings
import io.aequicor.magicpaper.domain.ChatMessage
import io.aequicor.magicpaper.domain.ChatRole
import io.aequicor.magicpaper.domain.ChatSession
import io.aequicor.magicpaper.domain.SearchProvider
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JsonRepositoriesTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val store = InMemoryKeyValueStore()

    @Test
    fun settingsRoundTrip() = runTest {
        val repo = JsonSettingsRepository(store, json)
        val settings = AppSettings(
            llmBaseUrl = "http://localhost:11434/v1",
            llmModel = "llama3.2",
            searchProvider = SearchProvider.QUERIT,
            queritApiKey = "k-1",
        )
        repo.save(settings)
        val loaded = JsonSettingsRepository(InMemoryKeyValueStore().also { }, json)
        assertEquals(settings, repo.load())
        // Повреждённые данные -> дефолты.
        store.write("settings", "{broken")
        assertEquals(AppSettings(), repo.load())
    }

    @Test
    fun chatRoundTripAndDelete() = runTest {
        val repo = JsonChatRepository(store, json)
        val message = ChatMessage(id = "m1", role = ChatRole.USER, text = "привет", createdAt = 1L)
        val session = ChatSession(id = "s1", title = "тест", createdAt = 1L, updatedAt = 2L, messages = listOf(message))
        repo.save(session)

        val loaded = repo.session("s1")
        assertEquals(session, loaded)
        assertEquals(1, repo.sessions().size)

        repo.delete("s1")
        assertNull(repo.session("s1"))
        assertTrue(repo.sessions().isEmpty())
    }

    @Test
    fun wipeClearsAll() = runTest {
        val repo = JsonChatRepository(store, json)
        repo.save(ChatSession(id = "a", title = "a", createdAt = 1L, updatedAt = 1L))
        repo.wipe()
        assertTrue(repo.sessions().isEmpty())
    }
}
