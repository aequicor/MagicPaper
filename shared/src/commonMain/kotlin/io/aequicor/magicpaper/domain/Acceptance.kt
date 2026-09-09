package io.aequicor.magicpaper.domain

import kotlinx.serialization.Serializable

@Serializable enum class EvidenceEnvironment { REVIEW, LOCAL_TEST, HERMETIC, REAL_BACKEND, MANUAL }
@Serializable enum class CheckStatus { PASS, FAIL, NOT_RUN, SKIPPED, BLOCKED, STALE }
@Serializable enum class AcceptanceStatus { UNKNOWN, ACCEPTED, PARTIAL, FAILED, BLOCKED, STALE, ACCEPTED_WITH_SKIPS }

/** Created only by an explicit questionnaire answer, scoped to this run and exact criterion. */
@Serializable data class AcceptanceWaiver(
    val runId: String,
    val criterion: AcceptanceCriterion,
    val attemptId: String,
    val snapshotId: String,
    val createdAt: Long,
)

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
    val waivers: List<AcceptanceWaiver> = emptyList(),
) {
    internal val permitsProgress: Boolean get() = status in setOf(AcceptanceStatus.ACCEPTED, AcceptanceStatus.ACCEPTED_WITH_SKIPS)
    internal fun wasSkippedByUser(criterion: AcceptanceCriterion): Boolean =
        waivers.any { it.runId == runId && it.criterion == criterion } &&
            findings.singleOrNull { it.criterionId == criterion.id }?.status == CheckStatus.SKIPPED

    /** A skipped check makes no assertion about file contents or snapshot freshness. */
    internal val allChecksSkippedByUser: Boolean get() = criteria.isNotEmpty() && criteria.all(::wasSkippedByUser)
    internal val canSkipByUser: Boolean get() = status in setOf(AcceptanceStatus.PARTIAL, AcceptanceStatus.BLOCKED, AcceptanceStatus.STALE) &&
        criteria.any { it.required } && criteria.filter { it.required }.all { criterion ->
            findings.singleOrNull { it.criterionId == criterion.id }?.status in
                setOf(CheckStatus.PASS, CheckStatus.NOT_RUN, CheckStatus.SKIPPED, CheckStatus.BLOCKED, CheckStatus.STALE)
        }
    fun summary(): String = "Приёмка: $status. " + findings.filter { it.status != CheckStatus.PASS }
        .joinToString("; ") { "${it.criterionId}: ${it.status} — ${it.observed}" }

    /** Missing host evidence cannot be supplied by another worker report. */
    internal val canRetryWithWorker: Boolean get() = status in setOf(AcceptanceStatus.FAILED, AcceptanceStatus.PARTIAL) &&
        criteria.filter { it.required }.all { criterion ->
            val finding = findings.singleOrNull { it.criterionId == criterion.id }
            finding?.status == CheckStatus.PASS ||
                finding?.status in setOf(CheckStatus.FAIL, CheckStatus.NOT_RUN, CheckStatus.SKIPPED) &&
                (criterion.environment == EvidenceEnvironment.REVIEW || evidence.any {
                    it.criterionId == criterion.id && it.environment == criterion.environment && it.snapshotId == snapshotId &&
                        it.status in setOf(CheckStatus.PASS, CheckStatus.FAIL) && it.artifacts.isNotEmpty()
                })
        }

    internal fun userSummary(): String = buildString {
        append(when (status) {
            AcceptanceStatus.ACCEPTED -> "Результат подтверждён."
            AcceptanceStatus.ACCEPTED_WITH_SKIPS -> "Продолжено без проверки по решению пользователя."
            AcceptanceStatus.FAILED -> "Проверка обнаружила несоответствие требованиям."
            AcceptanceStatus.STALE -> "Состояние файлов после проверки изменилось или недоступно. Можно проверить актуальный результат или продолжить без проверки."
            else -> "Результат пока не подтверждён: не все обязательные проверки выполнены."
        })
        criteria.filter { it.required }.forEach { criterion ->
            val finding = findings.singleOrNull { it.criterionId == criterion.id }
            if (finding?.status == CheckStatus.PASS) return@forEach
            val proof = evidence.singleOrNull { it.criterionId == criterion.id && it.environment == criterion.environment && it.snapshotId == snapshotId }
            val unavailable = criterion.environment != EvidenceEnvironment.REVIEW &&
                (proof == null || proof.status == CheckStatus.NOT_RUN && proof.artifacts.isEmpty())
            append("\n\n• ${criterion.description}\n")
            if (finding?.status in setOf(CheckStatus.SKIPPED, CheckStatus.STALE) && waivers.any { it.runId == runId && it.criterion == criterion })
                append("Проверка пропущена по решению пользователя.")
            else if (unavailable) append("${criterion.environment.label().replaceFirstChar { it.uppercaseChar() }}: в приложении не подключён способ подтверждения. Можно повторить автоматическую проверку или продолжить без неё.")
            else append(finding?.observed ?: "Исполнитель должен предоставить подтверждение этого результата.")
        }
    }
}

/** Only host code can register collectors. Backend reports have no write path to this registry. */
class AcceptanceChecks(private val checks: Map<String, RegisteredAcceptanceCheck> = emptyMap()) {
    internal fun supports(criterion: AcceptanceCriterion): Boolean = criterion.environment == EvidenceEnvironment.REVIEW ||
        checks[criterion.checkId]?.environment == criterion.environment

    internal fun planningCatalog(): String = checks.entries.joinToString("\n") { "${it.key}: ${it.value.environment}" }
        .ifBlank { "Зарегистрированных проверок приложения нет." }

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
        require(criteria.isNotEmpty() && criteria.map { it.id }.distinct().size == criteria.size) { "Нет однозначных критериев приёмки" }
        val sameCriteria = record.criteria == criteria
        if (sameCriteria && record.allChecksSkippedByUser) return record.copy(status = AcceptanceStatus.ACCEPTED_WITH_SKIPS)
        if (snapshotId.isNullOrBlank() || record.snapshotId.isBlank() || snapshotId != record.snapshotId || !sameCriteria)
            return record.copy(status = AcceptanceStatus.STALE, findings = criteria.map {
                if (sameCriteria && record.wasSkippedByUser(it)) record.findings.single { finding -> finding.criterionId == it.id }
                else AcceptanceFinding(it.id, CheckStatus.STALE, it.description,
                    "Снимок файлов или критерии изменились либо недоступны; требуется новая проверка")
            })
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
        val requiredFindings = findings.filter { f -> criteria.single { it.id == f.criterionId }.required }
        val waived = requiredFindings.filter { f -> f.status == CheckStatus.SKIPPED && record.waivers.any {
            it.runId == record.runId && it.criterion == criteria.single { c -> c.id == f.criterionId }
        } }
        val required = requiredFindings - waived.toSet()
        val status = when {
            required.any { it.status == CheckStatus.STALE } -> AcceptanceStatus.STALE
            required.any { it.status == CheckStatus.FAIL } -> AcceptanceStatus.FAILED
            required.any { it.status == CheckStatus.BLOCKED } -> AcceptanceStatus.BLOCKED
            required.any { it.status != CheckStatus.PASS } -> AcceptanceStatus.PARTIAL
            waived.isNotEmpty() -> AcceptanceStatus.ACCEPTED_WITH_SKIPS
            else -> AcceptanceStatus.ACCEPTED
        }
        return record.copy(findings = findings, status = status)
    }
}
