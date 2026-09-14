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
    private val image = Attachment.fromBytes("clipboard.png", "image/png", byteArrayOf(1, 2, 3))
    private val qwen = LlmProfile("qwen", "Token Plan", modelId = "qwen3.7-plus",
        codingModelId = "qwen3.7-max", baseUrl = "https://token-intl.aliyuncs.com/compatible-mode/v1",
        provider = ProviderType.OPENAI_COMPATIBLE, modelLibraryVersion = 1)

    @Test fun unsupportedImageKeepsDraftAndCanBeSentAfterSelectingVisionModel() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        var service: DefaultCodingService? = null
        try {
            val f = ModelSettingsFixture(); f.seed(); f.profiles.save(qwen)
            val repo = JsonCodingProjectRepository(f.kv, f.json)
            repo.save(project); repo.saveSession(session.copy(engine = CodingEngine.PI, llmProfileId = qwen.id))
            val runtime = Runtime().apply { gate.complete(Unit) }
            val first = f.prepareCoding(runtime, repo); service = first; runCurrent()
            val draft = first.composerDraft(session.id); draft.awaitSaved()
            draft.text.value = "Что изображено?"; draft.attachments.value = listOf(image)
            draft.awaitSaved()
            first.sendCodingPromptTo(session.id, draft.text.value, draft.attachments.value); runCurrent()

            assertTrue(runtime.calls.isEmpty(), "Reject before starting an agent or sending provider requests")
            assertTrue(repo.messages(project.id, session.id).isEmpty())
            assertNull(repo.sessions(project.id).single().pendingRun)
            assertContains(assertNotNull(first.state.value.notice), "модель с поддержкой изображений")
            first.close()

            val next = f.prepareCoding(runtime, JsonCodingProjectRepository(f.kv, f.json)); service = next; runCurrent()
            val restored = next.composerDraft(session.id); restored.awaitSaved(); runCurrent()
            assertEquals("Что изображено?", restored.text.value)
            assertEquals(listOf(image), restored.attachments.value)
            next.selectCodingModel(session.id, ModelSelection(qwen.id, "qwen3.7-plus")); runCurrent()
            next.sendCodingPromptTo(session.id, restored.text.value, restored.attachments.value); runCurrent()

            assertEquals(1, runtime.calls.size)
            assertEquals(listOf(image), runtime.attachments.single())
            assertEquals("", restored.text.value)
            assertTrue(restored.attachments.value.isEmpty())
        } finally { service?.close(); Dispatchers.resetMain() }
    }

    @Test fun continuingUnsupportedImagePreservesCheckpointAndNativeIdentity() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        var service: DefaultCodingService? = null
        try {
            val f = ModelSettingsFixture(); f.seed(); f.profiles.save(qwen)
            val repo = JsonCodingProjectRepository(f.kv, f.json)
            val request = CodingRunCheckpoint("request", "Что изображено?", listOf(image),
                intent = ExecutionIntent.STOP, stoppedByUser = true)
            val stopped = session.copy(llmProfileId = qwen.id, pendingRun = request, piSessionId = "existing-native")
            repo.save(project); repo.saveSession(stopped)
            val runtime = Runtime().apply { gate.complete(Unit) }
            val model = f.prepareCoding(runtime, repo); service = model; runCurrent()
            assertTrue(model.state.value.coding.currentSession!!.canResume)
            model.resumeCodingSession(session.id); runCurrent()

            assertTrue(runtime.calls.isEmpty())
            assertTrue(runtime.reconciled.isEmpty())
            assertEquals(request, repo.sessions(project.id).single().pendingRun)
            assertEquals("existing-native", repo.sessions(project.id).single().piSessionId)
            assertContains(assertNotNull(model.state.value.notice), "модель с поддержкой изображений")
            model.selectCodingModel(session.id, ModelSelection(qwen.id, "qwen3.7-plus")); runCurrent()
            model.resumeCodingSession(session.id); runCurrent()
            assertEquals("existing-native", runtime.calls.single().first.piSessionId)
            assertEquals(listOf(image), runtime.attachments.single())
        } finally { service?.close(); Dispatchers.resetMain() }
    }

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
        var vm: DefaultCodingService? = null
        try {
            val f = ModelSettingsFixture(); val repo = JsonCodingProjectRepository(f.kv, f.json)
            repo.save(project); repo.saveSession(session)
            val runtime = Runtime().also { it.stopGate = stopGate }
            val model = f.prepareCoding(runtime, repo)
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
            val vm = f.prepareCoding(first, repo); runCurrent()
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
            // A restarted process has a fresh repository and cache.
            val restoredRepository = JsonCodingProjectRepository(f.kv, f.json)
            val next = f.prepareCoding(nextRuntime, restoredRepository); runCurrent()
            assertEquals(1, nextRuntime.calls.size)
            assertEquals("native-session", nextRuntime.calls.single().first.piSessionId)
            assertEquals(listOf(session.id), nextRuntime.reconciled)
            assertEquals(listOf(file), nextRuntime.attachments.single())
            assertEquals(1, restoredRepository.messages(project.id, session.id).count { it.role == CodingRole.USER })
            nextRuntime.gate.complete(Unit); runCurrent()
            val response = restoredRepository.messages(project.id, session.id).single { it.role == CodingRole.AGENT }
            assertEquals("Done", response.text)
            assertEquals(listOf(CodingStepKind.THINKING, CodingStepKind.ANSWER), response.steps.map { it.kind })
            assertEquals("Checked saved context", response.steps.first().title)
            assertNull(restoredRepository.sessions(project.id).single().pendingRun)
            assertFalse(next.state.value.coding.currentSession!!.canResume)
            next.shutdownCoding()
        } finally { Dispatchers.resetMain() }
    }

    @Test fun explicitStopSurvivesRestartAndContinueResumesOnlyOnceWithNewInstruction() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val f = ModelSettingsFixture(); val repo = JsonCodingProjectRepository(f.kv, f.json)
            repo.save(project); repo.saveSession(session)
            val vm = f.prepareCoding(Runtime(), repo); runCurrent()
            vm.sendCodingPromptTo(session.id, "Original task"); runCurrent()
            vm.abortCodingSession(session.id); runCurrent()
            assertEquals(ExecutionIntent.STOP, repo.sessions(project.id).single().pendingRun?.intent)
            assertTrue(vm.state.value.coding.currentSession!!.canResume)
            vm.shutdownCoding()
            val runtime = Runtime(); val next = f.prepareCoding(runtime, repo); runCurrent()
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
            val vm = f.prepareCoding(runtime, repo); runCurrent()
            vm.sendCodingPromptTo(session.id, "Task"); runCurrent()
            assertTrue(repo.messages(project.id, session.id).last().failed)
            assertEquals(ExecutionIntent.STOP, repo.sessions(project.id).single().pendingRun?.intent)
            assertFalse(vm.state.value.coding.currentSession!!.canResume)
            assertEquals(InteractionKind.RECOVER_RUN, vm.state.value.coding.interactions.single().kind)
            vm.shutdownCoding()
            val afterRestart = Runtime(); val next = f.prepareCoding(afterRestart, repo); runCurrent()
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
                val runtime = Runtime(); val vm = f.prepareCoding(runtime, repo); runCurrent()
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
            val runtime = Runtime(); val vm = f.prepareCoding(runtime, repo); runCurrent()
            assertEquals(setOf("session", "background"), runtime.calls.map { it.first.id }.toSet())
            vm.shutdownCoding()
        } finally { Dispatchers.resetMain() }
    }
}
