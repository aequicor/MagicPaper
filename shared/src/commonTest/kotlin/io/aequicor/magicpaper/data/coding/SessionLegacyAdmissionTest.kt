package io.aequicor.magicpaper.data.coding

import io.aequicor.magicpaper.data.storage.InMemoryKeyValueStore
import io.aequicor.magicpaper.domain.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class SessionLegacyAdmissionTest {
    private fun session(id: String, parent: String? = null) = CodingSession(id, "project", id, 1, parentSessionId = parent)

    @Test fun migratedPendingHistoryCannotOverbookActualRuntimeSlots() = runTest {
        val store = SessionOrganismStore(InMemoryKeyValueStore()) { 1_000 }
        val children = (1..6).map { session("child-$it", "root") }
        val saved = store.adopt("project", session("root"), children, OrganismLimits(activeSessions = 3))
        store.beginRun(saved.id, "root")
        val attempts = children.map { child -> async { runCatching { store.beginRun(saved.id, child.id) } } }.awaitAll()
        assertEquals(2, attempts.count { it.isSuccess })
        val after = store.get(saved.id)
        assertEquals(8, after.sessions.size, "Migration must preserve every historic node and immunity")
        assertEquals(3, after.sessions.values.count { it.observed == SessionObservedState.RUNNING })
        val finished = attempts.first { it.isSuccess }.getOrThrow()
        store.observe(saved.id, finished.id, finished.generation, SessionObservedState.COMPLETED)
        val pending = children.first { after.sessions.getValue(it.id).observed == SessionObservedState.PENDING }
        assertEquals(SessionObservedState.RUNNING, store.beginRun(saved.id, pending.id).observed)
    }

    @Test fun migrationRetainsDeepHistoryButFencesDepthAndClosedAncestorsAtRuntimeAdmission() = runTest {
        val store = SessionOrganismStore(InMemoryKeyValueStore()) { 1_000 }
        val saved = store.adopt("project", session("root"), listOf(session("child", "root"), session("grandchild", "child")),
            OrganismLimits(depth = 2))
        assertFailsWith<IllegalArgumentException> { store.beginRun(saved.id, "grandchild") }
        assertEquals(saved.version, store.get(saved.id).version)
        val root = store.beginRun(saved.id, "root")
        store.requestUserStop(saved.id, "root", "stop", archive = false)
        assertFailsWith<IllegalArgumentException> { store.beginRun(saved.id, "child") }
        assertEquals(root.generation, store.get(saved.id).sessions.getValue("root").generation)
    }

    @Test fun savedInterruptedNativeRequestMustBeReconciledBeforeMigrationCanRunIt() = runTest {
        val store = SessionOrganismStore(InMemoryKeyValueStore()) { 1_000 }
        val root = session("root").copy(pendingRun = CodingRunCheckpoint("message", "Saved request"))
        val saved = store.adopt("project", root, listOf(session("archived", "root").copy(archived = true,
            observedState = SessionObservedState.STOPPING)))
        assertEquals(SessionObservedState.UNKNOWN, saved.sessions.getValue("root").observed)
        assertEquals(SessionObservedState.UNKNOWN, saved.sessions.getValue("archived").observed)
        assertFailsWith<IllegalArgumentException> { store.beginRun(saved.id, "root") }
        assertEquals(saved, store.get(saved.id))
    }
}
