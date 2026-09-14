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
}
