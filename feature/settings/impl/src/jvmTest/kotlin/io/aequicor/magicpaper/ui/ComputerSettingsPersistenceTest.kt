package io.aequicor.magicpaper.ui

import io.aequicor.magicpaper.data.storage.*
import io.aequicor.magicpaper.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.Json
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ComputerSettingsPersistenceTest {
    @Test fun accessSavePreservesOverviewDraftAndFailedSaveDoesNotShowSuccess() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val store = InMemoryKeyValueStore()
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        var fail = false
        val secrets = object : SecretStore by store.secrets {
            override suspend fun write(reference: String, value: String) {
                if (fail) throw StorageException("controlled credential write", StorageException.Kind.WRITE)
                store.secrets.write(reference, value)
            }
        }
        val settings = JsonSettingsRepository(store, json, secrets)
        settings.save(AppSettings(googleApiKey = "stored-test-key"))
        val drafts = InMemoryDraftRepository()
        var prepares = 0
        val configuration = DefaultSettingsConfiguration(store, InMemoryEventJournal(), secrets, json,
            runtime = object : SettingsRuntimeParticipant {
                override suspend fun prepare(previous: AppSettings, next: AppSettings) { prepares++ }
                override suspend fun apply(settings: AppSettings) = Unit
            }, dispatcher = Dispatchers.Main)
        val service = DefaultSettingsService(configuration, JsonChatRepository(store, json),
            object : ProfileBridge {
                override val supportsFilePicker = false
                override suspend fun export(json: String) = false
                override suspend fun import(): String? = null
            }, store, json, pluginPreferences = TestPluginPreferences(), chatHistory = object : ChatHistoryCommands {
                override suspend fun importNotebooks(sessions: List<ChatSession>) = error("Unexpected history import")
                override suspend fun unlinkProfile(profileId: String) = error("Unexpected history update")
                override suspend fun wipeHistory() = error("Unexpected history reset")
            }, usage = object : UsageLedger {
                override val state = MutableStateFlow(UsageArchive())
                override val failure = MutableStateFlow<String?>(null)
                override suspend fun start() = Unit
                override suspend fun captureObservation(): UsageObservation = error("Unexpected usage capture")
                override suspend fun exportArchive() = state.value
                override suspend fun record(observation: UsageObservation, record: UsageRecord, replacesId: String?) = error("unexpected usage")
                override suspend fun context(observation: UsageObservation, snapshot: ContextUsageSnapshot) = error("unexpected usage")
                override suspend fun cumulative(observation: UsageObservation, key: String, fingerprint: String, total: TokenUsage, last: TokenUsage, record: UsageRecord) = error("unexpected usage")
                override suspend fun replace(archive: UsageArchive) = error("unexpected usage")
                override suspend fun clear() = error("unexpected usage")
                override suspend fun <T> measure(profile: LlmProfile, block: suspend () -> T): T = error("unexpected usage")
            }, draftRepository = drafts)
        try {
            service.start()
            val form = service.drafts.settings(service.state.value.settings)
            form.update { it.copy(settings = it.settings!!.copy(queritApiKey = "unsaved"), fields = mapOf("raw" to "invalid")) }
            form.awaitSaved()
            val before = drafts.load(SettingsDrafts.SETTINGS)
            service.saveComputerAccess(ComputerAccess.SCREEN, ComputerAccess.CONTROL)
            service.state.first { !it.settingsSaving }
            assertEquals(before, drafts.load(SettingsDrafts.SETTINGS))
            assertEquals("", settings.load().queritApiKey)
            assertEquals(ComputerAccess.CONTROL, settings.load().applicationAccess)
            val preparedBeforeFailure = prepares
            fail = true
            service.saveComputerAccess(ComputerAccess.OFF, ComputerAccess.OFF)
            service.state.first { !it.settingsSaving }
            assertEquals(ComputerAccess.CONTROL, service.state.value.settings.applicationAccess)
            assertEquals(before, drafts.load(SettingsDrafts.SETTINGS))
            assertEquals(preparedBeforeFailure, prepares, "A failed credential write cannot start runtime preparation")
            assertFalse(configuration.state.value.unknown, "Failure before Begin leaves the previous configuration known")
            assertTrue(service.state.value.notice!!.startsWith("Не удалось сохранить"))
            assertFalse(service.state.value.notice!!.contains("private"))
            fail = false
            service.saveOverviewSettings(form.state.value.value.settings!!)
            service.state.first { !it.settingsSaving }
            assertEquals(ComputerAccess.SCREEN, settings.load().computerAccess)
            assertEquals(ComputerAccess.CONTROL, settings.load().applicationAccess)
            assertEquals("unsaved", settings.load().queritApiKey)
        } finally { service.close(); Dispatchers.resetMain() }
    }
}
