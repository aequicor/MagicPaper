package io.aequicor.magicpaper.data.coding

import io.aequicor.magicpaper.data.storage.InMemoryKeyValueStore
import io.aequicor.magicpaper.domain.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.*

class CodingEnginePersistenceTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    @Test fun migrationRunsOnceAndSurvivesProviderChangesAndRestart() = runTest {
        val store = InMemoryKeyValueStore()
        store.write("coding-sessions", """[{"id":"s","projectId":"p","name":"old","createdAt":1,"piSessionId":"old-context"}]""")
        var inferred = CodingEngine.CODEX
        var calls = 0
        fun repository() = JsonCodingProjectRepository(store, json) { _, _ -> calls++; inferred }
        val session = repository().sessions("p").single()
        assertEquals(CodingEngine.CODEX, session.engine)
        assertEquals("old-context", session.piSessionId)
        inferred = CodingEngine.PI
        assertEquals(CodingEngine.CODEX, repository().sessions("p").single().engine)
        assertEquals(1, calls)
    }
    @Test fun modelCanChangeButSavedEngineCannot() = runTest {
        val repo = JsonCodingProjectRepository(InMemoryKeyValueStore(), json)
        val session = CodingSession("s", "p", "Session", 1, engine = CodingEngine.PI)
        repo.saveSession(session)
        repo.saveSession(session.copy(modelSelection = ModelSelection("subscription", "gpt")))
        assertEquals(CodingEngine.PI, repo.sessions("p").single().engine)
        assertFailsWith<IllegalArgumentException> { repo.saveSession(session.copy(engine = CodingEngine.CODEX)) }
        assertEquals(CodingEngine.PI, repo.sessions("p").single().engine)
    }
    @Test fun legacyWriterCannotClearSavedEngine() = runTest {
        val repo = JsonCodingProjectRepository(InMemoryKeyValueStore(), json)
        val session = CodingSession("s", "p", "Session", 1, engine = CodingEngine.CODEX)
        repo.saveSession(session); repo.saveSession(session.copy(name = "Renamed", engine = null))
        assertEquals(CodingEngine.CODEX, repo.sessions("p").single().engine)
    }
    @Test fun differentSessionsKeepIndependentEngines() = runTest {
        val repo = JsonCodingProjectRepository(InMemoryKeyValueStore(), json)
        repo.saveSession(CodingSession("a", "p", "A", 1, engine = CodingEngine.PI))
        repo.saveSession(CodingSession("b", "p", "B", 2, engine = CodingEngine.CODEX))
        assertEquals(mapOf("a" to CodingEngine.PI, "b" to CodingEngine.CODEX), repo.sessions("p").associate { it.id to it.engine })
    }
    @Test fun subscriptionUsesNativePiProtocolWithoutPersistingTokens() {
        val profile = LlmProfile("s", "ChatGPT", provider = ProviderType.OPENAI_SUBSCRIPTION, modelId = "gpt-test", apiKey = "must-not-be-copied")
        val config = PiModelsConfig.json(profile)
        assertContains(config, "openai-codex-responses")
        assertContains(config, "https://chatgpt.com/backend-api")
        assertFalse(config.contains(profile.apiKey))
        assertFalse(PiModelOptions.extension(profile).contains("max_tokens"))
    }
}
