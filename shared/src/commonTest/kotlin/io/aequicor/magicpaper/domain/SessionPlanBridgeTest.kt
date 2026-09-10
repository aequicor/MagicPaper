package io.aequicor.magicpaper.domain

import kotlinx.coroutines.test.runTest
import kotlin.test.*

class SessionPlanBridgeTest {
    private suspend fun plan(f: SessionOrganismTestFixture): Plan {
        val assignment = StageAssignment("profile", "model")
        val attempt = StageAttempt("attempt", "worker", assignment, path = "/worktree", baseCommit = "base", engine = CodingEngine.PI)
        f.projects.saveSession(CodingSession("worker", f.project.id, "Stage", 1, parentSessionId = f.root.id,
            planId = "plan", stageId = "stage", role = CodingSessionRole.WORKER, engine = CodingEngine.PI))
        return Plan("plan", f.project.id, "Goal", parentSessionId = f.root.id, runId = "run", intent = ExecutionIntent.RUN,
            confirmedRevision = 1, planningRulesSnapshot = f.root.planningRulesSnapshot,
            workspace = PlanWorkspace("/fixture", "/integration", "base", git = true),
            milestones = listOf(Milestone("stage", "Stage", "Implement stage", assignment = assignment, acceptance = "Check output", attempts = listOf(attempt))))
    }

    @Test fun confirmedLegacyStageJoinsExistingOrganismAndPreservesCapturedGenerationUntilActualAcceptance() = runTest {
        val f = SessionOrganismTestFixture(); f.initialize(CodingInteractionMode.PLANNING)
        val plan = plan(f); val attempt = plan.milestones.single().attempts.single()
        val admitted = f.service.preparePlanAttempt(plan, "stage", attempt)
        val session = f.projects.sessions(f.project.id).single { it.id == "worker" }
        assertEquals(f.root.organismId, session.organismId)
        assertEquals(CodingInteractionMode.CODE, session.interactionMode)
        assertEquals(f.root.planningRulesSnapshot, session.planningRulesSnapshot)
        val node = f.store.beginRun(session.organismId!!, session.id)
        assertEquals(admitted.sessionGeneration, node.generation)
        f.store.observe(session.organismId!!, session.id, node.generation, SessionObservedState.PENDING)
        f.service.planAttemptCheckpoint(plan, "stage", admitted.copy(phase = AttemptPhase.EXECUTING, awaitingPlanner = true))
        assertTrue(f.store.get(session.organismId!!).results.isEmpty())
        val accepted = admitted.copy(phase = AttemptPhase.COMPLETE, turnIndex = admitted.turnIndex + 1, report = "Checked", resultCommit = "sha", verificationSnapshot = "snapshot",
            acceptanceRecord = AcceptanceRecord("run", attempt.id, "snapshot", listOf(AcceptanceCriterion("check", "Check output")),
                listOf(AcceptanceFinding("check", CheckStatus.PASS, "valid", "valid")), status = AcceptanceStatus.ACCEPTED))
        f.service.planAttemptCheckpoint(plan, "stage", accepted)
        f.service.planAttemptCheckpoint(plan, "stage", accepted)
        val saved = f.store.get(session.organismId!!)
        assertTrue(saved.results.single().accepted)
        assertEquals("sha", saved.results.single().commitSha)
        assertEquals(SessionObservedState.COMPLETED, saved.sessions.getValue("worker").observed)
        assertEquals(1_000_000L, saved.sessions.values.sumOf { it.remainingTokens + it.spentTokens })
        assertEquals(MessageOrigin.SESSION, f.projects.messages(f.project.id, f.root.id).single().origin)
    }

