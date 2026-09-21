package io.aequicor.magicpaper.domain

/** Read projection of the chat owner's journal; application consumers cannot overwrite its state. */
interface ChatRepository {
    suspend fun sessions(): List<ChatSession>
    suspend fun session(id: String): ChatSession?
}

/** Legacy import and rebuildable cache. Only the chat journal owner writes this boundary. */
interface ChatCheckpointStore : ChatRepository {
    suspend fun legacySessions(excludingIds: Set<String>): List<ChatSession>
    suspend fun save(session: ChatSession)
    suspend fun delete(id: String)
    suspend fun wipe()
}
