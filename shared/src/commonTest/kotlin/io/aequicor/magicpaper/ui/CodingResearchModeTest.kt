package io.aequicor.magicpaper.ui

import io.aequicor.magicpaper.data.coding.JsonCodingProjectRepository
import io.aequicor.magicpaper.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class CodingResearchModeTest {
    private val project = CodingProject("p", "Project", "/fixture", 1)
    private val session = CodingSession("s", "p", "Task", 1, piSessionId = "old-editable", engine = CodingEngine.PI)
    private class Runtime : CodingRuntime {
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
            calls += session to prompt
            emit(CodingEvent.SessionStarted("new-native"))
            gate.await()
            emit(CodingEvent.FinalText("Research answer")); emit(CodingEvent.Finished)
        }
    }
    @Test fun researchHasFreshNativeContextAndSavedRequestModeIncludingRecovery() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        var vm: MagicPaperViewModel? = null
        try {
            val f = ModelSettingsFixture(); val repo = JsonCodingProjectRepository(f.kv, f.json)
            repo.save(project); repo.saveSession(session)
            repo.saveMessages("p", "s", listOf(CodingMessage("old", CodingRole.USER, "Remember the original requirement", createdAt = 1),
                CodingMessage("answer", CodingRole.AGENT, "Understood", createdAt = 2)))
            val runtime = Runtime(); val first = f.prepare(runtime, repo); vm = first; runCurrent()
            first.changeCodingInteractionMode("s", CodingInteractionMode.RESEARCH); runCurrent()
            assertTrue(repo.sessions("p").single().researchMode, "notice=${first.state.value.notice}; sessions=${first.state.value.coding.sessions}")
            first.sendCodingPromptTo("s", "Explain source.kt"); runCurrent()
            assertEquals("", runtime.calls.single().first.piSessionId)
            assertContains(runtime.calls.single().second, "Remember the original requirement")
            assertEquals(CodingInteractionMode.RESEARCH, repo.sessions("p").single().pendingRun?.interactionMode)
            first.changeCodingInteractionMode("s", CodingInteractionMode.CODE); runCurrent()
            assertTrue(repo.sessions("p").single().researchMode)
            first.shutdownCoding(); runCurrent()
            val recovered = Runtime(); val next = f.prepare(recovered, repo); vm = next; runCurrent()
            assertEquals(CodingInteractionMode.RESEARCH, recovered.calls.single().first.forPendingRun().interactionMode)
            assertEquals("new-native", recovered.calls.single().first.piSessionId)
            recovered.gate.complete(Unit); runCurrent()
            next.changeCodingInteractionMode("s", CodingInteractionMode.CODE); runCurrent()
            assertEquals(CodingInteractionMode.CODE, repo.sessions("p").single().interactionMode)
            assertEquals("", repo.sessions("p").single().piSessionId)
            assertTrue(repo.messages("p", "s").any { it.text == "Research answer" })
        } finally { vm?.shutdownCoding(); Dispatchers.resetMain() }
    }
    @Test fun stoppedRequestIsClosedOnModeChangeAndStalePlanningCannotBeReverted() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        var vm: MagicPaperViewModel? = null
        try {
            val f = ModelSettingsFixture(); val repo = JsonCodingProjectRepository(f.kv, f.json)
            repo.save(project); repo.saveSession(session.copy(researchMode = true, pendingRun = CodingRunCheckpoint("old", "Read",
                intent = ExecutionIntent.STOP, stoppedByUser = true, interactionMode = CodingInteractionMode.RESEARCH)))
            val runtime = Runtime(); val model = f.prepare(runtime, repo); vm = model; runCurrent()
            model.changeCodingInteractionMode("s", CodingInteractionMode.CODE); runCurrent()
            assertNull(repo.sessions("p").single().pendingRun, "notice=${model.state.value.notice}; sessions=${model.state.value.coding.sessions}"); assertTrue(runtime.calls.isEmpty())
            repo.updateSession("p", "s") { it.changeInteractionMode(CodingInteractionMode.PLANNING) }
            model.changeCodingInteractionMode("s", CodingInteractionMode.RESEARCH); runCurrent()
            assertEquals(CodingInteractionMode.PLANNING, repo.sessions("p").single().interactionMode)
            assertTrue(runtime.calls.isEmpty())
        } finally { vm?.shutdownCoding(); Dispatchers.resetMain() }
    }
    @Test fun persistedCheckpointMismatchFailsWithoutCallingRuntime() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        var vm: MagicPaperViewModel? = null
        try {
            val f = ModelSettingsFixture(); val repo = JsonCodingProjectRepository(f.kv, f.json)
            repo.save(project); repo.saveSession(session.copy(pendingRun = CodingRunCheckpoint("old", "Read",
                interactionMode = CodingInteractionMode.RESEARCH)))
            val runtime = Runtime(); vm = f.prepare(runtime, repo); runCurrent()
            assertTrue(runtime.calls.isEmpty())
            assertEquals(ExecutionIntent.STOP, repo.sessions("p").single().pendingRun?.intent)
        } finally { vm?.shutdownCoding(); Dispatchers.resetMain() }
    }
}
