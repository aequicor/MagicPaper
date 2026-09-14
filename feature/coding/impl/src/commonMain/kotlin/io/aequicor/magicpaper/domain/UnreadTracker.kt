package io.aequicor.magicpaper.domain

import io.aequicor.magicpaper.data.storage.KeyValueStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json

/**
 * Tracks the last read agent message per session so the sidebar can show unread indicators.
 *
 * A session is unread when it has agent messages newer than the last one the user read.
 * The "read" watermark is the message ID; it is updated when the user selects the session
 * in the sidebar. Agent replies to sessions the user is not viewing are considered unread
 * until the user navigates to the session.
 *
 * Persistence survives application restarts; the backing store is the same [KeyValueStore]
 * shared with drafts and settings.
 */
class UnreadTracker(private val storage: KeyValueStore, private val json: Json = Json) {
    private val key = "coding-unread-markers"
    private val _markers = MutableStateFlow(load())
    val markers: StateFlow<Map<String, String>> = _markers.asStateFlow()

    private fun load(): Map<String, String> =
        try { storage.read(key)?.let { json.decodeFromString<Map<String, String>>(it) }.orEmpty() }
        catch (_: Exception) { emptyMap() }

    private fun persist(map: Map<String, String>) {
        try { storage.write(key, json.encodeToString(map)) } catch (_: Exception) {}
    }

    /** The last agent message ID the user has read in this session, or null if never read. */
    fun lastRead(sessionId: String): String? = _markers.value[sessionId]

    /** Mark a session as read up to [messageId]. Called when the user selects the session. */
    fun markRead(sessionId: String, messageId: String) {
        val next = _markers.value + (sessionId to messageId)
        _markers.value = next
        persist(next)
    }

    /** Remove the unread marker for a session. Called when the session is deleted. */
    fun forget(sessionId: String) {
        val next = _markers.value - sessionId
        if (next.size != _markers.value.size) {
            _markers.value = next
            persist(next)
        }
    }

    /**
     * Returns true when the session has an agent reply newer than the last read message.
     * The current session (focused in the coding screen) is never unread.
     */
    fun hasUnread(sessionId: String, messages: List<CodingMessage>, currentSessionId: String?): Boolean {
        if (sessionId == currentSessionId) return false
        val lastAgent = messages.lastOrNull { it.role == CodingRole.AGENT && !it.systemContext && !it.systemNotice } ?: return false
        val read = _markers.value[sessionId] ?: return lastAgent.id.isNotBlank()
        return lastAgent.id != read
    }
}
