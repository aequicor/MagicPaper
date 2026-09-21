package io.aequicor.magicpaper.domain

/** Settings/profile transfer enters the chat owner through semantic commands, never snapshot writes. */
interface ChatHistoryCommands {
    suspend fun importNotebooks(sessions: List<ChatSession>)
    suspend fun unlinkProfile(profileId: String)
    /** Cancels and joins accepted effects/commits before clearing this owner's journal generation. */
    suspend fun wipeHistory()
}
