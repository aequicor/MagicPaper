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
    private val bridge = object : ProfileBridge {
        override val supportsFilePicker = true
        override suspend fun export(json: String) = true
        override suspend fun import(): String? = null
    }

    @Test fun withoutAPlatformAssemblyEveryCodingBindingResolvesAsUnavailable() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val runtime = buildRuntime(InMemoryKeyValueStore(), persistenceStores(InMemoryDurableByteStore()), bridge, NavigationSessionConfig())
        try {
            runtime.start()
            assertEquals(RuntimeState.Ready, runtime.ready.first { it != RuntimeState.Loading })
            // A missing binding would surface as a retry card for an action that can never succeed.
            assertSame(UnavailableCodingService, runtime.koin.get<CodingService>())
            assertSame(UnsupportedCodingComponentFactory, runtime.koin.get<CodingComponent.Factory>(FeatureFactoryQualifiers.coding))
            assertSame(UnavailableCodingProjectRepository, runtime.koin.get<CodingProjectRepository>())
            assertSame(UnavailablePlanningRepository, runtime.koin.get<PlanningRepository>())
            assertTrue(runtime.koin.get<CodingProjectRepository>().all().isEmpty())
        } finally { runtime.close(); runtime.awaitClosed(); Dispatchers.resetMain() }
    }

    @Test fun koinOwnersAreSingletonsAndResetKeepsThemUsable() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val store = InMemoryKeyValueStore()
        val persistence = persistenceStores(InMemoryDurableByteStore())
        var starts = 0
        var closes = 0
        val runtime = buildRuntime(store, persistence, bridge, NavigationSessionConfig(),
            coding = ::codingFeature, onPlatformStarted = { starts++ }, onPlatformClosed = { closes++ })
        try {
            runtime.start()
            runtime.start()
            assertEquals(RuntimeState.Ready, runtime.ready.first { it != RuntimeState.Loading })
            assertEquals(1, starts)
            val chat = runtime.koin.get<ChatService>()
            assertSame(chat, runtime.koin.get<DefaultChatService>())
            assertSame(runtime.koin.get<CodingService>(), runtime.koin.get<CodingFeature>().service)
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
            chat.newSession()
            runCurrent()
            assertEquals(1, runtime.koin.get<ChatRepository>().sessions().size)
            val coding = runtime.koin.get<CodingService>()
            runtime.koin.get<CodingProjectRepository>().save(CodingProject("creation", "Проект", "/test/creation", 1))
            coding.reload()
            val engineDraft = assertNotNull(coding.sessionCreationDraft("creation"))
            engineDraft.update(CodingEngine.CODEX); engineDraft.awaitSaved()
            val dormant = DraftSession(persistence.drafts, "coding-session-create:unopened", CodingEngine.serializer(),
                CodingEngine.PI, backgroundScope)
            dormant.update(CodingEngine.CODEX); dormant.awaitSaved()
            settings.wipeAll()
            resetFinished.await()
            assertTrue(runtime.koin.get<ChatRepository>().sessions().isEmpty())
            engineDraft.update(CodingEngine.CODEX)
            dormant.update(CodingEngine.CODEX)
            runCurrent()
            assertTrue(persistence.drafts.keys("coding-session-create:").isEmpty())
            assertNull(coding.sessionCreationDraft("creation"))
            assertSame(chat, runtime.koin.get<ChatService>())
            assertSame(feature, runtime.koin.get<CodingFeature>())
            (feature.service as DefaultCodingService).planningChat?.awaitReady()
            chat.newSession()
            runCurrent()
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
