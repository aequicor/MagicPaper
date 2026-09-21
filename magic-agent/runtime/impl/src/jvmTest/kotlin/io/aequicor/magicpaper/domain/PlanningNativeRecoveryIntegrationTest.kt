package io.aequicor.magicpaper.domain

import io.aequicor.magicpaper.backend.*
import io.aequicor.magicpaper.data.coding.*
import io.aequicor.magicpaper.data.planning.*
import io.aequicor.magicpaper.data.storage.*
import io.aequicor.magicpaper.domain.tools.*
import io.aequicor.magicpaper.util.Id
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import kotlin.test.*

/** Real factory/lifecycle journals and runtime mapping; the only controlled boundary is the native protocol. */
class PlanningNativeRecoveryIntegrationTest {
    @Test fun noDispatchRequiresInspectionAndExplicitContinuationWithFreshRequestAndExactConsumption() = runBlocking<Unit> {
        val f = Fixture(); try {
            f.start()
            f.awaitUnknown()
            val first = f.state().nativeRecovery.requests.single()
            val proof = f.runtime.recovery.inspect(first.sessionId).noDispatch.single().proof
            assertEquals(first.requestId, proof.requestId)
            assertEquals(0, f.adapter.processes)
            val inspected = f.service.inspectPlanRecovery("plan")
            assertEquals(1, f.adapter.entered)
            assertTrue(f.state().nativeRecovery.decisions.isEmpty())
            f.service.confirmPlanRecovery(inspected)
            assertEquals(1, f.adapter.entered, "Confirmation must not execute a native request")
            assertNull(f.store.currentAdmission("plan"))
            val ack = f.state().nativeRecovery.acknowledgements.getValue(first.requestId).single()
            assertEquals(proof, ack.noDispatch?.proof)
            assertNull(f.store.planFor("plan")!!.milestones.single().attempts.last().error,
                "Exact recovery clears its own stage blocker without starting work")
            f.adapter.mode = Mode.SUCCESS
            f.service.start("plan")
            f.awaitDone()
            val next = f.state().nativeRecovery.requests.first { it.sessionId == first.sessionId && it.requestId != first.requestId }
            assertEquals(ack, next.previous)
            assertEquals(NativeRunRecoveryConsumption(ack.id, first.engine, first.sessionId, next.requestId),
                f.state().nativeRecovery.consumptions[ack.id])
            assertEquals(1, f.store.planFor("plan")!!.milestones.single().attempts.size, "Recovery does not renumber the logical attempt")
            assertTrue(f.adapter.requests.distinct().size == f.adapter.requests.size)
        } finally { f.close() }
    }

    @Test fun hostFailureBeforeNativeBeginCannotTurnAnEmptyReceiptIntoKnownCompletion() = runBlocking<Unit> {
        val f = Fixture(Mode.SUCCESS); try {
            f.failSkills = true
            f.start()
            withTimeout(15_000) { while(f.state().run?.phase != PlanningMachine.RunPhase.UNKNOWN || f.store.unsettled("plan").isEmpty()) delay(10) }
            val request = f.state().nativeRecovery.requests.single()
            val native = f.runtime.recovery.inspect(request.sessionId)
            assertEquals(0, f.adapter.entered)
            assertTrue(native.items.isEmpty() && native.noDispatch.isEmpty())
            assertEquals(PlanningRecoveryIssues.nativeUncertainty, f.store.planFor("plan")!!.milestones.single().attempts.last().error)
            withTimeout(15_000) { while(true) {
                val failure = assertFailsWith<Exception> { f.service.inspectPlanRecovery("plan") }
                if(failure.message?.contains("Дождитесь остановки") != true) {
                    assertEquals("Нет точного подтверждения предыдущего запроса; повтор запрещён", failure.message)
                    break
                }
                delay(10)
            } }
            f.service.start("plan") // The public action reports the existing quarantine instead of throwing.
            assertEquals(PlanningMachine.RunPhase.UNKNOWN, f.state().run?.phase)
            assertNull(f.store.currentAdmission("plan"))
            assertEquals(listOf(request), f.state().nativeRecovery.requests)
            assertTrue(f.store.unsettled("plan").isNotEmpty())
            assertEquals(0, f.adapter.entered)
        } finally { f.close() }
    }

