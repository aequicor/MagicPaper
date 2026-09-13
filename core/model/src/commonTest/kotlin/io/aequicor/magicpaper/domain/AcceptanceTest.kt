package io.aequicor.magicpaper.domain

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.*

class AcceptanceTest {
    private val review = AcceptanceCriterion("docs", "Документы согласованы")
    private val live = AcceptanceCriterion("live", "Передача реальному backend", environment = EvidenceEnvironment.REAL_BACKEND, checkId = "live-delivery")
    private fun record(criteria: List<AcceptanceCriterion> = listOf(review), status: CheckStatus = CheckStatus.PASS) =
        AcceptanceRecord("run", "attempt", "snapshot", criteria,
            criteria.map { AcceptanceFinding(it.id, status, it.description, "Наблюдение", listOf("report:1")) })

    @Test fun historicalViolationAndUnclassifiedLegacyFailureNeverAuthorizeAutomaticRepair() {
        for (recovery in listOf(AcceptanceRecovery.OWNER, AcceptanceRecovery.UNAVAILABLE, AcceptanceRecovery.UNSPECIFIED)) {
            val base = record(status = CheckStatus.FAIL)
            val result = AcceptanceGate.evaluate(base.copy(findings = base.findings.map { it.copy(recovery = recovery) }), base.criteria, "snapshot")
            assertNotNull(result.automaticRepairProblem(null))
            assertFalse(result.permitsProgress)
            if (recovery != AcceptanceRecovery.UNSPECIFIED) assertFalse(result.canRetryWithWorker)
        }
    }

    @Test fun repeatedProblemStopsEvenWhenReportAndSnapshotChangeButNewProblemCanBeRepaired() {
        val base = record(status = CheckStatus.FAIL)
        val previous = AcceptanceGate.evaluate(base.copy(findings = base.findings.map {
            it.copy(recovery = AcceptanceRecovery.WORKER, problemKey = "head-moved")
        }), base.criteria, "snapshot")
        val repeated = previous.copy(snapshotId = "new", findings = previous.findings.map { it.copy(observed = "Another wording") })
        assertNotNull(repeated.automaticRepairProblem(previous))
        val fresh = repeated.copy(findings = repeated.findings.map { it.copy(problemKey = "different-case") })
        assertNull(fresh.automaticRepairProblem(previous))
        assertNull(repeated.copy(runId = "another-run").automaticRepairProblem(previous))
    }

    @Test fun aModelPassCannotReplaceMissingOrWrongEnvironmentHostEvidence() {
        val base = record(listOf(live))
        assertEquals(AcceptanceStatus.PARTIAL, AcceptanceGate.evaluate(base, base.criteria, "snapshot").status)
        for ((env, snapshot) in listOf(EvidenceEnvironment.HERMETIC to "snapshot", EvidenceEnvironment.REAL_BACKEND to "old")) {
            val forged = base.copy(evidence = listOf(AcceptanceEvidence(live.id, env, snapshot, CheckStatus.PASS, "Passed", listOf("artifact"))))
            assertEquals(AcceptanceStatus.PARTIAL, AcceptanceGate.evaluate(forged, base.criteria, "snapshot").status)
        }
        val accepted = base.copy(evidence = listOf(AcceptanceEvidence(live.id, live.environment, "snapshot", CheckStatus.PASS, "Real check", listOf("artifact"))))
        assertEquals(AcceptanceStatus.ACCEPTED, AcceptanceGate.evaluate(accepted, base.criteria, "snapshot").status)
    }

    @Test fun incompleteRequiredChecksNeverBecomeAccepted() {
        for (status in CheckStatus.entries.filter { it != CheckStatus.PASS }) {
            val base = record(status = status)
            assertNotEquals(AcceptanceStatus.ACCEPTED, AcceptanceGate.evaluate(base, base.criteria, "snapshot").status)
        }
        val optional = review.copy(required = false)
        val base = record(listOf(optional), CheckStatus.NOT_RUN)
        val result = AcceptanceGate.evaluate(base, base.criteria, "snapshot")
        assertEquals(AcceptanceStatus.ACCEPTED, result.status)
        assertEquals(CheckStatus.NOT_RUN, result.findings.single().status)
    }

