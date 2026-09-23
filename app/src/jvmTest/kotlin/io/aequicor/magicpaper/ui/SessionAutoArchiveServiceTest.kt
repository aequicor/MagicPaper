package io.aequicor.magicpaper.ui

import io.aequicor.magicpaper.data.coding.*
import io.aequicor.magicpaper.data.docs.EmbeddedDocRepository
import io.aequicor.magicpaper.data.storage.JsonChatRepository
import io.aequicor.magicpaper.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class SessionAutoArchiveServiceTest {
    @Test fun deletingChatDuringArchiveWriteCannotResurrectIt() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val f = ModelSettingsFixture(); f.seed()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val repository = object : ChatCheckpointStore by f.chats {
            override suspend fun save(session: ChatSession) {
                if (session.archived) { entered.complete(Unit); release.await() }
                f.chats.save(session)
            }
        }
        val chat = DefaultChatService(testGatewayRuntime(f.gateway, f.search, EmbeddedDocRepository()), f.newChatStore(repository),
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
        fun service(repository: CodingProjectRepository = repo) = DefaultCodingService(f.settings, f.profiles, f.kv, f.json, settingsCommands = f.testSettingsCommands(),
            codingProjects = journalCodingProjects(f.kv, f.json, f.chatJournal, Dispatchers.Main, repository as CodingCheckpointStore), usage = f.usage, workerDispatcher = Dispatchers.Main,
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
        val owner = journalCodingProjects(f.kv, f.json, f.chatJournal, Dispatchers.Main, backing)
        val repo = object : CodingProjectOwner by owner {
            override suspend fun dispatch(projectId: String, input: CodingMachine.Input): CodingMachine.Transition {
                if (fail && input is CodingMachine.Fact.ArchiveReadinessObserved) error("write failed")
                return owner.dispatch(projectId, input)
            }
        }
        val coding = DefaultCodingService(f.settings, f.profiles, f.kv, f.json, settingsCommands = f.testSettingsCommands(), codingProjects = repo,
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
        val chat = DefaultChatService(testGatewayRuntime(f.gateway, f.search, EmbeddedDocRepository()), f.chatStore,
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

    /**
     * The default ticker waits on Dispatchers.Default. A stop that returns before it has finished leaves that thread
     * resuming the collector on Main afterwards, which raced resetMain in RequestPinViewModelTest.
     */
    @Test fun codingShutdownReturnsOnlyAfterTheTickerStopped() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val f = ModelSettingsFixture(); f.seed()
        val ticker = HeldTicker()
        val coding = DefaultCodingService(f.settings, f.profiles, f.kv, f.json, settingsCommands = f.testSettingsCommands(),
            codingProjects = journalCodingProjects(f.kv, f.json, f.chatJournal, Dispatchers.Main, JsonCodingProjectRepository(f.kv, f.json)),
            usage = f.usage, workerDispatcher = Dispatchers.Main, archiveTicks = ticker.ticks)
        try {
            coding.start(); runCurrent()
            assertStopWaitsFor(ticker) { coding.shutdownCoding() }
        } finally { ticker.release.complete(Unit); Dispatchers.resetMain() }
    }

    @Test fun chatCloseReturnsOnlyAfterTheTickerStopped() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val f = ModelSettingsFixture(); f.seed()
        val ticker = HeldTicker()
        val chat = DefaultChatService(testGatewayRuntime(f.gateway, f.search, EmbeddedDocRepository()), f.chatStore,
            f.settings, f.profiles, null, workerDispatcher = Dispatchers.Main, archiveTicks = ticker.ticks)
        try {
            chat.start(); runCurrent()
            assertStopWaitsFor(ticker) { chat.close() }
        } finally { ticker.release.complete(Unit); Dispatchers.resetMain() }
    }

    /** A ticker on Dispatchers.Default, like the default one, whose stop the test holds open. */
    private class HeldTicker {
        val started = CompletableDeferred<Unit>()
        val stopping = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val ticks = flow<Unit> {
            started.complete(Unit)
            try { awaitCancellation() }
            finally { stopping.complete(Unit); withContext(NonCancellable) { release.await() } }
        }.flowOn(Dispatchers.Default)
    }

    private suspend fun TestScope.assertStopWaitsFor(ticker: HeldTicker, stop: suspend () -> Unit) {
        ticker.started.await()
        val stopped = launch { stop() }
        ticker.stopping.await()
        assertFalse(stopped.isCompleted, "The owner stopped while its ticker was still finishing on another thread")
        ticker.release.complete(Unit)
        stopped.join()
    }
}
