package io.aequicor.magicpaper.domain

/** Adjacent session-owner admission and projection; plan checkpoints remain owned by PlanningStore. */
interface PlanningAttemptAuthority {
    /** Check the adjacent configuration owner before admitting a new external operation. */
    suspend fun requireRuntimePolicyReady()
    suspend fun prepareAttempt(plan: Plan, stageId: String, attempt: StageAttempt): StageAttempt
    suspend fun authorizeRetry(plan: Plan, stageId: String, attempt: StageAttempt): PlanAttemptRetryAuthorization?
    suspend fun attemptCheckpoint(plan: Plan, stageId: String, attempt: StageAttempt)
    suspend fun stoppedCheckpoint(plan: Plan)
}
