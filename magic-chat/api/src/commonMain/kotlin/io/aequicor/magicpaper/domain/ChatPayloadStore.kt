package io.aequicor.magicpaper.domain

import kotlinx.serialization.Serializable

/** Reference to an immutable serialized Intent/Fact, outside the journal's diagnostic envelope. */
@Serializable data class ChatInputRef(val notebookId: String, val inputId: String, val kind: String, val digest: String)

/**
 * Stores transcript/input data privately. Journal envelopes contain this exact reference; replay
 * verifies owner, input identity, kind and digest before feeding the recovered Input to ChatMachine.
 * Saving the same identity with different bytes is a failure. Credentials are never part of Input.
 */
interface ChatPayloadStore {
    suspend fun save(notebookId: String, inputId: String, input: ChatMachine.Input): ChatInputRef
    suspend fun read(ref: ChatInputRef): ChatMachine.Input
    /** Called after journal tombstone/reset durability and after all writers have joined. */
    suspend fun removeNotebook(notebookId: String)
    suspend fun clear()
}
