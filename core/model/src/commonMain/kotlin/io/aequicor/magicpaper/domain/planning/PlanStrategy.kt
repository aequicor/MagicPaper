package io.aequicor.magicpaper.domain.planning

import io.aequicor.magicpaper.domain.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable enum class PlanFailureCause { TRANSIENT_TRANSPORT, PERSISTENT_CONFIGURATION, REJECTED_RESULT, CONFLICT, UNKNOWN }
@Serializable enum class PlanRecoveryStrategy { EXISTING_BACKOFF, PAUSE_FOR_REVIEW }
@Serializable enum class PlanClassificationStatus { MODEL, UNAVAILABLE, INVALID_RESPONSE }

/** Observed outcomes, never model prose or an inference that an external action succeeded. */
data class PlanStrategySample(
    val outcomeSeq: Long, val intentSeq: Long, val runId: String, val operation: String,
    val stageId: String, val status: PlanIntentStatus, val durationMillis: Long?,
)

@Serializable
data class PlanStrategyMetrics(
    val operation: String, val samples: Int, val completed: Int, val rejected: Int, val interrupted: Int,
    val consecutiveFailures: Int, val measuredDurations: Int, val meanDurationMillis: Long?,
)

data class PlanStrategyTrigger(val sourceSeq: Long, val runId: String, val stageId: String, val metrics: PlanStrategyMetrics)

/** A bounded operational heuristic, not a statistically trained detector. No wall-clock reads. */
fun detectPlanStrategy(plan: Plan, samples: List<PlanStrategySample>): PlanStrategyTrigger? {
    if (plan.runId.isBlank() || !plan.mayContinueAutomaticRecovery()) return null
    val current = samples.filter { it.runId == plan.runId }
    val latest = current.lastOrNull() ?: return null
    if (latest.status == PlanIntentStatus.COMPLETED) return null
    val window = current.filter { it.operation == latest.operation && it.stageId == latest.stageId }.takeLast(16)
    val consecutive = window.asReversed().takeWhile { it.status != PlanIntentStatus.COMPLETED }.size
    if (consecutive < 2) return null
    val durations = window.mapNotNull { it.durationMillis?.takeIf { value -> value >= 0 } }
    // Divide before summing so even a corrupt/huge wall-clock duration cannot overflow the mean.
    val mean = durations.takeIf { it.isNotEmpty() }?.let { values ->
        val count = values.size.toLong()
        values.sumOf { it / count } + values.sumOf { it % count } / count
    }
    return PlanStrategyTrigger(latest.outcomeSeq, plan.runId, latest.stageId, PlanStrategyMetrics(
        latest.operation, window.size, window.count { it.status == PlanIntentStatus.COMPLETED },
        window.count { it.status == PlanIntentStatus.REJECTED }, window.count { it.status == PlanIntentStatus.INTERRUPTED },
        consecutive, durations.size, mean))
}

/** Existing retry admission remains the authority. Classification can only narrow it. */
fun Plan.mayContinueAutomaticRecovery(): Boolean = intent == ExecutionIntent.RUN && !stopping &&
    phase != ExecutionPhase.COMPLETE && issue?.let { it.kind == IssueKind.TRANSIENT && !it.requiresUser && !it.retryBlocked } == true &&
    milestones.none { it.attempts.lastOrNull()?.pendingToolExternal == true } && finalAttempt?.pendingToolExternal != true

fun selectPlanStrategy(plan: Plan, cause: PlanFailureCause, status: PlanClassificationStatus, retryLimit: Int?): PlanRecoveryStrategy =
    if (status == PlanClassificationStatus.MODEL && cause == PlanFailureCause.TRANSIENT_TRANSPORT &&
        plan.mayContinueAutomaticRecovery() && (retryLimit == null || plan.issue!!.retries <= retryLimit))
        PlanRecoveryStrategy.EXISTING_BACKOFF else PlanRecoveryStrategy.PAUSE_FOR_REVIEW

@Serializable
data class PlanStrategySelection(
    val version: Int = 1, val sourceSeq: Long, val runId: String, val stageId: String,
    val metrics: PlanStrategyMetrics, val cause: PlanFailureCause, val status: PlanClassificationStatus,
    val strategy: PlanRecoveryStrategy,
) {
    fun encode(): String = Json.encodeToString(serializer(), this)
    companion object {
        fun decode(value: String): PlanStrategySelection = Json.decodeFromString(serializer(), value).also {
            require(it.version == 1 && it.sourceSeq > 0 && it.runId.isNotBlank()) { "Invalid strategy selection" }
            require(it.metrics.samples in 2..16 && it.metrics.completed in 0..it.metrics.samples && it.metrics.rejected in 0..it.metrics.samples && it.metrics.interrupted in 0..it.metrics.samples &&
                it.metrics.completed + it.metrics.rejected + it.metrics.interrupted == it.metrics.samples &&
                it.metrics.consecutiveFailures in 2..it.metrics.samples && it.metrics.measuredDurations in 0..it.metrics.samples &&
                ((it.metrics.measuredDurations == 0 && it.metrics.meanDurationMillis == null) ||
                    (it.metrics.measuredDurations > 0 && it.metrics.meanDurationMillis != null && it.metrics.meanDurationMillis >= 0))) {
                "Invalid strategy metrics"
            }
            require(PlanJournalOperation.of(it.metrics.operation)?.kind == JournalEntryKind.INTENT) { "Invalid strategy operation" }
        }
    }
}

/** Does not reset a budget, shorten backoff, clear an issue, or grant an execution permission. */
fun applyPlanStrategy(plan: Plan, selection: PlanStrategySelection): Plan {
    require(plan.runId == selection.runId) { "Strategy belongs to another run" }
    return when (selection.strategy) {
        PlanRecoveryStrategy.EXISTING_BACKOFF -> {
            require(selection.status == PlanClassificationStatus.MODEL && selection.cause == PlanFailureCause.TRANSIENT_TRANSPORT &&
                plan.mayContinueAutomaticRecovery()) { "Strategy cannot authorize recovery" }
            plan
        }
        PlanRecoveryStrategy.PAUSE_FOR_REVIEW -> plan.copy(intent = ExecutionIntent.PAUSE, phase = ExecutionPhase.WAITING,
            status = PlanStatus.FAILED, issue = checkNotNull(plan.issue).copy(requiresUser = true,
                message = "Автоматические повторы приостановлены после повторных ошибок. Проверьте подключение и продолжите выполнение."))
    }
}
