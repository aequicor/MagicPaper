package io.aequicor.magicpaper.navigation

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.runtime.snapshots.SnapshotMutableState
import androidx.compose.runtime.snapshots.SnapshotStateObserver
import io.aequicor.magicpaper.data.storage.StorageException
import io.aequicor.magicpaper.data.storage.logPersistenceFailure
import kotlinx.serialization.json.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay

/** An allowlist for presentation savers. Domain objects and arbitrary platform serialization are excluded. */
internal object PresentationCodec {
    // SaveableStateRegistry calls this for every provider, including while lazy items
    // detach. Validation must not allocate a serialized copy of the entire screen.
    fun canSave(value: Any?): Boolean = when (value) {
        null, is String, is Boolean, is Char, is IntArray, is LongArray,
        is FloatArray, is DoubleArray, is BooleanArray -> true
        is Number -> value::class in numberTypes
        is SnapshotMutableState<*> -> supportedPolicy(value) && canSave(value.value)
        is List<*> -> value.all(::canSave)
        is Pair<*, *> -> canSave(value.first) && canSave(value.second)
        is Map<*, *> -> value.all { (key, item) -> canSave(key) && canSave(item) }
        else -> false
    }
    private val numberTypes = setOf(Int::class, Long::class, Float::class, Double::class, Short::class, Byte::class)
    private fun supportedPolicy(value: SnapshotMutableState<*>) =
        value.policy == structuralEqualityPolicy<Any?>() || value.policy == referentialEqualityPolicy<Any?>() ||
            value.policy == neverEqualPolicy<Any?>()

    fun encode(values: Map<String, List<Any?>>): String = Json.encodeToString(JsonObject.serializer(),
        JsonObject(values.mapValues { (_, items) -> JsonArray(items.map(::encodeValue)) }))
    fun decode(snapshot: String?): Map<String, List<Any?>> {
        if (snapshot == null) return emptyMap()
        return try { Json.parseToJsonElement(snapshot).jsonObject.mapValues { (_, items) -> items.jsonArray.map(::decodeValue) } }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (failure: Exception) { throw StorageException("restore presentation", StorageException.Kind.CORRUPT, failure) }
    }
    private fun tagged(tag: String, payload: JsonElement = JsonNull) = JsonArray(listOf(JsonPrimitive(tag), payload))
    private fun encodeValue(value: Any?): JsonElement = when (value) {
        null -> tagged("null")
        is String -> tagged("string", JsonPrimitive(value))
        is Boolean -> tagged("boolean", JsonPrimitive(value))
        is Number -> encodeNumber(value)
        is Char -> tagged("char", JsonPrimitive(value.toString()))
        is SnapshotMutableState<*> -> {
            val policy = when (value.policy) {
                structuralEqualityPolicy<Any?>() -> "structural"
                referentialEqualityPolicy<Any?>() -> "referential"
                neverEqualPolicy<Any?>() -> "never"
                else -> error("Unsupported presentation mutation policy")
            }
            tagged("state", JsonArray(listOf(JsonPrimitive(policy), encodeValue(value.value))))
        }
        is List<*> -> tagged("list", JsonArray(value.map(::encodeValue)))
        is Pair<*, *> -> tagged("pair", JsonArray(listOf(encodeValue(value.first), encodeValue(value.second))))
        is Map<*, *> -> tagged("map", JsonArray(value.map { (key, item) -> JsonArray(listOf(encodeValue(key), encodeValue(item))) }))
        is IntArray -> tagged("ints", JsonArray(value.map(::JsonPrimitive)))
        is LongArray -> tagged("longs", JsonArray(value.map { JsonPrimitive(it.toString()) }))
        is FloatArray -> tagged("floats", JsonArray(value.map { JsonPrimitive(numberText(it)) }))
        is DoubleArray -> tagged("doubles", JsonArray(value.map { JsonPrimitive(numberText(it)) }))
        is BooleanArray -> tagged("booleans", JsonArray(value.map(::JsonPrimitive)))
        else -> error("Unsupported presentation value")
    }

