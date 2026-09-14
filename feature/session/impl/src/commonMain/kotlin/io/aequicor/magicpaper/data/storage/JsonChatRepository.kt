package io.aequicor.magicpaper.data.storage

import io.aequicor.magicpaper.domain.ChatRepository
import io.aequicor.magicpaper.domain.ChatSession
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Реализация хранилища чатов поверх KeyValueStore. */
class JsonChatRepository(
    private val store: KeyValueStore,
    private val json: Json,
) : ChatRepository {

    private val mutex = Mutex()
    private val indexSerializer = ListSerializer(String.serializer())

    override suspend fun sessions(): List<ChatSession> = mutex.withLock {
        val ids = index()
        ids.mapNotNull { id ->
            store.read(chatKey(id))?.let { raw ->
                decodeSession(raw)
            }
        }.sortedByDescending { it.updatedAt }
    }

    override suspend fun session(id: String): ChatSession? = mutex.withLock {
        val raw = store.read(chatKey(id)) ?: return@withLock null
        decodeSession(raw)
    }

    override suspend fun save(session: ChatSession) = mutex.withLock {
        store.write(chatKey(session.id), json.encodeToString(ChatSession.serializer(), session))
        val ids = index().toMutableSet()
        if (ids.add(session.id)) {
            store.write(KEY_INDEX, json.encodeToString(indexSerializer, ids.toList()))
        }
    }

    override suspend fun delete(id: String) = mutex.withLock {
        store.delete(chatKey(id))
        val ids = index().filterNot { it == id }
        store.write(KEY_INDEX, json.encodeToString(indexSerializer, ids))
    }

    override suspend fun wipe() = mutex.withLock {
        index().forEach { store.delete(chatKey(it)) }
        store.delete(KEY_INDEX)
    }

    private fun index(): List<String> {
        val raw = store.read(KEY_INDEX) ?: return emptyList()
        return try { json.decodeFromString(indexSerializer, raw) } catch (_: Exception) { throw StorageException("chat-index", StorageException.Kind.CORRUPT) }
    }

    private fun decodeSession(raw: String): ChatSession = try { json.decodeFromString<ChatSession>(raw) }
        catch (_: Exception) { throw StorageException("chat-read", StorageException.Kind.CORRUPT) }

    private fun chatKey(id: String) = "chat:$id"

    private companion object {
        const val KEY_INDEX = "chats"
    }
}
