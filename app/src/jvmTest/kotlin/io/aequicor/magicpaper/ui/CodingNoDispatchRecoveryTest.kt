package io.aequicor.magicpaper.ui

import io.aequicor.magicpaper.data.coding.JsonCodingProjectRepository
import io.aequicor.magicpaper.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class CodingNoDispatchRecoveryTest {
    private val project = CodingProject("project", "Project", "/fixture", 1)
    private val session = CodingSession("session", project.id, "Task", 1, engine = CodingEngine.PI)
    private val request = CodingRunCheckpoint("old-input", "Original request", intent = ExecutionIntent.STOP,
        stoppedByUser = true, responseId = "old-output", responseTimelineId = "old-timeline", runId = "old-request")
    private val proof = NativeRunNoDispatchProof(CodingEngine.PI, session.id, request.runId, "durable-proof", "journal-generation")

    private class Runtime(initial: NativeRunRecoverySnapshot) : CodingRuntime {
        var snapshot = initial
        val calls = mutableListOf<CodingSession>()
        val bindings = mutableListOf<NativeRunRecoveryBinding?>()
        var inspections = 0
        var acknowledgements = 0
        var loseAcknowledgementOnce = false
        var malformedAcknowledgement = false
        var failBeforeProcessOnce = false
        override val recovery = object : NativeRunRecovery {
            override suspend fun inspect(sessionId: String): NativeRunRecoverySnapshot { inspections++; return snapshot }
            override suspend fun stop(ref: NativeRunRecoveryRef): NativeRunRecoverySnapshot = error("No process attempt exists")
            override suspend fun acknowledge(ref: NativeRunRecoveryRef, parentDecisionId: String): NativeRunRecoveryAcknowledgement = error("No process attempt exists")
            override suspend fun acknowledgeNoDispatch(proof: NativeRunNoDispatchProof, parentDecisionId: String): NativeRunNoDispatchAcknowledgement {
                acknowledgements++
                check(snapshot.noDispatch.single().proof == proof)
                val ack = NativeRunNoDispatchAcknowledgement("no-dispatch-ack-$acknowledgements", proof, parentDecisionId)
                snapshot = snapshot.copy(noDispatch = listOf(NativeRunNoDispatchItem(proof, ack)))
                if (loseAcknowledgementOnce) { loseAcknowledgementOnce = false; error("Lost acknowledgement") }
                return if (malformedAcknowledgement) ack.copy(proof = proof.copy(journalGeneration = "foreign")) else ack
            }
        }
        override val supported = true
        override val rootPath = "/fixture"
        override suspend fun status() = RuntimeStatus(RuntimePhase.READY)
        override fun ensureReady() = flowOf(RuntimeStatus(RuntimePhase.READY))
        override suspend fun uninstall() = Unit
        override fun abort(sessionId: String) = Unit
        override fun abortAll() = Unit
        override fun run(project: CodingProject, session: CodingSession, prompt: String, profile: LlmProfile?, attachments: List<Attachment>) = flow {
            val binding = currentCoroutineContext()[NativeRunRecoveryBinding]
            calls += session; bindings += binding
            val ref = NativeRunRecoveryRef(checkNotNull(session.engine), session.id, checkNotNull(session.pendingRun).runId, 0)
            binding?.let {
                val ack = checkNotNull(it.noDispatchAcknowledgement)
                check(snapshot.noDispatch.single().acknowledgement == ack)
                check(snapshot.consumptions.none { consumed -> consumed.acknowledgementId == ack.id })
                snapshot = snapshot.copy(consumptions = snapshot.consumptions + NativeRunRecoveryConsumption(ack.id,
                    checkNotNull(session.engine), session.id, ref.requestId))
            }
            if (failBeforeProcessOnce) {
                failBeforeProcessOnce = false
                val nextProof = NativeRunNoDispatchProof(checkNotNull(session.engine), session.id, ref.requestId,
                    "proof-${ref.requestId}", "journal-generation")
                snapshot = snapshot.copy(noDispatch = listOf(NativeRunNoDispatchItem(nextProof, null)))
                throw NativeRunRecoveryRequired(snapshot)
            }
            snapshot = snapshot.copy(items = snapshot.items + NativeRunRecoveryItem(ref, NativeRunOutcome.SUCCEEDED, NativeRunTermination.STOPPED, null))
            emit(CodingEvent.SessionStarted("native-context"))
            emit(CodingEvent.FinalText("Done"))
            emit(CodingEvent.Finished)
        }
    }

    private suspend fun cache(f: ModelSettingsFixture): JsonCodingProjectRepository = JsonCodingProjectRepository(f.kv, f.json).also {
        it.save(project); it.saveSession(session.copy(pendingRun = request))
        it.saveMessages(project.id, session.id, listOf(CodingMessage(request.messageId, CodingRole.USER, request.prompt, createdAt = 1)))
    }

    @Test fun exactNoDispatchProofAllowsExplicitFreshRunAndIsConsumedOnlyOnce() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        var model: DefaultCodingService? = null
        try {
            val f = ModelSettingsFixture(); val repo = cache(f)
            val runtime = Runtime(NativeRunRecoverySnapshot(emptyList(), false, listOf(NativeRunNoDispatchItem(proof, null))))
            model = f.prepareCoding(runtime, repo); runCurrent()
            assertEquals(0, runtime.inspections, "Restore is not a recovery request")
            assertTrue(runtime.calls.isEmpty()); assertEquals(0, runtime.acknowledgements)
            model.resumeCodingSession(session.id); runCurrent()
            assertEquals(1, runtime.acknowledgements)
            assertNotEquals(request.runId, runtime.calls.single().pendingRun!!.runId)
            assertEquals(proof, runtime.bindings.single()!!.noDispatchAcknowledgement!!.proof)
            assertNull(runtime.bindings.single()!!.acknowledgement)
            assertNull(repo.sessions(project.id).single().pendingRun)
            model.sendCodingPromptTo(session.id, "Another explicit request"); runCurrent()
            assertEquals(2, runtime.calls.size)
            assertNull(runtime.bindings.last(), "An already consumed acknowledgement must not be sent again")
            assertNull(repo.sessions(project.id).single().pendingRun)
        } finally { model?.close(); Dispatchers.resetMain() }
    }

    @Test fun absenceForeignProofConflictAndUnknownPersistenceNeverAuthorizeAnotherRun() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val valid = NativeRunNoDispatchItem(proof, null)
            val cases = listOf(
                NativeRunRecoverySnapshot(emptyList(), false),
                NativeRunRecoverySnapshot(emptyList(), true, listOf(valid)),
                NativeRunRecoverySnapshot(emptyList(), false, listOf(valid.copy(proof = proof.copy(sessionId = "foreign")))),
                NativeRunRecoverySnapshot(emptyList(), false, listOf(valid.copy(proof = proof.copy(requestId = "foreign")))),
                NativeRunRecoverySnapshot(emptyList(), false, listOf(valid.copy(proof = proof.copy(engine = CodingEngine.CODEX)))),
                NativeRunRecoverySnapshot(emptyList(), false, listOf(valid, valid.copy(proof = proof.copy(proofId = "other")))),
                NativeRunRecoverySnapshot(listOf(NativeRunRecoveryItem(NativeRunRecoveryRef(CodingEngine.PI, session.id,
                    request.runId, 0), NativeRunOutcome.UNKNOWN, NativeRunTermination.STOPPED, null)), false, listOf(valid)),
                NativeRunRecoverySnapshot(emptyList(), false, listOf(valid.copy(acknowledgement = NativeRunNoDispatchAcknowledgement("foreign", proof, "not-a-parent-decision")))),
            )
            for (snapshot in cases) {
                val f = ModelSettingsFixture(); val repo = cache(f); val runtime = Runtime(snapshot)
                val model = f.prepareCoding(runtime, repo)
                try {
                    runCurrent(); assertEquals(0, runtime.inspections)
                    model.resumeCodingSession(session.id); runCurrent()
                    assertTrue(runtime.calls.isEmpty())
                    assertEquals(0, runtime.acknowledgements)
                    assertEquals(request, repo.sessions(project.id).single().pendingRun)
                    assertEquals(CodingMachine.Phase.UNKNOWN, model.state.value.coding.currentSession!!.runPhase)
                    assertNotNull(model.state.value.notice)
                } finally { model.close() }
            }
        } finally { Dispatchers.resetMain() }
    }

    @Test fun durableNoDispatchAcknowledgementCanBeRecoveredAfterLostReturnAndRestart() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        var model: DefaultCodingService? = null
        try {
            val f = ModelSettingsFixture(); val repo = cache(f)
            val runtime = Runtime(NativeRunRecoverySnapshot(emptyList(), false, listOf(NativeRunNoDispatchItem(proof, null)))).apply {
                loseAcknowledgementOnce = true
            }
            val first = f.prepareCoding(runtime, repo); model = first; runCurrent()
            first.resumeCodingSession(session.id); runCurrent()
            assertEquals(1, runtime.acknowledgements); assertTrue(runtime.calls.isEmpty())
            assertEquals(CodingMachine.Phase.UNKNOWN, first.state.value.coding.currentSession!!.runPhase)
            val saved = runtime.snapshot.noDispatch.single().acknowledgement
            assertNotNull(saved); first.close()
            val inspections = runtime.inspections
            val restored = f.prepareCoding(runtime, repo); model = restored; runCurrent()
            assertEquals(inspections, runtime.inspections); assertTrue(runtime.calls.isEmpty())
            restored.resumeCodingSession(session.id); runCurrent()
            assertEquals(1, runtime.acknowledgements, "Reuse only the exact durable parent decision, without another acknowledgement")
            assertEquals(saved, runtime.bindings.single()!!.noDispatchAcknowledgement)
            assertNotEquals(request.runId, runtime.calls.single().pendingRun!!.runId)
            assertNull(repo.sessions(project.id).single().pendingRun)
        } finally { model?.close(); Dispatchers.resetMain() }
    }

    @Test fun changedNoDispatchAcknowledgementCannotClearThePendingRequest() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        var model: DefaultCodingService? = null
        try {
            val f = ModelSettingsFixture(); val repo = cache(f)
            val runtime = Runtime(NativeRunRecoverySnapshot(emptyList(), false, listOf(NativeRunNoDispatchItem(proof, null)))).apply {
                malformedAcknowledgement = true
            }
            model = f.prepareCoding(runtime, repo); runCurrent()
            model.resumeCodingSession(session.id); runCurrent()
            assertEquals(1, runtime.acknowledgements); assertTrue(runtime.calls.isEmpty())
            assertEquals(request, repo.sessions(project.id).single().pendingRun)
            assertEquals(CodingMachine.Phase.UNKNOWN, model.state.value.coding.currentSession!!.runPhase)
            assertNotNull(model.state.value.notice)
        } finally { model?.close(); Dispatchers.resetMain() }
    }
    @Test fun failedRecoveryRetainsClarificationBeforeAnyAcknowledgement() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        var model: DefaultCodingService? = null
        try {
            val f = ModelSettingsFixture(); val repo = cache(f)
            val runtime = Runtime(NativeRunRecoverySnapshot(emptyList(), false))
            val first = f.prepareCoding(runtime, repo); model = first; runCurrent()
            first.resumeCodingSession(session.id, "Keep this clarification"); runCurrent()
            val queued = repo.sessions(project.id).single().queuedPrompts.single()
            assertContains(queued.prompt, "Original request")
            assertContains(queued.prompt, "Keep this clarification")
            assertTrue(runtime.calls.isEmpty()); assertEquals(0, runtime.acknowledgements)
            assertTrue(repo.messages(project.id, session.id).any { it.id == queued.messageId && it.text == "Keep this clarification" })
            first.close()
            val inspected = runtime.inspections
            val restored = f.prepareCoding(runtime, repo); model = restored; runCurrent()
            assertEquals(inspected, runtime.inspections)
            assertEquals(queued, repo.sessions(project.id).single().queuedPrompts.single())
            assertTrue(runtime.calls.isEmpty())
            runtime.snapshot = NativeRunRecoverySnapshot(emptyList(), false, listOf(NativeRunNoDispatchItem(proof, null)))
            restored.resumeCodingSession(session.id); runCurrent()
            assertEquals(queued.runId, runtime.calls.single().pendingRun!!.runId)
            assertContains(runtime.calls.single().pendingRun!!.prompt, "Keep this clarification")
            assertNull(repo.sessions(project.id).single().pendingRun)
        } finally { model?.close(); Dispatchers.resetMain() }
    }

    @Test fun anotherProvenPreflightFailureUsesItsOwnProofAndNeverReusesTheConsumedDecision() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        var model: DefaultCodingService? = null
        try {
            val f = ModelSettingsFixture(); val repo = cache(f)
            val runtime = Runtime(NativeRunRecoverySnapshot(emptyList(), false, listOf(NativeRunNoDispatchItem(proof, null)))).apply {
                failBeforeProcessOnce = true
            }
            model = f.prepareCoding(runtime, repo); runCurrent()
            model.resumeCodingSession(session.id); runCurrent()
            assertEquals(1, runtime.calls.size)
            assertTrue(runtime.snapshot.items.isEmpty(), "Preflight did not create an attempt")
            assertEquals(CodingMachine.Phase.UNKNOWN, model.state.value.coding.currentSession!!.runPhase)
            val nextProof = runtime.snapshot.noDispatch.single().proof
            assertNotEquals(proof, nextProof)
            assertEquals(runtime.calls.single().pendingRun!!.runId, nextProof.requestId)
            val recovery = model.state.value.coding.interactions.single { it.kind == InteractionKind.RECOVER_RUN }
            model.submitQuestionnaire(recovery.id, listOf(PlanningAnswer("decision", listOf("retry")))); runCurrent()
            assertEquals(2, runtime.calls.size)
            assertEquals(2, runtime.acknowledgements)
            assertEquals(nextProof, runtime.bindings.last()!!.noDispatchAcknowledgement!!.proof)
            assertNotEquals(runtime.bindings.first()!!.noDispatchAcknowledgement!!.id,
                runtime.bindings.last()!!.noDispatchAcknowledgement!!.id)
            assertNull(repo.sessions(project.id).single().pendingRun)
        } finally { model?.close(); Dispatchers.resetMain() }
    }

}
