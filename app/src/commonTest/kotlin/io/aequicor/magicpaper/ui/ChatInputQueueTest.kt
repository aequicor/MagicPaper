package io.aequicor.magicpaper.ui

import io.aequicor.magicpaper.data.coding.NoopCodingRuntime
import io.aequicor.magicpaper.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ChatInputQueueTest {
    private class Backend : CodingRuntime by NoopCodingRuntime {
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
        val service = DefaultChatService(backend, f.chats, f.settings, f.profiles, null,
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
            assertTrue(backend.turns.all { it.first.acquireComputerAccess })
            assertFalse(saved.acquireComputerAccess)
            assertNull(saved.pendingRun)
            assertTrue(saved.queuedPrompts.isEmpty())
            assertEquals(listOf("First", "Later"), saved.messages.filter { it.role == ChatRole.USER }.map { it.text })
        } finally { service.close(); Dispatchers.resetMain() }
    }

    @Test fun restoredChatQueueDoesNotAcquireAutomation() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val f = ModelSettingsFixture(); f.seed()
        f.settings.save(f.settings.load().copy(applicationAccess = ComputerAccess.CONTROL))
        f.chats.save(f.chats.session("first")!!.copy(queuedPrompts = listOf(CodingRunCheckpoint("q", "Saved before crash"))))
        val backend = Backend()
        val service = DefaultChatService(backend, f.chats, f.settings, f.profiles, null, workerDispatcher = Dispatchers.Main)
        try {
            service.start(); service.activate("first"); runCurrent()
            assertTrue(backend.turns.isEmpty(), "Restoring chat must not send")
            service.send("Explicit request"); runCurrent()
            assertTrue(backend.turns.single().first.acquireComputerAccess)
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
        val service = DefaultChatService(backend, f.chats, f.settings, f.profiles, null, workerDispatcher = Dispatchers.Main)
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
            // Reimporting the same queue IDs after deletion must not revive their in-memory authority.
            f.chats.save(exported)
            service.resetDrafts() // Clear the deleted-session tombstone when restoring its data.
            service.start(); service.activate("first"); runCurrent()
            service.send("Explicit request after import"); runCurrent()
            assertEquals(3, backend.turns.size)
            assertTrue(backend.turns.last().first.acquireComputerAccess)
            backend.permits.send(Unit); runCurrent()
            assertEquals("Later", backend.turns.last().second)
            assertFalse(backend.turns.last().first.acquireComputerAccess)
            backend.permits.send(Unit); runCurrent()
        } finally { service.close(); Dispatchers.resetMain() }
    }
}
