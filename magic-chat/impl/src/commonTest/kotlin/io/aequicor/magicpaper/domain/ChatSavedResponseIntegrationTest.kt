package io.aequicor.magicpaper.domain

import io.aequicor.magicpaper.data.storage.*
import io.aequicor.magicpaper.domain.tools.*
import io.aequicor.magicpaper.ui.DefaultChatService
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.Json
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ChatSavedResponseIntegrationTest {
    @Test fun providerArtifactSurvivesFailedChatReplyCommitAndRestoresWithoutModelSourceOrToolWork() = recoverSavedAnswer(false)
    @Test fun unavailableCitationRemainsRejectedAfterLostChatReplyAndSavedOutputRecovery() = recoverSavedAnswer(true)

    private fun recoverSavedAnswer(unavailable: Boolean) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val values = InMemoryKeyValueStore()
        val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
        val backing = InMemoryEventJournal()
        var failReply = true
        val journal = object : EventJournal by backing {
            override suspend fun append(expected: JournalRevision, operation: String, at: Long, detail: String): JournalRecord? {
                if (failReply && expected.stream.startsWith("chat-workflow:") && detail.contains("ReplyStored")) error("Disk unavailable")
                return backing.append(expected, operation, at, detail)
            }
        }
        val checkpoints = JsonChatRepository(values, json)
        val settings = JsonSettingsRepository(values, json)
        val profiles = JsonLlmProfileRepository(values, json)
        profiles.save(LlmProfile("first", "First", baseUrl = "https://provider.invalid", modelId = "original-model", apiKey = "PRIVATE_PROVIDER_KEY"))
        profiles.save(LlmProfile("second", "Second", baseUrl = "https://provider.invalid", modelId = "other-model", apiKey = "OTHER_PRIVATE_KEY",
            favoriteModels = listOf("other-model"), modelLibraryVersion = 1))
        settings.save(AppSettings(activeLlmProfileId = "first"))
        checkpoints.save(ChatSession("chat", "Question", 1, 1,
            modelSelection = ModelSelection("first", "original-model"), researchResourcesInitialized = true,
            resources = listOf(ResearchResource("source", "Source", "https://example.org/evidence")) +
                if (unavailable) listOf(ResearchResource("valid", "Readable source", "https://example.org/valid")) else emptyList()))
        var calls = 0
        var sourceReads = 0
        var toolPreparation = 0
        val search = object : SearchEngine {
            override val provider = SearchProvider.AUTO
            override val displayName = "Fixture search"
            override fun isConfigured(settings: AppSettings) = false
            override suspend fun search(query: String, settings: AppSettings, limit: Int): List<SearchHit> = error("Unexpected search")
        }
        val docs = object : DocRepository {
            override suspend fun articles(): List<DocArticle> = emptyList()
            override suspend fun search(query: String, limit: Int): List<DocMatch> = error("Unexpected document search")
        }
        val model = object : LlmGateway {
            override suspend fun complete(profile: LlmProfile, messages: List<LlmMessage>): String = error("Tool protocol required")
            override suspend fun turn(profile: LlmProfile, messages: List<LlmMessage>, tools: List<LlmToolDefinition>,
                exchanges: List<LlmToolExchange>): LlmToolTurn {
                calls++
                assertEquals("original-model", profile.modelId)
                return LlmToolTurn(text = if (unavailable) "UNVERIFIED CLAIM [source](https://example.org/evidence)" else "Immutable saved answer")
            }
        }
        val outputs = StoredProviderToolOutputs(values)
        fun backend(): GatewaySessionRuntime {
            val receipts = MemoryToolReceiptStore()
            val ownedTools = DefaultChatToolSessions(search, docs::articles, { docs.search(it) }, { emptyList() }, null,
                DefaultRuntimeQuestionnaireService(backing, "chat"), receipts, DefaultMediaToolReceiptOwner(receipts) { null })
            val tools = object : ChatToolSessions by ownedTools {
                override suspend fun create(session: ChatSession, requestId: String, settings: AppSettings, allowSearch: Boolean): ToolSession {
                    toolPreparation++
                    return ownedTools.create(session, requestId, settings, allowSearch)
                }
            }
            return GatewaySessionRuntime(DefaultProviderToolLoop(model, backing, outputs), tools, search, docs)
        }
        fun service() = DefaultChatService(backend(), ChatJournalStore(checkpoints, journal,
            StoredChatPayloads(values, json, Dispatchers.Main), json, Dispatchers.Main), settings, profiles, null,
            workerDispatcher = Dispatchers.Main,
            sourceAccess = ResearchSourceAccess { url -> sourceReads++; if (unavailable && url.endsWith("evidence")) error("Unavailable source"); "Verified saved source" })
        var chat = service()
        try {
            chat.start(); chat.activate("chat")
            chat.send("Explain the supplied source")
            advanceUntilIdle()
            assertEquals(1, calls)
            val interrupted = checkNotNull(checkpoints.session("chat")?.pendingRun)
            assertTrue(checkpoints.session("chat")!!.messages.none { it.role == ChatRole.AGENT })
            assertNotNull(chat.state.value.notice)
            val readsBefore = sourceReads
            val preparationsBefore = toolPreparation
            assertEquals(1, preparationsBefore)
            chat.close()
            failReply = false
            chat = service()
            chat.start(); chat.activate("chat"); advanceUntilIdle()
            assertEquals(1, calls)
            assertEquals(readsBefore, sourceReads)
            assertEquals(preparationsBefore, toolPreparation)
            assertNotNull(chat.state.value.current?.pendingRun)
            chat.selectChatModel(ModelSelection("second", "other-model")); advanceUntilIdle()
            chat.resume(); advanceUntilIdle()
            val recovered = checkNotNull(checkpoints.session("chat"))
            assertNull(recovered.pendingRun)
            assertEquals(interrupted.responseId, recovered.messages.last().id)
            if (unavailable) {
                assertContains(recovered.messages.last().text, "не удалось подтвердить")
                assertContains(recovered.messages.last().text, "Недоступные источники исключены")
                assertFalse(recovered.messages.last().text.contains("UNVERIFIED CLAIM"))
                assertEquals(listOf("https://example.org/valid"), recovered.messages.last().sources.map { it.url })
            } else {
                assertEquals("Immutable saved answer", recovered.messages.last().text)
                assertEquals(listOf("https://example.org/evidence"), recovered.messages.last().sources.map { it.url })
            }
            assertEquals(1, recovered.messages.count { it.role == ChatRole.USER })
            assertEquals("second", recovered.modelSelection?.profileId)
            assertEquals(1, calls, "Recovery reads the original artifact despite changed model selection")
            assertEquals(readsBefore, sourceReads, "Recovery cannot re-fetch sources")
            assertEquals(preparationsBefore, toolPreparation, "Recovery cannot create a tool session")
            val records = backing.streams().flatMap { backing.read(it) }
            assertTrue(records.any { it.detail.contains("RecoveredReply") })
            assertTrue(records.none { "PRIVATE_PROVIDER_KEY" in it.detail || "Immutable saved answer" in it.detail })
        } finally { chat.close(); Dispatchers.resetMain() }
    }
}
