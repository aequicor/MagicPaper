package io.aequicor.magicpaper.domain

import io.aequicor.magicpaper.domain.planning.*
import kotlinx.serialization.json.Json
import kotlin.test.*

class FinalAttemptRulesTest {
    private val criterion = AcceptanceCriterion("stage/result", "Result is verified")
    private fun record(snapshot: String = "main-snapshot", attempt: String = "final", accepted: Boolean = true) =
        AcceptanceRecord("run", attempt, snapshot, listOf(criterion),
            listOf(AcceptanceFinding(criterion.id, if (accepted) CheckStatus.PASS else CheckStatus.FAIL,
                criterion.description, if (accepted) "Verified" else "Missing")),
            status = if (accepted) AcceptanceStatus.ACCEPTED else AcceptanceStatus.FAILED)
    private fun prepared() = StageAttempt("final", "review-session", StageAssignment("profile", "model"), path = "/work")
    private fun reviewed() = prepared().copy(phase = AttemptPhase.VERIFYING, report = "Accepted review report",
        engineSessionId = "main-native-session", verificationSnapshot = "main-snapshot", acceptanceRecord = record())
    private fun accepted() = finalAttemptTransition(reviewed(), FinalAttemptMutation.Accepted).attempt
    private fun awaitingDelivery() = accepted().copy(mergePhase = AttemptPhase.VERIFYING,
        mergeVerificationSnapshot = "delivery-snapshot", mergeAcceptanceRecord = record("delivery-snapshot"))

    @Test fun acceptedMainReportAndIdentityRemainImmutableThroughDeliveryTrace() {
        val original = accepted()
        var current = original
        fun apply(mutation: FinalAttemptMutation) {
            val change = finalAttemptTransition(current, mutation)
            current = change.attempt
            assertEquals(ExecutionPhase.INTEGRATING, change.phase)
            assertEquals(AttemptPhase.COMPLETE, current.phase)
            assertEquals(original.report, current.report)
            assertEquals(original.acceptanceRecord, current.acceptanceRecord)
            assertEquals(original.id, current.id)
            assertEquals(original.sessionId, current.sessionId)
            assertEquals(original.assignment, current.assignment)
            assertEquals(original.engineSessionId, current.engineSessionId)
        }
        apply(FinalAttemptMutation.DeliveryRequested("/merge", retryLimit = 2))
        apply(FinalAttemptMutation.DeliveryStarted("/merge"))
        apply(FinalAttemptMutation.ProgressObserved(StageProgress.from(current).copy(
            mergeEngineSessionId = "delivery-native-session", mergeReport = "Conflict repaired"), delivery = true))
        apply(FinalAttemptMutation.DeliveryTurnEnded("delivery-snapshot"))
        apply(FinalAttemptMutation.DeliveryReviewed(record("delivery-snapshot")))
        apply(FinalAttemptMutation.DeliveryFinished(true))
        assertEquals(MergeProgress.Settled, current.mergeProgress)
        assertEquals(1, current.mergeRetries)
        assertEquals("Conflict repaired", current.mergeReport)
        assertEquals("delivery-native-session", current.mergeEngineSessionId)
    }

    @Test fun deliveryProgressCannotRewriteAnAcceptedReportOrItsNativeIdentity() {
        val running = accepted().copy(mergePhase = AttemptPhase.EXECUTING)
        val progress = StageProgress.from(running)
        for (changed in listOf(progress.copy(report = "Replaced review"), progress.copy(engineSessionId = "other-native"))) {
            assertFailsWith<IllegalArgumentException> {
                finalAttemptTransition(running, FinalAttemptMutation.ProgressObserved(changed, delivery = true))
            }
        }
    }

    @Test fun mainReviewProgressCannotRewriteDeliveryOutput() {
        val running = reviewed().copy(phase = AttemptPhase.EXECUTING, mergeReport = "Saved repair", mergeEngineSessionId = "merge-native")
        val progress = StageProgress.from(running)
        for (changed in listOf(progress.copy(mergeReport = "Other repair"), progress.copy(mergeEngineSessionId = "other-native"))) {
            assertFailsWith<IllegalArgumentException> {
                finalAttemptTransition(running, FinalAttemptMutation.ProgressObserved(changed))
            }
        }
    }

