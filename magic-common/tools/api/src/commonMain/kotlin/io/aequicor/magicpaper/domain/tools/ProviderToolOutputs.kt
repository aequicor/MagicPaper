package io.aequicor.magicpaper.domain.tools

import kotlinx.serialization.Serializable

/** Private response artifact: the same content is later handed to the owning chat history. */
@Serializable data class ProviderToolOutput(val ref: ProviderToolMachine.OutputRef, val text: String)

/** Immutable write by exact run/attempt. Payloads never enter the execution journal or normal logs. */
interface ProviderToolOutputs {
    suspend fun get(runId: String, attemptId: String): ProviderToolOutput?
    suspend fun save(output: ProviderToolOutput)
}
