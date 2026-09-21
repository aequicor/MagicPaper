package io.aequicor.magicpaper.data.storage

import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.domain.tools.toolArgumentsFingerprint
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/** Private immutable inputs; the shared event journal contains only exact references and outcome metadata. */
class StoredChatPayloads(private val store: KeyValueStore, private val json: Json,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default) : ChatPayloadStore {
    @Serializable private data class Stored(val notebookId: String, val inputId: String, val input: ChatMachine.Input)
    private val lock = Mutex()

    override suspend fun save(notebookId: String, inputId: String, input: ChatMachine.Input): ChatInputRef = withContext(dispatcher) {
        lock.withLock {
            require(notebookId.isNotBlank() && inputId.isNotBlank())
            val payload = Stored(notebookId, inputId, input)
            val element = json.encodeToJsonElement(Stored.serializer(), payload).jsonObject
            val ref = ChatInputRef(notebookId, inputId, kind(element), toolArgumentsFingerprint(element))
            val key = key(ref)
            val previous = store.read(key)
            if (previous != null) {
                check(json.encodeToJsonElement(ChatMachine.Input.serializer(), decode(ref, previous)) == element.getValue("input")) { "Идентификатор ввода чата уже занят" }
            } else {
                var failure: Exception? = null
                try { store.write(key, element.toString()) } catch (error: Exception) { failure = error }
                val stored = store.read(key) ?: throw failure ?: StorageException("chat-input-write", StorageException.Kind.WRITE)
                check(json.encodeToJsonElement(ChatMachine.Input.serializer(), decode(ref, stored)) == element.getValue("input")) { "Сохранён другой ввод чата" }
                if (failure is CancellationException) throw failure
            }
            ref
        }
    }

    override suspend fun read(ref: ChatInputRef): ChatMachine.Input = withContext(dispatcher) {
        lock.withLock { decode(ref, store.read(key(ref)) ?: throw StorageException("chat-input-read", StorageException.Kind.CORRUPT)) }
    }
    private suspend fun decode(ref: ChatInputRef, raw: String): ChatMachine.Input {
        val element = try { json.parseToJsonElement(raw).jsonObject }
        catch (failure: Exception) { throw StorageException("chat-input-read", StorageException.Kind.CORRUPT, cause = failure) }
        check(toolArgumentsFingerprint(element) == ref.digest && kind(element) == ref.kind) { "Содержимое ввода чата изменилось" }
        val stored = json.decodeFromJsonElement(Stored.serializer(), element)
        check(stored.notebookId == ref.notebookId && stored.inputId == ref.inputId) { "Ввод принадлежит другому чату" }
        return stored.input
    }
    override suspend fun removeNotebook(notebookId: String): Unit = withContext(dispatcher) {
        lock.withLock { store.keys("$PREFIX${encoded(notebookId)}:").forEach(store::delete) }
    }
    override suspend fun clear(): Unit = withContext(dispatcher) { lock.withLock { store.keys(PREFIX).forEach(store::delete) } }
    private fun kind(element: JsonObject) = element.getValue("input").jsonObject.getValue("type").jsonPrimitive.content
    private fun key(ref: ChatInputRef) = "$PREFIX${encoded(ref.notebookId)}:${encoded(ref.inputId)}"
    private fun encoded(value: String) = value.encodeToByteArray().joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
    private companion object { const val PREFIX = "chat-input:" }
}