    @Test fun wrongAttemptAndFailedAcceptanceNeverCompleteReviewOrDelivery() {
        assertFailsWith<IllegalArgumentException> {
            finalAttemptTransition(reviewed(), FinalAttemptMutation.AcceptanceRecorded(record(attempt = "other")))
        }
        assertFailsWith<IllegalArgumentException> {
            finalAttemptTransition(reviewed().copy(acceptanceRecord = record(accepted = false)), FinalAttemptMutation.Accepted)
        }
        assertFailsWith<IllegalArgumentException> {
            finalAttemptTransition(awaitingDelivery(), FinalAttemptMutation.DeliveryReviewed(record("delivery-snapshot", attempt = "other")))
        }
        assertFailsWith<IllegalArgumentException> {
            finalAttemptTransition(awaitingDelivery().copy(mergeAcceptanceRecord = record("delivery-snapshot", accepted = false)),
                FinalAttemptMutation.DeliveryFinished(true))
        }
    }

    @Test fun unknownExternalOutcomeOutranksTerminalAcceptance() {
        val review = reviewed().copy(pendingTool = "publish", pendingToolExternal = true)
        assertFailsWith<IllegalArgumentException> { finalAttemptTransition(review, FinalAttemptMutation.Accepted) }
        val delivery = awaitingDelivery().copy(pendingTool = "publish", pendingToolExternal = true)
        assertFailsWith<IllegalArgumentException> { finalAttemptTransition(delivery, FinalAttemptMutation.DeliveryFinished(true)) }
        val rejected = finalAttemptTransition(delivery, FinalAttemptMutation.DeliveryFinished(false)).attempt
        assertIs<StageResumption.UnknownOutcome>(rejected.resumption)
        assertFailsWith<IllegalArgumentException> {
            finalAttemptTransition(rejected, FinalAttemptMutation.DeliveryRequested("/merge", retryLimit = null))
        }
    }

    @Test fun corruptOrOutOfOrderPhaseDoesNotManufactureAResult() {
        val cases = listOf(
            prepared() to FinalAttemptMutation.VerificationStarted,
            prepared() to FinalAttemptMutation.VerificationTurnEnded,
            reviewed().copy(phase = AttemptPhase.EXECUTING) to FinalAttemptMutation.AcceptanceRecorded(record()),
            prepared() to FinalAttemptMutation.DeliveryRequested("/merge", retryLimit = 2),
            accepted().copy(mergePhase = AttemptPhase.PREPARED) to FinalAttemptMutation.DeliveryTurnEnded("snapshot"),
            accepted().copy(mergePhase = AttemptPhase.EXECUTING) to FinalAttemptMutation.DeliveryReviewed(record()),
            accepted().copy(mergePhase = AttemptPhase.EXECUTING) to FinalAttemptMutation.DeliveryFinished(true),
        )
        for ((attempt, mutation) in cases) assertFailsWith<IllegalArgumentException> { finalAttemptTransition(attempt, mutation) }
    }

    @Test fun deliveryFailurePreservesTheAlreadyAcceptedReview() {
        val original = accepted()
        val failed = finalAttemptTransition(original, FinalAttemptMutation.Failed(
            PlanningIssue(IssueKind.TRANSIENT, "Temporary transport failure"), StageRetryInputs(3, 100, 0), delivery = true))
        assertEquals(ExecutionPhase.INTEGRATING, failed.phase)
        assertEquals(original.report, failed.attempt.report)
        assertEquals(original.acceptanceRecord, failed.attempt.acceptanceRecord)
        assertEquals(AttemptPhase.COMPLETE, failed.attempt.phase)
        assertEquals(1, failed.attempt.transportRetries)
        assertNotNull(failed.attempt.error)
    }

    @Test fun serializedTraceReconstructsTheSameVerifiedFinalAttempt() {
        var direct = prepared()
        var replay = prepared()
        val trace = listOf<FinalAttemptMutation>(
            FinalAttemptMutation.EngineResolved(CodingEngine.PI),
            FinalAttemptMutation.VerificationPrepared("main-snapshot"),
            FinalAttemptMutation.VerificationStarted,
            FinalAttemptMutation.ProgressObserved(StageProgress.from(direct).copy(report = "Review result", engineSessionId = "native")),
            FinalAttemptMutation.VerificationTurnEnded,
            FinalAttemptMutation.AcceptanceRecorded(record()),
            FinalAttemptMutation.Accepted,
        )
        for (mutation in trace) {
            val raw = Json.encodeToString(FinalAttemptMutation.serializer(), mutation)
            val restored = Json.decodeFromString(FinalAttemptMutation.serializer(), raw)
            direct = finalAttemptTransition(direct, mutation).attempt
            replay = finalAttemptTransition(replay, restored).attempt
            assertEquals(direct, replay)
        }
        assertEquals(AttemptPhase.COMPLETE, replay.phase)
        assertEquals("Review result", replay.report)
        assertEquals(record(), replay.acceptanceRecord)
    }
}