    @Test fun stageRequiresHumanConfirmedSelectionAndLatePreviousTurnCannotAcceptNewGeneration() = runTest {
        val f = SessionOrganismTestFixture(); f.initialize(CodingInteractionMode.PLANNING)
        val plan = plan(f); val attempt = plan.milestones.single().attempts.single()
        assertFailsWith<IllegalArgumentException> { f.service.preparePlanAttempt(plan.copy(confirmedRevision = null), "stage", attempt) }
        val first = f.service.preparePlanAttempt(plan, "stage", attempt)
        f.store.beginRun(f.root.organismId!!, "worker")
        f.store.observe(f.root.organismId!!, "worker", first.sessionGeneration, SessionObservedState.PENDING)
        val nextAttempt = first.copy(turnIndex = first.turnIndex + 1)
        val nextPlan = plan.copy(milestones = listOf(plan.milestones.single().copy(attempts = listOf(nextAttempt))))
        val second = f.service.preparePlanAttempt(nextPlan, "stage", nextAttempt)
        assertEquals(first.sessionGeneration + 1, second.sessionGeneration)
        val acceptedOld = first.copy(phase = AttemptPhase.COMPLETE, resultCommit = "old-sha",
            acceptanceRecord = AcceptanceRecord("run", attempt.id, "snapshot", listOf(AcceptanceCriterion("check", "Check output")),
                listOf(AcceptanceFinding("check", CheckStatus.PASS, "valid", "valid")), status = AcceptanceStatus.ACCEPTED))
        assertFailsWith<IllegalArgumentException> { f.service.planAttemptCheckpoint(plan, "stage", acceptedOld) }
        assertTrue(f.store.get(f.root.organismId!!).results.isEmpty())
    }

    @Test fun persistedStoppedRootRequiresExplicitHumanTurnAndKeepsItsBudget() = runTest {
        val f = SessionOrganismTestFixture(); f.initialize()
        val original = f.store.get(f.root.organismId!!)
        f.store.beginRun(original.id, f.root.id)
        f.store.requestUserStop(original.id, f.root.id, "stop", false)
        f.store.observe(original.id, f.root.id, 1, SessionObservedState.STOPPED)
        assertFailsWith<IllegalArgumentException> { f.store.beginRun(original.id, f.root.id) }
        f.service.prepareUserTurn(f.root, "human-request")
        val next = f.store.get(original.id)
        assertEquals(SessionObservedState.PENDING, next.sessions.getValue(f.root.id).observed)
        assertEquals(original.sessions.values.sumOf { it.remainingTokens }, next.sessions.values.sumOf { it.remainingTokens })
        assertEquals("USER", next.audit.last().actor)
    }

    @Test fun stoppedManagedStageCannotBeReopenedByAnUnchangedRunningPlan() = runTest {
        val f = SessionOrganismTestFixture(); f.initialize(CodingInteractionMode.PLANNING)
        val plan = plan(f); val attempt = plan.milestones.single().attempts.single()
        val admitted = f.service.preparePlanAttempt(plan, "stage", attempt)
        f.store.requestUserStop(f.root.organismId!!, "worker", "stop-worker", false)
        f.store.observe(f.root.organismId!!, "worker", admitted.sessionGeneration, SessionObservedState.STOPPED)
        assertFailsWith<IllegalArgumentException> { f.service.preparePlanAttempt(plan, "stage", attempt) }
        assertEquals(SessionDesiredState.STOP, f.store.get(f.root.organismId!!).sessions.getValue("worker").desired)
    }

    private fun Plan.withAttempt(attempt: StageAttempt) = copy(milestones = listOf(milestones.single().copy(attempts = listOf(attempt))))

    private suspend fun stoppedBudgetAttempt(f: SessionOrganismTestFixture): Pair<Plan, StageAttempt> {
        val plan = plan(f)
        val attempt = f.service.preparePlanAttempt(plan, "stage", plan.milestones.single().attempts.single())
        val id = f.root.organismId!!
        val running = f.store.beginRun(id, attempt.sessionId)
        f.store.charge(SessionAuthority(f.project.id, id, running.id, running.generation, running.mode), running.remainingTokens + 1)
        f.store.observe(id, running.id, running.generation, SessionObservedState.STOPPED)
        val failed = attempt.copy(phase = AttemptPhase.FAILED, error = PlanningIssue(IssueKind.UNCERTAIN, "Бюджет сессии исчерпан", requiresUser = true))
        return plan.withAttempt(failed) to failed
    }

