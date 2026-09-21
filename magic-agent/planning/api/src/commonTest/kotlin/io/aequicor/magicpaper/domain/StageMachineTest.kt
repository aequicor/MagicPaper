package io.aequicor.magicpaper.domain

import io.aequicor.magicpaper.domain.planning.*
import io.aequicor.magicpaper.domain.tools.ToolPhase
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.*

class StageMachineTest {
    private val initial = StageAttempt("attempt", "session", StageAssignment("profile", "model"), path = "/work")
    private val retry = StageRetryInputs(limit = 2, now = 10_000, jitter = 17)
    private fun StageAttempt.on(event: StageEvent) = reduce(toState(), event)
    private fun StageTransition.checkpoint() = state.applyTo(initial)

    @Test fun adaptersPreserveLegacyAndCurrentCheckpointBytesWithoutNormalizingFlags() {
        val formats = listOf(Json, Json { encodeDefaults = true; explicitNulls = true })
        for (phase in AttemptPhase.entries) for (pending in listOf(null, false, true)) {
            val saved = initial.copy(phase = phase, coordinationPending = pending, awaitingPlanner = true,
                waitingForUser = "question", waitingForEvent = "event", mergePhase = AttemptPhase.INTEGRATING,
                interrupted = true, pendingTool = "upload", pendingToolExternal = true, sessionGeneration = 8)
            formats.forEach { format ->
                val encoded = format.encodeToString(saved)
                val restored = format.decodeFromString<StageAttempt>(encoded)
                assertEquals(encoded, format.encodeToString(restored.toState().applyTo(restored)))
            }
        }
    }

    @Test fun explicitInputsProduceTheSameCompleteTransitionTrace() {
        val events = listOf<StageMutation>(StageEvent.WorkerStarting("implement", 10),
            StageEvent.WorkerTurnEnded(20, "done"), StageEvent.WorkerAccepted("snapshot", true),
            StageEvent.PlannerDecided(StageTurnDecision(StageTurnAction.VERIFY, "review")),
            StageEvent.VerificationDecided(Verdict(true, "pass"), retry), StageEvent.Captured("commit"),
            StageEvent.MergeStarted(false), StageEvent.ConflictRequested("/merge", 2),
            StageEvent.ConflictStarted, StageEvent.ConflictTurnEnded, StageEvent.MergeFinished(true), StageEvent.Completed)
        fun replay(serialized: Boolean = false): List<StageTransition> {
            var state = initial.toState()
            return events.map {
                val input = if (serialized) Json.decodeFromString<StageMutation>(Json.encodeToString(it)) else it
                reduce(state, input).also { transition -> state = transition.state }
            }
        }
        val trace = replay()
        assertEquals(trace, replay())
        assertEquals(trace, replay(serialized = true), "Durable facts reproduce the exact stage transition trace")
        assertEquals(AttemptPhase.COMPLETE, trace.last().checkpoint().phase)
        assertEquals(1, trace.last().checkpoint().mergeRetries)
        assertEquals(0, trace.last().checkpoint().transportRetries)
        assertTrue(trace.last().has(StageEffect.Finish))
    }

    @Test fun aNewerCheckpointTransfersAuthorityWithoutStartingAnotherWorker() {
        val verifying = initial.copy(phase = AttemptPhase.VERIFYING, report = "durable report")
        val stage = Milestone("stage", "stage", attempts = listOf(verifying))
        val next = initial.on(StageEvent.CheckpointObserved(CheckpointDrift.Advanced(stage, verifying)))
        assertEquals(verifying, next.checkpoint())
        assertEquals(listOf(StageEffect.RunVerifier), next.effects)
        listOf(CheckpointDrift.Gone, CheckpointDrift.TakenOver,
            CheckpointDrift.Advanced(stage, verifying.copy(phase = AttemptPhase.COMPLETE))).forEach {
            assertTrue(initial.on(StageEvent.CheckpointObserved(it)).has(StageEffect.Yield))
        }
    }

    @Test fun preparationNeverReplacesAResolvedEngineOrWorkspace() {
        val saved = initial.copy(engine = CodingEngine.CODEX)
        assertTrue(saved.on(StageEvent.InspectPreparation).effects.isEmpty())
        assertEquals(listOf(StageEffect.ResolveEngine, StageEffect.PrepareWorkspace),
            initial.copy(path = "").on(StageEvent.InspectPreparation).effects)
    }