    @Test fun snapshotAndContractChangesInvalidatePreviouslyAcceptedEvidence() {
        val base = record().copy(status = AcceptanceStatus.ACCEPTED)
        assertEquals(AcceptanceStatus.STALE, AcceptanceGate.evaluate(base, base.criteria, "new").status)
        assertEquals(AcceptanceStatus.STALE, AcceptanceGate.evaluate(base, base.criteria, null).status)
        assertEquals(AcceptanceStatus.STALE, AcceptanceGate.evaluate(base, listOf(review.copy(required = false)), "snapshot").status)
        assertEquals(AcceptanceStatus.PARTIAL, AcceptanceGate.evaluate(base.copy(findings = emptyList()), base.criteria, "snapshot").status)
        assertEquals(AcceptanceStatus.PARTIAL, AcceptanceGate.evaluate(base.copy(findings = base.findings + base.findings), base.criteria, "snapshot").status)
    }

    @Test fun findingsAndEvidenceSurviveRestartWithoutBecomingAnOverallPass() {
        val original = record(status = CheckStatus.BLOCKED)
        val restored = Json.decodeFromString<AcceptanceRecord>(Json.encodeToString(AcceptanceRecord.serializer(), original))
        assertEquals(original, restored)
        assertEquals(AcceptanceStatus.BLOCKED, AcceptanceGate.evaluate(restored, restored.criteria, "snapshot").status)
    }

    @Test fun explicitUserSkipPermitsProgressWithoutInventingAPass() {
        val original = record(listOf(live), CheckStatus.SKIPPED)
        val waiver = AcceptanceWaiver(original.runId, live, original.attemptId, original.snapshotId, 1)
        val waived = original.copy(waivers = listOf(waiver))
        val restored = Json.decodeFromString<AcceptanceRecord>(Json.encodeToString(AcceptanceRecord.serializer(), waived))
        val result = AcceptanceGate.evaluate(restored, listOf(live), "snapshot")
        assertEquals(AcceptanceStatus.ACCEPTED_WITH_SKIPS, result.status)
        assertTrue(result.permitsProgress)
        assertEquals(CheckStatus.SKIPPED, result.findings.single().status)
        assertTrue(result.evidence.isEmpty())
        assertContains(result.userSummary(), "по решению пользователя")
        assertEquals(AcceptanceStatus.PARTIAL, AcceptanceGate.evaluate(original, listOf(live), "snapshot").status)
        assertFalse(AcceptanceGate.evaluate(waived.copy(runId = "next-run"), listOf(live), "snapshot").permitsProgress)
        assertEquals(AcceptanceStatus.ACCEPTED_WITH_SKIPS, AcceptanceGate.evaluate(waived, listOf(live), "changed").status)
        assertEquals(AcceptanceStatus.ACCEPTED_WITH_SKIPS, AcceptanceGate.evaluate(waived, listOf(live), null).status)
        assertFalse(AcceptanceGate.evaluate(waived.copy(criteria = listOf(live.copy(description = "New requirement"))),
            listOf(live.copy(description = "New requirement")), "snapshot").permitsProgress)
        val failed = waived.copy(findings = listOf(waived.findings.single().copy(status = CheckStatus.FAIL)))
        assertEquals(AcceptanceStatus.FAILED, AcceptanceGate.evaluate(failed, listOf(live), "snapshot").status)
    }

    @Test fun staleSnapshotKeepsSkippedChecksAndInvalidatesOnlyChecksThatStillNeedEvidence() {
        val original = record(listOf(review, live)).copy(
            findings = listOf(AcceptanceFinding(review.id, CheckStatus.PASS, review.description, "Reviewed"),
                AcceptanceFinding(live.id, CheckStatus.SKIPPED, live.description, "User skipped")),
            waivers = listOf(AcceptanceWaiver("run", live, "attempt", "snapshot", 1)))
        val result = AcceptanceGate.evaluate(original, original.criteria, "changed")
        assertEquals(AcceptanceStatus.STALE, result.status)
        assertEquals(listOf(CheckStatus.STALE, CheckStatus.SKIPPED), result.findings.map { it.status })
        assertTrue(result.canSkipByUser)
        assertContains(result.userSummary(), "Проверка пропущена по решению пользователя")
    }

}
