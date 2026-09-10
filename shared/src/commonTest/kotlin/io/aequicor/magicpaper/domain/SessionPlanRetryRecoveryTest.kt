package io.aequicor.magicpaper.domain

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.*

class SessionPlanRetryRecoveryTest {
    private data class Scenario(val fixture: SessionOrganismTestFixture, val plan: Plan, val attempt: StageAttempt) {
        val id get() = fixture.root.organismId!!
        fun planWith(attempt: StageAttempt) = plan.copy(milestones = listOf(plan.milestones.single().copy(attempts = listOf(attempt))))
    }

    private suspend fun scenario(begin: Boolean = true): Scenario {
        val f = SessionOrganismTestFixture(limits = OrganismLimits())
        f.initialize(CodingInteractionMode.PLANNING)
        val assignment = StageAssignment("profile", "model")
        val attempt = StageAttempt("attempt", "worker", assignment, path = "/worktree", baseCommit = "base", engine = CodingEngine.PI,
            engineSessionId = "native-attempt-checkpoint")
        f.projects.saveSession(CodingSession("worker", f.project.id, "Stage", 1, piSessionId = "stale-sidebar-projection", parentSessionId = f.root.id,
            planId = "plan", stageId = "stage", role = CodingSessionRole.WORKER, engine = CodingEngine.PI))
        val plan = Plan("plan", f.project.id, "Goal", parentSessionId = f.root.id, runId = "run", intent = ExecutionIntent.RUN,
            confirmedRevision = 1, planningRulesSnapshot = f.root.planningRulesSnapshot,
            workspace = PlanWorkspace("/fixture", "/integration", "base", git = true),
            milestones = listOf(Milestone("stage", "Stage", "Implement stage", assignment = assignment, acceptance = "Check output", attempts = listOf(attempt))))
        val admitted = f.service.preparePlanAttempt(plan, "stage", attempt)
        if (begin) f.store.beginRun(f.root.organismId!!, "worker")
        return Scenario(f, plan.copy(milestones = listOf(plan.milestones.single().copy(attempts = listOf(admitted)))), admitted)
    }

    private suspend fun Scenario.quarantine(operation: String = "native-exec") {
        fixture.store.quarantine(id, "worker", attempt.sessionGeneration, operation, "Неизвестный исход shell.exec")
    }

    private fun proof(request: PlanRetryRecoveryRequest) = PlanRetryRecoveryProof(
        request.quarantines.map { it.operationId }.toSet(), listOf("Native process stopped; exact execution receipt confirms exit code 0"))

    private fun Scenario.continuation(): Pair<Plan, StageAttempt> {
        val next = attempt.copy(turnIndex = attempt.turnIndex + 1, phase = AttemptPhase.FAILED)
        val coordination = CoordinationRecord("${attempt.id}-turn-${attempt.turnIndex}", "stage", StageReply(StageReplyKind.RESULT, "Report"),
            decision = CoordinatorReply("Verify the result", resultAction = CoordinatorResultAction.VERIFY),
            runId = plan.runId, attemptId = attempt.id, sourceSessionId = attempt.sessionId, turnIndex = attempt.turnIndex,
            status = HandoffStatus.RESOLVED)
        return planWith(next).copy(coordination = listOf(coordination)) to next
    }

    @Test fun explicitRetryReconcilesExactReceiptAndRetainsActualAndRequestedTurns() = runTest {
        val s = scenario(); val f = s.fixture
        s.quarantine()
        val (plan, next) = s.continuation()
        var requests = 0
        f.service.reconcilePlanRetry = { request ->
            requests++
            assertEquals(s.attempt.sessionGeneration, request.session.runtimeGeneration)
            assertEquals("native-attempt-checkpoint", request.session.piSessionId)
            assertEquals(s.attempt.engine, request.session.engine)
            assertEquals(0, request.binding.turnIndex)
            assertEquals(listOf("quarantine-native-exec"), request.quarantines.map { it.operationId })
            proof(request)
        }
        val authorization = assertNotNull(f.service.authorizePlanRetry(plan, "stage", next))
        val reconciled = f.store.get(s.id)
        assertEquals(1, requests)
        assertEquals(0, authorization.binding.turnIndex)
        assertEquals(1, authorization.requestedBinding.turnIndex)
        assertEquals(SessionDesiredState.STOP, reconciled.sessions.getValue("worker").desired)
        assertEquals(SessionObservedState.STOPPED, reconciled.sessions.getValue("worker").observed)
        assertEquals(s.attempt.sessionGeneration, reconciled.sessions.getValue("worker").generation)
        assertTrue(reconciled.unresolvedQuarantines("worker").isEmpty())
        assertEquals(1, reconciled.audit.count { it.action == "QUARANTINE" })
        assertEquals(1, reconciled.audit.count { it.action == "RECONCILE_QUARANTINE" })
        val retry = next.copy(retryAuthorization = authorization)
        val retryPlan = plan.copy(milestones = listOf(plan.milestones.single().copy(attempts = listOf(retry))))
        val admitted = f.service.preparePlanAttempt(retryPlan, "stage", retry)
        assertEquals(s.attempt.sessionGeneration + 1, admitted.sessionGeneration)
        val running = f.store.beginRun(s.id, "worker")
        assertEquals(admitted.sessionGeneration, running.generation)
        assertEquals(1, running.legacyAttempt?.turnIndex)
        assertEquals(1, running.retryCount)
        assertTrue(f.store.get(s.id).results.isEmpty())
        assertFailsWith<IllegalArgumentException> { f.store.observe(s.id, "worker", s.attempt.sessionGeneration, SessionObservedState.COMPLETED) }
    }