    @Test fun journalUncertaintyAlwaysPreventsResumeAndRetainsCheckpoint() {
        AttemptPhase.entries.forEach { phase ->
            val saved = initial.copy(phase = phase, interrupted = true)
            val next = saved.on(StageEvent.Reconciled(journalUnsettled = true))
            assertEquals(saved, next.checkpoint())
            assertEquals(IssueKind.UNCERTAIN, next.issue?.kind)
            assertTrue(next.has(StageEffect.Yield))
            assertFalse(next.has(StageEffect.RunWorker))
        }
    }

    @Test fun unavailableAcceptanceCannotSpendAnotherRepairOrRunWorker() {
        val record = AcceptanceRecord("run", initial.id, "snapshot",
            listOf(AcceptanceCriterion("check", "Host evidence", environment = EvidenceEnvironment.REAL_BACKEND)),
            listOf(AcceptanceFinding("check", CheckStatus.BLOCKED, "available host", "unavailable", recovery = AcceptanceRecovery.UNAVAILABLE)),
            status = AcceptanceStatus.BLOCKED)
        val next = initial.copy(repairRetries = 1, acceptanceRecord = record)
            .on(StageEvent.TurnRequested(coordinatorAvailable = true, coordinationRecorded = true))
        assertEquals(1, next.checkpoint().repairRetries)
        assertTrue(next.issue!!.requiresUser)
        assertTrue(next.issue!!.retryBlocked)
        assertTrue(next.has(StageEffect.Yield))
        assertFalse(next.has(StageEffect.Coordinate))
        assertFalse(next.has(StageEffect.PrepareWorker))
    }

    @Test fun durableHandoffResumesPlannerBeforeWorkerAndWaitsForAnAnswer() {
        val handedOff = initial.copy(phase = AttemptPhase.EXECUTING, report = "finished", awaitingPlanner = true)
        val next = handedOff.on(StageEvent.TurnRequested(true, false))
        assertTrue(next.has(StageEffect.Coordinate))
        assertFalse(next.has(StageEffect.PrepareWorker))
        val waiting = reduce(next.state, StageEvent.PlannerDecided(StageTurnDecision(StageTurnAction.WAIT, "question", "q"), restored = true))
        assertTrue(waiting.has(StageEffect.AskUser))
        assertEquals("q", waiting.checkpoint().waitingForUser)
        assertEquals(1, waiting.checkpoint().turnIndex)
        assertFalse(waiting.checkpoint().coordinationOwed)
        val answered = reduce(waiting.state, StageEvent.UserAnswered)
        assertNull(answered.checkpoint().waitingForUser)
        assertTrue(answered.has(StageEffect.RunWorker))
        assertFalse(answered.checkpoint().coordinationOwed)
    }

    @Test fun admissionRejectsChangedIdentityButAcceptsNativeGeneration() {
        listOf(initial.copy(id = "other"), initial.copy(sessionId = "other"), initial.copy(turnIndex = 1),
            initial.copy(path = "/other"), initial.copy(assignment = StageAssignment("other", "model"))).forEach {
            assertFailsWith<IllegalArgumentException> { initial.on(StageEvent.WorkerAdmitted(it)) }
        }
        assertEquals(4L, initial.on(StageEvent.WorkerAdmitted(initial.copy(sessionGeneration = 4))).checkpoint().sessionGeneration)
    }

    @Test fun verifierFailureRetainsVerificationAndChargesOnlyTransportBudget() {
        val saved = initial.copy(phase = AttemptPhase.VERIFYING, repairRetries = 1, mergeRetries = 1)
        val issue = PlanningIssue(IssueKind.TRANSIENT, "temporarily unavailable")
        val next = saved.on(StageEvent.VerificationDecided(Verdict(false, "", issue), retry))
        assertEquals(AttemptPhase.VERIFYING, next.checkpoint().phase)
        assertEquals(1, next.checkpoint().transportRetries)
        assertEquals(1, next.checkpoint().repairRetries)
        assertEquals(1, next.checkpoint().mergeRetries)
        assertTrue(next.issue!!.retryAt > retry.now)
        assertFalse(next.has(StageEffect.RecordVerification))
        assertFalse(next.has(StageEffect.Capture))
    }

