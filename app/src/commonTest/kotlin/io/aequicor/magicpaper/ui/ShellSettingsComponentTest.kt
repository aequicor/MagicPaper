package io.aequicor.magicpaper.ui

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.destroy
import io.aequicor.magicpaper.data.storage.*
import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.navigation.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.Json
import kotlin.test.*

/** Settings persist through their service; shell navigation is owned by the real root journal. */
@OptIn(ExperimentalCoroutinesApi::class)
class ShellSettingsComponentTest {
    private class Fixture {
        val store = InMemoryKeyValueStore()
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val settings = JsonSettingsRepository(store, json)
        val navigation = object : NavigationSnapshotStore {
            private var value: String? = null
            override suspend fun load() = value
            override suspend fun save(snapshot: String) { value = snapshot }
        }
        val service = DefaultSettingsService(
            configuration = DefaultSettingsConfiguration(store, InMemoryEventJournal(), store.secrets, json, dispatcher = Dispatchers.Main),
            chats = JsonChatRepository(store, json),
            bridge = object : ProfileBridge {
                override val supportsFilePicker = false
                override suspend fun export(json: String) = false
                override suspend fun import(): String? = null
            },
            store = store, json = json, pluginPreferences = DefaultPluginService(io.aequicor.magicpaper.plugins.PluginRegistry(), store, InMemoryEventJournal(), json, storageDispatcher = Dispatchers.Main), chatHistory = object : ChatHistoryCommands {
                override suspend fun importNotebooks(sessions: List<ChatSession>) = error("Unexpected history import")
                override suspend fun unlinkProfile(profileId: String) = error("Unexpected history update")
                override suspend fun wipeHistory() = error("Unexpected history reset")
            }, usage = UsageLedger(JsonUsageRepository(store, json), InMemoryEventJournal(), store, json),
        )
        suspend fun start() { settings.save(AppSettings(onboardingDone = true)); service.start() }
    }

    @Test fun explicitAgentLimitsPersistAndCanBeRemovedWithoutChangingOtherSettings() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val fixture = Fixture()
        try {
            fixture.start()
            val initial = fixture.service.state.value.settings
            val configured = initial.copy(agentLimits = OrganismLimits(tokens = 900_000,
                durationMillis = 9_000_000, activeSessions = 4, depth = 3, retries = 0,
                queueSize = 20, contextCharacters = 30_000))
            fixture.service.saveSettings(configured)
            advanceUntilIdle()
            assertEquals(configured, fixture.settings.load())
            assertEquals(configured, fixture.service.state.value.settings)
            fixture.service.saveSettings(configured.copy(agentLimits = OrganismLimits()))
            advanceUntilIdle()
            assertEquals(initial, fixture.settings.load())
            assertEquals(initial, fixture.service.state.value.settings)
        } finally { fixture.service.close(); Dispatchers.resetMain() }
    }

    @Test fun welcomeGatePreservesPendingDeepLinkAndSettingsWritesDoNotReplaceHistory() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val fixture = Fixture()
        val lifecycle = LifecycleRegistry()
        try {
            fixture.start()
            val root = DefaultRootComponent(DefaultComponentContext(lifecycle), fixture.navigation,
                FeatureComponentFactory { visit, _, _ -> visit.route }, initialWelcomeRequired = false)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                fixture.service.state.collect { root.setWelcomeRequired(it.showWelcome) }
            }
            root.navigate(AppRoute.Settings()); root.awaitIdle()
            assertEquals(AppRoute.Settings(), root.stack.value.active.instance)
            val saved = fixture.service.state.value.settings.copy(hideSystemSteps = true)
            fixture.service.saveSettings(saved)
            advanceUntilIdle(); root.awaitIdle()
            assertEquals(saved, fixture.settings.load())
            assertEquals("Настройки сохранены.", fixture.service.state.value.notice)
            assertEquals(AppRoute.Settings(), root.navigationState.value.route)
            fixture.service.restartOnboarding(); root.awaitIdle()
            assertTrue(root.navigationState.value.welcomeRequired)
            root.handleDeepLink("magicpaper://docs/guide"); root.awaitIdle()
            assertEquals(AppRoute.Settings(), root.navigationState.value.route)
            fixture.service.finishOnboarding(saved)
            advanceUntilIdle(); root.awaitIdle()
            assertFalse(root.navigationState.value.welcomeRequired)
            assertEquals(AppRoute.Docs("guide"), root.navigationState.value.route)
            assertTrue(fixture.settings.load().onboardingDone)
            root.back(); root.awaitIdle()
            assertEquals(AppRoute.Settings(), root.navigationState.value.route)
        } finally { lifecycle.destroy(); fixture.service.close(); Dispatchers.resetMain() }
    }
}