    @Test fun successfulInternalAttemptNeedsNoAcknowledgementWhenTheLastAttemptIsUnknown() = runBlocking<Unit> {
        val f = Fixture(Mode.KNOWN_THEN_UNKNOWN); try {
            f.start(); f.awaitUnknown()
            val request = f.state().nativeRecovery.requests.single()
            val before = f.runtime.recovery.inspect(request.sessionId).items
            assertEquals(listOf(NativeRunOutcome.SUCCEEDED, NativeRunOutcome.UNKNOWN), before.map { it.outcome })
            assertTrue(before.all { it.termination == NativeRunTermination.STOPPED })
            f.service.confirmPlanRecovery(f.service.inspectPlanRecovery("plan"))
            val ack = f.state().nativeRecovery.acknowledgements.getValue(request.requestId).single()
            assertEquals(before.last().ref, ack.attempt?.predecessor)
            assertNull(f.runtime.recovery.inspect(request.sessionId).items.first().acknowledgement)
            assertNull(f.store.planFor("plan")!!.milestones.single().attempts.last().error)
            assertEquals(1, f.adapter.entered)
            f.adapter.mode = Mode.SUCCESS
            f.service.start("plan"); f.awaitDone()
            assertEquals(1, f.state().nativeRecovery.consumptions.count { it.key == ack.id })
            val native = f.runtime.recovery.inspect(request.sessionId)
            assertEquals(before.map { it.outcome }, native.items.take(2).map { it.outcome })
            assertEquals(listOf(0, 1), native.items.take(2).map { it.ref.attempt })
        } finally { f.close() }
    }

    @Test fun deliveredUnknownRemainsUnknownAfterAcknowledgementAndIsNeverReplayedOnRestore() = runBlocking<Unit> {
        val f = Fixture(Mode.UNKNOWN); try {
            f.start(); f.awaitUnknown()
            val request = f.state().nativeRecovery.requests.single()
            val before = f.runtime.recovery.inspect(request.sessionId).items.single()
            assertEquals(NativeRunOutcome.UNKNOWN, before.outcome)
            assertEquals(NativeRunTermination.STOPPED, before.termination)
            val count = f.adapter.entered
            f.reopen()
            assertEquals(count, f.adapter.entered)
            val inspection = f.service.inspectPlanRecovery("plan")
            assertEquals(count, f.adapter.entered)
            f.service.confirmPlanRecovery(inspection)
            val after = f.runtime.recovery.inspect(request.sessionId).items.single()
            assertEquals(before.ref, after.ref)
            assertEquals(NativeRunOutcome.UNKNOWN, after.outcome)
            assertNotNull(after.acknowledgement)
            f.adapter.mode = Mode.SUCCESS
            f.service.start("plan"); f.awaitDone()
            assertEquals(before.ref.attempt, f.runtime.recovery.inspect(request.sessionId).items.first().ref.attempt)
            assertEquals(1, f.adapter.requests.count { it == request.requestId })
        } finally { f.close() }
    }

    @Test fun lostParentAcknowledgementCommitReusesOnlyItsSavedDecisionAfterReopen() = runBlocking<Unit> {
        val f = Fixture(); try {
            f.start(); f.awaitUnknown()
            val request = f.state().nativeRecovery.requests.single()
            f.rejectParentAcknowledgement = true
            assertFailsWith<Exception> { f.service.confirmPlanRecovery(f.service.inspectPlanRecovery("plan")) }
            val nativeAck = assertNotNull(f.runtime.recovery.inspect(request.sessionId).noDispatch.single().acknowledgement)
            f.rejectParentAcknowledgement = false
            f.reopen()
            assertEquals(nativeAck.parentDecisionId, f.state().nativeRecovery.decisions.getValue(request.requestId).id)
            f.service.confirmPlanRecovery(f.service.inspectPlanRecovery("plan"))
            assertEquals(nativeAck, f.state().nativeRecovery.acknowledgements.getValue(request.requestId).single().noDispatch)
            assertEquals(1, f.adapter.entered)
            val nativeInputs = f.events.streams().filter { it.startsWith("native-lifecycle") }.flatMap { f.events.read(it) }
            assertEquals(1, nativeInputs.count { Json.decodeFromString(NativeJournalEntry.serializer(), it.detail).input is NativeLifecycleMachine.Intent.AcknowledgeNoDispatch })
        } finally { f.close() }
    }

