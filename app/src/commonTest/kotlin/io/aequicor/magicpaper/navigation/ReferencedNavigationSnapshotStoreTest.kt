package io.aequicor.magicpaper.navigation

import io.aequicor.magicpaper.data.storage.NavigationSnapshotRecord
import io.aequicor.magicpaper.data.storage.NavigationSnapshotStore
import io.aequicor.magicpaper.data.storage.StorageException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ReferencedNavigationSnapshotStoreTest {
    private class Store : NavigationSnapshotStore {
        var snapshot: String? = null
        val presentations = mutableMapOf<String, String>()
        var failPresentationWrite = false
        var failJournalWrite = false
        override suspend fun load(): String? = snapshot
        override suspend fun save(snapshot: String) { this.snapshot = snapshot }
        override suspend fun loadWithPresentations(): NavigationSnapshotRecord? =
            snapshot?.let { NavigationSnapshotRecord(it, presentations.toMap()) }
        override suspend fun saveWithPresentations(snapshot: String, presentations: Map<String, String>) {
            if (failPresentationWrite) throw StorageException("write presentation", StorageException.Kind.WRITE)
            presentations.forEach { (reference, payload) ->
                check(this.presentations[reference]?.let { it == payload } != false)
                this.presentations[reference] = payload
            }
            if (failJournalWrite) throw StorageException("write journal", StorageException.Kind.WRITE)
            this.snapshot = snapshot
        }
    }

    @Test fun durableJournalContainsReferencesAndFreshOwnerRestoresCompleteState() = runTest {
        val raw = Store()
        val adapter = ReferencedNavigationSnapshotStore(raw)
        val journal = NavigationJournal().navigate(AppRoute.Docs("start")).navigate(AppRoute.Settings()).moveTo(1)
        val visit = journal.current.id
        val hydrated = journal.copy(presentation = mapOf(visit to "private-view-payload"))
        adapter.save(Json.encodeToString(hydrated))
        val stored = Json.parseToJsonElement(requireNotNull(raw.snapshot)).jsonObject
        assertFalse("presentation" in stored)
        assertFalse(requireNotNull(raw.snapshot).contains("private-view-payload"))
        val reference = stored.getValue("presentationRefs").jsonObject.getValue(visit).jsonPrimitive.content
        assertEquals("private-view-payload", raw.presentations[reference])
        val restored = Json.decodeFromString<NavigationJournal>(requireNotNull(ReferencedNavigationSnapshotStore(raw).load()))
        assertEquals(hydrated, restored)
        assertTrue(restored.canGoForward)
        adapter.save(Json.encodeToString(hydrated.moveTo(2)))
        val unchanged = Json.parseToJsonElement(requireNotNull(raw.snapshot)).jsonObject.getValue("presentationRefs")
        assertEquals(stored.getValue("presentationRefs"), unchanged)
    }

    @Test fun inlineLegacyStateMigratesOnlyOnSuccessfulSave() = runTest {
        val journal = NavigationJournal()
        val legacy = journal.copy(presentation = mapOf(journal.current.id to "legacy-query"))
        val raw = Store().apply { snapshot = Json.encodeToString(legacy) }
        val original = raw.snapshot
        val adapter = ReferencedNavigationSnapshotStore(raw)
        assertEquals(original, adapter.load())
        assertEquals(original, raw.snapshot)
        assertTrue(raw.presentations.isEmpty())
        adapter.save(Json.encodeToString(legacy))
        assertNotEquals(original, raw.snapshot)
        assertFalse(requireNotNull(raw.snapshot).contains("legacy-query"))
        assertEquals(legacy, Json.decodeFromString<NavigationJournal>(requireNotNull(ReferencedNavigationSnapshotStore(raw).load())))
    }

    @Test fun failedPresentationOrJournalWriteKeepsThePreviouslyRestorableSnapshot() = runTest {
        val raw = Store()
        val adapter = ReferencedNavigationSnapshotStore(raw)
        val journal = NavigationJournal()
        val first = journal.copy(presentation = mapOf(journal.current.id to "retained"))
        val next = journal.copy(presentation = mapOf(journal.current.id to "new-state"), revision = 1)
        adapter.save(Json.encodeToString(first))
        val committed = raw.snapshot
        raw.failPresentationWrite = true
        assertFailsWith<StorageException> { adapter.save(Json.encodeToString(next)) }
        assertEquals(committed, raw.snapshot)
        raw.failPresentationWrite = false
        raw.failJournalWrite = true
        assertFailsWith<StorageException> { adapter.save(Json.encodeToString(next)) }
        assertEquals(committed, raw.snapshot)
        assertEquals(first, Json.decodeFromString<NavigationJournal>(requireNotNull(ReferencedNavigationSnapshotStore(raw).load())))
        raw.failJournalWrite = false
        adapter.save(Json.encodeToString(next))
        assertEquals(next, Json.decodeFromString<NavigationJournal>(requireNotNull(ReferencedNavigationSnapshotStore(raw).load())))
    }

    @Test fun missingReferencedPresentationFailsWithoutReplacingTheJournal() = runTest {
        val raw = Store()
        val journal = NavigationJournal()
        ReferencedNavigationSnapshotStore(raw).save(Json.encodeToString(journal.copy(presentation = mapOf(journal.current.id to "state"))))
        val committed = raw.snapshot
        raw.presentations.clear()
        val failure = assertFailsWith<StorageException> { ReferencedNavigationSnapshotStore(raw).load() }
        assertEquals(StorageException.Kind.CORRUPT, failure.kind)
        assertEquals(committed, raw.snapshot)
    }
}
