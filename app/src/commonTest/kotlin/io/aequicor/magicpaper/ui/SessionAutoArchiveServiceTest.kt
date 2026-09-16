package io.aequicor.magicpaper.ui

import io.aequicor.magicpaper.data.coding.JsonCodingProjectRepository
import io.aequicor.magicpaper.data.docs.EmbeddedDocRepository
import io.aequicor.magicpaper.data.storage.JsonChatRepository
import io.aequicor.magicpaper.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class SessionAutoArchiveServiceTest {
    @Test fun deletingChatDuringArchiveWriteCannotResurrectIt() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val f = ModelSettingsFixture(); f.seed()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val repository = object : ChatRepository by f.chats {
            override suspend fun save(session: ChatSession) {
                if (session.archived) { entered.complete(Unit); release.await() }
                f.chats.save(session)
            }
        }
        val chat = DefaultChatService(GatewaySessionRuntime(f.gateway, f.search, EmbeddedDocRepository()), repository,
            f.settings, f.profiles, null, workerDispatcher = Dispatchers.Main, archiveClock = { 1 },
            archiveTicks = MutableSharedFlow())
        try {
            chat.start(); runCurrent()
            chat.archiveSession("first"); runCurrent()
            assertTrue(entered.isCompleted)
            chat.deleteSession("first"); runCurrent()
            release.complete(Unit); runCurrent()
            assertNull(JsonChatRepository(f.kv, f.json).session("first"))
            assertTrue(chat.state.value.sessions.isEmpty())
        } finally { release.complete(Unit); chat.close(); Dispatchers.resetMain() }
    }

    @Test fun codingReadinessSurvivesRestartAndRestoreGrantsTwoMoreHours() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val f = ModelSettingsFixture()
        f.seed()
        var now = 10_000L
        val ticks = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val repo = JsonCodingProjectRepository(f.kv, f.json)
        repo.save(CodingProject("p", "Project", "/fixture", 1))
        repo.saveSession(CodingSession("s", "p", "Task", 1, engine = CodingEngine.PI))
        fun service(repository: CodingProjectRepository = repo) = DefaultCodingService(f.settings, f.profiles, f.kv, f.json,
            codingProjects = repository, usage = f.usage, workerDispatcher = Dispatchers.Main,
            archiveClock = { now }, archiveTicks = ticks)
        var coding = service()
        try {
            coding.start(); runCurrent()
            assertEquals(now, repo.sessions("p").single().archiveReadySince)
            now += 7_199_999
            ticks.emit(Unit); runCurrent()
            assertFalse(repo.sessions("p").single().archived)
            coding.close()
            now += 1
            coding = service(JsonCodingProjectRepository(f.kv, f.json))
            coding.start(); runCurrent()
            assertTrue(coding.state.value.coding.sessions.single().session.archived)
            coding.restoreCodingSession("s"); runCurrent()
            assertFalse(coding.state.value.coding.sessions.single().session.archived)
            assertEquals(now, coding.state.value.coding.sessions.single().session.archiveReadySince)
            ticks.emit(Unit); runCurrent()
            assertFalse(coding.state.value.coding.sessions.single().session.archived)
            assertTrue(f.calls.isEmpty())
            now += 7_200_000
            ticks.emit(Unit); runCurrent()
            assertTrue(JsonCodingProjectRepository(f.kv, f.json).sessions("p").single().archived)
        } finally { coding.close(); Dispatchers.resetMain() }
    }

    @Test fun unreadAndUnverifiedResultsResetReadinessAndFailedWritesRemainRetryable() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val f = ModelSettingsFixture(); f.seed()
        var now = 100L
        val ticks = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val backing = JsonCodingProjectRepository(f.kv, f.json)
        backing.save(CodingProject("p", "Project", "/fixture", 1))
        backing.saveSession(CodingSession("s", "p", "Task", 1, engine = CodingEngine.PI, archiveReadySince = 1))
        backing.saveMessages("p", "s", listOf(CodingMessage("answer", CodingRole.AGENT, "Done", createdAt = 2)))
        var fail = false
        val repo = object : CodingProjectRepository by backing {
            override suspend fun updateSession(projectId: String, sessionId: String, update: (CodingSession) -> CodingSession): CodingSession {
                if (fail) error("write failed")
                return backing.updateSession(projectId, sessionId, update)
            }
        }
        val coding = DefaultCodingService(f.settings, f.profiles, f.kv, f.json, codingProjects = repo,
            usage = f.usage, workerDispatcher = Dispatchers.Main, archiveClock = { now }, archiveTicks = ticks)
        try {
            coding.start(); runCurrent()
            assertNull(backing.sessions("p").single().archiveReadySince)
            coding.markSessionRead("s", "answer"); runCurrent()
            coding.setSessionManuallyVerified("s", "answer", true); runCurrent()
            assertEquals(now, backing.sessions("p").single().archiveReadySince)
            now += 1_000
            coding.setSessionManuallyVerified("s", "answer", false); runCurrent()
            assertNull(backing.sessions("p").single().archiveReadySince)
            coding.setSessionManuallyVerified("s", "answer", true); runCurrent()
            assertEquals(now, backing.sessions("p").single().archiveReadySince)
            now += 7_200_000
            fail = true
            ticks.emit(Unit); runCurrent()
            assertFalse(coding.state.value.coding.sessions.single().session.archived)
            assertNotNull(coding.state.value.notice)
            fail = false
            ticks.emit(Unit); runCurrent()
            assertTrue(backing.sessions("p").single().archived)
        } finally { coding.close(); Dispatchers.resetMain() }
    }

    @Test fun chatsUseTwoDaysSkipPendingWorkAndPersistRestoreWithoutSending() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val f = ModelSettingsFixture(); f.seed()
        var now = 172_800_000L
        val ticks = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        f.chats.save(ChatSession("pending", "Pending", 1, 1, pendingRun = CodingRunCheckpoint("r", "Input")))
        val chat = DefaultChatService(GatewaySessionRuntime(f.gateway, f.search, EmbeddedDocRepository()), f.chats,
            f.settings, f.profiles, null, workerDispatcher = Dispatchers.Main, archiveClock = { now }, archiveTicks = ticks)
        try {
            chat.start(); runCurrent()
            assertFalse(f.chats.session("first")!!.archived)
            now += 1
            ticks.emit(Unit); runCurrent()
            assertTrue(JsonChatRepository(f.kv, f.json).session("first")!!.archived)
            assertFalse(f.chats.session("pending")!!.archived)
            chat.selectSession("first"); runCurrent()
            assertTrue(chat.state.value.current!!.archived)
            chat.restoreSession("first"); runCurrent()
            assertFalse(chat.state.value.current!!.archived)
            assertEquals(now, JsonChatRepository(f.kv, f.json).session("first")!!.archiveRestoredAt)
            ticks.emit(Unit); runCurrent()
            assertFalse(f.chats.session("first")!!.archived)
            assertTrue(f.calls.isEmpty())
            now += 172_800_000
            ticks.emit(Unit); runCurrent()
            assertTrue(f.chats.session("first")!!.archived)
        } finally { chat.close(); Dispatchers.resetMain() }
    }
}
