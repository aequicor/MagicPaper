package io.aequicor.magicpaper.di

import io.aequicor.magicpaper.data.storage.*
import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.ui.*
import io.aequicor.magicpaper.navigation.AppChild
import androidx.compose.runtime.Composable
import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.doOnDestroy
import com.arkivanov.essenty.lifecycle.resume
import com.arkivanov.essenty.lifecycle.destroy
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class RuntimeLifecycleTest {
    private suspend fun createChatAndAwaitCommit(chat: ChatService) {
        val previous = chat.state.value.sessions.map { it.id }.toSet()
        chat.newSession()
        // The real journal commits on its own dispatcher; advancing virtual Main cannot flush it.
        withContext(Dispatchers.Default) { withTimeout(5_000) {
            chat.state.first { state -> state.sessions.any { it.id !in previous } }
        } }
    }
    private val bridge = object : ProfileBridge {
        override val supportsFilePicker = true
        override suspend fun export(json: String) = true
        override suspend fun import(): String? = null
    }

    @Test fun nativeResetTracksAttemptedOwnersAndResumesEveryParticipantDespiteFailure() = runTest {
        val events = mutableListOf<String>()
        val computerFailure = IllegalStateException("computer resume")
        val cancellation = CancellationException("native resume")
        var fail = true
        val feature = ResetFeature(events)
        val computer = ResetComputer(events) { if (fail) throw computerFailure }
        val extension = NativeRuntimeExtension(feature, computer,
            { events += "native.pause" }, { events += "native.resume"; if (fail) throw cancellation })
        extension.prepareForReset(); extension.pauseForReset()
        val result = assertFailsWith<CancellationException> { extension.resumeAfterReset() }
        assertSame(cancellation, result)
        assertTrue(computerFailure in result.suppressed)
        assertEquals(listOf("feature.prepare", "feature.pause", "native.pause", "computer.pause",
            "computer.resume", "native.resume", "feature.resume"), events)
        fail = false
        events.clear()
        extension.resumeAfterReset()
        assertEquals(listOf("computer.resume", "native.resume"), events,
            "A successful participant is not resumed twice when a sibling needs explicit retry")
    }

    @Test fun consentedPauseDiscardsUnresolvableChecksBeforeTheFeatureReconcilesSessions() = runTest {
        val events = mutableListOf<String>()
        val extension = NativeRuntimeExtension(ResetFeature(events), ResetComputer(events),
            { events += "native.pause" }, { events += "native.resume" }, { events += "checks.discard" })
        extension.prepareForReset(); extension.pauseForReset(discardUnresolvable = true)
        assertEquals(listOf("feature.prepare", "checks.discard", "feature.pause", "native.pause", "computer.pause"), events)
        extension.resumeAfterReset(); events.clear()
        extension.prepareForReset(); extension.pauseForReset()
        assertFalse("checks.discard" in events, "without the user's consent no evidence is discarded")
    }

    @Test fun failedNativePauseDoesNotResumeAnUntouchedComputerOwner() = runTest {
        val events = mutableListOf<String>()
        val failure = IllegalStateException("native pause")
        val extension = NativeRuntimeExtension(ResetFeature(events), ResetComputer(events),
            { events += "native.pause"; throw failure }, { events += "native.resume" })
        extension.prepareForReset()
        assertSame(failure, assertFailsWith<IllegalStateException> { extension.pauseForReset() })
        extension.resumeAfterReset()
        assertEquals(listOf("feature.prepare", "feature.pause", "native.pause", "native.resume", "feature.resume"), events)
    }

    @Test fun applicationResetResumesLaterOwnersAndCompletesNavigationAfterAnEarlierResumeFails() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val events = mutableListOf<String>()
        val first = ResetExtension("first", events, failResume = true)
        val second = ResetExtension("second", events)
        val runtime = buildRuntime(InMemoryKeyValueStore(), persistenceStores(InMemoryDurableByteStore()), bridge,
            NavigationSessionConfig(), runtimeExtensions = { listOf(first, second) })
        try {
            runtime.start()
            assertEquals(RuntimeState.Ready, runtime.ready.first { it != RuntimeState.Loading })
            val resetFinished = CompletableDeferred<Unit>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                runtime.koin.get<NavigationEvents>().events.collect { event -> when (event) {
                    is NavigationEvents.Event.Reset -> event.completed.complete(Unit)
                    is NavigationEvents.Event.ResetComplete -> { event.completed.complete(Unit); resetFinished.complete(Unit) }
                    else -> Unit
                } }
            }
            events.clear()
            runtime.koin.get<SettingsService>().wipeAll()
            resetFinished.await(); runCurrent()
            assertTrue("first.pause(discard)" in events, "a user-confirmed erase carries its consent to every owner")
            assertTrue("first.resume" in events)
            assertTrue("second.resume" in events)
            assertTrue("second.reload" in events)
            assertFalse("first.reload" in events, "A failed owner must not be reloaded as if its recovery succeeded")
            val notice = runtime.koin.get<SettingsService>().state.first { it.notice != null }.notice
            assertEquals("Не удалось завершить удаление данных. Повторите действие.", notice)
            createChatAndAwaitCommit(runtime.koin.get<ChatService>())
            assertEquals(1, runtime.koin.get<ChatRepository>().sessions().size)
        } finally { runtime.close(); runtime.awaitClosed(); Dispatchers.resetMain() }
    }

    @Test fun shutdownContinuesThroughFeatureAndPlatformFailures() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val events = mutableListOf<String>()
        val extension = ResetExtension("feature", events, failClose = true)
        val runtime = buildRuntime(InMemoryKeyValueStore(), persistenceStores(InMemoryDurableByteStore()), bridge,
            NavigationSessionConfig(), runtimeExtensions = { listOf(extension) },
            onPlatformClosed = { events += "platform.close"; error("platform cleanup") })
        try {
            runtime.start()
            assertEquals(RuntimeState.Ready, runtime.ready.first { it != RuntimeState.Loading })
            runtime.close(); runtime.awaitClosed()
            assertEquals(listOf("feature", "platform"), runtime.shutdownErrors.value)
            assertTrue("feature.close" in events && "platform.close" in events)
        } finally { runtime.close(); runtime.awaitClosed(); Dispatchers.resetMain() }
    }

    @Test fun withoutAPlatformAssemblyNoExecutableCodingOwnerIsRegistered() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val runtime = buildRuntime(InMemoryKeyValueStore(), persistenceStores(InMemoryDurableByteStore()), bridge, NavigationSessionConfig())
        try {
            runtime.start()
            assertEquals(RuntimeState.Ready, runtime.ready.first { it != RuntimeState.Loading })
            assertTrue(runtime.koin.get<RuntimeExtensions>().owners.isEmpty())
            assertNull(runtime.koin.getOrNull<CodingService>())
            assertNull(runtime.koin.getOrNull<CodingComponent.Factory>(FeatureFactoryQualifiers.coding))
            assertNull(runtime.koin.getOrNull<CodingProjectRepository>())
            assertNull(runtime.koin.getOrNull<PlanningRepository>())
            assertSame(runtime.koin.get<GatewaySessionRuntime>(), runtime.koin.get<ChatBackend>())
        } finally { runtime.close(); runtime.awaitClosed(); Dispatchers.resetMain() }
    }

    @Test fun koinOwnersAreSingletonsAndResetKeepsThemUsable() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val store = InMemoryKeyValueStore()
        val persistence = persistenceStores(InMemoryDurableByteStore())
        var starts = 0
        var closes = 0
        val nativeReset = mutableListOf<String>()
        val computer = io.aequicor.magicpaper.data.computer.DesktopComputerUse(persistence.events)
        val runtime = buildRuntime(store, persistence, bridge, NavigationSessionConfig(),
            platformDefinitions = { scope -> nativeRuntimeBindings(scope, LifecycleTestRuntime(), null,
                LocalPlanningWorkspace(), UnavailableTaskWorkspace, null) },
            runtimeExtensions = { listOf(NativeRuntimeExtension(get(), computer,
                { nativeReset += "pause" }, { nativeReset += "resume" })) },
            featurePlugins = { get<CodingFeature>().plugins },
            onPlatformStarted = { starts++ }, onPlatformClosed = { closes++; computer.close() })
        try {
            runtime.start()
            runtime.start()
            assertEquals(RuntimeState.Ready, runtime.ready.first { it != RuntimeState.Loading })
            assertEquals(1, starts)
            val chat = runtime.koin.get<ChatService>()
            assertSame(chat, runtime.koin.get<DefaultChatService>())
            assertSame(runtime.koin.get<CodingService>(), runtime.koin.get<CodingFeature>().service)
            assertSame(runtime.koin.get<GatewaySessionRuntime>(), runtime.koin.get<ChatBackend>(),
                "Installing the native feature must not replace ordinary chat's provider tool owner")
            val settings = runtime.koin.get<SettingsService>()
            assertSame(settings, runtime.koin.get<DefaultSettingsService>())
            assertIs<DefaultChatComponentFactory>(runtime.koin.get<ChatComponent.Factory>(FeatureFactoryQualifiers.chat))
            assertIs<DefaultCodingComponentFactory>(runtime.koin.get<CodingComponent.Factory>(FeatureFactoryQualifiers.coding))
            assertIs<DefaultSettingsComponentFactory>(runtime.koin.get<SettingsComponent.Factory>(FeatureFactoryQualifiers.settings))
            assertIs<DefaultDocsComponentFactory>(runtime.koin.get<DocsComponent.Factory>(FeatureFactoryQualifiers.docs))
            assertIs<DefaultPluginsComponentFactory>(runtime.koin.get<PluginsComponent.Factory>(FeatureFactoryQualifiers.plugins))
            assertIs<DefaultSkillsComponentFactory>(runtime.koin.get<SkillsComponent.Factory>(FeatureFactoryQualifiers.skills))
            val feature = runtime.koin.get<CodingFeature>()
            assertSame(runtime.koin.get<CodingRuntime>(), runtime.koin.get<CodingRuntime>())
            val resetFinished = CompletableDeferred<Unit>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                runtime.koin.get<NavigationEvents>().events.collect { event ->
                    when (event) {
                        is NavigationEvents.Event.Reset -> event.completed.complete(Unit)
                        is NavigationEvents.Event.ResetComplete -> {
                            event.completed.complete(Unit)
                            resetFinished.complete(Unit)
                        }
                        else -> Unit
                    }
                }
            }
            createChatAndAwaitCommit(chat)
            assertEquals(1, runtime.koin.get<ChatRepository>().sessions().size)
            val coding = runtime.koin.get<CodingService>()
            (runtime.koin.get<CodingProjectRepository>() as CodingProjectOwner).dispatch("creation",
                CodingMachine.Intent.CreateProject(CodingProject("creation", "Проект", "/test/creation", 1)))
            coding.reload()
            val engineDraft = assertNotNull(coding.sessionCreationDraft("creation"))
            engineDraft.update(CodingEngine.CODEX); engineDraft.awaitSaved()
            val dormant = DraftSession(persistence.drafts, "coding-session-create:unopened", CodingEngine.serializer(),
                CodingEngine.PI, backgroundScope)
            dormant.update(CodingEngine.CODEX); dormant.awaitSaved()
            settings.wipeAll()
            resetFinished.await()
            assertEquals(listOf("pause", "resume"), nativeReset)
            assertTrue(runtime.koin.get<ChatRepository>().sessions().isEmpty())
            engineDraft.update(CodingEngine.CODEX)
            dormant.update(CodingEngine.CODEX)
            runCurrent()
            assertTrue(persistence.drafts.keys("coding-session-create:").isEmpty())
            assertNull(coding.sessionCreationDraft("creation"))
            assertSame(chat, runtime.koin.get<ChatService>())
            assertSame(feature, runtime.koin.get<CodingFeature>())
            (feature.service as DefaultCodingService).planningChat?.awaitReady()
            createChatAndAwaitCommit(chat)
            assertEquals(1, runtime.koin.get<ChatRepository>().sessions().size,
                "Reset keeps the application-owned supervisor usable")
            runtime.close()
            runtime.close()
            runtime.awaitClosed()
            assertEquals(RuntimeState.Closed, runtime.ready.value)
            assertEquals(1, closes)
        } finally {
            runtime.close()
            runtime.awaitClosed()
            Dispatchers.resetMain()
        }
    }

    @Test fun failedStorageDoesNotStartServicesOrExposeBackendValues() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        var started = false
        var closed = false
        val store = object : KeyValueStore {
            override val description = "test"
            override fun read(key: String): String? = error("private-backend-value")
            override fun write(key: String, value: String) = error("private-backend-value")
            override fun delete(key: String) = Unit
            override fun keys(prefix: String): List<String> = emptyList()
            override fun clear() = Unit
        }
        val runtime = buildRuntime(store, persistenceStores(InMemoryDurableByteStore()), bridge,
            NavigationSessionConfig(), onPlatformStarted = { started = true }, onPlatformClosed = { closed = true })
        try {
            runtime.start()
            val failure = runtime.ready.first { it is RuntimeState.Failed } as RuntimeState.Failed
            assertFalse(started)
            assertFalse(failure.message.contains("private-backend-value"))
            runtime.close()
            runtime.awaitClosed()
            assertTrue(closed)
        } finally {
            runtime.close()
            runtime.awaitClosed()
            Dispatchers.resetMain()
        }
    }

    @Test fun closeFailureIsObservableAndDoesNotRepeatPlatformCleanup() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        var closes = 0
        val runtime = buildRuntime(InMemoryKeyValueStore(), persistenceStores(InMemoryDurableByteStore()), bridge,
            NavigationSessionConfig(), onPlatformClosed = { closes++; error("private-cleanup-value") })
        try {
            runtime.start()
            assertEquals(RuntimeState.Ready, runtime.ready.first { it != RuntimeState.Loading })
            runtime.close()
            runtime.awaitClosed()
            runtime.close()
            runtime.awaitClosed()
            assertEquals(RuntimeState.Closed, runtime.ready.value)
            assertEquals(listOf("platform"), runtime.shutdownErrors.value)
            assertEquals(1, closes)
        } finally {
            runtime.close()
            runtime.awaitClosed()
            Dispatchers.resetMain()
        }
    }

    @Test fun failedScreenCreationDisposesPartialAttemptBeforeExplicitRetry() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val runtime = buildRuntime(InMemoryKeyValueStore(), persistenceStores(InMemoryDurableByteStore()), bridge,
            NavigationSessionConfig())
        val lifecycle = LifecycleRegistry().apply { resume() }
        var attempts = 0
        var destroyed = 0
        try {
            runtime.start()
            assertEquals(RuntimeState.Ready, runtime.ready.first { it != RuntimeState.Loading })
            val child = AppChild(DefaultComponentContext(lifecycle), runtime) { context ->
                attempts++
                context.lifecycle.doOnDestroy { destroyed++ }
                if (attempts == 1) error("private-factory-value")
                val render: @Composable () -> Unit = {}
                render
            }
            runCurrent()
            assertEquals(1, attempts)
            assertEquals(1, destroyed)
            assertFalse(assertNotNull(child.creationFailure.value).contains("private-factory-value"))
            child.retry()
            runCurrent()
            assertEquals(2, attempts)
            assertEquals(1, destroyed)
            assertNull(child.creationFailure.value)
            lifecycle.destroy()
            assertEquals(2, destroyed)
        } finally {
            lifecycle.destroy()
            runtime.close(); runtime.awaitClosed()
            Dispatchers.resetMain()
        }
    }
}

