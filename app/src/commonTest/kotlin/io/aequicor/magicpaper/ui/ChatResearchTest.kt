package io.aequicor.magicpaper.ui

import io.aequicor.magicpaper.data.coding.NoopCodingRuntime
import io.aequicor.magicpaper.data.storage.JsonChatRepository
import io.aequicor.magicpaper.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ChatResearchTest {
    private class Runtime : CodingRuntime by NoopCodingRuntime {
        data class Call(val session: ChatSession, val prompt: String, val attachments: List<Attachment>)
        val calls = mutableListOf<Call>()
        val events = mutableMapOf<String, Channel<CodingEvent>>()
        val deleted = mutableListOf<String>()
        val reconciled = mutableListOf<String>()
        override fun runChat(session: ChatSession, prompt: String, profile: LlmProfile?, attachments: List<Attachment>) = flow {
            calls += Call(session, prompt, attachments)
            val channel = events.getOrPut(session.id) { Channel(Channel.UNLIMITED) }
            for (event in channel) { emit(event); if (event == CodingEvent.Finished) break }
        }
        suspend fun finish(id: String, answer: String) {
            events.getValue(id).send(CodingEvent.FinalText(answer))
            events.getValue(id).send(CodingEvent.Finished)
        }
        override suspend fun deleteChatSession(session: ChatSession) { deleted += session.id }
        override suspend fun reconcile(sessionId: String) { reconciled += sessionId }
    }

    private suspend fun service(f: ModelSettingsFixture, runtime: Runtime, repository: ChatRepository = f.chats,
        search: SearchEngine? = null): DefaultChatService {
        f.seed()
        return DefaultChatService(runtime, repository, f.settings, f.profiles, null,
            workerDispatcher = Dispatchers.Main, draftRepository = f.draftRepository, draftBlobs = f.draftBlobs,
            researchSearch = search)
            .also { it.start(); it.activate("first") }
    }

    @Test fun automaticSearchResultsStayWithQuestionAndBecomeAnswerFootnotes() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val f = ModelSettingsFixture()
        val runtime = Runtime()
        val hit = SearchHit("Android Developers", "https://developer.android.com/", "Official documentation")
        val search = object : SearchEngine {
            override val provider = SearchProvider.WIKIPEDIA
            override val displayName = "Fixture"
            override fun isConfigured(settings: AppSettings) = true
            override suspend fun search(query: String, settings: AppSettings, limit: Int) = listOf(hit)
        }
        val service = service(f, runtime, search = search)
        try {
            service.send("android разработка")
            runCurrent()
            assertTrue(service.state.value.notebook?.resources.orEmpty().isEmpty())
            assertEquals(hit.url, service.state.value.current?.questionResources?.single()?.url)
            assertEquals(hit.url, runtime.calls.single().session.resources.single().url)
            runtime.finish("first", "Обзор")
            advanceUntilIdle()
            assertEquals(listOf(hit), service.state.value.current?.messages?.last()?.sources)
        } finally { service.close(); Dispatchers.resetMain() }
    }

    @Test fun searchTimeoutContinuesResearchWithAvailableSources() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val f = ModelSettingsFixture()
        val runtime = Runtime()
        val search = object : SearchEngine {
            override val provider = SearchProvider.WIKIPEDIA
            override val displayName = "Fixture"
            override fun isConfigured(settings: AppSettings) = true
            override suspend fun search(query: String, settings: AppSettings, limit: Int): List<SearchHit> = awaitCancellation()
        }
        val service = service(f, runtime, search = search)
        try {
            service.send("Долгий поиск")
            runCurrent()
            advanceTimeBy(15_001)
            runCurrent()
            assertEquals("Долгий поиск", runtime.calls.single().prompt)
            assertNotNull(service.state.value.notice)
            runtime.finish("first", "Ответ без новых источников")
            advanceUntilIdle()
            assertFalse(service.state.value.busy)
        } finally { service.close(); Dispatchers.resetMain() }
    }

    @Test fun restartRestoresEveryRunningQuestionWithoutRepeatingStoppedWork() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val f = ModelSettingsFixture()
        f.seed()
        val root = f.chats.session("first")!!.copy(selectedQuestionId = "second")
        fun question(id: String, intent: ExecutionIntent) = ChatSession(
            id = id,
            title = id,
            createdAt = 2,
            updatedAt = 2,
            messages = listOf(ChatMessage("request-$id", ChatRole.USER, "Question $id", 2)),
            researchParentId = root.id,
            modelSelection = root.modelSelection,
            pendingRun = CodingRunCheckpoint("request-$id", "Question $id", responseId = "response-$id", intent = intent),
        )
        f.chats.save(root)
        f.chats.save(question("second", ExecutionIntent.RUN))
        f.chats.save(question("third", ExecutionIntent.RUN))
        f.chats.save(question("stopped", ExecutionIntent.STOP))
        val runtime = Runtime()
        val restored = DefaultChatService(runtime, JsonChatRepository(f.kv, f.json), f.settings, f.profiles, null,
            workerDispatcher = Dispatchers.Main, draftRepository = f.draftRepository, draftBlobs = f.draftBlobs)
        try {
            restored.start()
            restored.activate(root.id)
            runCurrent()

            assertEquals(setOf("second", "third"), runtime.calls.map { it.session.id }.toSet())
            assertEquals(setOf("second", "third"), runtime.reconciled.toSet())
            assertTrue(runtime.calls.all { it.prompt.startsWith("Продолжи незавершённую работу") })
            assertEquals("second", restored.state.value.current?.id)
            assertTrue(restored.state.value.busy)
            assertEquals(1, f.chats.session("second")!!.messages.count { it.id == "request-second" })

            runtime.finish("second", "Second answer")
            runtime.finish("third", "Third answer")
            advanceUntilIdle()
            assertNull(f.chats.session("second")!!.pendingRun)
            assertNull(f.chats.session("third")!!.pendingRun)
            assertEquals(ExecutionIntent.STOP, f.chats.session("stopped")!!.pendingRun?.intent)
            assertFalse(restored.state.value.busy)
        } finally { restored.close(); Dispatchers.resetMain() }
    }

    @Test fun questionsShareExplicitSourcesButKeepDiscoveredSourcesDraftsAndRepliesIndependent() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val f = ModelSettingsFixture()
        val runtime = Runtime()
        val service = service(f, runtime)
        var closed = false
        try {
            val file = Attachment.fromBytes("notes.txt", "text/plain", "Observations".encodeToByteArray())
            assertTrue(service.addResources("first", listOf(file)).isSuccess)
            assertTrue(service.addWebsite("first", "https://example.org/report").isSuccess)
            service.send("Первый вопрос"); runCurrent()
            runtime.events.getValue("first").send(CodingEvent.ToolFinished("web.search", false,
                resultPreview = """[{"title":"Найденный отчёт","url":"https://example.org/found","snippet":"Evidence"}]"""))
            runCurrent()
            assertEquals(2, service.state.value.notebook?.resources?.size)
            assertEquals(1, service.state.value.current?.questionResources?.size,
                "Discovered resources belong to the active question")
            service.composerDraft("first").update(ComposerDraftData("Уточнение первого"))
            assertTrue(service.newQuestion().isSuccess)
            val second = service.state.value.current!!.id
            assertEquals(1, service.state.value.notebooks.size)
            assertEquals(2, service.state.value.questions.size)
            assertFalse(service.state.value.busy)
            service.composerDraft(second).update(ComposerDraftData("Второй вопрос")); runCurrent()
            service.send("Второй вопрос"); runCurrent()
            assertEquals(listOf(file), runtime.calls.last().attachments)
            assertEquals(2, runtime.calls.last().session.resources.size)
            assertEquals(listOf("Второй вопрос"), runtime.calls.last().session.messages.map { it.text })
            runtime.finish("first", "Ответ первому"); runCurrent()
            assertEquals(second, service.state.value.current?.id)
            assertTrue(service.state.value.busy)
            assertEquals(listOf("Второй вопрос"), service.state.value.current?.messages?.map { it.text })
            runtime.finish(second, "Ответ второму"); advanceUntilIdle()
            assertTrue(service.selectQuestion("first").isSuccess)
            assertEquals("Уточнение первого", service.composerDraft("first").state.value.value.text)
            assertEquals("Ответ первому", service.state.value.current?.messages?.last()?.text)
            assertTrue(service.selectQuestion(second).isSuccess)
            service.close()
            closed = true
            val reopened = service(f, runtime, JsonChatRepository(f.kv, f.json))
            try {
                assertEquals(second, reopened.state.value.current?.id)
                assertEquals(2, reopened.state.value.notebook?.resources?.size)
                assertEquals(1, reopened.state.value.questions.first { it.id == "first" }.questionResources.size)
                assertEquals(2, runtime.calls.size, "Restoration never runs a question")
            } finally { reopened.close() }
        } finally { if (!closed) service.close(); Dispatchers.resetMain() }
    }

    @Test fun removingSourceDuringSearchSurvivesLateResultAndReopen() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val f = ModelSettingsFixture()
        val runtime = Runtime()
        val service = service(f, runtime)
        var closed = false
        try {
            assertTrue(service.addWebsite("first", "https://EXAMPLE.org/report#part").isSuccess)
            assertTrue(service.addWebsite("first", "https://example.org/report").isSuccess)
            assertEquals(1, service.state.value.notebook?.resources?.size)
            service.send("Найди сведения"); runCurrent()
            val source = service.state.value.notebook!!.resources.single()
            assertTrue(service.removeResource("first", source.id).isSuccess)
            runtime.events.getValue("first").send(CodingEvent.FinalText("Ответ", sources = listOf(SearchHit("Отчёт", source.url))))
            runtime.events.getValue("first").send(CodingEvent.Finished)
            advanceUntilIdle()
            assertTrue(service.state.value.notebook!!.resources.isEmpty())
            service.send("Уточни вывод"); runCurrent()
            assertEquals(listOf(source.url), runtime.calls.last().session.resources.map { it.url })
            runtime.finish("first", "Уточнение"); advanceUntilIdle()
            service.close()
            closed = true
            val reopened = service(f, runtime, JsonChatRepository(f.kv, f.json))
            try {
                assertTrue(reopened.state.value.notebook!!.resources.isEmpty())
                assertTrue(reopened.addWebsite("first", source.url).isSuccess)
                assertEquals(1, reopened.state.value.notebook?.resources?.size)
            } finally { reopened.close() }
        } finally { if (!closed) service.close(); Dispatchers.resetMain() }
    }

    @Test fun resourceSearchAddsOnlySelectedResultToSharedLibrary() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val f = ModelSettingsFixture()
        val runtime = Runtime()
        val hits = listOf(
            SearchHit("Первый", "https://example.org/one", "One"),
            SearchHit("Второй", "https://example.org/two", "Two"),
        )
        val search = object : SearchEngine {
            override val provider = SearchProvider.WIKIPEDIA
            override val displayName = "Fixture"
            override fun isConfigured(settings: AppSettings) = true
            override suspend fun search(query: String, settings: AppSettings, limit: Int) = hits
        }
        val service = service(f, runtime, search = search)
        try {
            assertEquals(hits, service.searchResources("выбор источника").getOrThrow())
            assertTrue(service.state.value.notebook?.resources.orEmpty().isEmpty())
            assertTrue(service.addSearchResult("first", hits[1]).isSuccess)
            val added = service.state.value.notebook?.resources?.single()
            assertEquals(hits[1].title, added?.title)
            assertEquals(hits[1].url, added?.url)

            assertTrue(service.newQuestion().isSuccess)
            val second = checkNotNull(service.state.value.current).id
            service.send("Вопрос с общим источником")
            runCurrent()
            assertTrue(hits[1].url in runtime.calls.single { it.session.id == second }.session.resources.map { it.url })
            runtime.finish(second, "Ответ")
            advanceUntilIdle()
        } finally { service.close(); Dispatchers.resetMain() }
    }

    @Test fun failedSourceWritePreservesLibraryAndReportsFailure() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val f = ModelSettingsFixture()
        val runtime = Runtime()
        var fail = false
        val repository = object : ChatRepository by f.chats {
            override suspend fun save(session: ChatSession) { if (fail) error("Disk full") else f.chats.save(session) }
        }
        val service = service(f, runtime, repository)
        try {
            assertTrue(service.addWebsite("first", "https://example.org/").isSuccess)
            val source = service.state.value.notebook!!.resources.single()
            fail = true
            assertTrue(service.removeResource("first", source.id).isFailure)
            assertEquals(listOf(source), service.state.value.notebook?.resources)
            assertEquals(listOf(source), f.chats.session("first")?.resources)
            assertNotNull(service.state.value.notice)
            assertTrue(service.addWebsite("first", "javascript:alert(1)").isFailure)
        } finally { service.close(); Dispatchers.resetMain() }
    }

    @Test fun deletingNotebookRemovesEveryQuestionAndItsDraft() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val f = ModelSettingsFixture()
        val runtime = Runtime()
        val service = service(f, runtime)
        try {
            assertTrue(service.newQuestion().isSuccess)
            val second = service.state.value.current!!.id
            val draft = service.composerDraft(second)
            draft.update(ComposerDraftData("Черновик")); runCurrent()
            service.send("Запрос"); runCurrent()
            service.deleteSession("first"); advanceUntilIdle()
            assertTrue(f.chats.sessions().isEmpty())
            assertEquals(setOf("first", second), runtime.deleted.toSet())
            assertNull(service.state.value.current)
            assertFalse(service.state.value.busy)
            assertTrue(service.addWebsite("first", "https://example.org/").isFailure)
            assertTrue(f.chats.sessions().isEmpty())
        } finally { service.close(); Dispatchers.resetMain() }
    }

    @Test fun oldChatBecomesFirstQuestionAndImportsItsAttachmentsOnce() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val f = ModelSettingsFixture()
        f.seed()
        val file = Attachment.fromBytes("old.txt", "text/plain", "Old evidence".encodeToByteArray())
        val old = f.chats.session("first")!!.copy(nativeSessionId = "native-existing", messages = listOf(
            ChatMessage("old-message", ChatRole.USER, "Старый вопрос", 1, attachments = listOf(file))))
        f.chats.save(old)
        val runtime = Runtime()
        val service = service(f, runtime)
        try {
            assertEquals(old.messages, service.state.value.current?.messages)
            assertEquals("native-existing", service.state.value.current?.nativeSessionId)
            assertEquals(file, service.state.value.notebook!!.resources.single().attachment)
            assertTrue(runtime.calls.isEmpty())
            assertTrue(service.removeResource("first", file.id).isSuccess)
            service.start()
            assertTrue(service.state.value.notebook!!.resources.isEmpty())
        } finally { service.close(); Dispatchers.resetMain() }
    }
}
