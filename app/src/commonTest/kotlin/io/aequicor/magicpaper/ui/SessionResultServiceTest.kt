package io.aequicor.magicpaper.ui

import io.aequicor.magicpaper.data.coding.JsonCodingProjectRepository
import io.aequicor.magicpaper.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SessionResultServiceTest {
    @Test fun statusTransitionRecencySurvivesServiceRestart() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        var service: DefaultCodingService? = null
        try {
            val fixture = ModelSettingsFixture()
            val repo = JsonCodingProjectRepository(fixture.kv, fixture.json)
            repo.save(CodingProject("p", "Project", "/fixture", 1))
            repo.saveSession(CodingSession("s", "p", "Task", 1, engine = CodingEngine.PI))
            repo.saveMessages("p", "s", listOf(CodingMessage("a", CodingRole.AGENT, "Done", createdAt = 2)))
            val first = fixture.prepareCoding(codingProjects = repo)
            service = first
            advanceUntilIdle()
            first.markSessionRead("s", "a")
            advanceUntilIdle()

            val saved = JsonCodingProjectRepository(fixture.kv, fixture.json).sessions("p").single()
            assertEquals(CodingSessionStatus.NEEDS_TESTING, saved.lastStatus)
            assertTrue(saved.statusChangedAt > saved.createdAt)

            first.close()
            val second = fixture.prepareCoding(
                codingProjects = JsonCodingProjectRepository(fixture.kv, fixture.json),
            )
            service = second
            advanceUntilIdle()
            val restored = second.state.value.coding.sessions.single().session
            assertEquals(saved.statusChangedAt, restored.statusChangedAt)
            assertEquals(CodingSessionStatus.NEEDS_TESTING, restored.lastStatus)
        } finally {
            service?.close()
            Dispatchers.resetMain()
        }
    }

    @Test fun failedVerificationSaveKeepsUncheckedStateAndOffersRetry() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        var service: DefaultCodingService? = null
        try {
            val fixture = ModelSettingsFixture()
            val backing = JsonCodingProjectRepository(fixture.kv, fixture.json)
            backing.save(CodingProject("p", "Project", "/fixture", 1))
            backing.saveSession(CodingSession("s", "p", "Task", 1, engine = CodingEngine.PI))
            backing.saveMessages("p", "s", listOf(CodingMessage("a", CodingRole.AGENT, "Done", createdAt = 2)))
            var fail = false
            val repo = object : CodingProjectRepository by backing {
                override suspend fun updateSession(projectId: String, sessionId: String, update: (CodingSession) -> CodingSession): CodingSession {
                    if (fail) error("write failed")
                    return backing.updateSession(projectId, sessionId, update)
                }
            }
            val coding = fixture.prepareCoding(codingProjects = repo)
            service = coding
            fail = true
            coding.setSessionManuallyVerified("s", "a", true)
            advanceUntilIdle()
            assertFalse(coding.state.value.coding.sessions.single().manuallyVerified)
            assertNotNull(coding.state.value.notice)
            assertNull(JsonCodingProjectRepository(fixture.kv, fixture.json).sessions("p").single().manuallyVerifiedResponseId)
            fail = false
            coding.setSessionManuallyVerified("s", "a", true)
            advanceUntilIdle()
            assertTrue(coding.state.value.coding.sessions.single().manuallyVerified)
            assertNull(coding.state.value.notice)
        } finally {
            service?.close()
            Dispatchers.resetMain()
        }
    }

    @Test fun selectionReadAndManualAcceptanceSurviveReloadWithoutAcceptingFutureResults() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        var service: DefaultCodingService? = null
        try {
            val fixture = ModelSettingsFixture()
            val repo = JsonCodingProjectRepository(fixture.kv, fixture.json)
            repo.save(CodingProject("p", "Project", "/fixture", 1))
            repo.saveSession(CodingSession("s", "p", "Task", 1, engine = CodingEngine.PI))
            val response = CodingMessage("a", CodingRole.AGENT, "Done", createdAt = 2)
            repo.saveMessages("p", "s", listOf(response))
            val coding = fixture.prepareCoding(codingProjects = repo)
            service = coding
            fun current() = coding.state.value.coding.sessions.single()
            coding.selectCodingSession("s")
            runCurrent()
            assertEquals(CodingSessionStatus.UNREAD, current().status)
            coding.markSessionRead("s", "stale")
            runCurrent()
            assertEquals(CodingSessionStatus.UNREAD, current().status)
            coding.markSessionRead("s", "a")
            advanceUntilIdle()
            assertEquals(CodingSessionStatus.NEEDS_TESTING, current().status)
            coding.setSessionManuallyVerified("s", "a", true)
            advanceUntilIdle()
            assertEquals(CodingSessionStatus.IDLE, current().status)
            coding.reload()
            assertEquals(CodingSessionStatus.IDLE, current().status)
            assertEquals("a", JsonCodingProjectRepository(fixture.kv, fixture.json).sessions("p").single().manuallyVerifiedResponseId)
            repo.saveMessages("p", "s", listOf(response, response.copy(id = "next", createdAt = 3)))
            coding.reload()
            coding.setSessionManuallyVerified("s", "a", true)
            advanceUntilIdle()
            assertEquals(CodingSessionStatus.UNREAD, current().status)
            assertFalse(current().manuallyVerified)
        } finally {
            service?.close()
            Dispatchers.resetMain()
        }
    }
}
