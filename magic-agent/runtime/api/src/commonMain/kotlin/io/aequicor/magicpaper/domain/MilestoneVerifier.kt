package io.aequicor.magicpaper.domain

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import io.aequicor.magicpaper.util.Id

interface MilestoneVerifier {
    suspend fun verify(milestone: Milestone, goal: String, report: String, profile: LlmProfile?): Verdict
    suspend fun review(milestone: Milestone, criteria: List<AcceptanceCriterion>, goal: String, report: String, profile: LlmProfile?): AcceptanceReview {
        val findings = mutableListOf<AcceptanceFinding>()
        for (criterion in criteria) {
            val verdict = verify(milestone.copy(acceptance = criterion.description), goal, report, profile)
            verdict.issue?.let { return AcceptanceReview(findings, it) }
            findings += AcceptanceFinding(criterion.id, if (verdict.passed) CheckStatus.PASS else CheckStatus.FAIL,
                criterion.description, verdict.note, recovery = AcceptanceRecovery.WORKER)
        }
        return AcceptanceReview(findings)
    }
}