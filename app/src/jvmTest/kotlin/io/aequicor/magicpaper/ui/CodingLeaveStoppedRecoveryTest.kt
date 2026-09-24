package io.aequicor.magicpaper.ui

import io.aequicor.magicpaper.data.coding.JsonCodingProjectRepository
import io.aequicor.magicpaper.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlin.test.*

/**
 * «Оставить остановленной» подтверждает на нативном уровне только то, что держит общую папку: исход, который
 * никто не может знать. Каждое записанное подтверждение становится решением, которое следующий нативный
 * допуск сессии обязан принести с собой, — известный исход в таком решении не нуждается и навсегда
 * блокирует сессии её последующие запуски. Явное продолжение после «оставить остановленной» переиспользует
 * уже записанное решение вместо отказа.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CodingLeaveStoppedRecoveryTest {
    private val project = CodingProject("project", "Project", "/fixture", 1)
    private val session = CodingSession("session", project.id, "Task", 1, engine = CodingEngine.PI)
    private val request = CodingRunCheckpoint("old-input", "Original request", intent = ExecutionIntent.STOP,
        stoppedByUser = false, responseId = "old-output", responseTimelineId = "old-timeline", runId = "old-request")

    /** Mirrors the native journal's admission contract: an unconsumed recorded decision must be carried by the run. */
    private class Runtime(initial: NativeRunRecoverySnapshot) : CodingRuntime {
        var snapshot = initial
        val acknowledgements = mutableListOf<NativeRunRecoveryRef>()
        val noDispatchAcknowledgements = mutableListOf<NativeRunNoDispatchProof>()
        val calls = mutableListOf<CodingSession>()
        val bindings = mutableListOf<NativeRunRecoveryBinding?>()
        private val consumptions = mutableListOf<NativeRunRecoveryConsumption>()
        private fun view() = snapshot.copy(consumptions = consumptions.toList())
        override val recovery = object : NativeRunRecovery {
            override suspend fun inspect(sessionId: String): NativeRunRecoverySnapshot = view()
            override suspend fun stop(ref: NativeRunRecoveryRef): NativeRunRecoverySnapshot {
                snapshot = snapshot.copy(items = snapshot.items.map { if (it.ref == ref) it.copy(termination = NativeRunTermination.STOPPED) else it })
                return view()
            }
            override suspend fun acknowledge(ref: NativeRunRecoveryRef, parentDecisionId: String): NativeRunRecoveryAcknowledgement {
                snapshot.items.single { it.ref == ref }.acknowledgement?.let { saved ->
                    check(saved.parentDecisionId == parentDecisionId) { "Native recovery decision changed" }
                    return saved
                }
                acknowledgements += ref
                val ack = NativeRunRecoveryAcknowledgement("ack-${acknowledgements.size}", ref, parentDecisionId)
                snapshot = snapshot.copy(items = snapshot.items.map { if (it.ref == ref) it.copy(acknowledgement = ack) else it })
                return ack
            }
            override suspend fun acknowledgeNoDispatch(proof: NativeRunNoDispatchProof, parentDecisionId: String): NativeRunNoDispatchAcknowledgement {
                snapshot.noDispatch.single { it.proof == proof }.acknowledgement?.let { saved ->
                    check(saved.parentDecisionId == parentDecisionId) { "Native recovery decision changed" }
                    return saved
                }
                noDispatchAcknowledgements += proof
                val ack = NativeRunNoDispatchAcknowledgement("no-dispatch-ack-${noDispatchAcknowledgements.size}", proof, parentDecisionId)
                snapshot = snapshot.copy(noDispatch = snapshot.noDispatch.map { if (it.proof == proof) it.copy(acknowledgement = ack) else it })
                return ack
            }
        }
        override val supported = true
        override val rootPath = "/fixture"
        override suspend fun status() = RuntimeStatus(RuntimePhase.READY)
        override fun ensureReady() = flowOf(RuntimeStatus(RuntimePhase.READY))
        override suspend fun uninstall() = Unit
        override fun abort(sessionId: String) = Unit
        override fun abortAll() = Unit
        override suspend fun reconcile(sessionId: String) {
            if (snapshot.items.any { it.outcome == NativeRunOutcome.UNKNOWN || it.termination != NativeRunTermination.STOPPED })
                throw NativeRunRecoveryRequired(view())
        }
        override fun run(project: CodingProject, session: CodingSession, prompt: String, profile: LlmProfile?, attachments: List<Attachment>) = flow {
            val binding = currentCoroutineContext()[NativeRunRecoveryBinding]
            val consumed = consumptions.map { it.acknowledgementId }.toSet()
            val pending = snapshot.items.filter { it.acknowledgement != null && it.acknowledgement!!.id !in consumed }
            check(pending.isEmpty() || binding?.acknowledgement == pending.last().acknowledgement) {
                "Нет явного решения продолжить после неизвестного исхода" }
            check(snapshot.noDispatch.none { it.acknowledgement == null }) { "Запуск не был отправлен; требуется явное решение продолжить" }
            val pendingProofs = snapshot.noDispatch.filter { it.acknowledgement != null && it.acknowledgement!!.id !in consumed }
            check(pendingProofs.isEmpty() || binding?.noDispatchAcknowledgement == pendingProofs.last().acknowledgement) {
                "Нет решения продолжить неотправленный запуск" }
            calls += session; bindings += binding
            val engine = checkNotNull(session.engine)
            binding?.acknowledgement?.let { consumptions += NativeRunRecoveryConsumption(it.id, engine, session.id, checkNotNull(session.pendingRun).runId) }
            binding?.noDispatchAcknowledgement?.let { consumptions += NativeRunRecoveryConsumption(it.id, engine, session.id, checkNotNull(session.pendingRun).runId) }
            val ref = NativeRunRecoveryRef(engine, session.id, checkNotNull(session.pendingRun).runId, 0)
            snapshot = snapshot.copy(items = snapshot.items + NativeRunRecoveryItem(ref, NativeRunOutcome.SUCCEEDED, NativeRunTermination.STOPPED, null))
            emit(CodingEvent.SessionStarted("native-${session.id}"))
            emit(CodingEvent.FinalText("Done"))
            emit(CodingEvent.Finished)
        }
    }

    private suspend fun cache(f: ModelSettingsFixture): JsonCodingProjectRepository = JsonCodingProjectRepository(f.kv, f.json).also {
        it.save(project); it.saveSession(session.copy(pendingRun = request))
        it.saveMessages(project.id, session.id, listOf(CodingMessage(request.messageId, CodingRole.USER, request.prompt, createdAt = 1)))
    }

    private suspend fun TestScope.leaveStopped(model: DefaultCodingService) {
        val card = model.state.value.coding.interactions.single { it.kind == InteractionKind.RECOVER_RUN }
        model.submitQuestionnaire(card.id, listOf(PlanningAnswer("decision", listOf("leave"))))
        runCurrent()
    }

    @Test fun leaveStoppedWithAKnownOutcomeRecordsNoDecisionAndContinuationIsAdmitted() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        var model: DefaultCodingService? = null
        try {
            val f = ModelSettingsFixture(); val repo = cache(f)
            // The crashed attempt failed with an outcome the engine itself reported: nothing is undecided.
            val runtime = Runtime(NativeRunRecoverySnapshot(listOf(NativeRunRecoveryItem(
                NativeRunRecoveryRef(CodingEngine.PI, session.id, request.runId, 0),
                NativeRunOutcome.FAILED, NativeRunTermination.STOPPED, null)), false))
            model = f.prepareCoding(runtime, repo); runCurrent()
            leaveStopped(model)

            assertTrue(repo.sessions(project.id).single().pendingRun!!.stoppedByUser)
            assertTrue(runtime.acknowledgements.isEmpty(), "A known outcome needs no decision; recording one fences the session's next runs")
            assertTrue(runtime.noDispatchAcknowledgements.isEmpty())

            model.resumeCodingSession(session.id); runCurrent()
            assertEquals(1, runtime.calls.size, "Continuation must reach the engine after the explicit abandon handshake")
            assertEquals(1, runtime.acknowledgements.size, "Only the handshake itself records the decision")
            assertEquals("ack-1", runtime.bindings.single()!!.acknowledgement!!.id)
            val response = repo.messages(project.id, session.id).last { it.role == CodingRole.AGENT }
            assertFalse(response.failed)
            assertNull(repo.sessions(project.id).single().pendingRun)
        } finally { model?.close(); Dispatchers.resetMain() }
    }

    @Test fun continuationAfterLeaveStoppedReusesTheRecordedDecisionAndDrainsIt() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        var model: DefaultCodingService? = null
        try {
            val f = ModelSettingsFixture(); val repo = cache(f)
            val runtime = Runtime(NativeRunRecoverySnapshot(listOf(NativeRunRecoveryItem(
                NativeRunRecoveryRef(CodingEngine.PI, session.id, request.runId, 0),
                NativeRunOutcome.UNKNOWN, NativeRunTermination.STOPPED, null)), false))
            model = f.prepareCoding(runtime, repo); runCurrent()
            leaveStopped(model)

            assertEquals(listOf(NativeRunRecoveryRef(CodingEngine.PI, session.id, request.runId, 0)), runtime.acknowledgements,
                "The undecided outcome is exactly what the leave decision confirms")
            val recorded = runtime.snapshot.items.single().acknowledgement

            model.resumeCodingSession(session.id); runCurrent()
            assertEquals(1, runtime.acknowledgements.size, "Continuation reuses the recorded decision instead of refusing it")
            assertEquals(recorded, runtime.bindings.single()!!.acknowledgement)
            val response = repo.messages(project.id, session.id).last { it.role == CodingRole.AGENT }
            assertFalse(response.failed)
            assertNull(repo.sessions(project.id).single().pendingRun)

            model.sendCodingPromptTo(session.id, "Another explicit request"); runCurrent()
            assertEquals(2, runtime.calls.size)
            assertNull(runtime.bindings.last(), "An already consumed acknowledgement must not be sent again")
        } finally { model?.close(); Dispatchers.resetMain() }
    }

    @Test fun leaveStoppedConfirmsAnUndispatchedRequestAndContinuationReusesThatDecision() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        var model: DefaultCodingService? = null
        try {
            val f = ModelSettingsFixture(); val repo = cache(f)
            val proof = NativeRunNoDispatchProof(CodingEngine.PI, session.id, request.runId, "durable-proof", "journal-generation")
            val runtime = Runtime(NativeRunRecoverySnapshot(emptyList(), false, listOf(NativeRunNoDispatchItem(proof, null))))
            model = f.prepareCoding(runtime, repo); runCurrent()
            leaveStopped(model)

            assertEquals(listOf(proof), runtime.noDispatchAcknowledgements,
                "A request proven never dispatched is decided by the leave, not left fencing every next admission")
            val recorded = runtime.snapshot.noDispatch.single().acknowledgement

            model.resumeCodingSession(session.id); runCurrent()
            assertEquals(1, runtime.noDispatchAcknowledgements.size, "Continuation reuses the recorded decision")
            assertEquals(recorded, runtime.bindings.single()!!.noDispatchAcknowledgement)
            assertEquals(1, runtime.calls.size)
            assertNull(repo.sessions(project.id).single().pendingRun)
        } finally { model?.close(); Dispatchers.resetMain() }
    }
}
