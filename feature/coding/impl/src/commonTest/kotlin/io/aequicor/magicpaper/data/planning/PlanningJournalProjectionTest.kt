package io.aequicor.magicpaper.data.planning

import io.aequicor.magicpaper.data.storage.*
import io.aequicor.magicpaper.domain.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.*

class PlanningJournalProjectionTest {
    private val initial = Plan("plan", "project", "Original", revision = 12, createdAt = 123)
    private fun repository() = JsonPlanningRepository(InMemoryKeyValueStore(), Json)

    @Test fun legacySeedIsWrittenOnceAndDoesNotInventMessagesOrChangeIdentity() = runTest {
        val repo = repository()
        val legacy = initial.copy(parentSessionId = "parent", runId = "run", phase = ExecutionPhase.COMPLETE)
        repo.save(legacy)
        val expected = repo.planFor(initial.id)!!
        val events = InMemoryEventJournal()
        val intent = events.append(initial.id, PlanJournalOperation.AGENT_INTENT.wire, 1)
        val first = PlanningStore(repo, events)
        assertEquals(expected, first.planFor(initial.id))
        val seeded = events.read(initial.id)
        assertEquals(2, seeded.size)
        val reopened = PlanningStore(repo, events)
        assertEquals(expected, reopened.planFor(initial.id))
        assertEquals(seeded, events.read(initial.id))
        assertEquals(listOf(intent), reopened.unsettled(initial.id))
        assertTrue(expected.messageEvents.isEmpty())
    }

    @Test fun missingCheckpointsAreRebuiltWithExactlyTheAcceptedMessageIdsAndTimes() = runTest {
        val repo = repository()
        val events = InMemoryEventJournal()
        val store = PlanningStore(repo, events)
        store.save(initial.copy(parentSessionId = "parent", runId = "run"))
        val accepted = store.update(initial.id) { it.copy(phase = ExecutionPhase.COMPLETE) }
        assertEquals(1, accepted.messageEvents.size)
        repo.wipe()
        val reopened = PlanningStore(repo, events)
        assertEquals(accepted, reopened.planFor(initial.id))
        reopened.recover()
        assertEquals(accepted, repo.planFor(initial.id))
        assertEquals(accepted, projectPlanJournal(events.read(initial.id)))
        assertEquals(accepted.messageEvents, reopened.planFor(initial.id)!!.messageEvents)
    }

    @Test fun newPlanIsDiscoverableWhenItsFirstCheckpointWasNeverWritten() = runTest {
        val repo = repository()
        val events = InMemoryEventJournal()
        val store = PlanningStore(object : PlanningRepository by repo {
            override suspend fun save(plan: Plan) = error("checkpoint unavailable")
        }, events)
        assertFailsWith<PlanningPersistenceException> { store.save(initial) }
        assertTrue(repo.plans().isEmpty())
        assertNotNull(store.failure.value)
        assertEquals(DecisionCompiler.migrate(initial), PlanningStore(repo, events).planFor(initial.id))
    }

    @Test fun staleSnapshotCannotOverwriteAnAcceptedChangeOrConsumeARevision() = runTest {
        val repo = repository()
        val events = InMemoryEventJournal()
        val first = PlanningStore(repo, events)
        first.save(initial)
        val second = PlanningStore(repo, events)
        val stale = second.planFor(initial.id)!!
        val accepted = first.update(initial.id, stale.revision) { it.copy(goal = "Winner") }
        val records = events.read(initial.id)
        assertFailsWith<PlanningRevisionConflictException> {
            second.update(initial.id, stale.revision) { it.copy(goal = "Stale") }
        }
        assertNull(second.failure.value, "A revision conflict is not a storage outage")
        assertEquals(records, events.read(initial.id))
        assertEquals(accepted, second.planFor(initial.id))
        assertFailsWith<IllegalArgumentException> { second.save(stale.copy(goal = "Still stale")) }
        assertEquals(records, events.read(initial.id))
        assertEquals("Winner", repo.planFor(initial.id)!!.goal)
    }

    @Test fun deletionFenceWinsOverOldCheckpointsAndRejectsStaleWriters() = runTest {
        val repo = repository()
        val events = InMemoryEventJournal()
        var failDelete = true
        val owner = PlanningStore(object : PlanningRepository by repo {
            override suspend fun deletePlan(projectId: String) {
                if (failDelete) error("checkpoint cleanup unavailable")
                repo.deletePlan(projectId)
            }
        }, events)
        owner.save(initial)
        val stale = PlanningStore(repo, events)
        stale.plans()
        assertFailsWith<PlanningPersistenceException> { owner.deletePlan(initial.id) }
        assertNotNull(repo.planFor(initial.id))
        assertTrue(events.read(initial.id).isEmpty())
        assertFailsWith<PlanningRevisionConflictException> { stale.update(initial.id) { it.copy(goal = "resurrect") } }
        failDelete = false
        owner.recover()
        assertNull(owner.planFor(initial.id))
        assertNull(repo.planFor(initial.id))
        assertFailsWith<PlanningRevisionConflictException> { owner.save(initial) }
        assertTrue(events.read(initial.id).isEmpty())
    }

