package io.aequicor.magicpaper.ui

import io.aequicor.magicpaper.data.coding.*
import io.aequicor.magicpaper.data.coding.NoopCodingRuntime
import io.aequicor.magicpaper.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ConversationHistoryTest {
    private val project = CodingProject("p", "Project", "/fixture", 1)
    private val session = CodingSession("s", "p", "Task", 1, piSessionId = "old-native", engine = CodingEngine.CODEX, worktreeEnabled = false)
    private val history = listOf(
        CodingMessage("u1", CodingRole.USER, "keep-first", createdAt = 1),
        CodingMessage("a1", CodingRole.AGENT, "remove-answer", createdAt = 2),
        CodingMessage("u2", CodingRole.USER, "old-question", createdAt = 3),
        CodingMessage("a2", CodingRole.AGENT, "old-result", createdAt = 4),
    )

    private class Runtime : CodingRuntime by NoopCodingRuntime {
        val coding = mutableListOf<Pair<CodingSession, String>>()
        val chats = mutableListOf<ChatSession>()
        val inputs = mutableListOf<List<Attachment>>()
        var gate: CompletableDeferred<Unit>? = null
        override val supported = true
        override suspend fun status() = RuntimeStatus(RuntimePhase.READY)
        override fun run(project: CodingProject, session: CodingSession, prompt: String, profile: LlmProfile?, attachments: List<Attachment>) = flow {
            coding += session to prompt; inputs += attachments
            emit(CodingEvent.SessionStarted("fresh-${session.id}"))
            gate?.await()
            emit(CodingEvent.FinalText("new-result")); emit(CodingEvent.Finished)
        }
        override fun runChat(session: ChatSession, prompt: String, profile: LlmProfile?, attachments: List<Attachment>) = flow {
            chats += session; inputs += attachments
            emit(CodingEvent.SessionStarted("fresh-${session.id}"))
            gate?.await()
            emit(CodingEvent.FinalText("new-result")); emit(CodingEvent.Finished)
        }
    }

    @Test fun switchingEngineSurvivesRestartAndSeedsSavedDialogueWithoutReplayingIt() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val f = ModelSettingsFixture(); val repo = JsonCodingProjectRepository(f.kv, f.json)
        repo.save(project); repo.saveSession(session); repo.saveMessages("p", "s", history)
        val runtime = Runtime()
        var service: DefaultCodingService? = null
        try {
            service = f.prepareCoding(runtime, repo); runCurrent()
            service.changeCodingEngine("s", CodingEngine.PI); runCurrent()
            val switched = repo.sessions("p").single()
            assertEquals(CodingEngine.PI, switched.engine)
            assertEquals("", switched.piSessionId)
            assertTrue(switched.needsHistorySeed)
            assertEquals(history, repo.messages("p", "s"))
            assertTrue(runtime.coding.isEmpty())
            service.close()

            service = f.prepareCoding(runtime, JsonCodingProjectRepository(f.kv, f.json)); runCurrent()
            service.sendCodingPromptTo("s", "next-question"); advanceUntilIdle()
            val (sent, prompt) = runtime.coding.single()
            assertEquals(CodingEngine.PI, sent.engine)
            assertEquals("", sent.piSessionId)
            assertContains(prompt, "keep-first")
            assertContains(prompt, "old-result")
            assertContains(prompt, "next-question")
            assertEquals(1, repo.messages("p", "s").count { it.text == "keep-first" })
        } finally { service?.close(); Dispatchers.resetMain() }
    }

    @Test fun newSessionCanChangeEngineBeforeItsFirstRequest() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val f = ModelSettingsFixture(); val repo = JsonCodingProjectRepository(f.kv, f.json)
        val fresh = session.copy(piSessionId = "", engine = CodingEngine.PI)
        repo.save(project); repo.saveSession(fresh)
        val runtime = Runtime()
        val service = f.prepareCoding(runtime, repo)
        try {
            runCurrent()
            val historyBefore = repo.messages("p", "s")
            service.changeCodingEngine("s", CodingEngine.CODEX); runCurrent()
            assertEquals(CodingEngine.CODEX, repo.sessions("p").single().engine)
            assertEquals(historyBefore, repo.messages("p", "s"))
            assertTrue(runtime.coding.isEmpty())
            service.sendCodingPromptTo("s", "first task"); advanceUntilIdle()
            assertEquals(CodingEngine.CODEX, runtime.coding.single().first.engine)
        } finally { service.close(); Dispatchers.resetMain() }
    }

    @Test fun deletingEitherRoleSurvivesRestartAndSeedsOnlyRemainingCodingContext() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val f = ModelSettingsFixture()
        val repo = JsonCodingProjectRepository(f.kv, f.json)
        repo.save(project); repo.saveSession(session); repo.saveMessages("p", "s", history)
        val runtime = Runtime()
        var service: DefaultCodingService? = null
        try {
            service = f.prepareCoding(runtime, repo); runCurrent()
            assertTrue(service.deleteMessage("s", "a1").isSuccess)
            assertTrue(service.deleteMessage("s", "u2").isSuccess)
            assertTrue(runtime.coding.isEmpty())
            service.close()
            val reopened = JsonCodingProjectRepository(f.kv, f.json)
            // A planner replay cannot recreate explicitly removed identities.
            reopened.saveMessages("p", "s", history)
            assertEquals(listOf("u1", "a2"), reopened.messages("p", "s").map { it.id })
            service = f.prepareCoding(runtime, reopened); runCurrent()
            service.sendCodingPromptTo("s", "next-question"); advanceUntilIdle()
            val (sent, prompt) = runtime.coding.single()
            assertEquals("", sent.piSessionId)
            assertContains(prompt, "keep-first")
            assertContains(prompt, "old-result")
            assertFalse(prompt.contains("remove-answer"))
            assertFalse(prompt.contains("old-question"))
        } finally { service?.close(); Dispatchers.resetMain() }
    }

    @Test fun codingEditTruncatesFutureAndResendsAttachmentsWithoutClearingNewDraft() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val f = ModelSettingsFixture(); val repo = JsonCodingProjectRepository(f.kv, f.json)
        val file = Attachment.fromBytes("notes.txt", "text/plain", "file contents".encodeToByteArray())
        val image = Attachment.fromBytes("image.png", "image/png", byteArrayOf(1, 2, 3))
        val attachments = listOf(file, image)
        val messages = history.map { if (it.id == "u2") it.copy(attachments = attachments.map { file -> file.asMeta() }, inputAttachments = attachments) else it }
        repo.save(project); repo.saveSession(session); repo.saveMessages("p", "s", messages)
        val runtime = Runtime(); val service = f.prepareCoding(runtime, repo)
        try {
            runCurrent()
            val draft = service.composerDraft("s"); draft.awaitSaved(); draft.text.value = "unsent draft"; draft.awaitSaved()
            assertTrue(service.editMessage("s", "u2", "corrected-question").isSuccess)
            advanceUntilIdle()
            assertEquals(listOf("keep-first", "remove-answer", "corrected-question", "new-result"), repo.messages("p", "s").map { it.text })
            assertEquals(attachments, runtime.inputs.single())
            val edited = repo.messages("p", "s").first { it.text == "corrected-question" }
            val invocation = assertNotNull(runtime.coding.single().first.pendingRun)
            assertEquals(invocation.runId, edited.images.single().invocationId)
            assertEquals(invocation.responseTimelineId, edited.images.single().timelineId)
            assertTrue(edited.images.single().isInputFor(edited))
            assertEquals("", runtime.coding.single().first.piSessionId)
            assertFalse(runtime.coding.single().second.contains("old-result"))
            assertEquals("unsent draft", draft.text.value)
            assertTrue(service.editMessage("s", "a1", "tampered").isFailure)
        } finally { service.close(); Dispatchers.resetMain() }
    }

    @Test fun forkOfRunningSessionCopiesChosenHistoryWithoutExecutionOrAuthority() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val f = ModelSettingsFixture(); val repo = JsonCodingProjectRepository(f.kv, f.json)
        repo.save(project); repo.saveSession(session); repo.saveMessages("p", "s", history)
        val runtime = Runtime().apply { gate = CompletableDeferred() }
        val service = f.prepareCoding(runtime, repo)
        try {
            runCurrent(); service.sendCodingPromptTo("s", "running-question"); runCurrent()
            assertTrue(service.deleteMessage("s", "u1").isFailure)
            val forkId = service.forkSession("s", "a1").getOrThrow(); runCurrent()
            val fork = repo.sessions("p").first { it.id == forkId }
            assertEquals(listOf("keep-first", "remove-answer"), repo.messages("p", forkId).map { it.text })
            assertEquals("", fork.piSessionId); assertNull(fork.pendingRun); assertTrue(fork.queuedPrompts.isEmpty())
            assertNull(fork.organismId); assertNull(fork.planId); assertNull(fork.parentSessionId); assertNull(fork.taskWorktree)
            assertTrue(fork.needsHistorySeed); assertFalse(fork.acquireComputerAccess)
            assertEquals(1, runtime.coding.size)
            assertEquals("running-question", repo.messages("p", "s").last().text)
        } finally { runtime.gate?.complete(Unit); runCurrent(); service.close(); Dispatchers.resetMain() }
    }

    @Test fun failedHistoryWriteKeepsMessagesAndDoesNotLaunchEditedRequest() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val f = ModelSettingsFixture(); val stored = JsonCodingProjectRepository(f.kv, f.json)
        stored.save(project); stored.saveSession(session); stored.saveMessages("p", "s", history)
        val owner = journalCodingProjects(f.kv, f.json, f.chatJournal, Dispatchers.Main, stored)
        val repo = object : CodingProjectOwner by owner {
            override suspend fun dispatch(projectId: String, input: CodingMachine.Input): CodingMachine.Transition {
                if (input is CodingMachine.Intent.EditRequest) error("disk unavailable")
                return owner.dispatch(projectId, input)
            }
        }
        val runtime = Runtime(); val service = f.prepareCoding(runtime, repo)
        try {
            runCurrent()
            assertTrue(service.editMessage("s", "u2", "corrected").isFailure)
            assertEquals(history, stored.messages("p", "s"))
            assertTrue(runtime.coding.isEmpty()); assertNotNull(service.state.value.notice)
            assertEquals("old-native", stored.sessions("p").single().piSessionId, "A rejected atomic edit preserves the previous native identity")
        } finally { service.close(); Dispatchers.resetMain() }
    }

    @Test fun chatEditDeleteAndForkUseIndependentDurableContext() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val f = ModelSettingsFixture(); f.seed()
        val old = ChatSession("first", "Chat", 1, 4, history.map {
            ChatMessage(it.id, if (it.role == CodingRole.USER) ChatRole.USER else ChatRole.AGENT, it.text, it.createdAt)
        }, engine = CodingEngine.CODEX, nativeSessionId = "old-native")
        f.chats.save(old)
        val runtime = Runtime()
        val service = DefaultChatService(runtime, f.chatStore, f.settings, f.profiles, null, workerDispatcher = Dispatchers.Main)
        try {
            service.start(); service.activate("first")
            val forkId = service.forkSession("first", "a1").getOrThrow()
            val fork = assertNotNull(f.chats.session(forkId))
            assertEquals(listOf("keep-first", "remove-answer"), fork.messages.map { it.text })
            assertEquals("", fork.nativeSessionId); assertNull(fork.pendingRun)
            assertTrue(service.deleteMessage("first", "a1").isSuccess)
            assertTrue(service.editMessage("first", "u2", "corrected-question").isSuccess)
            advanceUntilIdle()
            val call = runtime.chats.single()
            assertEquals("", call.nativeSessionId)
            assertEquals(listOf("keep-first", "corrected-question"), call.messages.map { it.text })
            assertEquals(listOf("keep-first", "corrected-question", "new-result"), f.chats.session("first")!!.messages.map { it.text })
            assertEquals(fork, f.chats.session(forkId))
            assertTrue(service.editMessage(forkId, fork.messages.last().id, "agent edit").isFailure)
            assertEquals(fork, f.chats.session(forkId))
        } finally { service.close(); Dispatchers.resetMain() }
    }

    @Test fun forkingQuestionPreservesNotebookMediaPolicyAndIndependentAssetReference() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val f = ModelSettingsFixture(); f.seed()
        val shared = ResearchResource("shared", "Shared", "https://example.com/shared")
        val excluded = "https://example.com/excluded"
        val root = ChatSession("root", "Research", 1, 1, resources = listOf(shared),
            excludedResourceUrls = setOf(excluded), researchResourcesInitialized = true,
            selectedQuestionId = "question", mediaTools = SessionMediaTools(images = false, videos = false))
        val asset = MediaAsset("a".repeat(64), "image/png", 100, 640, 480)
        val media = GeneratedMedia("original-media", MediaKind.IMAGE, MediaPhase.READY, asset = asset)
        val question = ChatSession("question", "Question", 1, 2, researchParentId = root.id,
            nativeSessionId = "old-native", disabledResourceKeys = setOf(shared.key),
            messages = listOf(ChatMessage("answer", ChatRole.AGENT, "Before\n\nAfter", 2, content = listOf(
                TranscriptBlock.Markdown("before", "Before"), TranscriptBlock.Media(media.id, media),
                TranscriptBlock.Markdown("after", "After")))))
        f.chats.save(root); f.chats.save(question)
        val runtime = Runtime()
        val service = DefaultChatService(runtime, f.chatStore, f.settings, f.profiles, null, workerDispatcher = Dispatchers.Main)
        try {
            service.start(); service.activate(question.id)
            val forkId = service.forkSession(question.id, "answer").getOrThrow()
            val fork = assertNotNull(f.chats.session(forkId))
            assertEquals(root.mediaTools, fork.mediaTools)
            assertEquals(root.resources, fork.resources)
            assertEquals(root.excludedResourceUrls, fork.excludedResourceUrls)
            assertEquals(question.disabledResourceKeys, fork.disabledResourceKeys)
            assertNull(fork.researchParentId); assertNull(fork.selectedQuestionId)
            assertNull(fork.pendingRun); assertTrue(fork.queuedPrompts.isEmpty())
            assertEquals("", fork.nativeSessionId)
            val blocks = fork.messages.single().content
            assertEquals(listOf("Before", "After"), blocks.filterIsInstance<TranscriptBlock.Markdown>().map { it.text })
            val copiedMedia = (blocks[1] as TranscriptBlock.Media).media
            assertNotEquals(media.id, copiedMedia.id)
            assertEquals(asset, copiedMedia.asset)
            service.deleteSession(root.id); advanceUntilIdle()
            assertNull(f.chats.session(question.id))
            assertEquals(fork, f.chats.session(forkId))
            assertEquals(listOf(asset), assertNotNull(f.chats.session(forkId)).generatedMediaAssets())
            assertTrue(runtime.chats.isEmpty())
        } finally { service.close(); Dispatchers.resetMain() }
    }

    @Test fun finishingChatDuringForkDoesNotStrandAcceptedQueue() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val f = ModelSettingsFixture(); f.seed()
        val forkWrite = CompletableDeferred<Unit>()
        val repo = object : ChatCheckpointStore by f.chats {
            override suspend fun save(session: ChatSession) {
                if (session.id != "first") forkWrite.await()
                f.chats.save(session)
            }
        }
        val runtime = Runtime().apply { gate = CompletableDeferred() }
        val service = DefaultChatService(runtime, f.newChatStore(repo), f.settings, f.profiles, null, workerDispatcher = Dispatchers.Main)
        try {
            service.start(); service.activate("first")
            service.send("first request"); runCurrent()
            service.send("queued request"); runCurrent()
            val fork = async { service.forkSession("first") }; runCurrent()
            runtime.gate!!.complete(Unit); runCurrent()
            assertEquals(1, runtime.chats.size)
            forkWrite.complete(Unit); assertTrue(fork.await().isSuccess); advanceUntilIdle()
            assertEquals(2, runtime.chats.size)
            assertTrue(f.chats.session("first")!!.queuedPrompts.isEmpty())
            assertEquals(listOf("first request", "new-result", "queued request", "new-result"), f.chats.session("first")!!.messages.map { it.text })
        } finally { forkWrite.complete(Unit); runtime.gate?.complete(Unit); service.close(); Dispatchers.resetMain() }
    }
}