    @Test fun secondPreflightFailureConsumesFirstDecisionAndCreatesItsOwnDurableProof() = runBlocking<Unit> {
        val f = Fixture(); try {
            f.start(); f.awaitUnknown()
            val first = f.state().nativeRecovery.requests.single()
            f.service.confirmPlanRecovery(f.service.inspectPlanRecovery("plan"))
            val firstAck = f.state().nativeRecovery.acknowledgements.getValue(first.requestId).single()
            f.service.start("plan"); f.awaitUnknown(2)
            val second = f.state().nativeRecovery.requests.last()
            assertNotEquals(first.requestId, second.requestId)
            assertEquals(second.requestId, f.state().nativeRecovery.consumptions.getValue(firstAck.id).requestId)
            f.service.confirmPlanRecovery(f.service.inspectPlanRecovery("plan"))
            val secondAck = f.state().nativeRecovery.acknowledgements.getValue(second.requestId).single()
            assertNotEquals(firstAck.id, secondAck.id)
            f.adapter.mode = Mode.SUCCESS
            f.service.start("plan"); f.awaitDone()
            assertEquals(0, f.state().nativeRecovery.acknowledgements.values.flatten().count { it.id !in f.state().nativeRecovery.consumptions })
        } finally { f.close() }
    }

    @Test fun nativeJournalLossAfterInspectionCannotBecomeAnEmptyProofOrAuthorizeContinuation() = runBlocking<Unit> {
        val f = Fixture(); try {
            f.start(); f.awaitUnknown()
            val shown = f.service.inspectPlanRecovery("plan")
            f.events.streams().filter { it.startsWith("native-lifecycle") }.forEach { f.events.drop(it) }
            assertFailsWith<Exception> { f.service.confirmPlanRecovery(shown) }
            assertTrue(f.store.unsettled("plan").isNotEmpty())
            assertNull(f.store.currentAdmission("plan"))
            assertEquals(1, f.adapter.entered)
        } finally { f.close() }
    }

    @Test fun lostConsumptionCommitIsRecoveredBeforeAnotherFreshRequestCanReuseTheDecision() = runBlocking<Unit> {
        val f = Fixture(); try {
            f.start(); f.awaitUnknown()
            val first = f.state().nativeRecovery.requests.single()
            f.service.confirmPlanRecovery(f.service.inspectPlanRecovery("plan"))
            val ack = f.state().nativeRecovery.acknowledgements.getValue(first.requestId).single()
            f.rejectParentConsumption = true
            f.adapter.mode = Mode.SUCCESS
            f.service.start("plan")
            withTimeout(15_000) { while(f.store.failure.value == null) delay(10) }
            val consumer = f.state().nativeRecovery.requests.last()
            assertEquals(ack.id, consumer.previous?.id)
            assertEquals(consumer.requestId, f.runtime.recovery.inspect(first.sessionId).consumptions.single().requestId)
            f.rejectParentConsumption = false
            f.reopen()
            assertNull(f.state().nativeRecovery.consumptions[ack.id])
            f.service.confirmPlanRecovery(f.service.inspectPlanRecovery("plan"))
            assertEquals(consumer.requestId, f.state().nativeRecovery.consumptions.getValue(ack.id).requestId)
            f.service.start("plan"); f.awaitDone()
            assertTrue(f.state().nativeRecovery.requests.filter { it.requestId != consumer.requestId }.none { it.previous?.id == ack.id })
        } finally { f.close() }
    }

