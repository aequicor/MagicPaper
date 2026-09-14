package io.aequicor.magicpaper.ui

import io.aequicor.magicpaper.data.coding.*
import io.aequicor.magicpaper.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class SessionInputQueueTest {
    private class Backend : CodingRuntime by NoopCodingRuntime {
        override val supported = true
        val turns = mutableListOf<Pair<CodingSession, String>>()
        val permits = Channel<Unit>(Channel.UNLIMITED)
        var cleanup: CompletableDeferred<Unit>? = null
        override fun run(project: CodingProject, session: CodingSession, prompt: String, profile: LlmProfile?, attachments: List<Attachment>) = flow {
            turns += session to prompt
            emit(CodingEvent.SessionStarted("native"))
            try {
                permits.receive()
                emit(CodingEvent.FinalText("Done")); emit(CodingEvent.Finished)
            } finally { withContext(NonCancellable) { cleanup?.await() } }
        }
    }

    @Test fun queueIsDurableAndStartsAfterPreviousCompletionWithoutClearingNewDraft() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val f = ModelSettingsFixture(); f.seed()
        val repo = JsonCodingProjectRepository(f.kv, f.json)
        repo.save(CodingProject("p", "Project", "/fixture", 1))
        repo.saveSession(CodingSession("s", "p", "Session", 1, engine = CodingEngine.PI))
        val backend = Backend()
        val service = f.prepareCoding(backend, repo)
        try {
            service.sendCodingPromptTo("s", "First"); runCurrent()
            val draft = service.composerDraft("s")
            draft.text.value = "Second"; draft.awaitSaved()
            service.sendCodingPromptTo("s", "Second"); runCurrent()
            assertEquals(1, backend.turns.size)
            assertEquals(listOf("Second"), JsonCodingProjectRepository(f.kv, f.json).sessions("p").single().queuedPrompts.map { it.prompt })
            draft.text.value = "New typing"
            backend.permits.send(Unit); runCurrent()
            assertEquals(listOf("First", "Second"), backend.turns.map { it.second })
            assertEquals("native", backend.turns.last().first.piSessionId)
            assertEquals("New typing", draft.text.value)
            assertTrue(repo.sessions("p").single().queuedPrompts.isEmpty())
            backend.permits.send(Unit); runCurrent()
            assertNull(repo.sessions("p").single().pendingRun)
        } finally { service.close(); Dispatchers.resetMain() }
    }

    @Test fun clarificationWaitsForCleanupAndKeepsQueuedWorkSeparate() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val f = ModelSettingsFixture(); f.seed()
        val repo = JsonCodingProjectRepository(f.kv, f.json)
        repo.save(CodingProject("p", "Project", "/fixture", 1))
        repo.saveSession(CodingSession("s", "p", "Session", 1, engine = CodingEngine.PI))
        val backend = Backend(); val cleanup = CompletableDeferred<Unit>(); backend.cleanup = cleanup
        val service = f.prepareCoding(backend, repo)
        try {
            service.sendCodingPromptTo("s", "First"); runCurrent()
            service.sendCodingPromptTo("s", "Later"); runCurrent()
            service.clarifyCodingSession("s", "Keep the original files"); runCurrent()
            assertEquals(1, backend.turns.size)
            assertContains(repo.sessions("p").single().pendingRun!!.prompt, "Keep the original files")
            cleanup.complete(Unit); runCurrent()
            assertEquals(2, backend.turns.size)
            assertContains(backend.turns.last().second, "Keep the original files")
            assertEquals(listOf("Later"), repo.sessions("p").single().queuedPrompts.map { it.prompt })
            backend.permits.send(Unit); runCurrent()
            assertEquals("Later", backend.turns.last().second)
            backend.permits.send(Unit); runCurrent()
        } finally { cleanup.complete(Unit); service.close(); Dispatchers.resetMain() }
    }
}
