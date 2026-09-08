package io.aequicor.magicpaper.ui

import io.aequicor.magicpaper.data.coding.JsonCodingProjectRepository
import io.aequicor.magicpaper.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class QuestionnaireViewModelTest {
    private val project = CodingProject("p", "Project", "/test", 1)
    private val session = CodingSession("s", "p", "Session", 1, engine = CodingEngine.PI)
    private val q = PlanningQuestion("q", "Выберите", QuestionKind.SINGLE, listOf(QuestionOption("one", "Один")))
    private class Runtime : CodingRuntime {
        override val questionnaires = MutableStateFlow<List<UserInteractionRequest>>(emptyList())
        override val approvals = MutableStateFlow<List<CodingApproval>>(emptyList())
        val answers = mutableListOf<Pair<String, List<PlanningAnswer>>>()
        val permissionDecisions = mutableListOf<CodingApprovalDecision>()
        val gate = CompletableDeferred<Unit>()
        override suspend fun respondQuestionnaire(id: String, answers: List<PlanningAnswer>) {
            this.answers += id to answers
            gate.await()
            questionnaires.value = questionnaires.value.filterNot { it.id == id }
        }
        override suspend fun respondApproval(id: String, decision: CodingApprovalDecision) {
            permissionDecisions += decision; approvals.value = approvals.value.filterNot { it.id == id }
        }
        override val supported = true
        override val rootPath = "/test"
        override suspend fun status() = RuntimeStatus(RuntimePhase.READY)
        override fun ensureReady() = flowOf(RuntimeStatus(RuntimePhase.READY))
        override fun run(project: CodingProject, session: CodingSession, prompt: String, profile: LlmProfile?, attachments: List<Attachment>) = emptyFlow<CodingEvent>()
        override fun abort(sessionId: String) = Unit
        override fun abortAll() = Unit
        override suspend fun uninstall() = Unit
    }

    @Test fun arrivalDoesNotPreemptDraftAndDoubleConfirmationSendsOneReply() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val f = ModelSettingsFixture(); val repo = JsonCodingProjectRepository(f.kv, f.json); val runtime = Runtime()
        repo.save(project); repo.saveSession(session)
        val vm = f.prepare(runtime, repo)
        try {
            runCurrent()
            val first = UserInteractionRequest("runtime:one", "p", "s", InteractionKind.RUNTIME, listOf(q))
            val second = first.copy(id = "runtime:two", sourceId = "runtime:two")
            runtime.questionnaires.value = listOf(first); runCurrent()
            val draft = QuestionnaireDraft(listOf(PlanningAnswer("q", listOf("one"), "Comment")), reviewing = true)
            vm.updateQuestionnaireDraft(first.id, draft)
            runtime.questionnaires.value = listOf(second, first); runCurrent()
            assertEquals(listOf(first.id, second.id), vm.state.value.coding.interactions.map { it.id })
            assertEquals(draft, vm.questionnaireDrafts.value[first.id])
            assertEquals(CodingSessionStatus.WAITING, vm.state.value.coding.currentSession!!.status)
            vm.submitQuestionnaire(first.id, draft.answers); vm.submitQuestionnaire(first.id, draft.answers); runCurrent()
            assertEquals(1, runtime.answers.size)
            assertTrue(vm.state.value.coding.interactions.first().submitting)
            runtime.gate.complete(Unit); runCurrent()
            assertEquals(second.id, vm.state.value.coding.interactions.single().id)
            vm.submitQuestionnaire(second.id, listOf(PlanningAnswer("q", skipped = true))); runCurrent()
            assertTrue(vm.state.value.coding.interactions.isEmpty())
            assertEquals(CodingSessionStatus.IDLE, vm.state.value.coding.currentSession!!.status)
        } finally { vm.shutdownCoding(); Dispatchers.resetMain() }
    }

    @Test fun leaveStoppedIsDurableAndDoesNotInventAnotherUnansweredUserMessage() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val f = ModelSettingsFixture(); val repo = JsonCodingProjectRepository(f.kv, f.json)
        repo.save(project); repo.saveSession(session)
        repo.saveMessages("p", "s", listOf(CodingMessage("old", CodingRole.USER, "Unfinished request", createdAt = 1)))
        val vm = f.prepare(Runtime(), repo)
        try {
            runCurrent()
            val recovery = vm.state.value.coding.interactions.single()
            vm.submitQuestionnaire(recovery.id, listOf(PlanningAnswer("decision", listOf("leave")))); runCurrent()
            assertTrue(vm.state.value.coding.interactions.isEmpty())
            assertTrue(repo.sessions("p").single().pendingRun!!.stoppedByUser)
        } finally { vm.shutdownCoding() }
        val restored = f.prepare(Runtime(), repo)
        try { runCurrent(); assertTrue(restored.state.value.coding.interactions.isEmpty()); assertTrue(restored.state.value.coding.currentSession!!.canResume) }
        finally { restored.shutdownCoding(); Dispatchers.resetMain() }
    }

    @Test fun permissionCannotBeGrantedByDraftOrInvalidResponse() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val f = ModelSettingsFixture(); val repo = JsonCodingProjectRepository(f.kv, f.json); val runtime = Runtime()
        repo.save(project); repo.saveSession(session)
        val vm = f.prepare(runtime, repo)
        try {
            runCurrent(); runtime.approvals.value = listOf(CodingApproval("permission", "s", "p", "Session", CodingApprovalKind.COMMAND, "Reason", "echo test")); runCurrent()
            val request = vm.state.value.coding.interactions.single()
            vm.updateQuestionnaireDraft(request.id, QuestionnaireDraft(listOf(PlanningAnswer("decision", listOf("yes"))), reviewing = true)); runCurrent()
            assertTrue(runtime.permissionDecisions.isEmpty())
            vm.submitQuestionnaire(request.id, listOf(PlanningAnswer("decision", skipped = true))); runCurrent()
            assertTrue(runtime.permissionDecisions.isEmpty()); assertNotNull(vm.state.value.coding.interactions.single().error)
            vm.submitQuestionnaire(request.id, listOf(PlanningAnswer("decision", listOf("no")))); runCurrent()
            assertEquals(listOf(CodingApprovalDecision.DENY), runtime.permissionDecisions)
        } finally { vm.shutdownCoding(); Dispatchers.resetMain() }
    }

    @Test fun backgroundProjectAttentionAndBothDraftsSurviveSwitchingProjects() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val f = ModelSettingsFixture(); val repo = JsonCodingProjectRepository(f.kv, f.json); val runtime = Runtime()
        repo.save(project); repo.saveSession(session)
        val other = project.copy(id = "other"); val otherSession = session.copy(id = "other-session", projectId = other.id)
        repo.save(other); repo.saveSession(otherSession)
        repo.saveMessages(other.id, otherSession.id, listOf(CodingMessage("failed", CodingRole.USER, "Interrupted", createdAt = 1)))
        val vm = f.prepare(runtime, repo)
        try {
            runCurrent()
            assertEquals(CodingSessionStatus.WAITING, vm.state.value.coding.statusOf(other.id))
            val request = vm.state.value.coding.interactions.single()
            val draft = QuestionnaireDraft(listOf(PlanningAnswer("decision", listOf("leave"), "Later")), reviewing = true)
            vm.updateQuestionnaireDraft(request.id, draft)
            val normal = io.aequicor.magicpaper.ui.components.CodingComposerDraft().apply { text.value = "Unsent message" }
            vm.composerDrafts[session.id] = normal
            vm.selectCodingProject(other.id); runCurrent(); vm.selectCodingProject(project.id); runCurrent()
            assertEquals(request.id, vm.state.value.coding.interactions.single().id)
            assertEquals(draft, vm.questionnaireDrafts.value[request.id]); assertEquals("Unsent message", vm.composerDrafts[session.id]!!.text.value)
            assertEquals(CodingSessionStatus.WAITING, vm.state.value.coding.statusOf(other.id))
        } finally { vm.shutdownCoding(); Dispatchers.resetMain() }
    }
}
