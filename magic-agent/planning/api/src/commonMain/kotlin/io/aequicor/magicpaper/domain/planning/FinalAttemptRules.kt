package io.aequicor.magicpaper.domain.planning

import io.aequicor.magicpaper.domain.*
import kotlinx.serialization.Serializable

/** Facts for final review and delivery; none can replace an attempt or its execution identity. */
@Serializable
sealed interface FinalAttemptMutation {
    @Serializable data class EngineResolved(val engine: CodingEngine) : FinalAttemptMutation
    @Serializable data object LegacyReviewReopened : FinalAttemptMutation
    @Serializable data object WaiversApplied : FinalAttemptMutation
    @Serializable data class VerificationPrepared(val snapshot: String) : FinalAttemptMutation
    @Serializable data object VerificationStarted : FinalAttemptMutation
    @Serializable data object VerificationTurnEnded : FinalAttemptMutation
    @Serializable data class AcceptanceRecorded(val record: AcceptanceRecord) : FinalAttemptMutation
    @Serializable data object Accepted : FinalAttemptMutation
    @Serializable data class Failed(val issue: PlanningIssue, val retry: StageRetryInputs, val delivery: Boolean = false) : FinalAttemptMutation
    @Serializable data class DeliveryRequested(val path: String, val retryLimit: Int?) : FinalAttemptMutation
    @Serializable data class DeliveryStarted(val path: String) : FinalAttemptMutation
    @Serializable data class DeliveryTurnEnded(val snapshot: String?) : FinalAttemptMutation
    @Serializable data class DeliveryReviewed(val record: AcceptanceRecord) : FinalAttemptMutation
    @Serializable data class DeliveryFinished(val valid: Boolean) : FinalAttemptMutation
    @Serializable data class ProgressObserved(val progress: StageProgress, val delivery: Boolean = false) : FinalAttemptMutation
}

data class FinalAttemptChange(val attempt: StageAttempt, val phase: ExecutionPhase)