    @Test fun negativeVerificationChargesRepairOnlyAndExhaustionPreservesPhase() {
        val saved = initial.copy(phase = AttemptPhase.VERIFYING, transportRetries = 7, mergeRetries = 3)
        val next = saved.on(StageEvent.VerificationDecided(Verdict(false, "fix output"), retry))
        assertEquals(AttemptPhase.FAILED, next.checkpoint().phase)
        assertEquals(1, next.checkpoint().repairRetries)
        assertEquals(7, next.checkpoint().transportRetries)
        assertEquals(3, next.checkpoint().mergeRetries)
        assertNull(next.issue)
        assertEquals(listOf(StageEffect.RecordVerification, StageEffect.Persist, StageEffect.Yield), next.effects)
        val exhausted = saved.copy(repairRetries = 2).on(StageEvent.VerificationDecided(Verdict(false, "fix output"), retry))
        assertEquals(AttemptPhase.VERIFYING, exhausted.checkpoint().phase)
        assertEquals(2, exhausted.checkpoint().repairRetries)
        assertTrue(exhausted.issue!!.requiresUser)
        assertFalse(exhausted.has(StageEffect.Capture))
    }

    @Test fun mergeRecoveryRequiresVerdictEvenWhenCommitAlreadyIntegrated() {
        val saved = initial.copy(phase = AttemptPhase.INTEGRATING, mergePhase = AttemptPhase.VERIFYING, mergeRetries = 2)
        assertTrue(saved.on(StageEvent.MergeStarted(integrated = true)).has(StageEffect.RunMerge))
        val resumed = saved.on(StageEvent.ConflictRequested("/merge", retryLimit = 0))
        assertTrue(resumed.has(StageEffect.RunMergeVerifier))
        assertFalse(resumed.has(StageEffect.RunConflictAgent))
        assertEquals(saved, resumed.checkpoint())
        val legacy = saved.copy(mergePhase = AttemptPhase.INTEGRATING).on(StageEvent.ConflictRequested("/merge", 0))
        assertTrue(legacy.has(StageEffect.RunConflictAgent))
        assertEquals(2, legacy.checkpoint().mergeRetries)
    }

    @Test fun mergeRetryDelayPrecedesAdmissionAndBudgetExhaustion() {
        val saved = initial.copy(phase = AttemptPhase.INTEGRATING, mergePhase = AttemptPhase.FAILED, mergeRetries = 1)
        val next = saved.on(StageEvent.ConflictRequested("/merge", 2))
        assertIs<StageEffect.Delay>(next.effects.first())
        assertEquals(StageEffect.Persist, next.effects[1])
        assertEquals(2, next.checkpoint().mergeRetries)
        val exhausted = saved.on(StageEvent.ConflictRequested("/merge", 1))
        assertEquals(saved, exhausted.checkpoint())
        assertEquals(listOf(StageEffect.Yield), exhausted.effects)
    }

    @Test fun cancellationPreservesNewestAcceptedPhaseAndUnsavedExternalEffects() {
        val running = initial.copy(phase = AttemptPhase.EXECUTING, chatTurns = listOf(StageChatTurn(0, 10)))
        val live = running.copy(report = "partial", pendingTool = "publish", pendingToolExternal = true,
            steps = listOf(CodingStep(CodingStepKind.EXEC, "publish", callId = "tool", running = true)))
        val interrupted = running.on(StageEvent.Interrupted(live, waiting = true, at = 20)).checkpoint()
        assertEquals("partial", interrupted.report)
        assertTrue(interrupted.pendingToolExternal)
        assertEquals(ToolPhase.UNKNOWN, interrupted.steps.single().toolPhase)
        assertEquals(20L, interrupted.chatTurns.single().completedAt)
        val verifying = running.copy(phase = AttemptPhase.VERIFYING, report = "accepted")
        val kept = verifying.on(StageEvent.Interrupted(live, false, 20)).checkpoint()
        assertEquals(AttemptPhase.VERIFYING, kept.phase)
        assertEquals("accepted", kept.report)
        assertFalse(kept.pendingToolExternal)
        val complete = verifying.copy(phase = AttemptPhase.COMPLETE)
        assertEquals(complete, complete.on(StageEvent.Interrupted(live, false, 20)).checkpoint())
        assertTrue(complete.on(StageEvent.Interrupted(live.copy(id = "other"), false, 20)).effects.isEmpty())
    }
}
