package io.aequicor.magicpaper.domain

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import io.aequicor.magicpaper.util.Id

interface CodingProjectRepository {
    suspend fun all(): List<CodingProject>
    suspend fun save(project: CodingProject)
    suspend fun delete(id: String)

    /** Сессии проекта в порядке создания (первая — «основная»). */
    suspend fun sessions(projectId: String): List<CodingSession>
    suspend fun saveSession(session: CodingSession)
    /** Atomic read/modify/write in persistent implementations. */
    suspend fun updateSession(projectId: String, sessionId: String, update: (CodingSession) -> CodingSession): CodingSession {
        val latest = sessions(projectId).firstOrNull { it.id == sessionId } ?: error("Сессия удалена")
        return update(latest).also { saveSession(it) }
    }
    suspend fun deleteSession(projectId: String, sessionId: String)

    suspend fun messages(projectId: String, sessionId: String): List<CodingMessage>
    suspend fun saveMessages(projectId: String, sessionId: String, messages: List<CodingMessage>)
    suspend fun orchestration(sessionId: String): OrchestrationState? = null
    suspend fun saveOrchestration(state: OrchestrationState) { error("Хранилище оркестратора недоступно") }
    suspend fun wipe()
}