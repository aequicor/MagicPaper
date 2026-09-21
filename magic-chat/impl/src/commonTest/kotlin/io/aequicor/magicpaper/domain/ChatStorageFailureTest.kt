package io.aequicor.magicpaper.domain

import io.aequicor.magicpaper.data.storage.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.*

class ChatStorageFailureTest {
    @Test fun corruptedHistoryIsReportedWithoutReplacingSavedBytes() = runTest {
        val store = InMemoryKeyValueStore()
        store.write("chats", "[\"chat\"]")
        store.write("chat:chat", "{broken")
        val repository = JsonChatRepository(store, Json)
        assertFailsWith<StorageException> { repository.sessions() }
        assertEquals("{broken", store.read("chat:chat"))
    }
    @Test fun historyCannotChangeItsIdentityByChangingTheEmbeddedSessionId() = runTest {
        val store = InMemoryKeyValueStore()
        val raw = Json.encodeToString(ChatSession.serializer(), ChatSession("different", "Private history", 1, 1))
        store.write("chats", "[\"chat\"]")
        store.write("chat:chat", raw)
        val repository = JsonChatRepository(store, Json)
        assertFailsWith<StorageException> { repository.session("chat") }
        assertFailsWith<StorageException> { repository.legacySessions(emptySet()) }
        assertEquals(raw, store.read("chat:chat"))
        assertNull(store.read("chat:different"))
    }

    @Test fun orphanedLegacySessionCanBeImportedAndExplicitWipeCannotLeaveItToResurrect() = runTest {
        val store = InMemoryKeyValueStore()
        val session = ChatSession("orphan", "Saved before index acknowledgement", 1, 1)
        store.write("chat:orphan", Json.encodeToString(ChatSession.serializer(), session))
        val repository = JsonChatRepository(store, Json)
        assertEquals(listOf(session), repository.legacySessions(emptySet()))
        assertTrue(repository.legacySessions(setOf("orphan")).isEmpty())
        repository.wipe()
        assertNull(store.read("chat:orphan"))
        assertTrue(repository.legacySessions(emptySet()).isEmpty())
    }

}