    @Test fun lostProjectionAfterIntentReconciliationReplaysTheSavedReleaseWithoutNativeEffects() = runBlocking<Unit> {
        val f = Fixture(); try {
            f.start(); f.awaitUnknown()
            val request = f.state().nativeRecovery.requests.single()
            f.rejectReconciliationProjection = true
            assertFailsWith<Exception> { f.service.confirmPlanRecovery(f.service.inspectPlanRecovery("plan")) }
            assertTrue(unsettledPlanIntents(f.events.read("plan")).isEmpty(), "The intent's reconciliation was committed")
            assertTrue(request.requestId in f.state().nativeRecovery.releaseAuthorizations)
            val acknowledgement = assertNotNull(f.runtime.recovery.inspect(request.sessionId).noDispatch.single().acknowledgement)
            f.rejectReconciliationProjection = false
            f.reopen()
            assertEquals(1, f.adapter.entered, "Reopening projects the saved decision without calling an adapter")
            assertNull(f.store.currentAdmission("plan"))
            assertTrue(f.store.unsettled("plan").isEmpty())
            assertNull(f.store.planFor("plan")!!.milestones.single().attempts.last().error)
            assertTrue(request.requestId in f.state().nativeRecovery.released)
            assertEquals(acknowledgement, f.runtime.recovery.inspect(request.sessionId).noDispatch.single().acknowledgement)
            f.adapter.mode = Mode.SUCCESS
            f.service.start("plan"); f.awaitDone()
            assertEquals(1, f.state().nativeRecovery.consumptions.count { it.key == acknowledgement.id })
        } finally { f.close() }
    }

    @Test fun explicitPlanDecisionResolvesActualOrganismWithoutChangingUnknownToolOrNativeHistory() = runBlocking<Unit> {
        val f = Fixture(Mode.UNKNOWN, withOrganisms = true); try {
            f.start(); f.awaitUnknown()
            val request = f.state().nativeRecovery.requests.single()
            val session = f.projects.sessions("project").single { it.id == request.sessionId }
            val organisms = checkNotNull(f.organisms)
            val original = organisms.ensure(session)
            val receipts = StoredToolReceipts(f.kv)
            val receipt = ToolReceipt("project/${session.id}/${request.requestId}/native/tool-1", "write", JsonObject(emptyMap()),
                phase = ToolPhase.UNKNOWN, runtimeGeneration = session.runtimeGeneration, native = true, resultComplete = false)
            receipts.save(receipt)
            val savedReceipt = assertNotNull(receipts.get(receipt.id))
            assertEquals(ToolPhase.UNKNOWN, savedReceipt.phase)
            assertFalse(savedReceipt.resultComplete)
            organisms.project(organisms.store.quarantine(original.id, session.id, original.sessions.getValue(session.id).generation,
                "unconfirmed-tool", "Неизвестный исход инструмента"))
            f.service.confirmPlanRecovery(f.service.inspectPlanRecovery("plan"))
            val resolved = organisms.store.get(original.id)
            assertTrue(resolved.unresolvedQuarantines(session.id).isEmpty())
            assertTrue(resolved.audit.filter { it.action == QUARANTINE_RESOLVED_ACTION }.all { it.actor == "USER" })
            assertEquals(savedReceipt, receipts.get(receipt.id), "User acknowledgement does not change the persisted tool receipt")
            assertEquals(NativeRunOutcome.UNKNOWN, f.runtime.recovery.inspect(session.id).items.single().outcome)
            assertEquals(0, f.genericQuarantineReconciliations, "Only the exact planning authority bypasses generic UNKNOWN reconciliation")
            f.reopen()
            assertEquals(1, f.adapter.entered)
            assertTrue(f.store.unsettled("plan").isEmpty())
            assertNull(f.store.currentAdmission("plan"))
            assertEquals(NativeRunOutcome.UNKNOWN, f.runtime.recovery.inspect(session.id).items.single().outcome)
        } finally { f.close() }
    }

