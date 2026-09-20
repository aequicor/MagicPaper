package io.aequicor.magicpaper.domain

import io.aequicor.magicpaper.data.storage.KeyValueStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

/**
 * Tracks the last read agent message per session so the sidebar can show unread indicators.
 *
 * A session is unread when it has agent messages newer than the last one the user read.
 * The watermark advances only when the transcript displays the latest reply.
 *
 * Persistence survives application restarts; the backing store is the same [KeyValueStore]
 * shared with drafts and settings.
 */
class UnreadTracker(private val storage: KeyValueStore, private val json: Json = Json,
    private val dispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.Default,
    private val onFailure: (Exception) -> Unit = { error ->
        io.aequicor.magicpaper.logging.AppLog.error("coding", "read-marker.load.failed", error)
    },
) {
    private val key = "coding-unread-markers"
    private val mutex = Mutex()
    private var loadFailure: Exception? = null
    private val _markers = MutableStateFlow(load())
    val markers: StateFlow<Map<String, String>> = _markers.asStateFlow()

    private fun load(): Map<String, String> =
        try { storage.read(key)?.let { json.decodeFromString<Map<String, String>>(it) }.orEmpty() }
        catch (error: kotlinx.coroutines.CancellationException) { throw error }
        catch (error: Exception) { loadFailure = error; onFailure(error); emptyMap() }

    private fun persist(map: Map<String, String>) {
        loadFailure?.let { throw it }
        storage.write(key, json.encodeToString(map))
    }

    /** The last agent message ID the user has read in this session, or null if never read. */
    fun lastRead(sessionId: String): String? = _markers.value[sessionId]

    /** Mark the exact reply displayed by the transcript. Publish only after persistence. */
    suspend fun markRead(sessionId: String, messageId: String) = mutex.withLock {
        if (_markers.value[sessionId] == messageId) return@withLock
        val next = _markers.value + (sessionId to messageId)
        withContext(dispatcher) { persist(next) }
        _markers.value = next
    }

    /** Remove the unread marker for a session. Called when the session is deleted. */
    suspend fun forget(sessionId: String) = mutex.withLock {
        val next = _markers.value - sessionId
        if (next.size != _markers.value.size) {
            withContext(dispatcher) { persist(next) }
            _markers.value = next
        }
    }

    /**
     * Returns true when the session has an agent reply newer than the last read message.
     * Selection alone does not establish that the result was displayed.
     */
    fun hasUnread(sessionId: String, messages: List<CodingMessage>): Boolean {
        val lastAgent = messages.lastOrNull { it.role == CodingRole.AGENT && !it.systemContext && !it.systemNotice } ?: return false
        val read = _markers.value[sessionId] ?: return lastAgent.id.isNotBlank()
        return lastAgent.id != read
    }
}
