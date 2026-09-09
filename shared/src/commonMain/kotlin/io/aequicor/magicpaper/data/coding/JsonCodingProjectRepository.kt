package io.aequicor.magicpaper.data.coding

import io.aequicor.magicpaper.data.storage.KeyValueStore
import io.aequicor.magicpaper.domain.CodingMessage
import io.aequicor.magicpaper.domain.CodingProject
import io.aequicor.magicpaper.domain.CodingProjectRepository
import io.aequicor.magicpaper.domain.OrchestrationState
import io.aequicor.magicpaper.domain.CodingSession
import io.aequicor.magicpaper.domain.interactionMode
import io.aequicor.magicpaper.domain.changeInteractionMode
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
    private val initialContext: suspend (CodingSession, CodingProject) -> String? = { _, _ -> null },
    private val migrateEngine: suspend (CodingSession, CodingProject?) -> io.aequicor.magicpaper.domain.CodingEngine = { _, _ -> io.aequicor.magicpaper.domain.CodingEngine.PI },
) : CodingProjectRepository {

    private val sessionWriteLock = Mutex()
    private val projectsSerializer = ListSerializer(CodingProject.serializer())
    private val sessionsSerializer = ListSerializer(CodingSession.serializer())
    private val messagesSerializer = ListSerializer(CodingMessage.serializer())

    override suspend fun orchestration(sessionId: String): OrchestrationState? {
        val key = "coding-orchestration-$sessionId"
        val raw = store.read(key) ?: store.read("$key-backup") ?: return null
        return runCatching { json.decodeFromString<OrchestrationState>(raw) }.getOrElse {
            store.read("$key-backup")?.let { json.decodeFromString<OrchestrationState>(it) }
                ?: error("Повреждено состояние оркестратора; требуется восстановление")
        }
    }

    override suspend fun saveOrchestration(state: OrchestrationState) {
        val key = "coding-orchestration-${state.sessionId}"
        store.read(key)?.let { previous ->
            if (runCatching { json.decodeFromString<OrchestrationState>(previous) }.isSuccess)
                store.write("$key-backup", previous)
        }
        store.write(key, json.encodeToString(OrchestrationState.serializer(), state))
    }

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
            store.delete("coding-orchestration-${it.id}")
            store.delete("coding-orchestration-${it.id}-backup")
        }
        saveSessions(allSessions().filterNot { it.projectId == id })
        // Журнал легаси-проекта, если миграция ещё не успела произойти.
        store.delete(legacyLogKey(id))
        store.delete(clearedKey(id))
    }

    // ---- Сессии -----------------------------------------------------------

    override suspend fun sessions(projectId: String): List<CodingSession> {
        val existing = allSessions().filter { it.projectId == projectId }.sortedByDescending { it.createdAt }
        if (existing.isNotEmpty()) return existing
        if (store.read(clearedKey(projectId)) != null) return emptyList()
        val project = all().firstOrNull { it.id == projectId } ?: return emptyList()
        val main = CodingSession(
            id = "main-${project.id}",
            projectId = project.id,
            name = "Основная",
            createdAt = project.createdAt,
            piSessionId = project.piSessionId,
        )
        val migrated = main.copy(engine = migrateEngine(main, project))
        saveSessions(allSessions() + migrated)
        migrateLegacyLog(project.id, main.id)
        if (messages(project.id, main.id).isEmpty()) initializeContext(migrated, project)
        return listOf(migrated)
    }

    override suspend fun saveSession(session: CodingSession) = sessionWriteLock.withLock { saveSessionUnlocked(session) }

    override suspend fun updateSession(projectId: String, sessionId: String, update: (CodingSession) -> CodingSession): CodingSession = sessionWriteLock.withLock {
        val latest = allSessions().firstOrNull { it.id == sessionId && it.projectId == projectId } ?: error("Сессия удалена")
        val updated = update(latest)
        require(updated.id == latest.id && updated.projectId == latest.projectId) { "Идентичность сессии изменить нельзя" }
        val saved = if (latest.interactionMode != updated.interactionMode) {
            val transition = latest.changeInteractionMode(updated.interactionMode)
            updated.copy(piSessionId = transition.piSessionId, pendingRun = transition.pendingRun, needsHistorySeed = true)
        } else updated
        saveSessionUnlocked(saved, allowModeChange = true)
        saved
    }

    private suspend fun saveSessionUnlocked(session: CodingSession, allowModeChange: Boolean = false) {
        require(!(session.planningMode && session.researchMode)) { "Исследование и планирование несовместимы" }
        require(!session.researchMode || session.stageId == null) { "Исполнитель не может быть исследователем" }
        val current = allSessions()
        val previous = current.firstOrNull { it.id == session.id }
        require(allowModeChange || previous == null || previous.interactionMode == session.interactionMode) { "Режим сессии изменился; обновите актуальную запись" }
        require(previous?.engine == null || session.engine == null || session.engine == previous.engine) { "Движок существующей сессии изменить нельзя" }
        val saved = if (session.engine != null) session else session.copy(engine = previous?.engine ?: migrateEngine(session, all().firstOrNull { it.id == session.projectId }))
        if (previous == null) all().firstOrNull { it.id == saved.projectId }?.let { initializeContext(saved, it) }
        saveSessions(current.filterNot { it.id == session.id } + saved)
    }

    private suspend fun initializeContext(session: CodingSession, project: CodingProject) {
        if (messages(project.id, session.id).isNotEmpty()) return
        val text = initialContext(session, project) ?: return
        saveMessages(project.id, session.id, listOf(CodingMessage(
            id = "${session.id}-system-context", role = io.aequicor.magicpaper.domain.CodingRole.AGENT,
            text = text, createdAt = session.createdAt, systemContext = true,
        )))
    }

    override suspend fun deleteSession(projectId: String, sessionId: String) {
        // An explicitly emptied project must not be mistaken for an unmigrated legacy project.
        store.write(clearedKey(projectId), "true")
        saveSessions(allSessions().filterNot { it.id == sessionId })
        store.delete(logKey(projectId, sessionId))
        store.delete("coding-orchestration-$sessionId")
        store.delete("coding-orchestration-$sessionId-backup")
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
        store.keys("coding-orchestration-").forEach { store.delete(it) }
        allSessions().forEach { store.delete(logKey(it.projectId, it.id)) }
        all().forEach { store.delete(legacyLogKey(it.id)); store.delete(clearedKey(it.id)) } // легаси-журналы немигрированных проектов
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
        val sessions = runCatching { json.decodeFromString(sessionsSerializer, raw) }.getOrDefault(emptyList())
        if (sessions.none { it.engine == null }) return sessions
        val projects = all().associateBy { it.id }
        val migrated = sessions.map { if (it.engine != null) it else it.copy(engine = migrateEngine(it, projects[it.projectId])) }
        saveSessions(migrated)
        return migrated
    }

    private suspend fun saveSessions(sessions: List<CodingSession>) {
        store.write(KEY_SESSIONS, json.encodeToString(sessionsSerializer, sessions))
    }

    private fun logKey(projectId: String, sessionId: String) = "coding-log:$projectId:$sessionId"

    private fun clearedKey(projectId: String) = "coding-sessions-cleared:$projectId"

    private fun legacyLogKey(projectId: String) = "coding-log:$projectId"

    private companion object {
        const val KEY_PROJECTS = "coding-projects"
        const val KEY_SESSIONS = "coding-sessions"
    }
}
