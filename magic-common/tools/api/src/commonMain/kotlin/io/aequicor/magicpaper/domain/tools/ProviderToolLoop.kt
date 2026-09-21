package io.aequicor.magicpaper.domain.tools

import io.aequicor.magicpaper.domain.LlmMessage
import io.aequicor.magicpaper.domain.LlmProfile

/** One provider request and its scoped tool capability. The host supplies stable run identity. */
interface ProviderToolLoop {
    suspend fun run(runId: String, profile: LlmProfile, messages: List<LlmMessage>, tools: ToolSession,
        maxTurns: Int = 16, maxCalls: Int = 64): String
    /** Read-only handoff of an owner-verified artifact. Does not rebuild prompts or create tool sessions. */
    suspend fun inspect(runId: String): ProviderToolRecovery
    /** Journal replay is inspection only: it never calls the provider or a tool. */
    suspend fun restore(runId: String): ProviderToolMachine.State
}

/** Interrupted requests require a new explicit request; unknown attempts are never retried by inspection. */
sealed interface ProviderToolRecovery {
    data class Completed(val output: ProviderToolOutput) : ProviderToolRecovery
    data object Unknown : ProviderToolRecovery
    data object Interrupted : ProviderToolRecovery
    data object Missing : ProviderToolRecovery
}
