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
    /** Returns the committed list after applying explicit history removals. */
    suspend fun saveMessages(projectId: String, sessionId: String, messages: List<CodingMessage>): List<CodingMessage>
    /** Compare the displayed history and persist removal identities against later projections. */
    suspend fun replaceHistory(projectId: String, sessionId: String, expected: List<CodingMessage>, messages: List<CodingMessage>) {
        check(messages(projectId, sessionId) == expected) { "История изменилась. Повторите действие." }
        saveMessages(projectId, sessionId, messages)
    }
    suspend fun orchestration(sessionId: String): OrchestrationState? = null
    suspend fun saveOrchestration(state: OrchestrationState) { error("Хранилище оркестратора недоступно") }
    suspend fun wipe()
}
