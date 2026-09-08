package io.aequicor.magicpaper.domain

import kotlinx.serialization.Serializable

@Serializable enum class EvidenceEnvironment { REVIEW, LOCAL_TEST, HERMETIC, REAL_BACKEND, MANUAL }
@Serializable enum class CheckStatus { PASS, FAIL, NOT_RUN, SKIPPED, BLOCKED, STALE }
@Serializable enum class AcceptanceStatus { UNKNOWN, ACCEPTED, PARTIAL, FAILED, BLOCKED, STALE }

internal fun EvidenceEnvironment.label(): String = when (this) {
    EvidenceEnvironment.REVIEW -> "оценка результата"
    EvidenceEnvironment.LOCAL_TEST -> "локальная проверка"
    EvidenceEnvironment.HERMETIC -> "проверка на фикстурах"
    EvidenceEnvironment.REAL_BACKEND -> "реальный сервис"
    EvidenceEnvironment.MANUAL -> "ручная проверка"
}

/** Part of the approved milestone specification. A model cannot waive it in a result. */
@Serializable data class AcceptanceCriterion(
    val id: String,
    val description: String,
    val required: Boolean = true,
    val environment: EvidenceEnvironment = EvidenceEnvironment.REVIEW,
    /** Identifier of an application-registered check, never a shell command from a report. */
    val checkId: String = "",
)

@Serializable data class AcceptanceFinding(
    val criterionId: String,
    val status: CheckStatus,
    val expected: String,
    val observed: String,
    val artifacts: List<String> = emptyList(),
)

@Serializable data class AcceptanceEvidence(
    val criterionId: String,
    val environment: EvidenceEnvironment,
    val snapshotId: String,
    val status: CheckStatus,
    val detail: String,
    val artifacts: List<String> = emptyList(),
)

@Serializable data class AcceptanceRecord(
    val runId: String,
    val attemptId: String,
    val snapshotId: String,
    val criteria: List<AcceptanceCriterion>,
    val findings: List<AcceptanceFinding>,
    val evidence: List<AcceptanceEvidence> = emptyList(),
    val status: AcceptanceStatus = AcceptanceStatus.UNKNOWN,
) {
    fun summary(): String = "Приёмка: $status. " + findings.filter { it.status != CheckStatus.PASS }
        .joinToString("; ") { "${it.criterionId}: ${it.status} — ${it.observed}" }
}

/** Only host code can register collectors. Backend reports have no write path to this registry. */
class AcceptanceChecks(private val checks: Map<String, RegisteredAcceptanceCheck> = emptyMap()) {
    suspend fun collect(criteria: List<AcceptanceCriterion>, path: String, snapshotId: String): List<AcceptanceEvidence> =
        criteria.filter { it.environment != EvidenceEnvironment.REVIEW }.map { criterion ->
            val check = checks[criterion.checkId]?.takeIf { it.environment == criterion.environment }
            val result = check?.run?.invoke(path) ?: AcceptanceCheckResult(CheckStatus.NOT_RUN,
                "Для ${criterion.environment} не зарегистрирована проверка ${criterion.checkId.ifBlank { "(ID отсутствует)" }}")
            AcceptanceEvidence(criterion.id, criterion.environment, snapshotId, result.status, result.detail, result.artifacts)
        }
}

data class RegisteredAcceptanceCheck(val environment: EvidenceEnvironment, val run: suspend (String) -> AcceptanceCheckResult)
data class AcceptanceCheckResult(val status: CheckStatus, val detail: String, val artifacts: List<String> = emptyList())

internal fun Milestone.criteria(): List<AcceptanceCriterion> = acceptanceCriteria.ifEmpty {
    listOf(AcceptanceCriterion("result", acceptance.ifBlank { description }.ifBlank { title }))
}.map { it.copy(id = "$id/${it.id}") }

internal fun Plan.acceptanceCriteria(): List<AcceptanceCriterion> = selectedMilestones.flatMap { it.criteria() }

/** Authoritative aggregation: no model-supplied overall passed flag participates here. */
object AcceptanceGate {
    fun evaluate(record: AcceptanceRecord, criteria: List<AcceptanceCriterion>, snapshotId: String?): AcceptanceRecord {
        if (snapshotId.isNullOrBlank() || record.snapshotId.isBlank() || snapshotId != record.snapshotId || record.criteria != criteria)
            return record.copy(status = AcceptanceStatus.STALE, findings = criteria.map {
                AcceptanceFinding(it.id, CheckStatus.STALE, it.description,
                    "Снимок файлов или критерии изменились либо недоступны; требуется новая проверка")
            })
        require(criteria.isNotEmpty() && criteria.map { it.id }.distinct().size == criteria.size) { "Нет однозначных критериев приёмки" }
        val findings = criteria.map { criterion ->
            val reviews = record.findings.filter { it.criterionId == criterion.id }
            val review = reviews.singleOrNull() ?: AcceptanceFinding(criterion.id, CheckStatus.NOT_RUN,
                criterion.description, "Нет единственного результата проверки критерия")
            if (review.status != CheckStatus.PASS || criterion.environment == EvidenceEnvironment.REVIEW) review
            else {
                val proof = record.evidence.singleOrNull { it.criterionId == criterion.id &&
                    it.snapshotId == snapshotId && it.environment == criterion.environment }
                if (proof?.status == CheckStatus.PASS && proof.artifacts.isNotEmpty()) review
                else AcceptanceFinding(criterion.id, proof?.status?.takeUnless { it == CheckStatus.PASS } ?: CheckStatus.NOT_RUN,
                    criterion.description, proof?.detail ?: "Нет подтверждения приложения для ${criterion.environment}", proof?.artifacts.orEmpty())
            }
        }
        val required = findings.filter { f -> criteria.single { it.id == f.criterionId }.required }
        val status = when {
            required.any { it.status == CheckStatus.STALE } -> AcceptanceStatus.STALE
            required.any { it.status == CheckStatus.FAIL } -> AcceptanceStatus.FAILED
            required.any { it.status == CheckStatus.BLOCKED } -> AcceptanceStatus.BLOCKED
            required.any { it.status != CheckStatus.PASS } -> AcceptanceStatus.PARTIAL
            else -> AcceptanceStatus.ACCEPTED
        }
        return record.copy(findings = findings, status = status)
    }
}