    @Test fun failedOrganismResolutionAppendKeepsPlanPendingAndReusesItsExactDecisionAfterReopen() = runBlocking<Unit> {
        val f = Fixture(Mode.UNKNOWN, withOrganisms = true); try {
            f.start(); f.awaitUnknown()
            val request = f.state().nativeRecovery.requests.single()
            val shown = f.service.inspectPlanRecovery("plan")
            f.rejectOrganismResolution = true
            assertFailsWith<Exception> { f.service.confirmPlanRecovery(shown) }
            assertTrue(unsettledPlanIntents(f.events.read("plan")).isNotEmpty(), "Parent remains recoverable until child commit succeeds")
            val ack = assertNotNull(f.runtime.recovery.inspect(request.sessionId).items.single().acknowledgement)
            f.rejectOrganismResolution = false
            f.reopen()
            assertEquals(1, f.adapter.entered)
            f.service.confirmPlanRecovery(f.service.inspectPlanRecovery("plan"))
            assertEquals(ack, f.runtime.recovery.inspect(request.sessionId).items.single().acknowledgement)
            assertTrue(f.store.unsettled("plan").isEmpty())
            assertNull(f.store.planFor("plan")!!.milestones.single().attempts.last().error)
            assertEquals(1, f.adapter.entered)
        } finally { f.close() }
    }

    @Test fun unrelatedQuarantineAddedAfterInspectionCannotBeClearedByTheOldPlanConfirmation() = runBlocking<Unit> {
        val f = Fixture(Mode.UNKNOWN, withOrganisms = true); try {
            f.start(); f.awaitUnknown()
            val request = f.state().nativeRecovery.requests.single()
            val shown = f.service.inspectPlanRecovery("plan")
            val session = f.projects.sessions("project").single { it.id == request.sessionId }
            val organisms = checkNotNull(f.organisms)
            val saved = organisms.ensure(session)
            organisms.project(organisms.store.quarantine(saved.id, session.id, saved.sessions.getValue(session.id).generation,
                "unrelated-external-effect", "Исход другой операции не подтверждён"))
            val before = organisms.store.get(saved.id).unresolvedQuarantines(session.id)
            assertFailsWith<IllegalArgumentException> { f.service.confirmPlanRecovery(shown) }
            assertEquals(before, organisms.store.get(saved.id).unresolvedQuarantines(session.id))
            assertTrue(f.store.unsettled("plan").isNotEmpty())
            assertNull(f.runtime.recovery.inspect(session.id).items.single().acknowledgement)
            assertEquals(1, f.adapter.entered)
        } finally { f.close() }
    }

    @Test fun firstEngineUnknownCannotHideAnotherEnginesUnconfirmedCleanup() = runBlocking<Unit> {
        val f = Fixture(Mode.UNKNOWN); try {
            f.start(); f.awaitUnknown()
            val first = f.state().nativeRecovery.requests.single()
            f.service.confirmPlanRecovery(f.service.inspectPlanRecovery("plan"))
            val other = NativeRunRecoveryRef(CodingEngine.CODEX, first.sessionId, "foreign-request", 0)
            val stops = mutableListOf<NativeRunRecoveryRef>()
            val aggregate = object : NativeRunRecovery by f.runtime.recovery {
                override suspend fun inspect(sessionId: String) = f.runtime.recovery.inspect(sessionId).let {
                    it.copy(items = it.items + NativeRunRecoveryItem(other, NativeRunOutcome.UNKNOWN, NativeRunTermination.LIVE, null))
                }
                override suspend fun stop(ref: NativeRunRecoveryRef): NativeRunRecoverySnapshot {
                    stops += ref
                    return inspect(ref.sessionId) // The second owner has not proved cleanup.
                }
            }
            val partial = object : CodingRuntime by f.runtime { override val recovery = aggregate }
            val interpreter = PlanningNativeRecoveryInterpreter(f.store, partial)
            assertFailsWith<IllegalArgumentException> { interpreter.prepare("plan", first.sessionId) }
            assertFailsWith<IllegalArgumentException> { interpreter.stopResources(first.sessionId) }
            assertEquals(listOf(other), stops)
            assertEquals(1, f.adapter.entered)
        } finally { f.close() }
    }

