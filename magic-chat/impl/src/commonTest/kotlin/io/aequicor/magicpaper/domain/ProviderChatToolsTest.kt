package io.aequicor.magicpaper.domain

import io.aequicor.magicpaper.data.storage.InMemoryEventJournal
import io.aequicor.magicpaper.data.storage.InMemoryKeyValueStore
import io.aequicor.magicpaper.domain.tools.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ProviderChatToolsTest {
    private val profile = LlmProfile("profile", "Provider", baseUrl = "https://example.invalid", modelId = "model")
    private val docs = object : DocRepository {
        override suspend fun articles() = listOf(DocArticle("doc", "Guide", "Verified documentation"))
        override suspend fun search(query: String, limit: Int) = articles().map { DocMatch(it, 1.0) }
    }
    private val skills = listOf(Skill("skill", "Writing", "Write clearly", "Use clear sentences"),
        Skill("disabled", "Disabled", "Hidden", "Never expose this", enabled = false))
    private var searches = 0
    private val search = object : SearchEngine {
        override val provider = SearchProvider.AUTO
        override val displayName = "Search"
        override fun isConfigured(settings: AppSettings) = true
        override suspend fun search(query: String, settings: AppSettings, limit: Int): List<SearchHit> {
            searches++
            return listOf(SearchHit("Readable", "https://example.org/readable", "UNVERIFIED_SEARCH_SNIPPET"),
                SearchHit("Blocked", "https://example.org/blocked", "BLOCKED_SEARCH_SNIPPET"))
        }
    }
    private fun call(id: String, name: String, key: String? = null, value: String = "") =
        LlmToolCall(id, "magicpaper_" + name.replace('.', '_'), buildJsonObject { if (key != null) put(key, value) })
    private fun backend(action: suspend (List<LlmToolDefinition>, List<LlmToolExchange>) -> LlmToolTurn): GatewaySessionRuntime {
        val journal = InMemoryEventJournal()
        val receipts = MemoryToolReceiptStore()
        val reader: suspend (String) -> String = { url -> if (url.endsWith("blocked")) error("Unavailable") else "Original source evidence" }
        val tools = DefaultChatToolSessions(search, docs::articles, { docs.search(it) }, { skills }, null,
            DefaultRuntimeQuestionnaireService(journal, "chat"), receipts, DefaultMediaToolReceiptOwner(receipts) { null },
            readResearchPage = reader)
        val gateway = object : LlmGateway {
            override suspend fun complete(profile: LlmProfile, messages: List<LlmMessage>): String = error("Plain completion bypassed the tool loop")
            override suspend fun turn(profile: LlmProfile, messages: List<LlmMessage>, tools: List<LlmToolDefinition>,
                exchanges: List<LlmToolExchange>) = action(tools, exchanges)
        }
        return GatewaySessionRuntime(DefaultProviderToolLoop(gateway, journal, StoredProviderToolOutputs(InMemoryKeyValueStore())), tools, search, docs, readResearchPage = reader)
    }
    private fun session(prompt: String = "Обработай материалы") = ChatSession("session", "Chat", 1, 1,
        messages = listOf(ChatMessage("request", ChatRole.USER, prompt, 1)))

    @Test fun modelCanReadDocumentsSkillsAndVerifiedWebPagesThroughTheSameExecutor() = runTest {
        val runtime = backend { definitions, exchanges ->
            assertEquals(setOf("magicpaper_web_search", "magicpaper_questionnaire", "magicpaper_docs_search", "magicpaper_docs_read",
                "magicpaper_skills_list", "magicpaper_skills_read"), definitions.map { it.name }.toSet())
            when (exchanges.size) {
                0 -> LlmToolTurn(calls = listOf(call("search", "web.search", "query", "evidence"),
                    call("docs", "docs.read", "id", "doc"), call("skills", "skills.list")))
                1 -> {
                    val outputs = exchanges.single().results
                    assertContains(outputs[0].content.toString(), "Original source evidence")
                    assertFalse(outputs[0].content.toString().contains("UNVERIFIED_SEARCH_SNIPPET"))
                    assertFalse(outputs[0].content.toString().contains("BLOCKED_SEARCH_SNIPPET"))
                    assertContains(outputs[0].content.toString(), "Недоступные источники")
                    assertContains(outputs[1].content.toString(), "Verified documentation")
                    assertFalse(outputs[2].content.toString().contains("disabled"))
                    LlmToolTurn(calls = listOf(call("skill", "skills.read", "id", "skill")))
                }
                else -> { assertContains(exchanges.last().results.single().content.toString(), "Use clear sentences"); LlmToolTurn(text = "Completed") }
            }
        }
        val events = runtime.runChat(session(), "Обработай материалы", profile).toList()
        assertEquals("Completed", events.filterIsInstance<CodingEvent.FinalText>().single().text)
        assertEquals(1, searches)
        val searchResult = events.filterIsInstance<CodingEvent.ToolFinished>().single { it.tool == "web.search" }
        assertEquals(listOf("https://example.org/readable"), searchResult.sources.map { it.url })
        assertEquals(4, events.filterIsInstance<CodingEvent.ToolFinished>().size)
    }

    @Test fun realProviderQuestionnaireWaitsForUserAndReturnsConfirmedAnswer() = runTest {
        val runtime = backend { _, exchanges ->
            if (exchanges.isEmpty()) LlmToolTurn(calls = listOf(LlmToolCall("clarify", "magicpaper_questionnaire", buildJsonObject {
                putJsonArray("questions") { add(buildJsonObject { put("id", "q"); put("title", "Как продолжить?") }) }
            }))) else {
                assertContains(exchanges.single().results.single().content.toString(), "Ответ пользователя")
                LlmToolTurn(text = "Ответ принят")
            }
        }
        val response = async { runtime.runChat(session(), "Обработай материалы", profile).toList() }
        runCurrent()
        assertFalse(response.isCompleted)
        val question = runtime.questionnaires.value.single()
        assertEquals("session", question.sessionId)
        assertEquals("request", question.runId)
        runtime.respondQuestionnaire(question.id, listOf(PlanningAnswer("q", text = "Ответ пользователя")))
        assertEquals("Ответ принят", response.await().filterIsInstance<CodingEvent.FinalText>().single().text)
        assertTrue(runtime.questionnaires.value.isEmpty())
    }

    @Test fun suppliedSourceRequestCannotAcquireSearchCapability() = runTest {
        val runtime = backend { definitions, _ ->
            assertFalse(definitions.any { it.name == "magicpaper_web_search" })
            LlmToolTurn(calls = listOf(call("not-permitted", "web.search", "query", "replacement")))
        }
        val prompt = "https://example.org/readable\nкраткий пересказ"
        assertFailsWith<IllegalStateException> { runtime.runChat(session(prompt), prompt, profile).toList() }
        assertEquals(0, searches)
    }

    @Test fun providerFailureReleasesConversationScopeForAnExplicitNewRequest() = runTest {
        var fail = true
        val runtime = backend { _, _ -> if (fail) error("offline") else LlmToolTurn(text = "Done") }
        assertFailsWith<IllegalStateException> { runtime.runChat(session(), "First", profile).toList() }
        fail = false
        val next = session().copy(messages = listOf(ChatMessage("new-request", ChatRole.USER, "Second", 2)))
        assertEquals("Done", runtime.runChat(next, "Second", profile).toList().filterIsInstance<CodingEvent.FinalText>().single().text)
    }
}
