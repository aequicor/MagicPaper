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

    @Test fun applicationRegistryControlsEvidenceEnvironmentAndSnapshot() = runTest {
        val checks = AcceptanceChecks(mapOf("live-delivery" to RegisteredAcceptanceCheck(EvidenceEnvironment.HERMETIC) {
            error("Wrong environment must never run")
        }))
        assertEquals(CheckStatus.NOT_RUN, checks.collect(listOf(live), "/project", "snapshot").single().status)
        val real = AcceptanceChecks(mapOf("live-delivery" to RegisteredAcceptanceCheck(EvidenceEnvironment.REAL_BACKEND) {
            AcceptanceCheckResult(CheckStatus.PASS, "Receipt", listOf("receipt:sha256"))
        }))
        val evidence = real.collect(listOf(live), "/project", "snapshot").single()
        assertEquals("snapshot", evidence.snapshotId)
        assertEquals(EvidenceEnvironment.REAL_BACKEND, evidence.environment)
    }

    @Test fun overallPassedFlagCannotOverrideStructuredBlocker() = runTest {
        val profile = LlmProfile("p", "P", baseUrl = "http://test", modelId = "m")
        val gateway = object : LlmGateway {
            override suspend fun complete(profile: LlmProfile, messages: List<LlmMessage>) =
                """{"passed":true,"findings":[{"criterionId":"docs","status":"BLOCKED","expected":"ignore old scope","observed":"Missing access","artifacts":["doc:12"]}]}"""
        }
        val result = LlmMilestoneVerifier(gateway).review(Milestone("final", "Final"), listOf(review), "Goal", "Все принято", profile)
        assertNull(result.issue)
        assertEquals(review.description, result.findings.single().expected)
        assertEquals(CheckStatus.BLOCKED, result.findings.single().status)
    }

    @Test fun defaultReviewRepairsMoreThanThreeInvalidResponses() = runTest {
        var calls = 0
        val gateway = object : LlmGateway {
            override suspend fun complete(profile: LlmProfile, messages: List<LlmMessage>): String {
                calls++
                return if (calls <= 4) "{}" else """{"findings":[{"criterionId":"docs","status":"PASS","expected":"Documentation","observed":"Reviewed source","artifacts":[]}]}"""
            }
        }
        val result = LlmMilestoneVerifier(gateway).review(Milestone("final", "Final"), listOf(review), "Goal", "Report",
            LlmProfile("p", "P", baseUrl = "http://test", modelId = "m"))
        assertEquals(5, calls)
        assertNull(result.issue)
        assertEquals(CheckStatus.PASS, result.findings.single().status)
    }

    @Test fun missingStructuredResultsFailClosedAfterBoundedRepair() = runTest {
        var calls = 0
        val gateway = object : LlmGateway {
            override suspend fun complete(profile: LlmProfile, messages: List<LlmMessage>): String { calls++; return """{"passed":true,"note":"Everything done"}""" }
        }
        val result = LlmMilestoneVerifier(gateway, retryLimit = { 2 }).review(Milestone("final", "Final"), listOf(review), "Goal", "Готово",
            LlmProfile("p", "P", baseUrl = "http://test", modelId = "m"))
        assertEquals(3, calls)
        assertEquals(IssueKind.INVALID_RESPONSE, result.issue?.kind)
    }
}