    private enum class Mode { NO_DISPATCH, UNKNOWN, SUCCESS, KNOWN_THEN_UNKNOWN }
    private class Fixture(initial: Mode = Mode.NO_DISPATCH, private val withOrganisms: Boolean = false) {
        val root = Files.createTempDirectory("planning-native-recovery").toFile()
        val kv = InMemoryKeyValueStore()
        val events = InMemoryEventJournal()
        var rejectParentAcknowledgement = false
        var rejectParentConsumption = false
        var rejectReconciliationProjection = false
        var rejectOrganismResolution = false
        val journal = object : EventJournal by events {
            override suspend fun append(expected: JournalRevision, operation: String, at: Long, detail: String): JournalRecord? {
                if(rejectOrganismResolution && operation == "organism.input.v1" &&
                    Json.parseToJsonElement(detail).jsonObject.getValue("ref").jsonObject.getValue("kind").jsonPrimitive.content.endsWith("ResolveSessionQuarantine"))
                    error("Controlled organism resolution journal write failure")
                if(expected.stream == "plan" && (rejectParentAcknowledgement || rejectParentConsumption || rejectReconciliationProjection)) {
                    val input = PlanInputCommit.from(JournalRecord(1, at, expected.stream, operation, detail))?.input
                    if(rejectParentAcknowledgement && (input as? PlanningMachine.Fact.NativeObserved)?.value is PlanningNativeFact.Acknowledged)
                        error("Controlled parent journal write failure")
                    if(rejectParentConsumption && (input as? PlanningMachine.Fact.NativeObserved)?.value is PlanningNativeFact.ConsumptionObserved)
                        error("Controlled consumption journal write failure")
                    if(rejectReconciliationProjection && input is PlanningMachine.Fact.EvidenceObserved && input.pending.isEmpty())
                        error("Controlled reconciliation projection write failure")
                }
                return events.append(expected, operation, at, detail)
            }
        }
        var store = TestPlanningStore(JsonPlanningRepository(kv, Json), journal)
        val projects = journalCodingProjects(kv, Json)
        val profile = LlmProfile("agent", "Agent", baseUrl = "http://127.0.0.1:1", modelId = "fixture", apiKey = "unit-secret-do-not-use",
            favoriteModels = listOf("fixture"), modelLibraryVersion = 1)
        val adapter = Adapter(initial)
        var failSkills = false
        var runtime = runtime()
        var organisms: SessionOrganismService? = null
        var genericQuarantineReconciliations = 0
        lateinit var service: PlanningExecutionService
        fun state() = checkNotNull(store.machineStates.value["plan"])
        private fun runtime(): DesktopCodingRuntime {
            val contribution = object : BackendAgentContribution {
                override val descriptor = adapter.descriptor
                override val paths = NativeBackendPaths("fixture", "processes", "questions", "questions")
                override fun create(environment: NativeBackendEnvironment) = adapter
            }
            val processes = object : NativeProcessRecovery {
                override fun belongsTo(id: String, process: Process?) = false
                override fun reconcile(id: String) = error("No detached process")
                override fun record(id: String, process: Process, attachLifetime: Boolean) = error("Protocol fixture owns its live process")
                override fun clear(id: String) = error("No detached process")
            }
            val questions = object : NativeQuestionnaires {
                override suspend fun ask(request: UserInteractionRequest): List<PlanningAnswer> = error("No question")
                override suspend fun beginDelivery(requestId: String): String = error("No question")
                override suspend fun finishDelivery(requestId: String, attemptId: String, outcome: QuestionnaireDeliveryOutcome) = error("No question")
            }
            val env = NativeBackendEnvironment(Json, root.path, resources = NativeResources { error("No resources") },
                processes = processes, cachedAccessTokens = NativeAuthTokens { error("No credentials") },
                refreshedAccessTokens = NativeAuthTokens { error("No credentials") }, questionnaires = questions,
                diagnostics = NativeDiagnostics { _, _, _, _ -> }, toolPresentation = NativeToolPresentationResolver { _, _, _ -> error("No native tool") },
                providerLibrary = Library, lifecycleJournal = NativeLifecycleJournalAdapter(journal, root.path))
            val agent = createBackendAgentCatalog(listOf(contribution)).create { _, _ -> env }.single()
            val generic = GenericNativeRuntime(agent, Library, {}, { it }, null,
                testQuestionnaireFactory().create("fixture", null), testBrowserSessions, testCommandChecks)
            return DesktopCodingRuntime(listOf(NativeRuntimeBinding(adapter.descriptor, generic)), null,
                { if (failSkills) error("Controlled skill selection failure"); CodingSkillSelection(emptyList()) }, testCommandChecks)
        }
        suspend fun start() {
            projects.createTestProject(CodingProject("project", "Project", root.path, 1))
            JsonLlmProfileRepository(kv, Json).save(profile)
            store.save(Plan("plan", "project", "Goal", engine = CodingEngine.PI, sharedWorkspace = true,
                milestones = listOf(Milestone("work", "Work", description = "Check result", agentProfileId = "agent"))))
            service = createService(); service.start("plan")
        }
        private fun createService(): PlanningExecutionService {
            val workspace = object : PlanningWorkspace by LocalPlanningWorkspace() {
                override suspend fun verificationSnapshot(path: String, operation: WorkspaceOperation?) = "stable-fixture-snapshot"
            }
            if(withOrganisms) {
                val ports = TestOrganismPorts().apply {
                    reconcileUnknownOutcomes = { request -> genericQuarantineReconciliations++
                        SessionQuarantineRecovery(runtime, StoredToolReceipts(kv)).reconcile(request) }
                }
                organisms = testOrganismService(DefaultSessionOrganismStore(kv, journal, dispatcher = Dispatchers.Unconfined),
                    projects, JsonSettingsRepository(kv, Json), ports)
            }
            return PlanningExecutionService(store, runtime, projects, JsonLlmProfileRepository(kv, Json), JsonSettingsRepository(kv, Json),
                object : MilestoneVerifier { override suspend fun verify(milestone: Milestone, goal: String, report: String,
                    profile: LlmProfile?) = Verdict(true, "checked") }, workspace,
                journalRecovery = PlanningJournalRecovery(store, runtime, projects, organisms),
                attemptAuthority = TestPlanningExecutionPorts(), chatHooksProvider = { null })
        }
        suspend fun awaitUnknown(count: Int = 1) {
            try { withTimeout(15_000) { while(adapter.entered < count || store.unsettled("plan").isEmpty() ||
                state().run?.phase != PlanningMachine.RunPhase.UNKNOWN) delay(10) } }
            catch(timeout: TimeoutCancellationException) {
                throw AssertionError("Expected unknown: entered=${adapter.entered}, requests=${state().nativeRecovery.requests.size}, " +
                    "phase=${state().run?.phase}, issue=${store.planFor("plan")?.issue}, error=${service.error.value}", timeout)
            }
            // The public inspection lock must see controller cleanup completed.
            withTimeout(15_000) { while(true) {
                try { service.inspectPlanRecovery("plan"); break }
                catch(failure: IllegalArgumentException) { if(failure.message?.contains("Дождитесь остановки") != true) throw failure }
                catch(failure: IllegalStateException) { if(failure.message?.contains("Дождитесь остановки") != true) throw failure }
                delay(10)
            } }
        }
        suspend fun awaitDone() {
            try { withTimeout(15_000) { while(store.planFor("plan")?.status != PlanStatus.DONE) delay(10) } }
            catch(timeout: TimeoutCancellationException) { throw AssertionError("Expected completed: entered=${adapter.entered}, " +
                "phase=${state().run?.phase}, issue=${store.planFor("plan")?.issue}, error=${service.error.value}", timeout) }
        }
        suspend fun reopen() { service.shutdown(); organisms?.shutdown(); store = TestPlanningStore(JsonPlanningRepository(kv, Json), journal); store.plans(); runtime = runtime(); service = createService() }
        suspend fun close() { if(::service.isInitialized) service.shutdown(); organisms?.shutdown(); runtime.abortAll(); root.deleteRecursively() }
    }
    private class Adapter(var mode: Mode) : NativeAgentAdapter {
        @Volatile var entered = 0
        @Volatile var processes = 0
        val requests = mutableListOf<String>()
        override val descriptor = createBackendAgentCatalog().descriptor(io.aequicor.magicpaper.domain.CodingEngine.PI).copy(capabilities = emptySet())
        override val rootPath = "fixture"
        override val approvals: NativeApprovalRequests? = null
        override val history: NativeToolHistory? = null
        override val removal: NativeRemoval? = null
        override suspend fun status() = NativeInstallationStatus(NativeInstallationPhase.READY, "Ready")
        override fun prepare() = flowOf(NativeInstallationStatus(NativeInstallationPhase.READY, "Ready"))
        override fun modelProfile(profile: LlmProfile, mode: CodingInteractionMode, speedBoost: Boolean) = profile
        override fun modelConnection(profile: LlmProfile) = NativeModelConnectionKind.DIRECT
        override fun run(request: NativeAgentRequest) = flow {
            entered++; requests += checkNotNull(request.session.pendingRun).runId
            if(mode == Mode.NO_DISPATCH) error("Controlled preflight failure")
            val context = checkNotNull(currentCoroutineContext()[NativeAttemptContext])
            suspend fun attempt(known: Boolean) {
                val attempt = context.events.admitLaunch(context.run)
                // A local JVM has no provider/account access and runs no child command.
                val process = ProcessBuilder(File(System.getProperty("java.home"), "bin/java").path, "-version").redirectErrorStream(true).start()
                processes++
                try {
                    context.events.attached(attempt, NativeProcessIdentity(Id.new(), process.pid(), Id.now()))
                    context.events.deliver(attempt, NativeDelivery.PI_STDIN)
                    process.inputStream.readBytes(); check(process.waitFor() == 0)
                    if(known) context.events.terminal(attempt, NativeOutcome.SUCCEEDED)
                } finally { withContext(NonCancellable) {
                    if(process.isAlive) process.destroyForcibly()
                    process.waitFor()
                    context.events.stopping(attempt); context.events.stopped(attempt)
                } }
            }
            if (mode == Mode.KNOWN_THEN_UNKNOWN) attempt(known = true)
            attempt(known = mode == Mode.SUCCESS)
                emit(CodingEvent.SessionStarted("protocol-${request.session.id}"))
                emit(CodingEvent.FinalText("Verified result"))
            emit(CodingEvent.Finished)
        }
        override suspend fun reconcile(sessionId: String) = Unit
        override fun abort(sessionId: String) = Unit
        override fun abortAll() = Unit
        override fun close() = Unit
    }
    private object Library : NativeProviderLibrary {
        override fun prepare(): Flow<NativeInstallationStatus> = error("No provider installation")
        override suspend fun turn(profile: LlmProfile, messages: List<LlmMessage>, tools: List<LlmToolDefinition>,
            exchanges: List<LlmToolExchange>, accessToken: String, onUsage: (UsageCallResult) -> Unit): LlmToolTurn = error("No provider")
        override suspend fun bridge(profile: LlmProfile, parameters: JsonObject): NativeProviderBridge = error("No provider")
        override suspend fun shutdown() = Unit
        override suspend fun prepareForReset() = Unit
        override suspend fun resumeAfterReset() = Unit
        override fun close() = Unit
    }
}
