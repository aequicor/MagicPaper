package io.aequicor.magicpaper.data.coding

import io.aequicor.magicpaper.domain.*
import kotlinx.coroutines.flow.StateFlow

/** One durable boundary for organism inputs and their verified projections. */
interface SessionOrganismStore {
    val organisms: StateFlow<Map<String, SessionOrganism>>
    val projections: StateFlow<Map<String, SessionOrganismProjection>>
    val failures: StateFlow<Map<String, String>>
    suspend fun get(id: String): SessionOrganism
    suspend fun projection(id: String): SessionOrganismProjection
    suspend fun loadAll(): List<SessionOrganism>
    suspend fun dispatch(id: String, input: SessionOrganismMachine.Input): SessionOrganismMachine.Transition
    suspend fun clearForReset(): Unit
    suspend fun setArchiveVisibility(id: String, sessionId: String, generation: Long, archived: Boolean,
        stillReady: () -> Boolean = { true }): SessionOrganism
    suspend fun applyLimits(id: String, limits: OrganismLimits): SessionOrganism
    suspend fun admitIntegration(scope: SessionAuthority, request: SessionIntegrationRequest, fingerprint: String): Pair<SessionOrganism, Boolean>
    suspend fun checkpointIntegration(id: String, record: SessionIntegration): SessionOrganism
    suspend fun adopt(projectId: String, root: CodingSession, descendants: List<CodingSession>, limits: OrganismLimits = OrganismLimits()): SessionOrganism
    suspend fun renameByUser(id: String, target: String, name: String, operationId: String): SessionOrganism
    suspend fun check(scope: SessionAuthority): Unit
    suspend fun requestFailureStop(id: String, rootId: String, generation: Long, reason: String): SessionOrganism
    suspend fun authorizePlanRetry(id: String, sessionId: String, binding: SessionLegacyAttempt,
        continuationConfirmed: Boolean = false): PlanAttemptRetryAuthorization?
    suspend fun reconcileAndAuthorizePlanRetry(id: String, request: OrganismRetryRequest, proof: PlanRetryRecoveryProof,
        requestedBinding: SessionLegacyAttempt, continuationConfirmed: Boolean = false): PlanAttemptRetryAuthorization
    suspend fun admitPlanWorker(id: String, session: CodingSession, task: SessionTask,
        binding: SessionLegacyAttempt, rules: PlanningRulesSnapshot?, unfinishedStageIds: Set<String>,
        retryAuthorization: PlanAttemptRetryAuthorization? = null, continuationConfirmed: Boolean = false): SessionOrganism
    suspend fun acceptPlanResult(id: String, binding: SessionLegacyAttempt, result: SessionResult): SessionOrganism
    suspend fun recordWorkspace(id: String, sessionId: String, generation: Long, workspace: SessionCodingWorkspace): SessionOrganism
    suspend fun changeRootMode(id: String, sessionId: String, mode: CodingInteractionMode): SessionOrganism
    suspend fun prepareUserTurn(id: String, sessionId: String, requestId: String): SessionOrganism
    suspend fun resolveSessionQuarantine(id: String, sessionId: String, resolution: SessionQuarantineResolution): SessionOrganism
    suspend fun reconcileInterruptedRun(id: String, sessionId: String, generation: Long, version: Long): SessionOrganism
    suspend fun beginRun(id: String, sessionId: String): SessionNode
    suspend fun finishStop(id: String, sessionIds: Set<String>): SessionOrganism
    suspend fun requestUserStop(id: String, target: String, operationId: String, archive: Boolean): SessionOrganism
    suspend fun restoreByUser(id: String, target: String, operationId: String, rules: PlanningRulesSnapshot, sourceVersion: String?): SessionOrganism
    suspend fun command(scope: SessionAuthority, operationId: String, request: OrganismCommand): SessionOrganism
    suspend fun commandTransition(scope: SessionAuthority, operationId: String, request: OrganismCommand): SessionOrganismMachine.Transition
    suspend fun observe(id: String, sessionId: String, generation: Long, observed: SessionObservedState): SessionOrganism
    suspend fun acknowledge(id: String, deliveryId: String, recipient: String, generation: Long, processed: Boolean): SessionOrganism
    suspend fun charge(scope: SessionAuthority, tokens: Long): SessionOrganism
    suspend fun beginAuxiliary(context: OrganismAuxiliaryAdmission): SessionAuxiliaryRun
    suspend fun chargeAuxiliary(organismId: String, auxiliaryId: String, sourceId: String, totalTokens: Long): SessionOrganism
    suspend fun finishAuxiliary(organismId: String, auxiliaryId: String, observed: SessionObservedState): SessionOrganism
    suspend fun recover(id: String): SessionOrganism
    suspend fun deleteHistoryByUser(id: String, target: String?): SessionOrganism
    suspend fun recordResult(id: String, result: SessionResult): SessionOrganism
    suspend fun proposeImmunityInterventions(id: String): SessionOrganism
    suspend fun acceptImmunityIntervention(id: String, proposalId: String, action: ImmunityAction,
        rules: PlanningRulesSnapshot? = null, sourceVersion: String? = null, reconciled: Boolean = false,
    ): SessionOrganism
    suspend fun finishImmunityIntervention(id: String, proposalId: String, error: String? = null): SessionOrganism
    suspend fun dismissImmunityIntervention(id: String, proposalId: String): SessionOrganism
    suspend fun inspectSignals(id: String): SessionOrganism
    suspend fun quarantine(id: String, sessionId: String, generation: Long, operationId: String, reason: String): SessionOrganism
}

fun interface SessionOrganismStoreFactory {
    fun create(knownSecrets: () -> Set<String>): SessionOrganismStore
}