    @Test fun unresolvedOutcomeNeverReopensFromOrdinaryAdmissionOrAnUnprovedRetry() = runTest {
        val s = scenario(); val f = s.fixture; s.quarantine()
        val before = f.store.get(s.id)
        var requests = 0
        f.service.reconcilePlanRetry = { requests++; null }
        assertFailsWith<IllegalArgumentException> { f.service.preparePlanAttempt(s.plan, "stage", s.attempt) }
        assertEquals(0, requests)
        assertFailsWith<IllegalArgumentException> { f.service.authorizePlanRetry(s.plan, "stage", s.attempt) }
        assertEquals(1, requests)
        assertEquals(before, f.store.get(s.id))
    }

    @Test fun proofMustCoverEveryCurrentQuarantineAndContainEvidence() = runTest {
        val s = scenario(); val f = s.fixture; s.quarantine(); s.quarantine("second-exec")
        val before = f.store.get(s.id)
        for (proof in listOf(PlanRetryRecoveryProof(setOf("quarantine-native-exec"), listOf("First receipt confirmed")),
            PlanRetryRecoveryProof(setOf("quarantine-native-exec", "quarantine-second-exec"), emptyList()))) {
            f.service.reconcilePlanRetry = { proof }
            assertFailsWith<IllegalArgumentException> { f.service.authorizePlanRetry(s.plan, "stage", s.attempt) }
            assertEquals(before, f.store.get(s.id))
        }
    }

    @Test fun laterStopInvalidatesInFlightProofAndCapturedAuthorization() = runTest {
        val s = scenario(); val f = s.fixture; s.quarantine()
        f.service.reconcilePlanRetry = { request ->
            f.store.requestUserStop(s.id, "worker", "new-user-stop", false)
            proof(request)
        }
        assertFailsWith<IllegalArgumentException> { f.service.authorizePlanRetry(s.plan, "stage", s.attempt) }
        assertEquals(1, f.store.get(s.id).unresolvedQuarantines("worker").size)
        f.service.reconcilePlanRetry = ::proof
        val auth = assertNotNull(f.service.authorizePlanRetry(s.plan, "stage", s.attempt))
        f.store.requestUserStop(s.id, "worker", "stop-after-proof", false)
        val retry = s.attempt.copy(retryAuthorization = auth)
        assertFailsWith<IllegalArgumentException> { f.service.preparePlanAttempt(s.planWith(retry), "stage", retry) }
        assertEquals(SessionDesiredState.STOP, f.store.get(s.id).sessions.getValue("worker").desired)
    }

    @Test fun continuationNeedsExactResolvedHostCheckpointAndUnchangedIdentity() = runTest {
        val s = scenario(); val f = s.fixture; s.quarantine()
        val (plan, next) = s.continuation()
        var requests = 0
        f.service.reconcilePlanRetry = { requests++; proof(it) }
        val badPlans = listOf(plan.copy(coordination = emptyList()),
            plan.copy(coordination = plan.coordination.map { it.copy(status = HandoffStatus.PROCESSING) }),
            plan.copy(coordination = plan.coordination.map { it.copy(runId = "old-run") }),
            plan.copy(coordination = plan.coordination.map { it.copy(sourceSessionId = "other-worker") }))
        for (bad in badPlans) assertFailsWith<IllegalArgumentException> { f.service.authorizePlanRetry(bad, "stage", next) }
        for (badAttempt in listOf(next.copy(turnIndex = 2), next.copy(sessionGeneration = next.sessionGeneration + 1), next.copy(id = "other-attempt"))) {
            val bad = plan.copy(milestones = listOf(plan.milestones.single().copy(attempts = listOf(badAttempt))))
            assertFailsWith<IllegalArgumentException> { f.service.authorizePlanRetry(bad, "stage", badAttempt) }
        }
        assertEquals(0, requests)
        assertEquals(SessionObservedState.UNKNOWN, f.store.get(s.id).sessions.getValue("worker").observed)
    }