/** The plan owner validates the exact attempt reference before applying these pure rules. */
fun finalAttemptTransition(current: StageAttempt, mutation: FinalAttemptMutation): FinalAttemptChange {
    fun reviewOpen() = require(current.phase != AttemptPhase.COMPLETE) { "Итоговая проверка уже завершена" }
    fun knownOutcome() = require(current.resumption !is StageResumption.UnknownOutcome) { "Исход команды неизвестен" }
    fun deliveryOpen() {
        require(current.phase == AttemptPhase.COMPLETE && current.acceptanceRecord?.permitsProgress == true) { "Итоговая проверка не принята" }
    }
    fun matching(record: AcceptanceRecord) = require(record.attemptId == current.id) { "Проверка принадлежит другой попытке" }
    var phase = ExecutionPhase.VERIFYING
    val next = when (mutation) {
        is FinalAttemptMutation.EngineResolved -> {
            require(current.engine == null || current.engine == mutation.engine) { "Движок попытки изменился" }
            current.copy(engine = mutation.engine)
        }
        FinalAttemptMutation.LegacyReviewReopened -> {
            require(current.phase == AttemptPhase.COMPLETE && current.acceptanceRecord == null) { "Повтор итоговой проверки недоступен" }
            knownOutcome()
            current.copy(phase = AttemptPhase.PREPARED)
        }
        FinalAttemptMutation.WaiversApplied -> {
            require(current.phase == AttemptPhase.PREPARED && current.pendingTool.isBlank() && !current.pendingToolExternal)
            current.copy(phase = AttemptPhase.VERIFYING, report = "Проверки пропущены по решению пользователя")
        }
        is FinalAttemptMutation.VerificationPrepared -> {
            reviewOpen(); require(mutation.snapshot.isNotBlank()) { "Снимок файлов недоступен" }
            current.copy(verificationSnapshot = mutation.snapshot)
        }
        FinalAttemptMutation.VerificationStarted -> {
            reviewOpen(); knownOutcome()
            require(!current.verificationSnapshot.isNullOrBlank()) { "Снимок файлов недоступен" }
            current.copy(phase = AttemptPhase.EXECUTING)
        }
        FinalAttemptMutation.VerificationTurnEnded -> {
            require(current.phase == AttemptPhase.EXECUTING)
            current.copy(phase = AttemptPhase.VERIFYING)
        }
        is FinalAttemptMutation.AcceptanceRecorded -> {
            require(current.phase == AttemptPhase.VERIFYING); matching(mutation.record)
            current.copy(acceptanceRecord = mutation.record)
        }
        FinalAttemptMutation.Accepted -> {
            require(current.phase == AttemptPhase.VERIFYING && current.acceptanceRecord?.permitsProgress == true) { "Приёмка не подтверждена" }
            knownOutcome()
            current.copy(phase = AttemptPhase.COMPLETE, error = null)
        }
        is FinalAttemptMutation.Failed -> {
            if (mutation.delivery) {
                require(current.phase == AttemptPhase.COMPLETE)
                phase = ExecutionPhase.INTEGRATING
            } else reviewOpen()
            val retry = PlanningRetryPolicy.decide(mutation.issue, current.transportRetries,
                mutation.retry.limit, mutation.retry.now, mutation.retry.jitter)
            current.copy(transportRetries = (retry as? RetryDecision.Again)?.retries ?: current.transportRetries,
                error = retry.applyTo(mutation.issue))
        }
        is FinalAttemptMutation.DeliveryRequested -> {
            deliveryOpen(); knownOutcome(); phase = ExecutionPhase.INTEGRATING
            require(current.mergeProgress.needsResolver && mutation.path.isNotBlank())
            require(PlanningRetryPolicy.canRetry(current.mergeRetries, mutation.retryLimit)) { "Лимит исправлений переноса исчерпан" }
            current.copy(mergeRetries = PlanningRetryPolicy.nextRetry(current.mergeRetries), activity = "Разрешение конфликта переноса",
                mergeAssignment = current.mergeAssignment ?: current.assignment, mergePath = mutation.path).merging(MergeProgress.Admitted)
        }
        is FinalAttemptMutation.DeliveryStarted -> {
            deliveryOpen(); knownOutcome(); phase = ExecutionPhase.INTEGRATING
            require(mutation.path.isNotBlank())
            current.copy(mergePath = mutation.path).merging(MergeProgress.Running)
        }
        is FinalAttemptMutation.DeliveryTurnEnded -> {
            deliveryOpen(); phase = ExecutionPhase.INTEGRATING
            require(current.mergeProgress == MergeProgress.Running)
            current.copy(mergeVerificationSnapshot = mutation.snapshot).merging(MergeProgress.AwaitingVerdict)
        }
        is FinalAttemptMutation.DeliveryReviewed -> {
            deliveryOpen(); phase = ExecutionPhase.INTEGRATING; matching(mutation.record)
            require(current.mergeProgress == MergeProgress.AwaitingVerdict)
            current.copy(mergeAcceptanceRecord = mutation.record)
        }
        is FinalAttemptMutation.DeliveryFinished -> {
            deliveryOpen(); phase = ExecutionPhase.INTEGRATING
            require(current.mergeProgress == MergeProgress.AwaitingVerdict)
            require(!mutation.valid || current.mergeAcceptanceRecord?.permitsProgress == true) { "Проверка переноса не пройдена" }
            if (mutation.valid) knownOutcome()
            current.merging(if (mutation.valid) MergeProgress.Settled else MergeProgress.Rejected)
        }
        is FinalAttemptMutation.ProgressObserved -> {
            if (mutation.delivery) {
                deliveryOpen(); phase = ExecutionPhase.INTEGRATING
                require(current.mergeProgress == MergeProgress.Running)
                require(mutation.progress.report == current.report && mutation.progress.engineSessionId == current.engineSessionId) {
                    "Перенос не может менять принятый результат проверки"
                }
            } else {
                require(current.phase == AttemptPhase.EXECUTING)
                require(mutation.progress.mergeReport == current.mergeReport && mutation.progress.mergeEngineSessionId == current.mergeEngineSessionId) {
                    "Итоговая проверка не может менять результат переноса"
                }
            }
            mutation.progress.applyTo(current)
        }
    }
    return FinalAttemptChange(next, phase)
}
