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
    @Test fun lateBrowserReadCannotRestoreARemovedAndRecreatedSource() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val f = ModelSettingsFixture()
        val runtime = Runtime()
        val read = CompletableDeferred<ResearchBrowserContent>()
        val browser = object : ResearchPageBrowser {
            override suspend fun open(url: String) = object : ResearchBrowserPage {
                override suspend fun read() = read.await()
                override suspend fun close() = Unit
            }
        }
        val service = service(f, runtime, sourceAccess = ResearchSourceAccess { "Verify you are human" }, sourceBrowser = browser)
        try {
            val url = "https://example.org/article"
            service.addWebsite("first", url).getOrThrow()
            val original = f.chats.session("first")!!.resources.single()
            service.openSourceBrowser("first", original.key)
            advanceUntilIdle()
            service.readSourceBrowser()
            runCurrent()
            service.removeResource("first", original.id).getOrThrow()
            service.addWebsite("first", url).getOrThrow()
            read.complete(ResearchBrowserContent(url, "Old snapshot"))
            advanceUntilIdle()
            assertEquals(ResearchBrowserPhase.FAILED, service.state.value.sourceBrowser?.phase)
            assertContains(service.state.value.sourceBrowser?.problem.orEmpty(), "удалён")
            service.send("Обсудим новый источник")
            advanceUntilIdle()
            assertTrue(runtime.calls.single().session.resources.isEmpty())
            runtime.finish("first", "Пока нет прочитанного материала")
            advanceUntilIdle()
        } finally { service.close(); Dispatchers.resetMain() }
    }

    @Test fun browserReadRecoversAnUnavailableSourceAndSharesItOnlyInsideItsNotebook() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val f = ModelSettingsFixture()
        val runtime = Runtime()
        var pageClosed = false
        val browser = object : ResearchPageBrowser {
            override suspend fun open(url: String) = object : ResearchBrowserPage {
                override suspend fun read() = ResearchBrowserContent(url, "User approved readable article")
                override suspend fun close() { pageClosed = true }
            }
        }
        val service = service(f, runtime, sourceAccess = ResearchSourceAccess { "Verify you are human" }, sourceBrowser = browser)
        try {
            service.addWebsite("first", "https://example.org/article").getOrThrow()
            service.send("Обсудим статью")
            advanceUntilIdle()
            assertTrue(runtime.calls.single().session.resources.isEmpty())
            runtime.finish("first", "Источник пока недоступен")
            advanceUntilIdle()
            val resource = f.chats.session("first")!!.resources.single()
            service.openSourceBrowser("first", resource.key)
            advanceUntilIdle()
            assertEquals(ResearchBrowserPhase.READY, service.state.value.sourceBrowser?.phase)
            service.newQuestion().getOrThrow()
            val sibling = service.state.value.current!!.id
            service.readSourceBrowser()
            advanceUntilIdle()
            assertTrue(pageClosed)
            assertNull(service.state.value.sourceBrowser)
            assertTrue(service.state.value.sourceReadProblems.getValue("first").isEmpty())
            assertNull(f.chats.session("first")!!.resources.single().readableText, "Browser text must not persist in the library")
            service.send("Перескажи выбранную статью")
            advanceUntilIdle()
            assertEquals("User approved readable article", runtime.calls.last().session.resources.single().readableText)
            runtime.finish(sibling, "Пересказ прочитанной статьи")
            advanceUntilIdle()
            service.setResourceEnabled(sibling, resource.key, false).getOrThrow()
            service.send("Продолжим без источников")
            advanceUntilIdle()
            assertTrue(runtime.calls.last().session.resources.isEmpty(), "Browser import must respect selection")
            runtime.finish(sibling, "Ответ без источников")
            advanceUntilIdle()
        } finally { service.close(); Dispatchers.resetMain() }
    }
    @Test fun twoReadableSourcesAreEnoughWhenThreePagesFailAndAToolReadFails() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val f = ModelSettingsFixture()
        val runtime = Runtime()
        val service = service(f, runtime, sourceAccess = ResearchSourceAccess { url -> when {
            url.endsWith("timeout") -> withTimeout(10) { delay(100); "late page" }
            url.endsWith("blocked") -> "Verify you are human"
            url.endsWith("error") -> error("transport failure")
            else -> "Verified article text"
        } })
        try {
            for (source in listOf("ok1", "ok2", "timeout", "blocked", "error"))
                service.addWebsite("first", "https://example.org/$source").getOrThrow()
            service.send("Исследуй вопрос по доступным материалам")
            advanceUntilIdle()
            assertTrue(service.state.value.busy)
            assertEquals(2, runtime.calls.single().session.resources.size)
            assertEquals(3, service.state.value.sourceReadProblems.getValue("first").size)
            runtime.events.getValue("first").send(CodingEvent.ToolStarted("web.read", "", "extra-read"))
            runtime.events.getValue("first").send(CodingEvent.ToolFinished("web.read", true, "extra-read"))
            runtime.finish("first", "Ответ по двум прочитанным источникам")
            advanceUntilIdle()
            val stored = f.chats.session("first")!!
            assertNull(stored.pendingRun)
            assertFalse(service.state.value.busy)
            assertEquals(5, stored.resources.size)
            assertEquals(2, stored.messages.last().sources.size)
            assertContains(stored.messages.last().text, "Ответ по двум прочитанным источникам")
        } finally { service.close(); Dispatchers.resetMain() }
    }

    @Test fun modelFailureHasOneSafeErrorAndCanResumeWithoutLosingSourcesOrDuplicatingTheQuestion() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val f = ModelSettingsFixture()
        val runtime = Runtime()
        val service = service(f, runtime)
        try {
            service.addWebsite("first", "https://example.org/readable").getOrThrow()
            service.send("Исследуй материал")
            advanceUntilIdle()
            runtime.events.getValue("first").send(CodingEvent.Failed(" "))
            runtime.events.getValue("first").send(CodingEvent.Finished)
            advanceUntilIdle()
            val stored = f.chats.session("first")!!
            assertEquals(ExecutionIntent.STOP, stored.pendingRun?.intent)
            assertFalse(service.state.value.busy)
            assertEquals(RESEARCH_MODEL_FAILURE, stored.pendingActivity.single { it.kind == CodingStepKind.ERROR }.title)
            assertContains(service.state.value.notice.orEmpty(), "подключение к модели")
            assertEquals(1, stored.resources.size)
            service.resume()
            advanceUntilIdle()
            assertEquals(2, runtime.calls.size)
            assertEquals(1, runtime.calls.last().session.resources.size)
            runtime.finish("first", "Ответ после восстановления подключения")
            advanceUntilIdle()
            val completed = f.chats.session("first")!!
            assertNull(completed.pendingRun)
            assertEquals(1, completed.messages.count { it.role == ChatRole.USER })
            assertContains(completed.messages.last().text, "Ответ после восстановления подключения")
        } finally { service.close(); Dispatchers.resetMain() }
    }

    @Test fun followUpSendsOnceInItsQuestionAndPreservesTheComposerDraft() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val f = ModelSettingsFixture()
        val runtime = Runtime()
        val service = service(f, runtime)
        try {
            service.send("Помоги разобраться")
            advanceUntilIdle()
            runtime.finish("first", "Ответ.\n\n<!-- magicpaper:follow-ups\n[\"Разобрать пример\",\"Написать статью: Kotlin\"]\n-->")
            advanceUntilIdle()
            val answer = service.state.value.current!!.messages.last()
            assertEquals("Ответ.", answer.text)
            assertEquals(listOf("Разобрать пример", "Написать статью: Kotlin"), answer.followUps)
            assertEquals(answer.followUps, f.chats.session("first")!!.messages.last().followUps)
            val draft = service.composerDraft("first")
            draft.update(ComposerDraftData("Мой незавершённый вопрос"))
            service.sendFollowUp("first", answer.id, "Произвольный запрос")
            service.newQuestion().getOrThrow()
            service.sendFollowUp("first", answer.id, answer.followUps.first())
            advanceUntilIdle()
            assertEquals(1, runtime.calls.size, "An old view cannot send into a newly selected question")
            service.selectQuestion("first").getOrThrow()
            service.sendFollowUp("first", answer.id, answer.followUps.first())
            service.sendFollowUp("first", answer.id, answer.followUps.first())
            advanceUntilIdle()
            assertEquals(2, runtime.calls.size, "A double click must not queue another request")
            assertEquals("Разобрать пример", service.state.value.current!!.messages.last().text)
            assertTrue(service.state.value.current!!.queuedPrompts.isEmpty())
            assertEquals("first", runtime.calls.last().session.id)
            assertEquals("Мой незавершённый вопрос", draft.state.value.value.text)
            runtime.finish("first", "Разбор примера")
            advanceUntilIdle()
            service.sendFollowUp("first", answer.id, answer.followUps.last())
            advanceUntilIdle()
            assertEquals(2, runtime.calls.size, "Only the current answer exposes continuation actions")
        } finally { service.close(); Dispatchers.resetMain() }
    }

    @Test fun aLinkedSummaryReadsOnlyTheRequestedPageAndDoesNotSearchOrAddUnrelatedSources() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val f = ModelSettingsFixture()
        val runtime = Runtime()
        var searches = 0
        val reads = mutableListOf<String>()
        val search = object : SearchEngine {
            override val provider = SearchProvider.AUTO
            override val displayName = "Fixture"
            override fun isConfigured(settings: AppSettings) = true
            override suspend fun search(query: String, settings: AppSettings, limit: Int): List<SearchHit> {
                searches++; return listOf(SearchHit("Unrelated result", "https://example.org/unrelated"))
            }
        }
        val service = service(f, runtime, search = search,
            sourceAccess = ResearchSourceAccess { reads += it; "Readable requested paper" })
        val url = "https://pubmed.ncbi.nlm.nih.gov/37396145/"
        try {
            service.addWebsite("first", "https://example.org/old", ResearchResourceScope.SHARED)
            service.send("$url\nкраткий пересказ")
            advanceUntilIdle()
            assertEquals(0, searches)
            assertEquals(listOf(url), reads)
            assertEquals(listOf(url), runtime.calls.single().session.resources.map { it.url })
            assertEquals(listOf(url), service.state.value.current!!.questionResources.map { it.url })
            runtime.events.getValue("first").send(CodingEvent.ToolFinished("web_search", false,
                sources = listOf(SearchHit("Unrelated native result", "https://example.org/unrequested"))))
            runtime.finish("first", "Пересказ [указанной страницы]($url)")
            advanceUntilIdle()
            assertEquals(listOf(url), service.state.value.current!!.messages.last().sources.map { it.url })
            assertEquals(listOf(url), reads, "Unrequested native references must not expand a source task")
            assertEquals(listOf("https://example.org/old"), service.state.value.notebook!!.resources.map { it.url },
                "A direct source stays with its question instead of becoming shared automatically")
            assertEquals(listOf(url), service.state.value.current!!.questionResources.map { it.url })
            service.send("Уточни последний вывод")
            advanceUntilIdle()
            assertEquals(0, searches, "A follow-up is not automatic discovery")
            runtime.finish("first", "Уточнение")
            advanceUntilIdle()
            service.send("Найди ещё исследования")
            advanceUntilIdle()
            assertEquals(1, searches, "Explicit search remains available")
            runtime.finish("first", "Сравнение")
            advanceUntilIdle()
        } finally { service.close(); Dispatchers.resetMain() }
    }

    @Test fun unreadablePagesAreExcludedBeforeTheNativeRunAndRecheckedOnTheNextQuestion() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val f = ModelSettingsFixture()
        val runtime = Runtime()
        var unlocked = false
        val access = ResearchSourceAccess { url ->
            if (url.endsWith("blocked") && !unlocked) "Verify you are human to continue" else "Verified full page evidence"
        }
        val service = service(f, runtime, sourceAccess = access)
        try {
            service.addWebsite("first", "https://example.org/readable")
            service.addWebsite("first", "https://example.org/blocked")
            service.send("Сравни материалы")
            advanceUntilIdle()
            val call = runtime.calls.single()
            assertEquals(listOf("https://example.org/readable"), call.session.resources.map { it.url })
            assertEquals("Verified full page evidence", call.session.resources.single().readableText)
            assertContains(call.prompt, "Недоступные источники исключены")
            assertFalse("Verify you are human" in call.prompt)
            assertContains(service.state.value.sourceReadProblems.getValue("first").getValue("url:https://example.org/blocked"), "CAPTCHA")
            runtime.finish("first", "UNSUPPORTED_ASSERTION [Статья](https://example.org/blocked)")
            advanceUntilIdle()
            val answer = service.state.value.current!!.messages.last()
            assertFalse("UNSUPPORTED_ASSERTION" in answer.text)
            assertTrue(answer.sources.none { it.url.endsWith("blocked") })
            assertEquals(2, service.state.value.notebook!!.resources.size, "Read failures must not delete user sources")
            unlocked = true
            service.send("Повтори проверку")
            advanceUntilIdle()
            assertEquals(2, runtime.calls.last().session.resources.size)
            assertTrue(service.state.value.sourceReadProblems.getValue("first").isEmpty())
            runtime.finish("first", "Вывод по двум прочитанным страницам")
            advanceUntilIdle()
            assertEquals(2, service.state.value.current!!.messages.last().sources.size)
        } finally { service.close(); Dispatchers.resetMain() }
    }

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
        search: SearchEngine? = null, pins: RequestPinService? = null,
        sourceAccess: ResearchSourceAccess = ResearchSourceAccess { "Readable fixture evidence" },
        sourceBrowser: ResearchPageBrowser? = null): DefaultChatService {
        f.seed()
        return DefaultChatService(runtime, repository, f.settings, f.profiles, pins,
            workerDispatcher = Dispatchers.Main, draftRepository = f.draftRepository, draftBlobs = f.draftBlobs,
            researchSearch = search, sourceAccess = sourceAccess, sourceBrowser = sourceBrowser)
            .also { it.start(); it.activate("first") }
    }

    @Test fun researchDoesNotGenerateHiddenPinSummaries() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val f = ModelSettingsFixture()
        val runtime = Runtime()
        var syncs = 0
        var removals = 0
        val pins = object : RequestPinService {
            override val groups = kotlinx.coroutines.flow.MutableStateFlow<Map<PinConversation, List<RequestPinGroup>>>(emptyMap())
            override fun isTracking(conversation: PinConversation) = true
            override fun sync(conversation: PinConversation, messages: List<PinMessage>, profile: LlmProfile?, reopened: Boolean) { syncs++ }
            override fun remove(conversation: PinConversation) { removals++ }
            override fun clear() { removals++ }
        }
        val service = service(f, runtime, pins = pins)
        try {
            service.setVisible(true)
            service.send("Исследовательский вопрос")
            runCurrent()
            runtime.finish("first", "Ответ")
            advanceUntilIdle()
            service.setVisible(false)
            service.activate("first")
            runCurrent()
            assertEquals(0, syncs, "Reading and streaming research must not request obsolete pin summaries")
            assertEquals(0, removals, "Existing stored pins are not deleted by opening a research chat")
        } finally { service.close(); Dispatchers.resetMain() }
    }

    @Test fun firstSearchBuildsSharedLibraryAndBecomesAnswerFootnotes() = runTest {
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
            assertEquals(hit.url, service.state.value.notebook?.resources?.single()?.url)
            assertTrue(service.state.value.current?.questionResources.orEmpty().isEmpty())
            assertEquals(hit.url, runtime.calls.single().session.resources.single().url)
            runtime.finish("first", "Обзор")
            advanceUntilIdle()
            assertEquals(listOf(hit.copy(snippet = "")), service.state.value.current?.messages?.last()?.sources,
                "Only the checked page is cited; the search snippet is not evidence")
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
            service.send("Исходный вопрос"); runCurrent()
            runtime.finish("first", "Исходная база"); advanceUntilIdle()
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
                assertEquals(3, runtime.calls.size, "Restoration never runs a question")
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
            assertTrue(runtime.calls.last().session.resources.isEmpty(), "Late discoveries must not resurrect removed resources")
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
    @Test fun scopedFilesAndSelectionsSurviveReopenWithoutChangingOtherQuestionsOrCitations() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val f = ModelSettingsFixture()
        val runtime = Runtime()
        val service = service(f, runtime)
        var closed = false
        try {
            val shared = Attachment.fromBytes("shared.txt", "text/plain", "Shared".encodeToByteArray())
            val local = Attachment.fromBytes("local.txt", "text/plain", "Local".encodeToByteArray())
            service.addResources("first", listOf(shared)).getOrThrow()
            service.addWebsite("first", "https://example.org/shared").getOrThrow()
            service.newQuestion().getOrThrow()
            val second = service.state.value.current!!.id
            service.addResources(second, listOf(local), ResearchResourceScope.QUESTION).getOrThrow()
            val source = service.state.value.notebook!!.resources.first { it.url.isNotBlank() }
            service.send("Второй вопрос"); runCurrent()
            assertEquals(setOf(shared.id, local.id), runtime.calls.last().attachments.map { it.id }.toSet())
            runtime.finish(second, "Ответ со ссылкой"); advanceUntilIdle()
            val historicalSources = service.state.value.current!!.messages.last().sources
            service.setResourceEnabled(second, source.key, false).getOrThrow()
            service.setResourceEnabled(second, "file:${shared.id}", false).getOrThrow()
            service.send("Уточнение"); runCurrent()
            assertEquals(listOf(local.id), runtime.calls.last().attachments.map { it.id })
            assertTrue(runtime.calls.last().session.resources.none { it.url == source.url })
            runtime.finish(second, "Ответ только по локальному файлу"); advanceUntilIdle()
            service.selectQuestion("first").getOrThrow()
            service.send("Первый вопрос"); runCurrent()
            assertEquals(listOf(shared.id), runtime.calls.last().attachments.map { it.id })
            assertTrue(runtime.calls.last().session.resources.any { it.url == source.url })
            runtime.finish("first", "Ответ первому"); advanceUntilIdle()
            service.close(); closed = true
            val restored = service(f, runtime, JsonChatRepository(f.kv, f.json))
            try {
                restored.selectQuestion(second).getOrThrow()
                assertEquals(setOf(source.key, "file:${shared.id}"), restored.state.value.current!!.disabledResourceKeys)
                assertEquals(historicalSources, restored.state.value.current!!.messages[1].sources)
                assertEquals(local.id, restored.state.value.current!!.questionResources.single().attachment?.id)
            } finally { restored.close() }
        } finally { if (!closed) service.close(); Dispatchers.resetMain() }
    }

    @Test fun groupSelectionIsAtomicAndKeepsOtherGroupsAndQuestionsIndependent() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val f = ModelSettingsFixture()
        val runtime = Runtime()
        var writes = 0
        val repository = object : ChatRepository by f.chats {
            override suspend fun save(session: ChatSession) { writes++; f.chats.save(session) }
        }
        val service = service(f, runtime, repository)
        var closed = false
        try {
            val sharedFile = Attachment.fromBytes("shared.txt", "text/plain", "Shared".encodeToByteArray())
            service.addResources("first", listOf(sharedFile)).getOrThrow()
            service.addWebsite("first", "https://example.org/shared").getOrThrow()
            service.newQuestion().getOrThrow()
            val second = service.state.value.current!!.id
            service.addWebsite(second, "https://example.org/local-1", ResearchResourceScope.QUESTION).getOrThrow()
            service.addWebsite(second, "https://example.org/local-2", ResearchResourceScope.QUESTION).getOrThrow()
            val sharedKeys = service.state.value.notebook!!.resources.map { it.key }.toSet()
            val localKeys = service.state.value.current!!.questionResources.map { it.key }.toSet()
            val disabledLocal = localKeys.first()
            service.setResourceEnabled(second, disabledLocal, false).getOrThrow()

            writes = 0
            service.setResourcesEnabled(second, sharedKeys, false).getOrThrow()
            assertEquals(1, writes, "A group change is one durable edit, not one write per source")
            assertEquals(sharedKeys + disabledLocal, service.state.value.current!!.disabledResourceKeys)
            assertEquals(localKeys - disabledLocal, service.state.value.current!!
                .availableResearchResources(service.state.value.notebook!!).map { it.key }.toSet())

            service.setResourcesEnabled(second, sharedKeys, true).getOrThrow()
            assertEquals(setOf(disabledLocal), service.state.value.current!!.disabledResourceKeys,
                "Selecting shared sources must preserve the local group's partial selection")
            service.setResourcesEnabled(second, localKeys, false).getOrThrow()
            assertEquals(localKeys, service.state.value.current!!.disabledResourceKeys)
            service.setResourcesEnabled(second, localKeys, true).getOrThrow()
            assertTrue(service.state.value.current!!.disabledResourceKeys.isEmpty())

            writes = 0
            assertTrue(service.setResourcesEnabled(second, sharedKeys + "url:https://example.org/missing", false).isFailure)
            assertEquals(0, writes, "An invalid selection cannot partially disable the valid sources")
            assertTrue(service.state.value.current!!.disabledResourceKeys.isEmpty())
            assertNotNull(service.state.value.notice)
            service.setResourcesEnabled(second, sharedKeys, false).getOrThrow()
            service.selectQuestion("first").getOrThrow()
            assertTrue(service.state.value.current!!.disabledResourceKeys.isEmpty(),
                "Disabling shared sources in one question must not disable them in other questions")
            assertEquals(sharedKeys, service.state.value.current!!
                .availableResearchResources(service.state.value.notebook!!).map { it.key }.toSet())
            assertTrue(runtime.calls.isEmpty(), "Changing source selection must not start a request")

            service.close(); closed = true
            val restored = service(f, runtime, JsonChatRepository(f.kv, f.json))
            try {
                restored.selectQuestion(second).getOrThrow()
                assertEquals(sharedKeys, restored.state.value.current!!.disabledResourceKeys)
                assertEquals(localKeys, restored.state.value.current!!
                    .availableResearchResources(restored.state.value.notebook!!).map { it.key }.toSet())
            } finally { restored.close() }
        } finally { if (!closed) service.close(); Dispatchers.resetMain() }
    }

    @Test fun promotionDeduplicatesResourcesAndKeepsDisabledSelectionInOriginalQuestion() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val f = ModelSettingsFixture()
        val runtime = Runtime()
        val service = service(f, runtime)
        try {
            service.newQuestion().getOrThrow()
            val second = service.state.value.current!!.id
            service.addWebsite(second, "https://example.org/local", ResearchResourceScope.QUESTION).getOrThrow()
            val resource = service.state.value.current!!.questionResources.single()
            service.setResourceEnabled(second, resource.key, false).getOrThrow()
            service.shareResource(second, resource.id).getOrThrow()
            assertTrue(service.state.value.current!!.questionResources.isEmpty())
            assertEquals(listOf(resource), service.state.value.notebook!!.resources)
            assertTrue(service.state.value.current!!.availableResearchResources(service.state.value.notebook!!).isEmpty())
            service.selectQuestion("first").getOrThrow()
            assertEquals(listOf(resource), service.state.value.current!!.availableResearchResources(service.state.value.notebook!!))
            service.addWebsite("first", resource.url).getOrThrow()
            assertEquals(1, service.state.value.notebook!!.resources.size)
        } finally { service.close(); Dispatchers.resetMain() }
    }

    @Test fun laterSearchIsLocalAndCannotReenableADisabledSharedSource() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val f = ModelSettingsFixture()
        val runtime = Runtime()
        val common = SearchHit("Обзор", "https://example.org/overview")
        val extra = SearchHit("Детали", "https://example.org/details")
        var hits = listOf(common)
        val search = object : SearchEngine {
            override val provider = SearchProvider.WIKIPEDIA
            override val displayName = "Fixture"
            override fun isConfigured(settings: AppSettings) = true
            override suspend fun search(query: String, settings: AppSettings, limit: Int) = hits
        }
        val service = service(f, runtime, search = search)
        try {
            service.send("Общая тема"); runCurrent()
            runtime.finish("first", "Обзор"); advanceUntilIdle()
            val resource = service.state.value.notebook!!.resources.single()
            assertTrue(service.state.value.current!!.messages.last().researchActivity.any { it.tool == "web.search" && !it.running })
            service.newQuestion().getOrThrow()
            val second = service.state.value.current!!.id
            service.setResourceEnabled(second, resource.key, false).getOrThrow()
            hits = listOf(common, extra)
            service.send("Узкий вопрос"); runCurrent()
            assertEquals(listOf(extra.url), runtime.calls.last().session.resources.map { it.url })
            assertEquals(listOf(resource), service.state.value.notebook!!.resources)
            assertEquals(listOf(extra.url), service.state.value.current!!.questionResources.map { it.url })
            runtime.finish(second, "Детали"); advanceUntilIdle()
        } finally { service.close(); Dispatchers.resetMain() }
    }

    @Test fun liveOperationsAndInterruptedActivityBelongToTheirQuestionAndPersist() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val f = ModelSettingsFixture()
        val runtime = Runtime()
        val service = service(f, runtime)
        try {
            service.send("Исследуй"); runCurrent()
            runtime.events.getValue("first").send(CodingEvent.ToolStarted("web.search", "", "search", title = "Ищу источники"))
            runtime.events.getValue("first").send(CodingEvent.ThinkingDelta("Private detailed reasoning"))
            runtime.events.getValue("first").send(CodingEvent.TextDelta("Начало ответа"))
            runCurrent()
            val live = service.state.value.drafts.getValue("first")
            assertTrue(live.steps.any { it.tool == "web.search" && it.running })
            service.newQuestion().getOrThrow()
            assertNull(service.state.value.drafts[service.state.value.current?.id])
            service.selectQuestion("first").getOrThrow()
            service.pause(); advanceUntilIdle()
            val saved = f.chats.session("first")!!
            assertEquals(ExecutionIntent.STOP, saved.pendingRun?.intent)
            assertTrue(saved.pendingActivity.any { it.tool == "web.search" && !it.running })
            assertFalse(saved.pendingActivity.any { it.title.contains("Private") })
            assertFalse(service.state.value.busy)
        } finally { service.close(); Dispatchers.resetMain() }
    }

    @Test fun removingRootQuestionLocalSourceDoesNotExcludeItFromOtherQuestions() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val f = ModelSettingsFixture()
        val runtime = Runtime()
        val service = service(f, runtime)
        try {
            service.send("Общая тема"); runCurrent()
            runtime.finish("first", "Обзор"); advanceUntilIdle()
            service.addWebsite("first", "https://example.org/local", ResearchResourceScope.QUESTION).getOrThrow()
            val local = service.state.value.current!!.questionResources.single()
            service.removeResource("first", local.id, ResearchResourceScope.QUESTION).getOrThrow()
            assertTrue(service.state.value.notebook!!.excludedResourceUrls.isEmpty())
            service.newQuestion().getOrThrow()
            val second = service.state.value.current!!.id
            service.send("Другой вопрос"); runCurrent()
            runtime.events.getValue(second).send(CodingEvent.ToolFinished("web.search", false,
                sources = listOf(SearchHit("Материал", local.url))))
            runCurrent()
            assertEquals(local.url, service.state.value.current!!.questionResources.single().url)
            runtime.finish(second, "Ответ"); advanceUntilIdle()
        } finally { service.close(); Dispatchers.resetMain() }
    }

    @Test fun firstQueryFromANewQuestionStillBuildsSharedBase() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val f = ModelSettingsFixture()
        val runtime = Runtime()
        val service = service(f, runtime)
        try {
            service.newQuestion().getOrThrow()
            val second = service.state.value.current!!.id
            service.send("Первый запрос всего исследования"); runCurrent()
            runtime.events.getValue(second).send(CodingEvent.ToolFinished("web.search", false,
                sources = listOf(SearchHit("База", "https://example.org/base"))))
            runCurrent()
            assertEquals("https://example.org/base", service.state.value.notebook!!.resources.single().url)
            assertTrue(service.state.value.current!!.questionResources.isEmpty())
            runtime.finish(second, "Ответ"); advanceUntilIdle()
        } finally { service.close(); Dispatchers.resetMain() }
    }

    @Test fun choosingLocalSearchResultForSharedScopeMovesItWithoutDuplicateOrDisabledCopy() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val f = ModelSettingsFixture()
        val runtime = Runtime()
        val service = service(f, runtime)
        try {
            service.newQuestion().getOrThrow()
            val question = service.state.value.current!!.id
            val hit = SearchHit("Материал", "https://example.org/report")
            service.addSearchResult(question, hit, ResearchResourceScope.QUESTION).getOrThrow()
            val source = service.state.value.current!!.questionResources.single()
            service.setResourceEnabled(question, source.key, false).getOrThrow()
            service.addSearchResult(question, hit, ResearchResourceScope.SHARED).getOrThrow()
            assertTrue(service.state.value.current!!.questionResources.isEmpty())
            assertTrue(service.state.value.current!!.disabledResourceKeys.isEmpty())
            assertEquals(hit.url, service.state.value.notebook!!.resources.single().url)
        } finally { service.close(); Dispatchers.resetMain() }
    }

}
