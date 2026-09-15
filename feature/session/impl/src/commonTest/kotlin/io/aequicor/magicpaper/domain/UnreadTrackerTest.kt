package io.aequicor.magicpaper.domain

import io.aequicor.magicpaper.data.storage.InMemoryKeyValueStore
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.coroutines.test.runTest
import kotlin.test.assertFailsWith

class UnreadTrackerTest {
    private val json = Json
    private fun tracker() = UnreadTracker(InMemoryKeyValueStore(), json)

    private fun agentMsg(id: String) = CodingMessage(
        id = id, role = CodingRole.AGENT, text = "", createdAt = 0,
    )

    private fun systemMsg(id: String) = CodingMessage(
        id = id, role = CodingRole.AGENT, text = "", createdAt = 0, systemNotice = true,
    )

    @Test
    fun emptySessionIsNotUnread() {
        val t = tracker()
        assertFalse(t.hasUnread("s1", emptyList()))
    }

    @Test
    fun sessionWithOnlySystemMessagesIsNotUnread() {
        val t = tracker()
        assertFalse(t.hasUnread("s1", listOf(systemMsg("sys-1"))))
    }

    @Test
    fun unreadWhenNeverReadAndAgentReplied() {
        val t = tracker()
        assertTrue(t.hasUnread("s1", listOf(agentMsg("a1"))))
    }

    @Test
    fun notUnreadAfterMarkRead() = runTest {
        val t = tracker()
        t.markRead("s1", "a1")
        assertFalse(t.hasUnread("s1", listOf(agentMsg("a1"))))
    }

    @Test
    fun unreadWhenNewAgentMessageAfterMarkRead() = runTest {
        val t = tracker()
        t.markRead("s1", "a1")
        assertTrue(t.hasUnread("s1", listOf(agentMsg("a1"), agentMsg("a2"))))
    }

    @Test
    fun selectionDoesNotMarkAReplyRead() {
        val t = tracker()
        assertTrue(t.hasUnread("s1", listOf(agentMsg("a1"))))
    }

    @Test
    fun forgetClearsMarker() = runTest {
        val t = tracker()
        t.markRead("s1", "a1")
        t.forget("s1")
        assertNull(t.lastRead("s1"))
    }

    @Test
    fun persistenceSurvivesNewInstance() = runTest {
        val store = InMemoryKeyValueStore()
        val t1 = UnreadTracker(store, json)
        t1.markRead("s1", "a1")

        val t2 = UnreadTracker(store, json)
        assertFalse(t2.hasUnread("s1", listOf(agentMsg("a1"))))
    }

    @Test fun failedWriteKeepsTheResultUnread() = runTest {
        val backing = InMemoryKeyValueStore()
        val store = object : io.aequicor.magicpaper.data.storage.KeyValueStore by backing {
            override fun write(key: String, value: String) { error("write failed") }
        }
        val tracker = UnreadTracker(store)
        assertFailsWith<IllegalStateException> { tracker.markRead("s", "a") }
        assertNull(tracker.lastRead("s"))
        assertTrue(UnreadTracker(backing).hasUnread("s", listOf(agentMsg("a"))))
    }
}
