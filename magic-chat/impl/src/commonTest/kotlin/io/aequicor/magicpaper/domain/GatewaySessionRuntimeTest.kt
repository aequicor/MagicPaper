package io.aequicor.magicpaper.domain

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class GatewaySessionRuntimeTest {
    private val profile = LlmProfile("provider", "Provider", baseUrl = "https://example.invalid/v1", modelId = "model")
    private val docs = object : DocRepository {
        override suspend fun articles() = emptyList<DocArticle>()
        override suspend fun search(query: String, limit: Int) = emptyList<DocMatch>()
    }
    private val search = object : SearchEngine {
        override val provider = SearchProvider.AUTO
        override val displayName = "Search"
        override fun isConfigured(settings: AppSettings) = true
        override suspend fun search(query: String, settings: AppSettings, limit: Int) = emptyList<SearchHit>()
    }

    @Test fun savedDesktopIdentityDoesNotChangeProviderExecution() = runTest {
        val calls = mutableListOf<List<LlmMessage>>()
        val gateway = object : LlmGateway {
            override suspend fun complete(profile: LlmProfile, messages: List<LlmMessage>): String {
                calls += messages
                return "Provider answer"
            }
        }
        val backend: ChatBackend = testGatewayRuntime(gateway, search, docs)
        for (engine in CodingEngine.entries + null) {
            val session = ChatSession("chat", "Question", 1, 1, engine = engine,
                nativeSessionId = "persisted-desktop-id",
                messages = listOf(ChatMessage("question-$engine", ChatRole.USER, "Объясни результат", 1)))
            val events = backend.runChat(session, "Объясни результат", profile).toList()
            assertEquals("Provider answer", events.filterIsInstance<CodingEvent.FinalText>().single().text)
            assertEquals(CodingEvent.Finished, events.last())
            assertTrue(events.none { it is CodingEvent.SessionStarted })
            assertEquals(engine, session.engine)
            assertEquals("persisted-desktop-id", session.nativeSessionId)
        }
        assertEquals(CodingEngine.entries.size + 1, calls.size)
        assertTrue(calls.all { it.last().content == "Объясни результат" })
    }

    @Test fun abortCancelsOnlyItsConversationAndTheConversationCanRunAgain() = runTest {
        val release = CompletableDeferred<Unit>()
        val calls = mutableListOf<String>()
        val gateway = object : LlmGateway {
            override suspend fun complete(profile: LlmProfile, messages: List<LlmMessage>): String {
                val prompt = messages.last().content
                calls += prompt
                release.await()
                return prompt
            }
        }
        val backend: ChatBackend = testGatewayRuntime(gateway, search, docs)
        fun session(id: String) = ChatSession(id, id, 1, 1)
        val first = async { backend.runChat(session("first"), "first", profile).toList() }
        val second = async { backend.runChat(session("second"), "second", profile).toList() }
        runCurrent()
        assertEquals(setOf("first", "second"), calls.toSet())
        backend.abort("first")
        runCurrent()
        assertTrue(first.isCancelled)
        assertTrue(second.isActive)
        release.complete(Unit)
        assertEquals("second", second.await().filterIsInstance<CodingEvent.FinalText>().single().text)
        val resumed = backend.runChat(session("first"), "resumed", profile).toList()
        assertEquals("resumed", resumed.filterIsInstance<CodingEvent.FinalText>().single().text)
        assertEquals(CodingEvent.Finished, resumed.last())
    }

    @Test fun failedProviderCallPropagatesWithoutAFabricatedSuccessfulCompletion() = runTest {
        var fail = true
        val failure = IllegalStateException("provider unavailable")
        val gateway = object : LlmGateway {
            override suspend fun complete(profile: LlmProfile, messages: List<LlmMessage>): String {
                if (fail) throw failure
                return "Recovered"
            }
        }
        val backend: ChatBackend = testGatewayRuntime(gateway, search, docs)
        val session = ChatSession("chat", "Question", 1, 1)
        val events = mutableListOf<CodingEvent>()
        val propagated = assertFailsWith<IllegalStateException> {
            backend.runChat(session, "Question", profile).toList(events)
        }
        // Coroutine stack recovery can copy an exception, retaining the original as its cause.
        assertTrue(generateSequence<Throwable>(propagated) { it.cause }.any { it === failure })
        assertTrue(events.isEmpty())
        fail = false
        assertEquals("Recovered", backend.runChat(session, "Retry", profile).toList()
            .filterIsInstance<CodingEvent.FinalText>().single().text)
    }
}
