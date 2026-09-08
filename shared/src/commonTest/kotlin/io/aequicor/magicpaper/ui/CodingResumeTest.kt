package io.aequicor.magicpaper.ui

import io.aequicor.magicpaper.data.coding.JsonCodingProjectRepository
import io.aequicor.magicpaper.data.coding.PiEventParser
import io.aequicor.magicpaper.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class CodingResumeTest {
    private val project = CodingProject("project", "Project", "/fake", 1)
    private val session = CodingSession("session", project.id, "Task", 1, engine = CodingEngine.CODEX)
    private class Runtime : CodingRuntime {
        val calls = mutableListOf<Pair<CodingSession, String>>()
        val attachments = mutableListOf<List<Attachment>>()
        val reconciled = mutableListOf<String>()
        val gate = CompletableDeferred<Unit>()
        var stopGate: CompletableDeferred<Unit>? = null
        var failure = false
        var reply = listOf<CodingEvent>(CodingEvent.FinalText("Done"))
        override val supported = true
        override val rootPath = "/fake"
        override suspend fun status() = RuntimeStatus(RuntimePhase.READY)
        override fun ensureReady() = flowOf(RuntimeStatus(RuntimePhase.READY))
        override suspend fun uninstall() = Unit
        override fun abort(sessionId: String) = Unit
        override fun abortAll() = Unit
        override suspend fun reconcile(sessionId: String) { reconciled += sessionId }
        override fun run(project: CodingProject, session: CodingSession, prompt: String, profile: LlmProfile?, attachments: List<Attachment>) = flow {
            calls += session to prompt
            this@Runtime.attachments += attachments
            emit(CodingEvent.SessionStarted("native-${session.id}"))
            try {
                gate.await()
                if (failure) emit(CodingEvent.Failed("Offline")) else reply.forEach { emit(it) }
                emit(CodingEvent.Finished)
            } finally {
                stopGate?.let { withContext(NonCancellable) { it.await() } }
            }
        }
    }

    @Test fun cancelledRunKeepsItsSessionUntilCleanupFinishes() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val stopGate = CompletableDeferred<Unit>()
        var vm: MagicPaperViewModel? = null
        try {
            val f = ModelSettingsFixture(); val repo = JsonCodingProjectRepository(f.kv, f.json)
            repo.save(project); repo.saveSession(session)
            val runtime = Runtime().also { it.stopGate = stopGate }
            val model = f.prepare(runtime, repo)
            vm = model
            runCurrent()
            model.sendCodingPromptTo(session.id, "First request"); runCurrent()
            model.abortCodingSession(session.id); runCurrent()

            // Cancellation has begun, but the native runtime is still shutting down.
            model.sendCodingPromptTo(session.id, "Premature request"); runCurrent()
            assertEquals(1, runtime.calls.size, "A stopping run still owns its native session")
            assertEquals("First request", repo.sessions(project.id).single().pendingRun?.prompt)

            stopGate.complete(Unit); runCurrent()
            assertFalse(model.state.value.coding.currentSession!!.running)
            model.resumeCodingSession(session.id); runCurrent()
            assertEquals(2, runtime.calls.size)
            assertTrue(model.state.value.coding.currentSession!!.running)
            runtime.gate.complete(Unit); runCurrent()
            assertNull(repo.sessions(project.id).single().pendingRun)
        } finally {
            stopGate.complete(Unit)
            vm?.shutdownCoding()
            Dispatchers.resetMain()
        }
    }

    @Test fun restartContinuesSavedNativeSessionAndAttachmentsWithoutDuplicatingRequest() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val f = ModelSettingsFixture()
            val repo = JsonCodingProjectRepository(f.kv, f.json)
            repo.save(project); repo.saveSession(session.copy(engine = CodingEngine.PI))
            val first = Runtime()
            val vm = f.prepare(first, repo); runCurrent()
            val file = Attachment("file", "notes.txt", "text/plain", 3, "YWJj", AttachmentKind.TEXT)
            vm.sendCodingPromptTo(session.id, "Finish the task", listOf(file)); runCurrent()
            assertEquals("native-session", repo.sessions(project.id).single().piSessionId)
            assertEquals(ExecutionIntent.RUN, repo.sessions(project.id).single().pendingRun?.intent)
            vm.shutdownCoding(); runCurrent()
            val nextRuntime = Runtime().apply {
                // Pi streams thinking/text, then repeats both in the final message snapshot.
                reply = listOf(
                    """{"type":"message_start","message":{"role":"assistant","content":[]}}""",
                    """{"type":"message_update","assistantMessageEvent":{"type":"thinking_delta","delta":"Checking saved context"}}""",
                    """{"type":"message_update","assistantMessageEvent":{"type":"text_delta","delta":"Done"}}""",
                    """{"type":"message_end","message":{"role":"assistant","content":[{"type":"thinking","thinking":"Checked saved context"},{"type":"text","text":"Done"}],"stopReason":"stop"}}""",
                    """{"type":"agent_end"}""",
                ).flatMap(PiEventParser::parseEvents)
            }
            val next = f.prepare(nextRuntime, JsonCodingProjectRepository(f.kv, f.json)); runCurrent()
            assertEquals(1, nextRuntime.calls.size)
            assertEquals("native-session", nextRuntime.calls.single().first.piSessionId)
            assertEquals(listOf(session.id), nextRuntime.reconciled)
            assertEquals(listOf(file), nextRuntime.attachments.single())
            assertEquals(1, repo.messages(project.id, session.id).count { it.role == CodingRole.USER })
            nextRuntime.gate.complete(Unit); runCurrent()
            val response = repo.messages(project.id, session.id).single { it.role == CodingRole.AGENT }
            assertEquals("Done", response.text)
            assertEquals(listOf(CodingStepKind.THINKING, CodingStepKind.ANSWER), response.steps.map { it.kind })
            assertEquals("Checked saved context", response.steps.first().title)
            assertNull(repo.sessions(project.id).single().pendingRun)
            assertFalse(next.state.value.coding.currentSession!!.canResume)
            next.shutdownCoding()
        } finally { Dispatchers.resetMain() }
    }

    @Test fun explicitStopSurvivesRestartAndContinueResumesOnlyOnceWithNewInstruction() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val f = ModelSettingsFixture(); val repo = JsonCodingProjectRepository(f.kv, f.json)
            repo.save(project); repo.saveSession(session)
            val vm = f.prepare(Runtime(), repo); runCurrent()
            vm.sendCodingPromptTo(session.id, "Original task"); runCurrent()
            vm.abortCodingSession(session.id); runCurrent()
            assertEquals(ExecutionIntent.STOP, repo.sessions(project.id).single().pendingRun?.intent)
            assertTrue(vm.state.value.coding.currentSession!!.canResume)
            vm.shutdownCoding()
            val runtime = Runtime(); val next = f.prepare(runtime, repo); runCurrent()
            assertTrue(runtime.calls.isEmpty())
            next.resumeCodingSession(session.id, "Keep the existing result")
            next.resumeCodingSession(session.id, "Keep the existing result"); runCurrent()
            assertEquals(1, runtime.calls.size)
            assertContains(runtime.calls.single().second, "Original task")
            assertContains(runtime.calls.single().second, "Keep the existing result")
            assertEquals(2, repo.messages(project.id, session.id).count { it.role == CodingRole.USER })
            next.shutdownCoding()
        } finally { Dispatchers.resetMain() }
    }

    @Test fun failureWaitsForContinueAndDoesNotAutomaticallyLoopOnRestart() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val f = ModelSettingsFixture(); val repo = JsonCodingProjectRepository(f.kv, f.json)
            repo.save(project); repo.saveSession(session)
            val runtime = Runtime().also { it.failure = true; it.gate.complete(Unit) }
            val vm = f.prepare(runtime, repo); runCurrent()
            vm.sendCodingPromptTo(session.id, "Task"); runCurrent()
            assertTrue(repo.messages(project.id, session.id).last().failed)
            assertEquals(ExecutionIntent.STOP, repo.sessions(project.id).single().pendingRun?.intent)
            assertFalse(vm.state.value.coding.currentSession!!.canResume)
            assertEquals(InteractionKind.RECOVER_RUN, vm.state.value.coding.interactions.single().kind)
            vm.shutdownCoding()
            val afterRestart = Runtime(); val next = f.prepare(afterRestart, repo); runCurrent()
            assertTrue(afterRestart.calls.isEmpty())
            val recovery = next.state.value.coding.interactions.single()
            next.submitQuestionnaire(recovery.id, listOf(PlanningAnswer("decision", listOf("retry")))); runCurrent()
            assertEquals(1, afterRestart.calls.size)
            next.shutdownCoding()
        } finally { Dispatchers.resetMain() }
    }

    @Test fun responseSavedBeforeShutdownPreventsReplayingAnAlreadyFinishedTurn() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            for (failed in listOf(false, true)) {
                val f = ModelSettingsFixture(); val repo = JsonCodingProjectRepository(f.kv, f.json)
                repo.save(project)
                repo.saveSession(session.copy(pendingRun = CodingRunCheckpoint("request", "Task", responseId = "response")))
                repo.saveMessages(project.id, session.id, listOf(
                    CodingMessage("request", CodingRole.USER, "Task", createdAt = 1),
                    CodingMessage("response", CodingRole.AGENT, "Result", createdAt = 2, failed = failed)))
                val runtime = Runtime(); val vm = f.prepare(runtime, repo); runCurrent()
                assertTrue(runtime.calls.isEmpty())
                assertFalse(vm.state.value.coding.currentSession!!.canResume)
                assertEquals(failed, vm.state.value.coding.interactions.any { it.kind == InteractionKind.RECOVER_RUN })
                if (failed) assertEquals(ExecutionIntent.STOP, repo.sessions(project.id).single().pendingRun?.intent)
                else assertNull(repo.sessions(project.id).single().pendingRun)
                vm.shutdownCoding()
            }
        } finally { Dispatchers.resetMain() }
    }

    @Test fun automaticRecoveryIncludesUnopenedProjectsButSkipsArchivedAndPausedSessions() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val f = ModelSettingsFixture(); val repo = JsonCodingProjectRepository(f.kv, f.json)
            repo.save(project)
            val other = project.copy(id = "other"); repo.save(other)
            val request = CodingRunCheckpoint("message", "Task")
            repo.saveSession(session.copy(pendingRun = request))
            repo.saveSession(session.copy(id = "background", projectId = other.id, pendingRun = request.copy(messageId = "other")))
            repo.saveSession(session.copy(id = "paused", pendingRun = request.copy(intent = ExecutionIntent.PAUSE)))
            repo.saveSession(session.copy(id = "archived", archived = true, pendingRun = request))
            val runtime = Runtime(); val vm = f.prepare(runtime, repo); runCurrent()
            assertEquals(setOf("session", "background"), runtime.calls.map { it.first.id }.toSet())
            vm.shutdownCoding()
        } finally { Dispatchers.resetMain() }
    }
}
