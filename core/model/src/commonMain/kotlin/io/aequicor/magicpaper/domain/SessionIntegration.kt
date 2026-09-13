package io.aequicor.magicpaper.domain

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable

@Serializable enum class SessionIntegrationPhase { INTENT, PREPARING, MERGING, CONFLICT, VERIFYING, VERIFIED, BLOCKED, UNKNOWN }

/** Immutable application-authorized input; command arguments are passed to the existing sandbox. */
@Serializable data class SessionIntegrationRequest(
    val id: String, val organismId: String, val actorSessionId: String, val generation: Long,
    val resultIds: List<String>, val checks: List<List<String>>, val sourcePath: String, val sourceSnapshot: String,
)

@Serializable data class SessionIntegrationCheck(
    val command: List<String>, val exitCode: Int?, val output: String, val blockedReason: String? = null,
)

@Serializable data class SessionIntegration(
    val request: SessionIntegrationRequest,
    val phase: SessionIntegrationPhase = SessionIntegrationPhase.INTENT,
    val workspace: PlanWorkspace? = null,
    val mergedResultIds: List<String> = emptyList(),
    val checkResults: List<SessionIntegrationCheck> = emptyList(),
    val acceptance: AcceptanceRecord? = null,
    val commitSha: String = "", val snapshot: String = "", val error: String = "",
)

data class SessionIntegrationInput(val result: SessionResult, val workspace: SessionCodingWorkspace)



/** Git, native checks and aggregate checkpoints are separate durability domains. */
