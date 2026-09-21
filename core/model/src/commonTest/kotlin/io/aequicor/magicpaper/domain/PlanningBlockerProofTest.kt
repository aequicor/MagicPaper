package io.aequicor.magicpaper.domain

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlanningBlockerProofTest {
    private val issue = PlanningIssue(IssueKind.VERIFICATION, "Missing host evidence", retryAt = 101,
        retries = 2, requiresUser = true)
    private val criterion = AcceptanceCriterion("check", "Run tests", environment = EvidenceEnvironment.LOCAL_TEST)
    private val acceptance = AcceptanceRecord("run", "attempt", "snapshot", listOf(criterion),
        listOf(AcceptanceFinding("check", CheckStatus.NOT_RUN, "Tests passed", "Host unavailable")),
        status = AcceptanceStatus.PARTIAL)
    private val attempt = StageAttempt("attempt", "worker", StageAssignment("profile", "model"),
        phase = AttemptPhase.VERIFYING, error = issue, acceptanceRecord = acceptance,
        sessionGeneration = 3, turnIndex = 4, repairRetries = 2, mergeRetries = 1, transportRetries = 5)
    private val stage = Milestone("stage", "Stage", attempts = listOf(attempt))
    private val blocker = PlanningBlocker("plan", issue, stage, attempt, "run")

    @Test fun stableTokenHasAnExplicitWireGoldenIndependentOfEnumObjectHashes() {
        assertEquals("plan-blocked-run-stage-attempt-3-4-2-v2-5c41fafe", blocker.messageId)
        val reopenedIssue = Json.decodeFromString(PlanningIssue.serializer(), Json.encodeToString(PlanningIssue.serializer(), issue))
        assertEquals(blocker.messageId, blocker.copy(issue = reopenedIssue).messageId)
        assertEquals(blocker.messageId, blocker.copy(attempt = attempt.copy(updatedAt = 999, report = "Telemetry")).messageId)
    }

    @Test fun everyIssueFieldAndRunBindingChangesThePresentationToken() {
        listOf(issue.copy(kind = IssueKind.UNCERTAIN), issue.copy(message = "Changed"), issue.copy(retryAt = 102),
            issue.copy(retries = 3), issue.copy(requiresUser = false), issue.copy(retryBlocked = true)).forEach {
            assertNotEquals(blocker.messageId, blocker.copy(issue = it).messageId)
        }
        listOf(blocker.copy(runId = "new-run"), blocker.copy(stage = stage.copy(id = "other")),
            blocker.copy(attempt = attempt.copy(sessionGeneration = 4)), blocker.copy(attempt = attempt.copy(turnIndex = 5)),
            blocker.copy(attempt = attempt.copy(repairRetries = 3))).forEach { assertNotEquals(blocker.messageId, it.messageId) }
    }

    @Test fun fullProofBindsTheExactAcceptanceEvenWhenTheVisibleIssueDoesNotChange() {
        val proof = assertNotNull(blocker.verificationProof)
        assertEquals(proof, Json.decodeFromString(PlanningSkipProof.serializer(), Json.encodeToString(PlanningSkipProof.serializer(), proof)))
        val changed = blocker.copy(attempt = attempt.copy(acceptanceRecord = acceptance.copy(snapshotId = "new-snapshot")))
        assertEquals(blocker.messageId, changed.messageId)
        assertNotEquals(proof, changed.verificationProof)
        assertNotEquals(proof, blocker.copy(attempt = attempt.copy(mergeRetries = 2)).verificationProof)
        assertNotEquals(proof, blocker.copy(attempt = attempt.copy(transportRetries = 6)).verificationProof)
        assertNull(blocker.copy(attempt = attempt.copy(pendingToolExternal = true)).verificationProof)
        assertNull(blocker.copy(attempt = attempt.copy(phase = AttemptPhase.EXECUTING)).verificationProof)
        assertNull(blocker.copy(attempt = null).verificationProof)
    }

    @Test fun legacyRecognitionRequiresTheEntireOriginalBindingAndCanonicalIntSuffix() {
        val prefix = "plan-blocked-run-stage-attempt-3-4-2-"
        listOf("0", "123456789", "-2147483648", "2147483647").forEach {
            assertTrue(blocker.matchesLegacyMessageId(prefix + it))
        }
        listOf("", "01", "+1", "-0", "2147483648", "1junk", "v2-5c41fafe").forEach {
            assertFalse(blocker.matchesLegacyMessageId(prefix + it))
        }
        listOf("plan-blocked-stage-attempt-2-123", "plan-blocked-other-run-stage-attempt-3-4-2-123",
            "plan-blocked-run-other-stage-attempt-3-4-2-123", "plan-blocked-run-stage-attempt-4-4-2-123",
            "plan-blocked-run-stage-attempt-3-5-2-123").forEach { assertFalse(blocker.matchesLegacyMessageId(it)) }
    }
}