private class ResetExtension(override val id: String, private val events: MutableList<String>,
    private val failResume: Boolean = false, private val failClose: Boolean = false) : RuntimeExtension {
    override suspend fun start() = Unit
    override fun updateConfiguration(state: SettingsState) = Unit
    override suspend fun reload() { events += "$id.reload" }
    override suspend fun clearProfileOverrides(profileId: String) = Unit
    override suspend fun prepareForReset() { events += "$id.prepare" }
    override suspend fun pauseForReset(discardUnresolvable: Boolean) { events += if (discardUnresolvable) "$id.pause(discard)" else "$id.pause" }
    override suspend fun clearForReset() { events += "$id.clear" }
    override suspend fun resumeAfterReset() { events += "$id.resume"; if (failResume) error("private resume") }
    override suspend fun close() { events += "$id.close"; if (failClose) error("private close") }
}

private class ResetFeature(private val events: MutableList<String>) : CodingFeature {
    override val projects: CodingProjectRepository get() = error("Unexpected project access")
    override val planning: PlanningRepository get() = error("Unexpected planning access")
    override val runtime: CodingRuntime get() = error("Unexpected native access")
    override val models: CodingModelCatalog get() = error("Unexpected model catalog access")
    override val service: CodingService get() = error("Unexpected service access")
    override val componentFactory: CodingComponent.Factory get() = error("Unexpected UI access")
    override val presentation: io.aequicor.magicpaper.ui.components.CodingPresentation get() = error("Unexpected UI access")
    override val plugins: List<io.aequicor.magicpaper.plugins.MagicPlugin> = emptyList()
    override suspend fun prepareForReset() { events += "feature.prepare" }
    override suspend fun pauseForReset() { events += "feature.pause" }
    override suspend fun resumeAfterReset() { events += "feature.resume" }
}