    @Test fun knownNativeReceiptCannotClearOtherUnknownEffectsOrPendingArchive() = runTest {
        for (blocker in listOf("operation", "integration", "archive", "accepted-result", "child", "auxiliary")) {
            val s = scenario(); val f = s.fixture; s.quarantine()
            val old = f.store.get(s.id)
            val node = old.sessions.getValue("worker")
            val blocked = when (blocker) {
                "operation" -> old.copy(operations = old.operations + ("other" to OrganismOperation("other", "fingerprint", "worker", SessionOperationState.UNKNOWN)))
                "integration" -> old.copy(integrations = mapOf("integration" to SessionIntegration(SessionIntegrationRequest("integration", s.id,
                    "worker", node.generation, emptyList(), emptyList(), "/worktree", "snapshot"), phase = SessionIntegrationPhase.UNKNOWN)))
                "accepted-result" -> old.copy(results = listOf(SessionResult("accepted", "worker", node.generation, f.root.id, "Verified", accepted = true)))
                "child" -> old.copy(sessions = old.sessions + ("child" to SessionNode("child", SessionKind.SESSION, "Child", "worker", observed = SessionObservedState.RUNNING)))
                "auxiliary" -> old.copy(auxiliaryRuns = mapOf("aux" to SessionAuxiliaryRun("aux", "native-alias", "worker", node.generation,
                    "review", "request", CodingInteractionMode.CODE, observed = SessionObservedState.RUNNING)))
                else -> old
            }
            f.storage.write("session-organism-${s.id}", Json.encodeToString(blocked))
            if (blocker == "archive") f.store.requestUserStop(s.id, "worker", "archive-worker", true)
            val before = f.store.get(s.id)
            f.service.reconcilePlanRetry = ::proof
            assertFailsWith<IllegalArgumentException>(blocker) { f.service.authorizePlanRetry(s.plan, "stage", s.attempt) }
            assertEquals(before, f.store.get(s.id), blocker)
        }
    }

    @Test fun settledQuarantineStillRequiresReceiptProofAndRetainsHistory() = runTest {
        for (stop in listOf(false, true)) {
            val s = scenario(); val f = s.fixture; s.quarantine()
            if (stop) f.store.requestUserStop(s.id, "worker", "stop-after-quarantine", false)
            f.store.observe(s.id, "worker", s.attempt.sessionGeneration, SessionObservedState.STOPPED)
            f.service.reconcilePlanRetry = ::proof
            assertNotNull(f.service.authorizePlanRetry(s.plan, "stage", s.attempt))
            assertTrue(f.store.get(s.id).unresolvedQuarantines("worker").isEmpty())
            assertEquals(1, f.store.get(s.id).audit.count { it.action == "QUARANTINE" })
        }
    }

    @Test fun stoppedPendingLegacyRuntimeCanBeSettledOnlyByHostProof() = runTest {
        val s = scenario(); val f = s.fixture
        val old = f.store.get(s.id)
        f.storage.write("session-organism-${s.id}", Json.encodeToString(old.copy(sessions = old.sessions +
            ("worker" to old.sessions.getValue("worker").copy(desired = SessionDesiredState.STOP, observed = SessionObservedState.PENDING)))))
        assertFailsWith<IllegalArgumentException> { f.service.authorizePlanRetry(s.plan, "stage", s.attempt) }
        f.service.reconcilePlanRetry = { request ->
            assertTrue(request.quarantines.isEmpty())
            PlanRetryRecoveryProof(emptySet(), listOf("Native handle cleanup completed"))
        }
        assertNotNull(f.service.authorizePlanRetry(s.plan, "stage", s.attempt))
        assertEquals(SessionObservedState.STOPPED, f.store.get(s.id).sessions.getValue("worker").observed)
    }

    @Test fun normalScopeCompletionAfterStopCannotPersistPendingAndCannotClearQuarantine() = runTest {
        for (quarantine in listOf(false, true)) {
            val s = scenario(begin = false); val f = s.fixture
            val tree = SessionTreeRuntime(f.service, f.projects, f.profiles, f.settings, clock = { 1_000 })
            val session = f.projects.sessions(f.project.id).single { it.id == "worker" }
            tree.withScope(session) {
                if (quarantine) s.quarantine() else f.store.requestUserStop(s.id, "worker", "stop-during-scope", false)
            }
            val node = f.store.get(s.id).sessions.getValue("worker")
            assertEquals(if (quarantine) SessionObservedState.UNKNOWN else SessionObservedState.STOPPED, node.observed)
            assertEquals(if (quarantine) SessionDesiredState.QUARANTINE else SessionDesiredState.STOP, node.desired)
        }
    }

    @Test fun latePendingObservationRespectsStopAndRetainsUnresolvedQuarantine() = runTest {
        for (quarantine in listOf(false, true)) {
            val s = scenario(); val f = s.fixture
            if (quarantine) s.quarantine() else f.store.requestUserStop(s.id, "worker", "late-stop", false)
            f.store.observe(s.id, "worker", s.attempt.sessionGeneration, SessionObservedState.PENDING)
            assertEquals(if (quarantine) SessionObservedState.UNKNOWN else SessionObservedState.STOPPED,
                f.store.get(s.id).sessions.getValue("worker").observed)
        }
    }
}
