package io.aequicor.magicpaper.domain

import io.aequicor.magicpaper.domain.tools.ToolRole
import kotlinx.serialization.Serializable

/** The proof concerns one recorded native attempt; runtime session history stays with its owner. */
@Serializable data class OrganismRetryRequest(val projectId: String, val sessionId: String,
    val binding: SessionLegacyAttempt, val expectedNodeVersion: Long, val quarantines: List<SessionAuditEvent>)
@Serializable data class PlanRetryRecoveryProof(val quarantineOperationIds: Set<String>, val evidence: List<String>)
@Serializable data class SessionQuarantineResolution(val proven: Boolean, val userConfirmed: Boolean,
    val quarantineOperationIds: Set<String>, val evidence: List<String>)

/** Only host admission data crosses into the organism; this value contains no prompt or process handle. */
@Serializable data class OrganismAuxiliaryAdmission(val projectId: String, val organismId: String,
    val ownerSessionId: String, val sessionId: String, val runtimeGeneration: Long,
    val runId: String?, val requestId: String, val mode: CodingInteractionMode,
    val role: ToolRole, val planId: String?, val auxiliaryExecution: Boolean)

@Serializable data class OrganismRevision(val stream: String, val seq: Long, val resetEpoch: Long, val inputId: String)
data class SessionOrganismProjection(val organism: SessionOrganism, val revision: OrganismRevision)

class SessionQuarantineBlocked(val sessionId: String, message: String) : IllegalStateException(message)
