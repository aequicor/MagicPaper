package io.aequicor.magicpaper.data.coding

import io.aequicor.magicpaper.data.storage.KeyValueStore
import io.aequicor.magicpaper.domain.SessionOrganismMachine
import io.aequicor.magicpaper.domain.tools.toolArgumentsFingerprint
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable internal data class OrganismInputRef(val organismId: String, val inputId: String, val digest: String, val kind: String)

/** Private redacted command data is separate from the diagnostic journal's proof envelope. */
internal class OrganismInputStore(private val storage: KeyValueStore, private val json: Json) {
    @Serializable private data class Stored(val organismId: String, val input: SessionOrganismMachine.Input)
    suspend fun save(id: String, input: SessionOrganismMachine.Input): OrganismInputRef {
        val element = json.encodeToJsonElement(Stored.serializer(), Stored(id, input)).jsonObject
        val ref = OrganismInputRef(id, input.stamp.id, toolArgumentsFingerprint(element), element.getValue("input").jsonObject.getValue("type").jsonPrimitive.content)
        val key = key(ref)
        val old = storage.read(key)
        if (old != null) { check(read(ref) == input) { "Идентификатор ввода организма занят" }; return ref }
        var failure: Exception? = null
        try { storage.write(key, element.toString()) } catch (error: Exception) { failure = error }
        try { check(read(ref) == input) { "Сохранён другой ввод организма" } }
        catch (read: Exception) { failure?.let { it.addSuppressed(read); throw it }; throw read }
        if (failure is CancellationException) throw failure
        return ref
    }
    suspend fun read(ref: OrganismInputRef): SessionOrganismMachine.Input {
        val element = json.parseToJsonElement(checkNotNull(storage.read(key(ref))) { "Ввод организма отсутствует" }).jsonObject
        check(toolArgumentsFingerprint(element) == ref.digest && element.getValue("input").jsonObject.getValue("type").jsonPrimitive.content == ref.kind) { "Ввод организма изменился" }
        val saved = json.decodeFromJsonElement(Stored.serializer(), element)
        check(saved.organismId == ref.organismId && saved.input.stamp.id == ref.inputId) { "Ввод принадлежит другому организму" }
        return saved.input
    }
    fun clear() { storage.keys(PREFIX).forEach(storage::delete) }
    private fun key(ref: OrganismInputRef) = "$PREFIX${encodedOrganismId(ref.organismId)}:${encodedOrganismId(ref.inputId)}"
    private companion object { const val PREFIX = "organism-input:" }
}
internal fun encodedOrganismId(id: String) = id.encodeToByteArray().joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
