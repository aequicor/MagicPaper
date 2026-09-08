package io.aequicor.magicpaper.domain

import kotlinx.serialization.json.Json
import kotlin.test.*

class StageResultEvidenceTest {
    @Test fun evidenceKeepsRegressionsAndFailedChecksButExcludesOtherRunsAndAttempts() {
        val attempt = StageAttempt("a", "worker", StageAssignment("model", "m"), turnIndex = 3, report = "Current test: FAILED")
        fun record(id: String, text: String) = CoordinationRecord(id, "stage", StageReply(StageReplyKind.RESULT, text))
        val plan = Plan("p", "project", "Goal", runId = "run", coordination = listOf(
            record("other-turn-0", "UNRELATED_ATTEMPT"),
            record("a-turn-0", "OLD_RUN").copy(runId = "old"),
            record("a-turn-1", "Links: PASS; backend: NOT_RUN").copy(verification = StageVerification(false, "Missing rollback check")),
            record("a-turn-2", "Changed files again; previous PASS is stale; regression: FAILED"),
            record("a-turn-9", "FUTURE_TURN"),
        ))
        val restored = Json.decodeFromString<Plan>(Json.encodeToString(Plan.serializer(), plan))
        val evidence = restored.stageVerificationReport("stage", attempt)
        assertContains(evidence, "backend: NOT_RUN")
        assertContains(evidence, "Missing rollback check")
        assertContains(evidence, "previous PASS is stale")
        assertTrue(evidence.endsWith("Current test: FAILED"))
        assertFalse(evidence.contains("UNRELATED_ATTEMPT"))
        assertFalse(evidence.contains("OLD_RUN"))
        assertFalse(evidence.contains("FUTURE_TURN"))
    }
}
