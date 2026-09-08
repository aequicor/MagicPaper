package io.aequicor.magicpaper.data.coding

import io.aequicor.magicpaper.domain.*
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** JSON parsing and synchronous file access must not inherit a caller's UI dispatcher. */
class BackgroundCodingProjectRepository(
    private val delegate: CodingProjectRepository,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val historyCacheSize: Int = 8,
) : CodingProjectRepository {
    // Serialize read/modify/write transactions across concurrent sessions and migrations.
    private val lock = Mutex()
    private data class HistoryKey(val projectId: String, val sessionId: String)
    // All application history writes pass through this repository. Reuse saved lists
    // across planning/status refreshes instead of decoding and comparing megabytes again.
    private val histories = LinkedHashMap<HistoryKey, List<CodingMessage>>()
    private fun rememberHistory(key: HistoryKey, messages: List<CodingMessage>) {
        histories.remove(key)
        // Do not cache absent logs: a subsequent sessions() call can migrate a legacy log.
        if (historyCacheSize <= 0 || messages.isEmpty()) return
        histories[key] = messages
        while (histories.size > historyCacheSize) histories.remove(histories.keys.first())
    }
    private suspend fun <T> access(block: suspend () -> T): T = withContext(dispatcher) { lock.withLock { block() } }
    override suspend fun all() = access { delegate.all() }
    override suspend fun save(project: CodingProject) = access { delegate.save(project) }
    override suspend fun delete(id: String) = access {
        histories.keys.removeAll { it.projectId == id }
        delegate.delete(id)
    }
    override suspend fun sessions(projectId: String) = access { delegate.sessions(projectId) }
    override suspend fun saveSession(session: CodingSession) = access { delegate.saveSession(session) }
    override suspend fun deleteSession(projectId: String, sessionId: String) = access {
        histories.remove(HistoryKey(projectId, sessionId))
        delegate.deleteSession(projectId, sessionId)
    }
    override suspend fun messages(projectId: String, sessionId: String) = access {
        val key = HistoryKey(projectId, sessionId)
        val messages = histories[key] ?: delegate.messages(projectId, sessionId)
        rememberHistory(key, messages)
        messages
    }
    override suspend fun saveMessages(projectId: String, sessionId: String, messages: List<CodingMessage>) = access {
        val key = HistoryKey(projectId, sessionId)
        histories.remove(key) // A failed write can have committed before reporting its error.
        delegate.saveMessages(projectId, sessionId, messages)
        rememberHistory(key, messages)
    }
    override suspend fun orchestration(sessionId: String) = access { delegate.orchestration(sessionId) }
    override suspend fun saveOrchestration(state: OrchestrationState) = access { delegate.saveOrchestration(state) }
    override suspend fun wipe() = access { histories.clear(); delegate.wipe() }
}