    @Test fun lostAppendAcknowledgementRequiresRecoveryFromTheJournal() = runTest {
        val repo = repository()
        val memory = InMemoryEventJournal()
        var loseAck = false
        val events = object : EventJournal by memory {
            override suspend fun append(expected: JournalRevision, operation: String, at: Long, detail: String): JournalRecord? {
                val record = memory.append(expected, operation, at, detail)
                if (loseAck) error("acknowledgement lost")
                return record
            }
        }
        val store = PlanningStore(repo, events)
        store.save(initial)
        loseAck = true
        assertFailsWith<PlanningPersistenceException> { store.update(initial.id) { it.copy(goal = "Accepted") } }
        assertEquals("Original", store.planFor(initial.id)!!.goal)
        assertFailsWith<PlanningPersistenceException> { store.update(initial.id) { it.copy(goal = "Repeated") } }
        loseAck = false
        store.recover()
        assertEquals("Accepted", store.planFor(initial.id)!!.goal)
        assertEquals(initial.revision + 1, store.planFor(initial.id)!!.revision)
        assertEquals(2, memory.read(initial.id).size)
    }

    @Test fun recoveryErasesFencedPayloadBeforeDroppingItsLastCheckpoint() = runTest {
        val repo = repository()
        val bytes = InMemoryDurableByteStore()
        var failCleanup = false
        val backend = object : DurableByteStore by bytes {
            override suspend fun delete(area: StorageArea, key: String) {
                if (failCleanup && area == StorageArea.EVENTS) error("cleanup interrupted")
                bytes.delete(area, key)
            }
        }
        val store = PlanningStore(repo, DurableEventJournal(backend))
        store.save(initial)
        failCleanup = true
        assertFailsWith<PlanningPersistenceException> { store.deletePlan(initial.id) }
        assertNotNull(repo.planFor(initial.id))
        assertTrue(bytes.values(StorageArea.EVENTS).any { it.decodeToString().contains("Original") })
        failCleanup = false
        val reopened = PlanningStore(repo, DurableEventJournal(backend))
        reopened.recover()
        assertTrue(reopened.plans().isEmpty())
        assertTrue(repo.plans().isEmpty())
        assertFalse(bytes.values(StorageArea.EVENTS).any { it.decodeToString().contains("Original") })
    }

    @Test fun outcomePreservesAnotherWritersAcceptedChanges() = runTest {
        val repo = repository()
        val events = InMemoryEventJournal()
        val first = PlanningStore(repo, events)
        first.save(initial)
        first.withJournaledIntent(initial.id, PlanJournalOperation.AGENT_INTENT) {
            val second = PlanningStore(repo, events)
            second.update(initial.id) { it.copy(goal = "Changed concurrently") }
            complete()
        }
        assertEquals("Changed concurrently", first.planFor(initial.id)!!.goal)
        assertTrue(first.unsettled(initial.id).isEmpty())
        assertEquals(first.planFor(initial.id), projectPlanJournal(events.read(initial.id)))
    }

    @Test fun checkpointCannotOverrideTheJournalAndCorruptionCannotHideBehindCheckpoint() = runTest {
        val repo = repository()
        val events = InMemoryEventJournal()
        val store = PlanningStore(repo, events)
        store.save(initial)
        repo.save(initial.copy(revision = 99, goal = "Foreign checkpoint"))
        assertEquals(DecisionCompiler.migrate(initial), PlanningStore(repo, events).planFor(initial.id))
        events.append(initial.id, PLAN_STATE_OPERATION, 999, "plan-commit/2:{}")
        val reopened = PlanningStore(repo, events)
        assertFailsWith<PlanningPersistenceException> { reopened.plans() }
        assertNotNull(reopened.failure.value)
        assertTrue(reopened.plans.value.isEmpty())
    }

    @Test fun operationAndStateCannotBeSeparatedByACheckpointFailure() = runTest {
        val repo = repository()
        val events = InMemoryEventJournal()
        var fail = false
        val store = PlanningStore(object : PlanningRepository by repo {
            override suspend fun save(plan: Plan) {
                if (fail) error("checkpoint unavailable")
                repo.save(plan)
            }
        }, events)
        store.save(initial)
        fail = true
        assertFailsWith<PlanningPersistenceException> {
            store.journal(initial.id, PlanJournalOperation.STOP_INTENT) { it.copy(stopping = true) }
        }
        val reopened = PlanningStore(repo, events)
        assertTrue(reopened.planFor(initial.id)!!.stopping)
        assertEquals(1, reopened.unsettled(initial.id).size)
        assertEquals(2, events.read(initial.id).size, "Seed plus one atomic operation/state record")
    }
}
