package io.aequicor.magicpaper.data.coding

import io.aequicor.magicpaper.data.storage.KeyValueStore
import io.aequicor.magicpaper.domain.CodingMessage
import io.aequicor.magicpaper.domain.CodingProject
import io.aequicor.magicpaper.domain.CodingProjectRepository
import io.aequicor.magicpaper.domain.CodingSession
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Хранилище проектов, их кодинг-сессий и журналов поверх KeyValueStore
 * (та же схема, что у чатов).
 *
 * Миграция: у проекта до введения сессий был один общий журнал
 * («coding-log:<projectId>») и одна сессия пи на проекте (project.piSessionId).
 * При первом обращении к сессиям такого проекта создаётся «основная» сессия:
 * старый журнал переезжает под её ключ, а piSessionId переносится в сессию.
 */
class JsonCodingProjectRepository(
    private val store: KeyValueStore,
    private val json: Json,
) : CodingProjectRepository {

    private val projectsSerializer = ListSerializer(CodingProject.serializer())
    private val sessionsSerializer = ListSerializer(CodingSession.serializer())
    private val messagesSerializer = ListSerializer(CodingMessage.serializer())

    // ---- Проекты ----------------------------------------------------------

    override suspend fun all(): List<CodingProject> {
        val raw = store.read(KEY_PROJECTS) ?: return emptyList()
        return runCatching { json.decodeFromString(projectsSerializer, raw) }
            .getOrDefault(emptyList())
            .sortedByDescending { it.createdAt }
    }

    override suspend fun save(project: CodingProject) {
        val projects = all().filterNot { it.id == project.id } + project
        store.write(KEY_PROJECTS, json.encodeToString(projectsSerializer, projects))
    }

    override suspend fun delete(id: String) {
        val projects = all().filterNot { it.id == id }
        store.write(KEY_PROJECTS, json.encodeToString(projectsSerializer, projects))
        allSessions().filter { it.projectId == id }.forEach {
            store.delete(logKey(id, it.id))
        }
        saveSessions(allSessions().filterNot { it.projectId == id })
        // Журнал легаси-проекта, если миграция ещё не успела произойти.
        store.delete(legacyLogKey(id))
    }

    // ---- Сессии -----------------------------------------------------------

    override suspend fun sessions(projectId: String): List<CodingSession> {
        val existing = allSessions().filter { it.projectId == projectId }.sortedBy { it.createdAt }
        if (existing.isNotEmpty()) return existing
        val project = all().firstOrNull { it.id == projectId } ?: return emptyList()
        val main = CodingSession(
            id = "main-${project.id}",
            projectId = project.id,
            name = "Основная",
            createdAt = project.createdAt,
            piSessionId = project.piSessionId,
        )
        saveSessions(allSessions() + main)
        migrateLegacyLog(project.id, main.id)
        return listOf(main)
    }

    override suspend fun saveSession(session: CodingSession) {
        saveSessions(allSessions().filterNot { it.id == session.id } + session)
    }

    override suspend fun deleteSession(projectId: String, sessionId: String) {
        saveSessions(allSessions().filterNot { it.id == sessionId })
        store.delete(logKey(projectId, sessionId))
    }

    // ---- Журналы сессий -----------------------------------------------------

    override suspend fun messages(projectId: String, sessionId: String): List<CodingMessage> {
        val raw = store.read(logKey(projectId, sessionId)) ?: return emptyList()
        return runCatching { json.decodeFromString(messagesSerializer, raw) }.getOrDefault(emptyList())
    }

    override suspend fun saveMessages(projectId: String, sessionId: String, messages: List<CodingMessage>) {
        store.write(logKey(projectId, sessionId), json.encodeToString(messagesSerializer, messages))
    }

    override suspend fun wipe() {
        allSessions().forEach { store.delete(logKey(it.projectId, it.id)) }
        all().forEach { store.delete(legacyLogKey(it.id)) } // легаси-журналы немигрированных проектов
        store.delete(KEY_SESSIONS)
        store.delete(KEY_PROJECTS)
    }

    // ---- Миграция -----------------------------------------------------------

    /** Перенос старого журнала проекта под ключ основной сессии. */
    private fun migrateLegacyLog(projectId: String, sessionId: String) {
        val legacyKey = legacyLogKey(projectId)
        val raw = store.read(legacyKey) ?: return
        val key = logKey(projectId, sessionId)
        if (store.read(key) == null) store.write(key, raw)
        store.delete(legacyKey)
    }

    private suspend fun allSessions(): List<CodingSession> {
        val raw = store.read(KEY_SESSIONS) ?: return emptyList()
        return runCatching { json.decodeFromString(sessionsSerializer, raw) }.getOrDefault(emptyList())
    }

    private suspend fun saveSessions(sessions: List<CodingSession>) {
        store.write(KEY_SESSIONS, json.encodeToString(sessionsSerializer, sessions))
    }

    private fun logKey(projectId: String, sessionId: String) = "coding-log:$projectId:$sessionId"

    private fun legacyLogKey(projectId: String) = "coding-log:$projectId"

    private companion object {
        const val KEY_PROJECTS = "coding-projects"
        const val KEY_SESSIONS = "coding-sessions"
    }
}
