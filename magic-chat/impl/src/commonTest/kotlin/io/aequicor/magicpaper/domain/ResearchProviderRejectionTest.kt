package io.aequicor.magicpaper.domain

import io.aequicor.magicpaper.data.storage.*
import io.aequicor.magicpaper.domain.tools.*
import io.aequicor.magicpaper.logging.AppLog
import io.aequicor.magicpaper.ui.DefaultChatService
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.Json
import kotlin.test.*

/**
 * Отказ провайдера в чате-исследовании: подтверждённый отказ останавливает запрос известным
 * сбоем модели, потеря ответа сохраняет карантин неизвестного исхода.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ResearchProviderRejectionTest {
    @Test fun aRateLimitedProviderIsReportedAsAModelFailureWithRetryAdvice() =
        rejectedRequest(429, known = true, reason = "ограничил число запросов")

    /** Имя отвергнутого параметра — единственное, по чему человек может исправить настройку модели. */
    @Test fun aRejectedRequestParameterIsNamedSoTheSettingCanBeFixed() = rejectedRequest(400, known = true,
        reason = "reasoning_effort", rejection = ProviderRejection(code = "unsupported_parameter", param = "reasoning_effort",
            refusal = ProviderRefusal.PARAMETER))

    /** Переполнение контекста — не «проверьте подключение»: действие человека другое. */
    @Test fun anOversizedRequestIsReportedAsAContextLimit() = rejectedRequest(400, known = true,
        reason = "не помещается в контекст", rejection = ProviderRejection(code = "InvalidParameter",
            refusal = ProviderRefusal.CONTEXT_LENGTH))

    @Test fun aLostProviderAnswerStillStopsTheRequestAsAnUnknownOutcome() =
        rejectedRequest(503, known = false, reason = "не подтверждён")

    private fun rejectedRequest(status: Int, known: Boolean, reason: String, rejection: ProviderRejection? = null) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val values = InMemoryKeyValueStore()
        val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
        val journal = InMemoryEventJournal()
        val checkpoints = JsonChatRepository(values, json)
        val settings = JsonSettingsRepository(values, json)
        val profiles = JsonLlmProfileRepository(values, json)
        profiles.save(LlmProfile("first", "First", baseUrl = "https://provider.invalid", modelId = "model"))
        settings.save(AppSettings(activeLlmProfileId = "first"))
        checkpoints.save(ChatSession("chat", "Question", 1, 1, modelSelection = ModelSelection("first", "model"),
            researchResourcesInitialized = true))
        var calls = 0
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
                throw LlmTransportException(status, null, "private provider body", rejection)
            }
        }
        val receipts = MemoryToolReceiptStore()
        val chatTools = DefaultChatToolSessions(search, docs::articles, { docs.search(it) }, { emptyList() }, null,
            DefaultRuntimeQuestionnaireService(journal, "chat"), receipts, DefaultMediaToolReceiptOwner(receipts) { null })
        val backend = GatewaySessionRuntime(DefaultProviderToolLoop(model, journal, StoredProviderToolOutputs(values)),
            chatTools, search, docs)
        val store = ChatJournalStore(checkpoints, journal, StoredChatPayloads(values, json, Dispatchers.Main), json, Dispatchers.Main)
        val chat = DefaultChatService(backend, store, settings, profiles, null, workerDispatcher = Dispatchers.Main)
        try {
            chat.start(); chat.activate("chat")
            chat.send("Что известно по теме")
            advanceUntilIdle()
            assertEquals(1, calls)
            assertFalse(chat.state.value.busy)
            val run = checkNotNull(store.stateFor("chat")?.runs?.get("chat"))
            assertEquals(if (known) ChatMachine.Phase.INTERRUPTED else ChatMachine.Phase.UNKNOWN, run.phase)
            assertEquals(if (known) ChatMachine.Failure.MODEL else ChatMachine.Failure.UNKNOWN_OUTCOME, run.failure)
            val notice = checkNotNull(chat.state.value.notice)
            assertContains(notice, RESEARCH_MODEL_FAILURE)
            assertContains(notice, reason)
            assertFalse("private provider body" in notice, notice)
            val stored = checkNotNull(checkpoints.session("chat"))
            assertFalse("private provider body" in json.encodeToString(ChatSession.serializer(), stored))
            assertNotNull(stored.pendingRun, "Остановленный запрос сохраняется для проверки и повтора")
            assertEquals(RESEARCH_MODEL_FAILURE, stored.pendingActivity.single { it.kind == CodingStepKind.ERROR }.title)
            val reported = AppLog.history().last { it.component == "chat" && it.event == "send.failed" }
            assertEquals("model", reported.fields["phase"], reported.line())
            assertEquals(status.toString(), reported.fields["status"], reported.line())
            assertEquals("ProviderToolRunFailure", reported.fields["causeType"], reported.line())
            assertEquals(rejection?.param, reported.fields["param"], reported.line())
            assertFalse("private provider body" in reported.line(), reported.line())
            if (known) {
                // Подтверждённый отказ не требует восстановления: тот же вопрос можно задать снова.
                chat.discardPendingRequest(); advanceUntilIdle()
                chat.send("Новый вопрос после проверки подключения"); advanceUntilIdle()
                assertEquals(2, calls)
            } else {
                assertContains(notice, "сохранённый ответ")
            }
        } finally { chat.close(); Dispatchers.resetMain() }
    }
}
