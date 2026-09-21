package io.aequicor.magicpaper.ui

import io.aequicor.magicpaper.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ChatInputQueueTest {
    private class Backend : ChatBackend {
        override fun abort(sessionId: String) = Unit
        // This deterministic fixture proves that cancellation leaves no external operation in flight.
        override suspend fun inspectSavedResponse(request: ChatMachine.RunRef) = ChatSavedResponse.Interrupted
        val turns = mutableListOf<Triple<ChatSession, String, LlmProfile?>>()
        val permits = Channel<Unit>(Channel.UNLIMITED)
        override fun runChat(session: ChatSession, prompt: String, profile: LlmProfile?, attachments: List<Attachment>) = flow {
            turns += Triple(session, prompt, profile)
            emit(CodingEvent.SessionStarted("native-chat"))
            permits.receive()
            emit(CodingEvent.FinalText("Done")); emit(CodingEvent.Finished)
        }
    }

    @Test fun pauseResumeAndQueuePreserveHistoryNativeIdentityModelAndNewTyping() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val f = ModelSettingsFixture(); f.seed()
        val backend = Backend()
        val service = DefaultChatService(backend, f.chatStore, f.settings, f.profiles, null,
            workerDispatcher = Dispatchers.Main, draftRepository = f.draftRepository, draftBlobs = f.draftBlobs)
        try {
            service.start(); service.activate("first")
            service.send("First"); runCurrent()
            service.send("Later"); runCurrent()
            assertEquals(listOf("Later"), f.chats.session("first")!!.queuedPrompts.map { it.prompt })
            service.pause(); runCurrent()
            assertFalse(service.state.value.busy)
            assertEquals(ExecutionIntent.STOP, f.chats.session("first")!!.pendingRun!!.intent)
            assertEquals(1, backend.turns.size)
            service.selectChatModel(ModelSelection("anthropic", "claude-sonnet-4-6")); runCurrent()
            service.resume("Preserve the files"); runCurrent()
            assertContains(backend.turns.last().second, "Preserve the files")
            assertEquals("native-chat", backend.turns.last().first.nativeSessionId)
            assertEquals("claude-sonnet-4-6", backend.turns.last().third?.modelId)
            val draft = service.composerDraft("first")
            draft.update(ComposerDraftData("New typing")); runCurrent()
            backend.permits.send(Unit); runCurrent()
            assertEquals("Later", backend.turns.last().second)
            assertEquals("New typing", draft.state.value.value.text)
            backend.permits.send(Unit); runCurrent()
            val saved = f.chats.session("first")!!
            assertTrue(backend.turns.none { it.first.acquireComputerAccess }, "Provider chat never acquires native automation")
            assertFalse(saved.acquireComputerAccess)
            assertNull(saved.pendingRun)
            assertTrue(saved.queuedPrompts.isEmpty())
            assertEquals(listOf("First\n\nУточнение пользователя: Preserve the files", "Later"), saved.messages.filter { it.role == ChatRole.USER }.map { it.text })
        } finally { service.close(); Dispatchers.resetMain() }
    }

    @Test fun restoredChatQueueDoesNotAcquireAutomation() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val f = ModelSettingsFixture(); f.seed()
        f.settings.save(f.settings.load().copy(applicationAccess = ComputerAccess.CONTROL))
        f.chats.save(f.chats.session("first")!!.copy(queuedPrompts = listOf(CodingRunCheckpoint("q", "Saved before crash"))))
        val backend = Backend()
        val service = DefaultChatService(backend, f.chatStore, f.settings, f.profiles, null, workerDispatcher = Dispatchers.Main)
        try {
            service.start(); service.activate("first"); runCurrent()
            assertTrue(backend.turns.isEmpty(), "Restoring chat must not send")
            service.send("Explicit request"); runCurrent()
            assertFalse(backend.turns.single().first.acquireComputerAccess)
            backend.permits.send(Unit); runCurrent()
            assertEquals("Saved before crash", backend.turns.last().second)
            assertFalse(backend.turns.last().first.acquireComputerAccess)
            backend.permits.send(Unit); runCurrent()
        } finally { service.close(); Dispatchers.resetMain() }
    }

    @Test fun liveClarificationKeepsQueuedWorkAndDeletionCannotStartIt() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val f = ModelSettingsFixture(); f.seed()
        val backend = Backend()
        val service = DefaultChatService(backend, f.chatStore, f.settings, f.profiles, null, workerDispatcher = Dispatchers.Main)
        try {
            service.start(); service.activate("first")
            service.send("First"); runCurrent()
            service.send("Later"); runCurrent()
            service.clarify("Use the selected model"); runCurrent()
            assertEquals(2, backend.turns.size)
            assertContains(backend.turns.last().second, "Use the selected model")
            assertEquals(listOf("Later"), f.chats.session("first")!!.queuedPrompts.map { it.prompt })
            val exported = f.chats.session("first")!!.copy(pendingRun = null)
            service.deleteSession("first"); runCurrent()
            assertNull(f.chats.session("first"))
            assertEquals(2, backend.turns.size)
            // A raw compatibility snapshot cannot revive a tombstoned aggregate or its queued work.
            f.chats.save(exported)
            service.resetDrafts()
            service.start(); service.activate("first"); runCurrent()
            assertNull(service.state.value.current)
            assertEquals(2, backend.turns.size)
            assertFailsWith<ChatCommandRejected> { service.importNotebooks(listOf(exported)) }
            assertEquals(2, backend.turns.size)

        } finally { service.close(); Dispatchers.resetMain() }
    }

    @Test fun explicitLeaveStoppedPreservesPartialHistoryAndStartsNothingUntilNewSend() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val f = ModelSettingsFixture(); f.seed()
        val pending = CodingRunCheckpoint("interrupted", "Old request", responseId = "partial-answer")
        val partial = TranscriptBlock.Markdown("partial", "Already received text")
        val activity = CodingStep(CodingStepKind.INFO, "Observed operation")
        f.chats.save(f.chats.session("first")!!.copy(pendingRun = pending,
            pendingContent = listOf(partial), pendingActivity = listOf(activity),
            queuedPrompts = listOf(CodingRunCheckpoint("queued", "Queued before interruption"))))
        val backend = Backend()
        val service = DefaultChatService(backend, f.chatStore, f.settings, f.profiles, null, workerDispatcher = Dispatchers.Main)
        try {
            service.start(); service.activate("first"); runCurrent()
            assertTrue(backend.turns.isEmpty())
            service.discardPendingRequest(); runCurrent()
            val saved = checkNotNull(f.chats.session("first"))
            assertNull(saved.pendingRun)
            assertEquals(listOf("queued"), saved.queuedPrompts.map { it.messageId })
            val response = saved.messages.single { it.id == "partial-answer" }
            assertEquals("Already received text", response.text)
            assertEquals(listOf(partial), response.content)
            assertEquals(listOf(activity), response.researchActivity)
            assertTrue(backend.turns.isEmpty(), "Leaving a stopped request must not run queued work")
            service.send("New explicit request"); runCurrent()
            assertEquals("New explicit request", backend.turns.single().second)
            assertNotEquals(pending.runId, backend.turns.single().first.pendingRun?.runId)
        } finally { service.close(); Dispatchers.resetMain() }
    }
}
