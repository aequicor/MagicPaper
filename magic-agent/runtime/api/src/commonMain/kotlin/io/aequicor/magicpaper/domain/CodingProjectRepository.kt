package io.aequicor.magicpaper.domain

/** Read-only project/session/history projection. Commands belong to the journal owner. */
interface CodingProjectRepository {
    suspend fun all(): List<CodingProject>
    /** Сессии проекта в порядке создания (первая — «основная»). */
    suspend fun sessions(projectId: String): List<CodingSession>
    suspend fun messages(projectId: String, sessionId: String): List<CodingMessage>
    suspend fun orchestration(sessionId: String): OrchestrationState? = null
}
