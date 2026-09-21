package io.aequicor.magicpaper.data.computer

import java.security.MessageDigest
import kotlinx.serialization.json.*

/** Fingerprints bind ephemeral arguments to a journal intent without storing text or coordinates. */
internal fun computerArgumentsFingerprint(arguments: JsonObject): String {
    fun ordered(value: JsonElement): JsonElement = when (value) {
        is JsonObject -> JsonObject(value.entries.sortedBy { it.key }.associate { it.key to ordered(it.value) })
        is JsonArray -> JsonArray(value.map(::ordered))
        else -> value
    }
    return MessageDigest.getInstance("SHA-256").digest(ordered(arguments).toString().toByteArray(Charsets.UTF_8))
        .joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
}