    private fun encodeNumber(value: Number): JsonElement {
        // Kotlin/JS boxes Int, Float, Double, Short and Byte as the same JS number.
        // An `is Int` branch therefore also accepts fractions; KClass instead classifies
        // JS int32 values and doubles, while retaining the boxed types on native targets.
        val type = value::class
        val negativeZero = value.toDouble() == 0.0 && 1.0 / value.toDouble() == Double.NEGATIVE_INFINITY
        val tag = when {
            negativeZero -> if (type == Float::class) "float" else "double"
            type == Int::class -> "int"
            type == Long::class -> "long"
            type == Float::class -> "float"
            type == Double::class -> "double"
            type == Short::class -> "short"
            type == Byte::class -> "byte"
            else -> error("Unsupported presentation number")
        }
        return tagged(tag, JsonPrimitive(numberText(value)))
    }

    private fun numberText(value: Number): String =
        if (value.toDouble() == 0.0 && 1.0 / value.toDouble() == Double.NEGATIVE_INFINITY) "-0.0" else value.toString()
    private fun decodeValue(element: JsonElement): Any? {
        val tuple = element.jsonArray
        require(tuple.size == 2)
        val data = tuple[1]
        return when (tuple[0].jsonPrimitive.content) {
            "null" -> null
            "string" -> data.jsonPrimitive.content
            "boolean" -> data.jsonPrimitive.boolean
            "int" -> data.jsonPrimitive.int
            "long" -> data.jsonPrimitive.content.toLong()
            "float" -> data.jsonPrimitive.content.toFloat()
            "double" -> data.jsonPrimitive.content.toDouble()
            "short" -> data.jsonPrimitive.content.toShort()
            "byte" -> data.jsonPrimitive.content.toByte()
            "char" -> data.jsonPrimitive.content.single()
            "state" -> data.jsonArray.let { state ->
                require(state.size == 2)
                val policy: SnapshotMutationPolicy<Any?> = when (state[0].jsonPrimitive.content) {
                    "structural" -> structuralEqualityPolicy()
                    "referential" -> referentialEqualityPolicy()
                    "never" -> neverEqualPolicy()
                    else -> error("Unknown presentation mutation policy")
                }
                mutableStateOf(decodeValue(state[1]), policy)
            }
            "list" -> data.jsonArray.map(::decodeValue)
            "pair" -> data.jsonArray.let { require(it.size == 2); decodeValue(it[0]) to decodeValue(it[1]) }
            "map" -> data.jsonArray.associate { val entry = it.jsonArray; require(entry.size == 2); decodeValue(entry[0]) to decodeValue(entry[1]) }
            "ints" -> data.jsonArray.map { it.jsonPrimitive.int }.toIntArray()
            "longs" -> data.jsonArray.map { it.jsonPrimitive.content.toLong() }.toLongArray()
            "floats" -> data.jsonArray.map { it.jsonPrimitive.content.toFloat() }.toFloatArray()
            "doubles" -> data.jsonArray.map { it.jsonPrimitive.content.toDouble() }.toDoubleArray()
            "booleans" -> data.jsonArray.map { it.jsonPrimitive.boolean }.toBooleanArray()
            else -> error("Unknown presentation tag")
        }
    }
}

/** Kept by the Decompose child, so temporary composition disposal preserves the visit's own state. */
class VisitPresentationState(snapshot: String?, private val onError: (String) -> Unit = {}, private val onSave: (String) -> Unit) {
    private var latest = snapshot
    private var activeRegistry: TrackingSaveableRegistry? = null
    var restoreError: StorageException? = null
        private set
    internal fun registry(): TrackingSaveableRegistry {
        val restored = try { PresentationCodec.decode(latest) }
        catch (error: StorageException) {
            logPersistenceFailure("VisitPresentation", "restore_failed", error)
            restoreError = error; emptyMap()
        }
        return TrackingSaveableRegistry(restored) { values ->
            if (restoreError != null) return@TrackingSaveableRegistry
            val encoded = PresentationCodec.encode(values)
            if (encoded != latest) { latest = encoded; onSave(encoded) }
        }.also { activeRegistry = it }
    }
    fun flush() {
        try { activeRegistry?.let { it.save(it.performSave()) } }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (failure: Exception) {
            logPersistenceFailure("VisitPresentation", "flush_failed", failure)
            onError("Не удалось сохранить состояние экрана.")
        }
    }
    @Composable fun Content(content: @Composable () -> Unit) {
        val registry = remember(this) { registry() }
        LaunchedEffect(this) {
            if (restoreError != null) onError("Не удалось восстановить состояние экрана.")
        }
        LaunchedEffect(registry) {
            try {
                registry.trackChanges()
            }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (failure: Exception) {
                logPersistenceFailure("VisitPresentation", "save_failed", failure)
                onError("Не удалось сохранить состояние экрана.")
            }
        }
        DisposableEffect(registry) { onDispose { flush() } }
        CompositionLocalProvider(LocalSaveableStateRegistry provides registry, content = content)
    }
}

