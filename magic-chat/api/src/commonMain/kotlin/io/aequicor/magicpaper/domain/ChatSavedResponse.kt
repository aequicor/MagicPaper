package io.aequicor.magicpaper.domain

/** Read-only storage inspection; no request preparation, source reads, model or tool calls. */
sealed interface ChatSavedResponse {
    data class Completed(val request: ChatMachine.RunRef, val proof: ChatMachine.OutputProof,
        val text: String) : ChatSavedResponse
    data object Unknown : ChatSavedResponse
    data object Interrupted : ChatSavedResponse
    data object Missing : ChatSavedResponse
}
