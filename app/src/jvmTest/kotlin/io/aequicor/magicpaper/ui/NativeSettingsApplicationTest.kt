package io.aequicor.magicpaper.ui

import io.aequicor.magicpaper.data.coding.*
import io.aequicor.magicpaper.data.planning.*
import io.aequicor.magicpaper.data.storage.InMemoryEventJournal
import io.aequicor.magicpaper.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class NativeSettingsApplicationTest {
    private class Computer : ComputerUse {
        override val supported = true
        override val state = MutableStateFlow(ComputerUseState())
        var policy = ComputerAccess.CONTROL to ComputerAccess.CONTROL
        var enables = 0
        var previews = 0
        private var revision = 0L
        override fun capturePolicy() = ComputerPolicyRef("fixture", revision)
        override fun invalidatePolicy() {
            revision++
            policy = ComputerAccess.OFF to ComputerAccess.OFF
            state.value = ComputerUseState()
        }
        override fun configure(computer: ComputerAccess, application: ComputerAccess) {
            revision++
            policy = computer to application
            state.value = ComputerUseState()
        }
        override suspend fun enable(sessionId: String, access: ComputerAccess, expectedPolicy: ComputerPolicyRef): Boolean {
            if (expectedPolicy != capturePolicy()) return false
            enables++
            state.value = ComputerUseState(sessionId, access)
            return true
        }
        override fun disable(sessionId: String?) { state.value = ComputerUseState() }
        override suspend fun preview(sessionId: String) { previews++ }
        override fun openSystemSettings() = error("Unexpected system settings")
    }

    @Test fun defaultEnginePublishesOnlyConfirmedPreferenceAndPreservesPreviousValueOnFailure() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val fixture = ModelSettingsFixture()
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var fail = false
        val commands = object : SettingsCommands {
            override suspend fun runtimePolicy() = fixture.configuration.runtimePolicy()
            override suspend fun selectDefaultCodingEngine(engine: CodingEngine): AppSettings {
                entered.complete(Unit); release.await()
                check(!fail) { "private storage data" }
                return fixture.testSettingsCommands().selectDefaultCodingEngine(engine)
            }
        }
        val service = fixture.prepareCoding(NoopCodingRuntime, settingsCommands = commands)
        try {
            val previous = service.state.value.settings.defaultCodingEngine
            val next = CodingEngine.entries.first { it != previous }
            service.selectDefaultCodingEngine(next)
            entered.await()
            assertEquals(previous, service.state.value.settings.defaultCodingEngine)
            assertEquals(previous, fixture.settings.load().defaultCodingEngine)
            release.complete(Unit); runCurrent()
            assertEquals(next, service.state.value.settings.defaultCodingEngine)
            assertEquals(next, fixture.settings.load().defaultCodingEngine)
            fail = true
            service.selectDefaultCodingEngine(previous); runCurrent()
            assertEquals(next, service.state.value.settings.defaultCodingEngine)
            assertEquals(next, fixture.settings.load().defaultCodingEngine)
            assertContains(service.state.value.notice.orEmpty(), "Не удалось сохранить")
            assertFalse(service.state.value.notice.orEmpty().contains("private"))
        } finally { release.complete(Unit); service.close(); Dispatchers.resetMain() }
    }

    @Test fun engineSignInIsPendingUntilItEndsAndReportsItsOutcome() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val entered = Channel<Unit>(Channel.UNLIMITED)
        val outcomes = Channel<EngineSignInResult>(Channel.UNLIMITED)
        var calls = 0
        val runtime = object : CodingRuntime by NoopCodingRuntime {
            override suspend fun signIn(engine: CodingEngine): EngineSignInResult {
                assertEquals(CodingEngine.CLAUDE_CODE, engine)
                calls++; entered.send(Unit)
                return outcomes.receive()
            }
        }
        val service = ModelSettingsFixture().prepareCoding(runtime)
        val recovery = CodingRecovery.SignIn(CodingEngine.CLAUDE_CODE)
        try {
            service.recover(recovery); entered.receive()
            assertEquals(setOf<CodingRecovery>(recovery), service.state.value.coding.pendingRecoveries)
            service.recover(recovery); runCurrent()
            assertEquals(1, calls, "A pending sign-in is not started twice")
            outcomes.send(EngineSignInResult.SignedIn); runCurrent()
            assertTrue(service.state.value.coding.pendingRecoveries.isEmpty())
            assertContains(service.state.value.notice.orEmpty(), "Вход в Claude Code выполнен")

            service.recover(recovery); entered.receive()
            outcomes.send(EngineSignInResult.Failed("Вход в Claude Code не завершён.")); runCurrent()
            assertEquals("Вход в Claude Code не завершён.", service.state.value.notice)

            service.recover(recovery); entered.receive()
            service.cancelRecovery(recovery); runCurrent()
            assertTrue(service.state.value.coding.pendingRecoveries.isEmpty(), "Cancelling clears the pending sign-in")
            assertEquals("Вход в Claude Code не завершён.", service.state.value.notice, "A cancelled sign-in reports nothing")
        } finally { service.close(); Dispatchers.resetMain() }
    }

    @Test fun failedRememberedPreferenceKeepsCreatedSessionWithoutPublishingUnsavedEngine() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val fixture = ModelSettingsFixture()
        val checkpoints = JsonCodingProjectRepository(fixture.kv, fixture.json)
        checkpoints.save(CodingProject("project", "Project", "/fixture", 1))
        val commands = object : SettingsCommands {
            override suspend fun runtimePolicy() = fixture.configuration.runtimePolicy()
            override suspend fun selectDefaultCodingEngine(engine: CodingEngine): AppSettings = error("private storage data")
        }
        val service = fixture.prepareCoding(NoopCodingRuntime, checkpoints, settingsCommands = commands)
        try {
            val previous = service.state.value.settings.defaultCodingEngine
            val next = CodingEngine.entries.first { it != previous }
            val before = checkpoints.sessions("project").map { it.id }.toSet()
            val draft = assertNotNull(service.sessionCreationDraft("project"))
            draft.update(next); draft.awaitSaved()
            var created: String? = null
            service.createCodingSession("project") { created = it }
            advanceUntilIdle()
            val id = assertNotNull(created)
            val added = checkpoints.sessions("project").filter { it.id !in before }
            assertEquals(listOf(id), added.map { it.id })
            assertEquals(next, added.single().engine)
            assertEquals(previous, service.state.value.settings.defaultCodingEngine)
            assertEquals(previous, fixture.settings.load().defaultCodingEngine)
            assertContains(service.state.value.notice.orEmpty(), "Сессия создана")
            assertFalse(service.state.value.notice.orEmpty().contains("private"))
            assertNull(fixture.draftRepository.load("coding-session-create:project"))
        } finally { service.close(); Dispatchers.resetMain() }
    }

    @Test fun unconfirmedSettingsRestoreRevokesAccessAndQueuedInputWaitsForAnExplicitRetry() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val fixture = ModelSettingsFixture(); fixture.seed()
        val saved = fixture.settings.load().copy(computerAccess = ComputerAccess.CONTROL, applicationAccess = ComputerAccess.CONTROL)
        fixture.settings.save(saved)
        val checkpoints = JsonCodingProjectRepository(fixture.kv, fixture.json)
        checkpoints.save(CodingProject("project", "Project", "/fixture", 1))
        checkpoints.saveSession(CodingSession("session", "project", "Task", 1, engine = CodingEngine.CODEX, worktreeEnabled = false))
        var ready = false
        val commands = object : SettingsCommands {
            override suspend fun selectDefaultCodingEngine(engine: CodingEngine): AppSettings = error("Unexpected setting write")
            override suspend fun runtimePolicy(): SettingsRuntimePolicy =
                if (ready) SettingsRuntimePolicy.Confirmed(saved) else SettingsRuntimePolicy.Unconfirmed
        }
        val computer = Computer()
        var calls = 0
        val runtime = object : CodingRuntime by NoopCodingRuntime {
            override val supported = true
            override val computerUse = computer
            override suspend fun status() = RuntimeStatus(RuntimePhase.READY)
            override suspend fun status(engine: CodingEngine) = status()
            override fun run(project: CodingProject, session: CodingSession, prompt: String, profile: LlmProfile?, attachments: List<Attachment>) = flow {
                calls++
                emit(CodingEvent.FinalText("Saved reply")); emit(CodingEvent.Finished)
            }
        }
        val service = fixture.prepareCoding(runtime, checkpoints, settingsCommands = commands)
        try {
            runCurrent()
            assertEquals(ComputerAccess.OFF to ComputerAccess.OFF, computer.policy)
            assertNull(computer.state.value.sessionId)
            service.enableComputerUse("session", ComputerAccess.CONTROL); runCurrent()
            assertEquals(0, computer.enables)
            assertEquals(0, computer.previews)
            service.sendCodingPromptTo("session", "Retain this exact input"); advanceUntilIdle()
            val stopped = checkpoints.sessions("project").single()
            assertNull(stopped.pendingRun, "No BeginRun is admitted while settings are unconfirmed")
            assertEquals("Retain this exact input", stopped.queuedPrompts.single().prompt)
            assertEquals(0, calls)
            assertContains(service.state.value.notice.orEmpty(), "Изменение настроек не завершено")
            assertTrue(service.state.value.coding.currentSession!!.canResume)
            ready = true
            advanceUntilIdle()
            assertEquals(0, calls, "Confirming settings cannot replay an accepted input")
            service.resumeCodingSession("session"); advanceUntilIdle()
            assertEquals(1, calls)
            assertTrue(checkpoints.sessions("project").single().queuedPrompts.isEmpty())
        } finally { service.close(); Dispatchers.resetMain() }
    }

    @Test fun executionChecksCurrentOwnerPolicyAfterStartupAndBeforeBeginRun() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val fixture = ModelSettingsFixture(); fixture.seed()
        val checkpoints = JsonCodingProjectRepository(fixture.kv, fixture.json)
        checkpoints.save(CodingProject("project", "Project", "/fixture", 1))
        checkpoints.saveSession(CodingSession("session", "project", "Task", 1, engine = CodingEngine.CODEX, worktreeEnabled = false))
        var ready = true
        val commands = object : SettingsCommands {
            override suspend fun selectDefaultCodingEngine(engine: CodingEngine): AppSettings = error("Unexpected setting write")
            override suspend fun runtimePolicy(): SettingsRuntimePolicy =
                if (ready) SettingsRuntimePolicy.Confirmed(fixture.settings.load()) else SettingsRuntimePolicy.Unconfirmed
        }
        val runtime = object : CodingRuntime by NoopCodingRuntime {
            override fun run(project: CodingProject, session: CodingSession, prompt: String, profile: LlmProfile?, attachments: List<Attachment>): Flow<CodingEvent> =
                error("Unconfirmed settings cannot reach native execution")
        }
        val service = fixture.prepareCoding(runtime, checkpoints, settingsCommands = commands)
        try {
            runCurrent(); ready = false
            service.sendCodingPromptTo("session", "Saved while configuration is interrupted"); advanceUntilIdle()
            val session = checkpoints.sessions("project").single()
            assertNull(session.pendingRun)
            assertEquals("Saved while configuration is interrupted", session.queuedPrompts.single().prompt)
            assertContains(service.state.value.notice.orEmpty(), "Изменение настроек не завершено")
        } finally { service.close(); Dispatchers.resetMain() }
    }

    @Test fun failedLimitApplicationCannotApplyAutomationPolicy() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val fixture = ModelSettingsFixture(); fixture.seed()
        val projects = journalCodingProjects(fixture.kv, fixture.json)
        val organismStore = DefaultSessionOrganismStore(fixture.kv, InMemoryEventJournal(), dispatcher = Dispatchers.Main)
        val organisms = testOrganismService(object : SessionOrganismStore by organismStore {
            override suspend fun loadAll(): List<SessionOrganism> = error("Fixture propagation failure")
        }, projects, fixture.settings)
        val plans = TestPlanningStore(JsonPlanningRepository(fixture.kv, fixture.json))
        val ports = TestPlanningExecutionPorts()
        val execution = PlanningExecutionService(plans, NoopCodingRuntime, projects, fixture.profiles, fixture.settings,
            object : MilestoneVerifier {
                override suspend fun verify(milestone: Milestone, goal: String, report: String, profile: LlmProfile?) = error("Unexpected verification")
            }, workspaces = LocalPlanningWorkspace(), scope = backgroundScope, attemptAuthority = ports, chatHooksProvider = { ports.chatHooks })
        val planning = OrchestrationService(plans, execution, projects, fixture.profiles, fixture.settings,
            textPlanComposer(fixture.gateway), fixture.gateway, backgroundScope, organisms = organisms,
            workerDispatcher = StandardTestDispatcher(testScheduler), modelDossiers = testModelDossiers())
        ports.chatHooks = planning
        val computer = Computer()
        val runtime = object : CodingRuntime by NoopCodingRuntime { override val computerUse = computer }
        val service = DefaultCodingService(fixture.settings, fixture.profiles, fixture.kv, fixture.json,
            runtime, projects, planningChat = planning, usage = fixture.usage, workerDispatcher = Dispatchers.Main,
            settingsCommands = fixture.testSettingsCommands())
        try {
            val previous = fixture.settings.load()
            val next = previous.copy(computerAccess = ComputerAccess.CONTROL, applicationAccess = ComputerAccess.CONTROL)
            service.prepareSettings(previous, next)
            assertEquals(ComputerAccess.OFF to ComputerAccess.OFF, computer.policy)
            val result = service.applySettings(next)
            assertTrue(result.isFailure)
            assertEquals(ComputerAccess.OFF to ComputerAccess.OFF, computer.policy)
            assertEquals(previous, fixture.settings.load(), "The runtime participant never writes configuration")
            assertNull(computer.state.value.sessionId)
        } finally { service.close(); planning.shutdown(); Dispatchers.resetMain() }
    }

    @Test fun policyIsCapturedBeforeSettingsReadAndAnUnchangedPreparationFencesThatEnable() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val fixture = ModelSettingsFixture(); fixture.seed()
        val checkpoints = JsonCodingProjectRepository(fixture.kv, fixture.json)
        checkpoints.save(CodingProject("project", "Project", "/fixture", 1))
        checkpoints.saveSession(CodingSession("session", "project", "Task", 1, engine = CodingEngine.CODEX))
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var delayRead = false
        val commands = object : SettingsCommands {
            override suspend fun selectDefaultCodingEngine(engine: CodingEngine): AppSettings = error("Unexpected write")
            override suspend fun runtimePolicy(): SettingsRuntimePolicy {
                val captured = fixture.configuration.runtimePolicy()
                if (delayRead) { entered.complete(Unit); release.await() }
                return captured
            }
        }
        val computer = Computer()
        val runtime = object : CodingRuntime by NoopCodingRuntime { override val computerUse = computer }
        val service = fixture.prepareCoding(runtime, checkpoints, settingsCommands = commands)
        try {
            runCurrent(); delayRead = true
            service.enableComputerUse("session", ComputerAccess.CONTROL)
            entered.await()
            val previous = computer.capturePolicy()
            val settings = fixture.settings.load()
            service.prepareSettings(settings, settings)
            assertNotEquals(previous, computer.capturePolicy(), "Every application preparation invalidates the old policy, including identical values")
            release.complete(Unit); runCurrent()
            assertEquals(0, computer.enables, "A late Confirmed read cannot replace its earlier computer capture")
            assertEquals(0, computer.previews)
            assertNull(computer.state.value.sessionId)
        } finally { release.complete(Unit); service.close(); Dispatchers.resetMain() }
    }
}
