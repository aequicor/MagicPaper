package io.aequicor.magicpaper.navigation

import io.aequicor.magicpaper.data.storage.StorageException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private data class VisitPresentationEntries(val version: Int, val entries: Map<String, String>)
private val presentationEntriesJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

/** Independent presentation owners share a visit without replacing each other's snapshot. */
fun presentationEntry(snapshot: String?, key: String): String? = decodeEntries(snapshot).entries[key]

internal fun withPresentationEntry(snapshot: String?, key: String, value: String): String {
    val parsed = decodeEntries(snapshot)
    return presentationEntriesJson.encodeToString(parsed.copy(entries = parsed.entries + (key to value)))
}

private fun decodeEntries(snapshot: String?): VisitPresentationEntries {
    if (snapshot == null) return VisitPresentationEntries(1, emptyMap())
    // The original Docs-only snapshot was a plain query. JSON-shaped snapshots
    // are envelopes: corruption or a future version must never become a query.
    if (!snapshot.trimStart().startsWith("{")) return VisitPresentationEntries(1, mapOf("docs-query" to snapshot))
    return try {
        presentationEntriesJson.decodeFromString<VisitPresentationEntries>(snapshot).also {
            require(it.version == 1) { "Unsupported visit presentation version" }
        }
    } catch (failure: Exception) {
        throw StorageException("restore visit presentation", StorageException.Kind.CORRUPT, failure)
    }
}
