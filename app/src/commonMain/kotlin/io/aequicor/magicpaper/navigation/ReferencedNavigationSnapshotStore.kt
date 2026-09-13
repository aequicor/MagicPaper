package io.aequicor.magicpaper.navigation

import io.aequicor.magicpaper.data.storage.NavigationSnapshotStore
import io.aequicor.magicpaper.data.storage.StorageException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * The live journal carries hydrated presentation for component factories. The
 * durable journal carries only immutable references; the backend commits their
 * payloads before the journal and reads them under the same lock during restore.
 */
class ReferencedNavigationSnapshotStore(private val delegate: NavigationSnapshotStore) : NavigationSnapshotStore {
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private var cached = emptyMap<String, Presentation>()

    override suspend fun load(): String? = mutex.withLock {
        val record = delegate.loadWithPresentations() ?: return@withLock null
        val element = decode("restore navigation") { json.parseToJsonElement(record.snapshot).jsonObject }
        if ("presentationRefs" !in element) {
            // Existing inline snapshots remain readable. The next successful
            // write migrates them; restore alone never rewrites the old record.
            decode("restore navigation") { json.decodeFromString<NavigationJournal>(record.snapshot) }
            cached = emptyMap()
            return@withLock record.snapshot
        }
        val stored = decode("restore navigation") { json.decodeFromString<StoredJournal>(record.snapshot) }
        val restored = stored.presentationRefs.mapValues { (_, reference) ->
            val payload = record.presentations[reference]
                ?: throw StorageException("restore visit presentation", StorageException.Kind.CORRUPT)
            Presentation(reference, payload)
        }
        val journal = decode("restore navigation") {
            require(stored.presentationFormatVersion == 1)
            require(stored.presentationRefs.values.all(String::isNotBlank))
            require(stored.presentationRefs.keys.all { id -> stored.visits.any { it.id == id } })
            stored.hydrate(restored.mapValues { it.value.payload })
        }
        cached = restored
        json.encodeToString(journal)
    }

    override suspend fun save(snapshot: String) = mutex.withLock {
        val journal = decode("save navigation") {
            json.decodeFromString<NavigationJournal>(snapshot).also { value ->
                require(value.presentation.keys.all { id -> value.visits.any { it.id == id } })
            }
        }
        val next = journal.presentation.mapValues { (visitId, payload) ->
            cached[visitId]?.takeIf { it.payload == payload } ?: Presentation(newNavigationId(), payload)
        }
        val persisted = StoredJournal(
            id = journal.id, visits = journal.visits, cursor = journal.cursor, revision = journal.revision,
            presentationRefs = next.mapValues { it.value.reference }, pendingRoutes = journal.pendingRoutes,
            version = journal.version,
        )
        // Passing every active payload also makes a cloned journal independent
        // of a source tab that replaces its references before this commit.
        delegate.saveWithPresentations(json.encodeToString(persisted), next.values.associate { it.reference to it.payload })
        cached = next
    }

    private inline fun <T> decode(operation: String, block: () -> T): T = try { block() }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: Exception) { throw StorageException(operation, StorageException.Kind.CORRUPT, failure) }

    private data class Presentation(val reference: String, val payload: String)

    @Serializable
    private data class StoredJournal(
        val id: String,
        val visits: List<Visit>,
        val cursor: Int,
        val revision: Long,
        val presentationRefs: Map<String, String>,
        val pendingRoutes: List<AppRoute>,
        val version: Int,
        val presentationFormatVersion: Int = 1,
    ) {
        fun hydrate(presentation: Map<String, String>) = NavigationJournal(
            id = id, visits = visits, cursor = cursor, revision = revision,
            presentation = presentation, pendingRoutes = pendingRoutes, version = version,
        )
    }
}
