package io.aequicor.magicpaper.domain

import io.aequicor.magicpaper.data.coding.JsonCodingProjectRepository
import io.aequicor.magicpaper.data.coding.SessionOrganismStore
import io.aequicor.magicpaper.data.storage.InMemoryKeyValueStore
import io.aequicor.magicpaper.data.storage.JsonSettingsRepository
import io.aequicor.magicpaper.data.storage.KeyValueStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class SessionLimitPolicyRaceTest {
    private class FailingStore(private val backing: KeyValueStore = InMemoryKeyValueStore()) : KeyValueStore by backing {
        var discoveryFailure: Exception? = null
        override fun keys(prefix: String): List<String> {
            if (prefix == "session-organism-") discoveryFailure?.let { throw it }
            return backing.keys(prefix)
        }
    }

    private class GatedSettings(val backing: SettingsRepository) : SettingsRepository by backing {
        var pauseNextRead = false
        var saveFailure: Exception? = null
        val captured = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        override suspend fun load(): AppSettings {
            val snapshot = backing.load()
            if (pauseNextRead) {
                pauseNextRead = false
                captured.complete(Unit)
                release.await()
            }
            return snapshot
        }
        override suspend fun save(settings: AppSettings) {
            saveFailure?.let { throw it }
            backing.save(settings)
        }
    }

    private class Fixture {
        val storage = FailingStore()
        private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
        val settings = GatedSettings(JsonSettingsRepository(storage, json))
        val projects = JsonCodingProjectRepository(storage, json)
        val store = SessionOrganismStore(storage) { 1_000 }
        val service = SessionOrganismService(store, projects, settings)
        val initial = AppSettings(agentLimits = OrganismLimits(tokens = 1_000, durationMillis = 3_600_000))
        val root = CodingSession("root", "project", "Task", 1, researchMode = true)
        suspend fun initialize(adopt: Boolean = true) {
            settings.save(initial)
            projects.save(CodingProject("project", "Project", "/fixture", 1))
            projects.saveSession(root)
            if (adopt) service.ensure(root)
        }
    }

    @Test fun removingLimitsCannotBeOverwrittenByAnOlderSingleOrAllTaskSynchronization() = runTest {
        for (allTasks in listOf(false, true)) {
            val f = Fixture(); f.initialize()
            f.settings.pauseNextRead = true
            val older = async {
                if (allTasks) f.service.synchronizeAllLimits() else f.service.synchronizeLimits(f.root.id)
            }
            f.settings.captured.await()
            val removed = f.initial.copy(agentLimits = OrganismLimits())
            val newer = async { f.service.saveSettingsAndApplyLimits(removed) }
            runCurrent()
            assertFalse(newer.isCompleted, "Saving and applying a new policy must share the old reader's lock")
            assertEquals(f.initial, f.settings.backing.load())
            f.settings.release.complete(Unit)
            older.await()
            assertTrue(newer.await().isSuccess)
            assertEquals(removed, f.settings.backing.load())
            assertEquals(OrganismLimits(), f.store.get(f.root.id).limits)
            f.service.synchronizeAllLimits()
            assertEquals(OrganismLimits(), f.store.get(f.root.id).limits)
        }
    }

    @Test fun firstAdoptionCannotRestoreThePolicyCapturedBeforeANewerSave() = runTest {
        val f = Fixture(); f.initialize(adopt = false)
        f.settings.pauseNextRead = true
        val adoption = async { f.service.ensure(f.root) }
        f.settings.captured.await()
        val removed = f.initial.copy(agentLimits = OrganismLimits())
        val save = async { f.service.saveSettingsAndApplyLimits(removed) }
        runCurrent(); assertFalse(save.isCompleted)
        f.settings.release.complete(Unit)
        adoption.await(); assertTrue(save.await().isSuccess)
        assertEquals(OrganismLimits(), f.store.get(f.root.id).limits)
        assertEquals(removed, f.settings.backing.load())
    }

    @Test fun missingLegacyProjectionStillAppliesCurrentUserLimitsBeforeAdmission() = runTest {
        val f = Fixture(); f.initialize(adopt = false)
        f.store.adopt(f.root.projectId, f.root, emptyList())
        val adopted = f.service.ensure(f.root)
        assertEquals(f.initial.agentLimits, adopted.limits)
        assertEquals(1_000L, adopted.sessions.values.sumOf { it.remainingTokens })
    }

    @Test fun saveFailureAndPropagationFailureHaveDistinctOutcomesAndCancellationPropagates() = runTest {
        val f = Fixture(); f.initialize()
        val removed = f.initial.copy(agentLimits = OrganismLimits())
        f.settings.saveFailure = IllegalStateException("save failed")
        assertFailsWith<IllegalStateException> { f.service.saveSettingsAndApplyLimits(removed) }
        assertEquals(f.initial, f.settings.backing.load())
        assertEquals(f.initial.agentLimits, f.store.get(f.root.id).limits)
        f.settings.saveFailure = null
        f.storage.discoveryFailure = IllegalStateException("propagation failed")
        val propagation = f.service.saveSettingsAndApplyLimits(removed)
        assertEquals("propagation failed", propagation.exceptionOrNull()?.message)
        assertEquals(removed, f.settings.backing.load())
        assertEquals(f.initial.agentLimits, f.store.get(f.root.id).limits)
        f.storage.discoveryFailure = CancellationException("cancelled")
        assertFailsWith<CancellationException> { f.service.saveSettingsAndApplyLimits(removed) }
        f.storage.discoveryFailure = null
        assertTrue(f.service.saveSettingsAndApplyLimits(removed).isSuccess)
        assertEquals(OrganismLimits(), f.store.get(f.root.id).limits)
    }
}