/** Preserve values before unregister: Compose disposes providers when a visit leaves the active slot. */
internal class TrackingSaveableRegistry(
    restored: Map<String, List<Any?>>,
    private val onSave: (Map<String, List<Any?>>) -> Unit,
) : SaveableStateRegistry {
    private val delegate = SaveableStateRegistry(restored, PresentationCodec::canSave)
    private val detached = mutableMapOf<String, List<Any?>>()
    private val counts = mutableMapOf<String, Int>()
    private val providers = mutableMapOf<String, MutableList<() -> Any?>>()
    private val detaching = mutableMapOf<String, List<Any?>>()
    var registrationVersion by mutableIntStateOf(0)
        private set
    override fun canBeSaved(value: Any) = PresentationCodec.canSave(value)
    override fun consumeRestored(key: String): Any? {
        val original = delegate.consumeRestored(key)
        if (original != null) return original
        val values = detached[key] ?: return null
        val next = values.drop(1)
        if (next.isEmpty()) detached.remove(key) else detached[key] = next
        return values.firstOrNull()
    }
    override fun registerProvider(key: String, valueProvider: () -> Any?): SaveableStateRegistry.Entry {
        val entry = delegate.registerProvider(key, valueProvider)
        // Each registration needs its own identity, even when callbacks are shared.
        val provider = { valueProvider() }
        providers.getOrPut(key) { mutableListOf() }.add(provider)
        counts[key] = (counts[key] ?: 0) + 1
        detaching.remove(key)
        registrationVersion++
        var registered = true
        return object : SaveableStateRegistry.Entry {
            override fun unregister() {
                if (!registered) return
                registered = false
                // Only this key is leaving. Saving the whole delegate here makes a
                // viewport of N disposed rememberSaveable providers cost N full saves.
                detaching.getOrPut(key) { providers.getValue(key).map { it() } }
                entry.unregister()
                providers.getValue(key).remove(provider)
                val remaining = (counts[key] ?: 1) - 1
                if (remaining == 0) {
                    counts.remove(key)
                    providers.remove(key)
                    detached[key] = detaching.remove(key).orEmpty()
                } else counts[key] = remaining
                registrationVersion++
            }
        }
    }
    override fun performSave(): Map<String, List<Any?>> = detached + delegate.performSave()
    fun save(values: Map<String, List<Any?>>) = onSave(values)

    /** Observe saver reads without serializing every scroll offset. Navigation/disposal
     * still calls the owner's synchronous flush; autosaves coalesce a burst of changes. */
    suspend fun trackChanges() {
        val invalidations = Channel<Unit>(Channel.CONFLATED)
        val observer = SnapshotStateObserver { it() }
        val changed: (TrackingSaveableRegistry) -> Unit = { invalidations.trySend(Unit) }
        observer.start()
        try {
            while (true) {
                var values: Map<String, List<Any?>> = emptyMap()
                observer.observeReads(this, changed) {
                    registrationVersion
                    values = performSave()
                    // Detached values can themselves be MutableState saver results.
                    // Keep observing their contents as well as active providers.
                    values.values.forEach { it.forEach(PresentationCodec::canSave) }
                }
                save(values)
                invalidations.receive()
                delay(250)
                invalidations.tryReceive()
            }
        } finally {
            observer.stop()
            observer.clear()
            invalidations.close()
        }
    }
}