    @Test fun explicitBudgetRetryCreatesOneGenerationAndConsumesItsPersistedAuthorization() = runTest {
        val f = SessionOrganismTestFixture(); f.initialize(CodingInteractionMode.PLANNING)
        val (plan, failed) = stoppedBudgetAttempt(f)
        val authorization = assertNotNull(f.service.authorizePlanRetry(plan, "stage", failed))
        val retry = failed.copy(retryAuthorization = authorization)
        val retryPlan = plan.withAttempt(retry)
        val admitted = f.service.preparePlanAttempt(retryPlan, "stage", retry)
        val saved = f.store.get(f.root.organismId!!)
        val worker = saved.sessions.getValue("worker")
        assertEquals(failed.sessionGeneration + 1, admitted.sessionGeneration)
        assertEquals(SessionDesiredState.RUN, worker.desired)
        assertEquals(SessionObservedState.PENDING, worker.observed)
        assertEquals(1, worker.retryCount)
        assertTrue(worker.remainingTokens > 0)
        assertEquals(saved.limits.tokens, saved.sessions.values.sumOf { it.remainingTokens + it.spentTokens })
        assertEquals("USER", saved.audit.single { it.operationId == "plan-retry-${authorization.id}" }.actor)
        // A crash between aggregate admission and its Plan projection reuses the grant.
        val replay = f.service.preparePlanAttempt(retryPlan, "stage", retry)
        assertEquals(admitted.sessionGeneration, replay.sessionGeneration)
        assertEquals(worker.remainingTokens, f.store.get(saved.id).sessions.getValue("worker").remainingTokens)
        assertEquals(1, f.store.get(saved.id).audit.count { it.action == "PLAN_RETRY" })

        f.store.requestUserStop(saved.id, "worker", "later-stop", false)
        f.store.observe(saved.id, "worker", admitted.sessionGeneration, SessionObservedState.STOPPED)
        assertFailsWith<IllegalArgumentException> { f.service.preparePlanAttempt(retryPlan, "stage", retry) }
        assertEquals(SessionDesiredState.STOP, f.store.get(saved.id).sessions.getValue("worker").desired)
    }

    @Test fun stopAfterRetryApprovalInvalidatesItAndNeedsAFreshUserAction() = runTest {
        val f = SessionOrganismTestFixture(); f.initialize(CodingInteractionMode.PLANNING)
        val (plan, failed) = stoppedBudgetAttempt(f)
        val stale = failed.copy(retryAuthorization = assertNotNull(f.service.authorizePlanRetry(plan, "stage", failed)))
        f.store.requestUserStop(f.root.organismId!!, "worker", "stop-after-approval", false)
        assertFailsWith<IllegalArgumentException> { f.service.preparePlanAttempt(plan.withAttempt(stale), "stage", stale) }
        val fresh = failed.copy(retryAuthorization = assertNotNull(f.service.authorizePlanRetry(plan, "stage", failed)))
        val admitted = f.service.preparePlanAttempt(plan.withAttempt(fresh), "stage", fresh)
        assertEquals(failed.sessionGeneration + 1, admitted.sessionGeneration)
        assertEquals(1, f.store.get(f.root.organismId!!).sessions.getValue("worker").retryCount)
    }

    @Test fun retryAuthorizationCannotBypassUnknownStoppingOrQuarantinedState() = runTest {
        for (state in listOf("unknown", "stopping", "quarantine", "archive")) {
            val f = SessionOrganismTestFixture(); f.initialize(CodingInteractionMode.PLANNING)
            val plan = plan(f)
            val attempt = f.service.preparePlanAttempt(plan, "stage", plan.milestones.single().attempts.single())
            val id = f.root.organismId!!
            f.store.beginRun(id, "worker")
            if (state == "quarantine") {
                val parent = f.store.get(id).sessions.getValue(f.root.id)
                f.store.command(SessionAuthority(f.project.id, id, parent.id, parent.generation, parent.mode), "quarantine",
                    OrganismCommand(OrganismAction.QUARANTINE, target = "worker", reason = "Check actual effects"))
            } else f.store.requestUserStop(id, "worker", "stop", state == "archive")
            if (state != "stopping") f.store.observe(id, "worker", attempt.sessionGeneration,
                if (state == "unknown") SessionObservedState.UNKNOWN else SessionObservedState.STOPPED)
            if (state == "archive") f.store.finishStop(id, setOf("worker"))
            assertFailsWith<IllegalArgumentException>(state) { f.service.authorizePlanRetry(plan.withAttempt(attempt), "stage", attempt) }
            assertTrue(f.store.get(id).audit.none { it.action == "PLAN_RETRY" })
        }
    }
}
