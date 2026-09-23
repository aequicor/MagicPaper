package io.aequicor.magicpaper.data.coding

import io.aequicor.magicpaper.data.storage.*

import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.domain.tools.toolArgumentsFingerprint
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/** Private immutable inputs; the shared event journal contains only exact references and outcome metadata. */
class StoredCodingPayloads(private val store: KeyValueStore, private val json: Json,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default) : CodingPayloadStore {
    @Serializable private data class Stored(val projectId: String, val inputId: String, val input: CodingMachine.Input)
    private val lock = Mutex()

    override suspend fun save(projectId: String, inputId: String, input: CodingMachine.Input): CodingInputRef = withContext(dispatcher) {
        lock.withLock {
            require(projectId.isNotBlank() && inputId.isNotBlank())
            val payload = Stored(projectId, inputId, input)
            val element = json.encodeToJsonElement(Stored.serializer(), payload).jsonObject
            val ref = CodingInputRef(projectId, inputId, kind(element), toolArgumentsFingerprint(element))
            val key = key(ref)
            val previous = store.read(key)
            if (previous != null) {
                check(json.encodeToJsonElement(CodingMachine.Input.serializer(), decode(ref, previous)) == element.getValue("input")) { "Идентификатор ввода проекта уже занят" }
            } else {
                var failure: Exception? = null
                try { store.write(key, element.toString()) } catch (error: Exception) { failure = error }
                val stored = store.read(key) ?: throw failure ?: StorageException("coding-input-write", StorageException.Kind.WRITE)
                check(json.encodeToJsonElement(CodingMachine.Input.serializer(), decode(ref, stored)) == element.getValue("input")) { "Сохранён другой ввод проекта" }
                if (failure is CancellationException) throw failure
            }
            ref
        }
    }

    override suspend fun read(ref: CodingInputRef): CodingMachine.Input = withContext(dispatcher) {
        lock.withLock { decode(ref, store.read(key(ref)) ?: throw StorageException("coding-input-read", StorageException.Kind.CORRUPT)) }
    }
    override suspend fun readAll(refs: List<CodingInputRef>): List<CodingMachine.Input> = withContext(dispatcher) {
        lock.withLock {
            val raws = store.readAll(refs.map(::key))
            refs.map { ref -> decode(ref, raws[key(ref)] ?: throw StorageException("coding-input-read", StorageException.Kind.CORRUPT)) }
        }
    }
    private suspend fun decode(ref: CodingInputRef, raw: String): CodingMachine.Input {
        val element = try { json.parseToJsonElement(raw).jsonObject }
        catch (failure: Exception) { throw StorageException("coding-input-read", StorageException.Kind.CORRUPT, cause = failure) }
        check(toolArgumentsFingerprint(element) == ref.digest && kind(element) == ref.kind) { "Содержимое ввода проекта изменилось" }
        val stored = json.decodeFromJsonElement(Stored.serializer(), element)
        check(stored.projectId == ref.projectId && stored.inputId == ref.inputId) { "Ввод принадлежит другому проекту" }
        return stored.input
    }
    override suspend fun clear(): Unit = withContext(dispatcher) { lock.withLock { store.keys(PREFIX).forEach(store::delete) } }
    private fun kind(element: JsonObject) = element.getValue("input").jsonObject.getValue("type").jsonPrimitive.content
    private fun key(ref: CodingInputRef) = "$PREFIX${encoded(ref.projectId)}:${encoded(ref.inputId)}"
    private fun encoded(value: String) = value.encodeToByteArray().joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
    private companion object { const val PREFIX = "coding-input:" }
}