private class ResetComputer(private val events: MutableList<String>, private val resume: () -> Unit = {}) : NativeComputerUse {
    override val supported = false
    override val state = kotlinx.coroutines.flow.MutableStateFlow(ComputerUseState())
    override suspend fun prepareForReset() { events += "computer.pause" }
    override suspend fun resumeAfterReset() { events += "computer.resume"; resume() }
    override suspend fun begin(sessionId: String, requestId: String): ComputerLease? = error("Unexpected grant")
    override fun grant(sessionId: String): ComputerLease? = error("Unexpected grant")
    override fun release(lease: ComputerLease) = error("Unexpected release")
    override fun endpoint(lease: ComputerLease, requestId: String): ComputerEndpoint = error("Unexpected endpoint")
    override fun capturePolicy(): ComputerPolicyRef? = error("Unexpected permission capture")
    override fun configure(computer: ComputerAccess, application: ComputerAccess) = Unit
    override fun invalidatePolicy() = Unit
    override suspend fun enable(sessionId: String, access: ComputerAccess, expectedPolicy: ComputerPolicyRef): Boolean = error("Unexpected permission")
    override fun disable(sessionId: String?) = error("Unexpected permission")
    override suspend fun preview(sessionId: String) = error("Unexpected preview")
    override fun openSystemSettings() = error("Unexpected OS operation")
    override fun close() = Unit
}

/** Isolated executable fixture: starting the application must never invoke a native run. */
private class LifecycleTestRuntime : CodingRuntime {
    override val supported = true
    override val rootPath = "/test/runtime"
    override suspend fun status() = RuntimeStatus(RuntimePhase.READY)
    override fun ensureReady() = kotlinx.coroutines.flow.flowOf(RuntimeStatus(RuntimePhase.READY))
    override fun run(project: CodingProject, session: CodingSession, prompt: String, profile: LlmProfile?,
        attachments: List<Attachment>) = kotlinx.coroutines.flow.flow<CodingEvent> { error("Unexpected native run") }
    override fun abort(sessionId: String) = Unit
    override fun abortAll() = Unit
    override suspend fun uninstall() = Unit
}
