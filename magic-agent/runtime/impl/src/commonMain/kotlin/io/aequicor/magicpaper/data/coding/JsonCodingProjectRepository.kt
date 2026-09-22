package io.aequicor.magicpaper.data.coding

import io.aequicor.magicpaper.data.storage.KeyValueStore
import io.aequicor.magicpaper.domain.CodingMessage
import io.aequicor.magicpaper.domain.CodingProject
import io.aequicor.magicpaper.domain.CodingCheckpointStore
import io.aequicor.magicpaper.domain.CodingMachine
import io.aequicor.magicpaper.domain.OrchestrationState
import io.aequicor.magicpaper.domain.CodingSession
import io.aequicor.magicpaper.domain.interactionMode
import io.aequicor.magicpaper.domain.changeInteractionMode
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.Serializable
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
 *
 * Внутренний кэш: allSessions() и all() кэшируются в памяти и инвалидируются
 * при любой записи. Это устраняет повторный парсинг JSON-файлов при пакетных
 * чтениях (bootstrap, changes-коллектор, восстановление прогонов).
 */
class JsonCodingProjectRepository(
    private val store: KeyValueStore,
    private val json: Json,
    private val initialContext: suspend (CodingSession, CodingProject) -> String? = { _, _ -> null },
    private val migrateEngine: suspend (CodingSession, CodingProject?) -> io.aequicor.magicpaper.domain.CodingEngine = { _, _ -> io.aequicor.magicpaper.domain.CodingEngine.PI },
) : CodingCheckpointStore {

    private val sessionWriteLock = Mutex()
    private val projectsSerializer = ListSerializer(CodingProject.serializer())
    private val sessionsSerializer = ListSerializer(CodingSession.serializer())
    private val messagesSerializer = ListSerializer(CodingMessage.serializer())

    // In-memory caches invalidated on write. Eliminate redundant JSON parsing
    // when multiple readers (bootstrap, changes collector, restoreCodingRuns)
    // call allSessions()/all() in quick succession.
    private var _projectsCache: List<CodingProject>? = null
    private var _sessionsCache: List<CodingSession>? = null

    override suspend fun orchestration(sessionId: String): OrchestrationState? {
        val key = "coding-orchestration-$sessionId"
        val raw = store.read(key) ?: store.read("$key-backup") ?: return null
        return runCatching { json.decodeFromString<OrchestrationState>(raw) }.getOrElse {
            store.read("$key-backup")?.let { json.decodeFromString<OrchestrationState>(it) }
                ?: error("Повреждено состояние оркестратора; требуется восстановление")
        }
    }

    suspend fun saveOrchestration(state: OrchestrationState) {
        val key = "coding-orchestration-${state.sessionId}"
        store.read(key)?.let { previous ->
            if (runCatching { json.decodeFromString<OrchestrationState>(previous) }.isSuccess)
                store.write("$key-backup", previous)
        }
        store.write(key, json.encodeToString(OrchestrationState.serializer(), state))
    }

    // ---- Проекты ----------------------------------------------------------

    override suspend fun all(): List<CodingProject> {
        _projectsCache?.let { return it }
        val raw = store.read(KEY_PROJECTS) ?: return emptyList()
        val result = json.decodeFromString(projectsSerializer, raw).sortedByDescending { it.createdAt }
        _projectsCache = result
        return result
    }

    suspend fun save(project: CodingProject) {
        val projects = all().filterNot { it.id == project.id } + project
        store.write(KEY_PROJECTS, json.encodeToString(projectsSerializer, projects))
        _projectsCache = null
    }

    suspend fun delete(id: String) {
        val allSess = allSessions()
        val projects = all().filterNot { it.id == id }
        store.write(KEY_PROJECTS, json.encodeToString(projectsSerializer, projects))
        _projectsCache = null
        allSess.filter { it.projectId == id }.forEach {
            store.delete(logKey(id, it.id))
            store.delete("coding-orchestration-${it.id}")
            store.delete("coding-orchestration-${it.id}-backup")
        }
        saveSessions(allSess.filterNot { it.projectId == id })
        _lastHistories -= id; _lastRemovedMessages -= id
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

    suspend fun saveSession(session: CodingSession) = sessionWriteLock.withLock { saveSessionUnlocked(session) }

    suspend fun updateSession(projectId: String, sessionId: String, update: (CodingSession) -> CodingSession): CodingSession = sessionWriteLock.withLock {
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
        require(previous == null || session.runtimeGeneration >= previous.runtimeGeneration) { "Запуск сессии уже заменён" }
        require(previous?.organismId == null || (session.organismId == previous.organismId && session.parentSessionId == previous.parentSessionId)) {
            "Происхождение сессии изменилось; требуется проверенное переназначение"
        }
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

    suspend fun deleteSession(projectId: String, sessionId: String) {
        // An explicitly emptied project must not be mistaken for an unmigrated legacy project.
        store.write(clearedKey(projectId), "true")
        saveSessions(allSessions().filterNot { it.id == sessionId })
        store.delete(logKey(projectId, sessionId))
        store.delete("coding-orchestration-$sessionId")
        store.delete("coding-orchestration-$sessionId-backup")
    }

    // ---- Журналы сессий -----------------------------------------------------

    override suspend fun messages(projectId: String, sessionId: String): List<CodingMessage> {
        return history(projectId, sessionId).messages
    }

    suspend fun saveMessages(projectId: String, sessionId: String, messages: List<CodingMessage>): List<CodingMessage> {
        val previous = history(projectId, sessionId)
        val retained = if (previous.removedIds.isEmpty()) messages else messages.filterNot { it.id in previous.removedIds }
        // Projections may have captured their list before the user's rewrite. Once a
        // log has removals, ordinary saves update/append; only replaceHistory removes.
        val next = if (previous.removedIds.isEmpty()) retained else {
            val updates = retained.associateBy { it.id }
            val existing = previous.messages.map { it.id }.toSet()
            previous.messages.map { updates[it.id] ?: it } + retained.filterNot { it.id in existing }
        }
        writeHistory(projectId, sessionId, previous.copy(messages = next))
        return next
    }

    suspend fun replaceHistory(projectId: String, sessionId: String, expected: List<CodingMessage>, messages: List<CodingMessage>) {
        val previous = history(projectId, sessionId)
        check(previous.messages == expected) { "История изменилась. Повторите действие." }
        val retained = messages.map { it.id }.toSet()
        writeHistory(projectId, sessionId, History(messages, previous.removedIds + expected.filterNot { it.id in retained }.map { it.id }))
    }

    @Serializable
    private data class History(val messages: List<CodingMessage> = emptyList(), val removedIds: Set<String> = emptySet())

    private fun history(projectId: String, sessionId: String): History {
        val raw = store.read(logKey(projectId, sessionId)) ?: return History()
        // Existing logs remain readable without changing their storage keys.
        return if (raw.trimStart().startsWith("[")) History(json.decodeFromString(messagesSerializer, raw))
        else json.decodeFromString(History.serializer(), raw)
    }

    private fun writeHistory(projectId: String, sessionId: String, history: History) {
        store.write(logKey(projectId, sessionId), if (history.removedIds.isEmpty())
            json.encodeToString(messagesSerializer, history.messages) else json.encodeToString(History.serializer(), history))
    }

    override suspend fun wipe() {
        store.keys("coding-orchestration-").forEach { store.delete(it) }
        val allSess = allSessions()
        val allProjects = all()
        _sessionsCache = null
        _projectsCache = null
        allSess.forEach { store.delete(logKey(it.projectId, it.id)) }
        allProjects.forEach { store.delete(legacyLogKey(it.id)); store.delete(clearedKey(it.id)) } // легаси-журналы немигрированных проектов
        store.delete(KEY_SESSIONS)
        store.delete(KEY_PROJECTS)
    }

    /** One-time import reads old paths without creating sessions, deleting old logs or asking the runtime for context. */
    override suspend fun legacyProjects(excludingIds: Set<String>): List<CodingMachine.Fact.LegacyImported> {
        val projects = store.read(KEY_PROJECTS)?.let { json.decodeFromString(projectsSerializer, it) }.orEmpty()
        val sessions = store.read(KEY_SESSIONS)?.let { json.decodeFromString(sessionsSerializer, it) }.orEmpty()
        require(projects.distinctBy { it.id }.size == projects.size && sessions.distinctBy { it.id }.size == sessions.size) { "Повторяющиеся сохранённые идентификаторы" }
        require(sessions.all { s -> projects.any { it.id == s.projectId } }) { "Сессия ссылается на отсутствующий проект" }
        return projects.filterNot { it.id in excludingIds }.map { project ->
            val existing = sessions.filter { it.projectId == project.id }
            val legacyMain = existing.isEmpty() && store.read(clearedKey(project.id)) == null
            val imported = (if (legacyMain) listOf(CodingSession("main-${project.id}", project.id, "Основная", project.createdAt,
                piSessionId = project.piSessionId)) else existing).map { session ->
                if (session.engine != null) session else session.copy(engine = migrateEngine(session, project))
            }
            val histories = imported.associate { session ->
                val saved = history(project.id, session.id)
                val old = if (legacyMain && saved.messages.isEmpty()) store.read(legacyLogKey(project.id))?.let {
                    if (it.trimStart().startsWith("[")) History(json.decodeFromString(messagesSerializer, it))
                    else json.decodeFromString(History.serializer(), it)
                } else null
                session.id to (old ?: saved)
            }
            CodingMachine.Fact.LegacyImported(project, imported, histories.mapValues { it.value.messages },
                histories.mapValues { it.value.removedIds }, imported.mapNotNull { orchestration(it.id) })
        }
    }

    // Last histories/removedMessages this instance itself wrote per project, by reference. The
    // reducer's `state.histories + (id to messages)` pattern keeps every untouched session's
    // list at the same object identity, so a `!==` miss reliably means that session actually
    // changed since the last checkpoint of this project — letting checkpoint() below skip
    // rewriting the other ~N-1 sessions' history files on every single dispatch.
    private val _lastHistories = mutableMapOf<String, Map<String, List<CodingMessage>>>()
    private val _lastRemovedMessages = mutableMapOf<String, Map<String, Set<String>>>()

    override suspend fun checkpoint(state: CodingMachine.State) {
        val project = requireNotNull(state.project)
        if (state.deleted) { delete(project.id); return }
        // Write the complete projection without migration callbacks or independent mutation rules.
        // Every dispatch to any project checkpoints here, so reuse the class's own read cache
        // instead of re-reading and re-decoding the whole (every-project, every-session) blob
        // from disk on each call; only the changed project's rows actually move.
        val projects = all().filterNot { it.id == project.id } + project
        val sessions = allSessions().filterNot { it.projectId == project.id } + state.sessions.values
        store.write(KEY_PROJECTS, json.encodeToString(projectsSerializer, projects))
        store.write(KEY_SESSIONS, json.encodeToString(sessionsSerializer, sessions))
        store.write(clearedKey(project.id), "true")
        val previousHistories = _lastHistories[project.id]
        val previousRemoved = _lastRemovedMessages[project.id]
        state.histories.forEach { (id, messages) ->
            val removed = state.removedMessages[id].orEmpty()
            if (previousHistories?.get(id) !== messages || previousRemoved?.get(id) !== state.removedMessages[id])
                writeHistory(project.id, id, History(messages, removed))
        }
        _lastHistories[project.id] = state.histories; _lastRemovedMessages[project.id] = state.removedMessages
        state.removedSessions.forEach { id -> store.delete(logKey(project.id, id)); store.delete("coding-orchestration-$id"); store.delete("coding-orchestration-$id-backup") }
        state.orchestrations.values.forEach { saveOrchestration(it) }
        // Prime the cache with what was just written instead of invalidating it, so the next
        // checkpoint (there is one after every dispatch to any project) also skips the re-read.
        _projectsCache = projects.sortedByDescending { it.createdAt }; _sessionsCache = sessions
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
        _sessionsCache?.let { return it }
        val raw = store.read(KEY_SESSIONS)
        if (raw == null) {
            _sessionsCache = emptyList()
            return emptyList()
        }
        val sessions = json.decodeFromString(sessionsSerializer, raw)
        if (sessions.none { it.engine == null }) {
            _sessionsCache = sessions
            return sessions
        }
        val projects = all().associateBy { it.id }
        val migrated = sessions.map { if (it.engine != null) it else it.copy(engine = migrateEngine(it, projects[it.projectId])) }
        saveSessions(migrated)
        _sessionsCache = migrated
        return migrated
    }

    private suspend fun saveSessions(sessions: List<CodingSession>) {
        store.write(KEY_SESSIONS, json.encodeToString(sessionsSerializer, sessions))
        _sessionsCache = null
    }

    private fun logKey(projectId: String, sessionId: String) = "coding-log:$projectId:$sessionId"

    private fun clearedKey(projectId: String) = "coding-sessions-cleared:$projectId"

    private fun legacyLogKey(projectId: String) = "coding-log:$projectId"

    private companion object {
        const val KEY_PROJECTS = "coding-projects"
        const val KEY_SESSIONS = "coding-sessions"
    }
}
