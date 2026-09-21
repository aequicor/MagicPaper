package io.aequicor.magicpaper.ui

import io.aequicor.magicpaper.data.coding.JsonCodingProjectRepository
import io.aequicor.magicpaper.data.coding.journalCodingProjects
import io.aequicor.magicpaper.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class CodingResearchModeTest {
    private val project = CodingProject("p", "Project", "/fixture", 1)
    private val session = CodingSession("s", "p", "Task", 1, piSessionId = "old-editable", engine = CodingEngine.PI)
    private class Runtime(previous: List<NativeRunRecoveryItem> = emptyList()) : CodingRuntime {
        val recoveryItems = previous.toMutableList()
        override val recovery = object : NativeRunRecovery {
            override suspend fun acknowledgeNoDispatch(proof: NativeRunNoDispatchProof, parentDecisionId: String): NativeRunNoDispatchAcknowledgement = error("Unexpected no-dispatch recovery")
            override suspend fun inspect(sessionId: String) = NativeRunRecoverySnapshot(
                recoveryItems.filter { it.ref.sessionId == sessionId }, persistenceUnknown = false)
            override suspend fun stop(ref: NativeRunRecoveryRef): NativeRunRecoverySnapshot {
                check(recoveryItems.single { it.ref == ref }.termination == NativeRunTermination.STOPPED)
                return inspect(ref.sessionId)
            }
            override suspend fun acknowledge(ref: NativeRunRecoveryRef, parentDecisionId: String): NativeRunRecoveryAcknowledgement {
                check(recoveryItems.single { it.ref == ref }.termination == NativeRunTermination.STOPPED)
                return NativeRunRecoveryAcknowledgement("ack-$parentDecisionId", ref, parentDecisionId)
            }
        }
        val calls = mutableListOf<Pair<CodingSession, String>>()
        val gate = CompletableDeferred<Unit>()
        override val supported = true
        override val rootPath = "/fixture"
        override suspend fun status() = RuntimeStatus(RuntimePhase.READY)
        override fun ensureReady() = flowOf(RuntimeStatus(RuntimePhase.READY))
        override suspend fun uninstall() = Unit
        override fun abort(sessionId: String) = Unit
        override fun abortAll() = Unit
        override fun run(project: CodingProject, session: CodingSession, prompt: String, profile: LlmProfile?, attachments: List<Attachment>) = flow {
            val ref = NativeRunRecoveryRef(checkNotNull(session.engine), session.id, checkNotNull(session.pendingRun).runId, 0)
            recoveryItems += NativeRunRecoveryItem(ref, NativeRunOutcome.UNKNOWN, NativeRunTermination.LIVE, null)
            calls += session to prompt
            try {
                emit(CodingEvent.SessionStarted("new-native"))
                gate.await()
                emit(CodingEvent.FinalText("Research answer")); emit(CodingEvent.Finished)
                val index = recoveryItems.indexOfFirst { it.ref == ref }
                recoveryItems[index] = recoveryItems[index].copy(outcome = NativeRunOutcome.SUCCEEDED)
            } finally {
                val index = recoveryItems.indexOfFirst { it.ref == ref }
                recoveryItems[index] = recoveryItems[index].copy(termination = NativeRunTermination.STOPPED)
            }
        }
    }
    @Test fun researchHasFreshNativeContextAndSavedRequestModeIncludingRecovery() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        var vm: DefaultCodingService? = null
        try {
            val f = ModelSettingsFixture(); val repo = JsonCodingProjectRepository(f.kv, f.json)
            repo.save(project); repo.saveSession(session)
            repo.saveMessages("p", "s", listOf(CodingMessage("old", CodingRole.USER, "Remember the original requirement", createdAt = 1),
                CodingMessage("answer", CodingRole.AGENT, "Understood", createdAt = 2)))
            val runtime = Runtime(); val first = f.prepareCoding(runtime, repo); vm = first; runCurrent()
            first.changeCodingInteractionMode("s", CodingInteractionMode.RESEARCH); runCurrent()
            assertTrue(repo.sessions("p").single().researchMode, "notice=${first.state.value.notice}; sessions=${first.state.value.coding.sessions}")
            first.sendCodingPromptTo("s", "Explain source.kt"); runCurrent()
            assertEquals("", runtime.calls.single().first.piSessionId)
            assertContains(runtime.calls.single().second, "Remember the original requirement")
            assertEquals(CodingInteractionMode.RESEARCH, repo.sessions("p").single().pendingRun?.interactionMode)
            first.changeCodingInteractionMode("s", CodingInteractionMode.CODE); runCurrent()
            assertTrue(repo.sessions("p").single().researchMode)
            first.shutdownCoding(); runCurrent()
            val originalRequest = repo.sessions("p").single().pendingRun!!.runId
            val recovered = Runtime(runtime.recoveryItems); val next = f.prepareCoding(recovered, repo); vm = next; runCurrent()
            assertTrue(recovered.calls.isEmpty(), "Restoring research never starts an agent")
            val recovery = next.state.value.coding.interactions.single { it.kind == InteractionKind.RECOVER_RUN }
            next.submitQuestionnaire(recovery.id, listOf(PlanningAnswer("decision", listOf("retry")))); runCurrent()
            assertNotEquals(originalRequest, recovered.calls.single().first.pendingRun!!.runId)
            assertEquals(CodingInteractionMode.RESEARCH, recovered.calls.single().first.forPendingRun().interactionMode)
            assertEquals("new-native", recovered.calls.single().first.piSessionId)
            recovered.gate.complete(Unit); runCurrent()
            next.changeCodingInteractionMode("s", CodingInteractionMode.CODE); runCurrent()
            assertEquals(CodingInteractionMode.CODE, repo.sessions("p").single().interactionMode)
            assertEquals("", repo.sessions("p").single().piSessionId)
            assertTrue(repo.messages("p", "s").any { it.text == "Research answer" })
        } finally { vm?.shutdownCoding(); Dispatchers.resetMain() }
    }
    @Test fun legacyStopDoesNotAuthorizeModeChangeOrCloseUnknownRequest() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        var vm: DefaultCodingService? = null
        try {
            val f = ModelSettingsFixture(); val repo = JsonCodingProjectRepository(f.kv, f.json)
            repo.save(project); repo.saveSession(session.copy(researchMode = true, pendingRun = CodingRunCheckpoint("old", "Read",
                intent = ExecutionIntent.STOP, stoppedByUser = true, interactionMode = CodingInteractionMode.RESEARCH)))
            val runtime = Runtime(); val model = f.prepareCoding(runtime, repo); vm = model; runCurrent()
            model.changeCodingInteractionMode("s", CodingInteractionMode.CODE); runCurrent()
            assertNotNull(repo.sessions("p").single().pendingRun)
            assertEquals(CodingInteractionMode.RESEARCH, repo.sessions("p").single().interactionMode)
            assertEquals(CodingMachine.Phase.UNKNOWN, model.state.value.coding.currentSession!!.runPhase)
            assertTrue(runtime.calls.isEmpty())
            assertNotNull(model.state.value.notice)
        } finally { vm?.shutdownCoding(); Dispatchers.resetMain() }
    }
    @Test fun persistedCheckpointMismatchFailsWithoutCallingRuntime() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        var vm: DefaultCodingService? = null
        try {
            val f = ModelSettingsFixture(); val repo = JsonCodingProjectRepository(f.kv, f.json)
            repo.save(project); repo.saveSession(session.copy(pendingRun = CodingRunCheckpoint("old", "Read",
                interactionMode = CodingInteractionMode.RESEARCH)))
            val runtime = Runtime(); vm = f.prepareCoding(runtime, repo); runCurrent()
            assertTrue(runtime.calls.isEmpty())
            assertEquals(ExecutionIntent.RUN, repo.sessions("p").single().pendingRun?.intent,
                "Restore preserves the request; RUN is not proof that it may be replayed")
            assertEquals(CodingMachine.Phase.UNKNOWN, vm.state.value.coding.currentSession!!.runPhase)
            assertEquals(CodingInteractionMode.CODE, repo.sessions("p").single().interactionMode)
            assertEquals(CodingInteractionMode.RESEARCH, repo.sessions("p").single().pendingRun?.interactionMode)
        } finally { vm?.shutdownCoding(); Dispatchers.resetMain() }
    }
    @Test fun currentPlanningAuthorityCannotBeRevertedByAnOldUiSelection() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        var vm: DefaultCodingService? = null
        try {
            val f = ModelSettingsFixture(); val cache = JsonCodingProjectRepository(f.kv, f.json)
            cache.save(project); cache.saveSession(session.copy(researchMode = true))
            val owner = journalCodingProjects(f.kv, f.json, f.chatJournal, StandardTestDispatcher(testScheduler), cache)
            val runtime = Runtime(); val model = f.prepareCoding(runtime, owner); vm = model; runCurrent()
            owner.dispatch("p", CodingMachine.Intent.ChangeMode(CodingMachine.ref(owner.sessions("p").single()), CodingInteractionMode.PLANNING))
            model.changeCodingInteractionMode("s", CodingInteractionMode.RESEARCH); runCurrent()
            assertEquals(CodingInteractionMode.PLANNING, owner.sessions("p").single().interactionMode)
            assertTrue(runtime.calls.isEmpty())
            assertNotNull(model.state.value.notice)
        } finally { vm?.shutdownCoding(); Dispatchers.resetMain() }
    }

}
