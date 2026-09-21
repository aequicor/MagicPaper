package io.aequicor.magicpaper.domain

/** Controlled test neighbours. Mutability belongs to the fixture, never to the production service. */
class TestPlanningExecutionPorts(organisms: SessionOrganismService? = null) : PlanningAttemptAuthority {
    var runtimePolicyReady = true
    override suspend fun requireRuntimePolicyReady() { check(runtimePolicyReady) { "Controlled unconfirmed settings policy" } }
    var chatHooks: PlanningExecutionHooks? = null
    var onPrepareAttempt: suspend (Plan, String, StageAttempt) -> StageAttempt =
        organisms?.let { it::preparePlanAttempt } ?: { _, _, attempt -> attempt }
    var onAuthorizeRetry: suspend (Plan, String, StageAttempt) -> PlanAttemptRetryAuthorization? =
        organisms?.let { it::authorizePlanRetry } ?: { _, _, _ -> null }
    var onAttemptCheckpoint: suspend (Plan, String, StageAttempt) -> Unit =
        organisms?.let { it::planAttemptCheckpoint } ?: { _, _, _ -> }
    var onStoppedCheckpoint: suspend (Plan) -> Unit = organisms?.let { it::planStopped } ?: { }
    override suspend fun prepareAttempt(plan: Plan, stageId: String, attempt: StageAttempt) = onPrepareAttempt(plan, stageId, attempt)
    override suspend fun authorizeRetry(plan: Plan, stageId: String, attempt: StageAttempt) = onAuthorizeRetry(plan, stageId, attempt)
    override suspend fun attemptCheckpoint(plan: Plan, stageId: String, attempt: StageAttempt) = onAttemptCheckpoint(plan, stageId, attempt)
    override suspend fun stoppedCheckpoint(plan: Plan) = onStoppedCheckpoint(plan)
}
