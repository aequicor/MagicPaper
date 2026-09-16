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
        val settings = JsonSettingsRepository(store, json)
        val drafts = InMemoryDraftRepository()
        var fail = false
        val service = DefaultSettingsService(settings, JsonLlmProfileRepository(store, json), JsonChatRepository(store, json),
            object : ProfileBridge {
                override val supportsFilePicker = false
                override suspend fun export(json: String) = false
                override suspend fun import(): String? = null
            }, store, json, usage = object : UsageLedger {
                override val state = MutableStateFlow(UsageArchive())
                override val failure = MutableStateFlow<String?>(null)
                override suspend fun record(record: UsageRecord, replacesId: String?) = error("unexpected usage")
                override suspend fun context(snapshot: ContextUsageSnapshot) = error("unexpected usage")
                override suspend fun cumulative(key: String, fingerprint: String, total: TokenUsage, last: TokenUsage, record: UsageRecord) = error("unexpected usage")
                override suspend fun replace(archive: UsageArchive) = error("unexpected usage")
                override suspend fun clear() = error("unexpected usage")
                override suspend fun <T> measure(profile: LlmProfile, block: suspend () -> T): T = error("unexpected usage")
            }, applyRuntimeSettings = { if (fail) error("private persistence failure"); settings.save(it); Result.success(Unit) }, draftRepository = drafts)
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
            fail = true
            service.saveComputerAccess(ComputerAccess.OFF, ComputerAccess.OFF)
            service.state.first { !it.settingsSaving }
            assertEquals(ComputerAccess.CONTROL, service.state.value.settings.applicationAccess)
            assertEquals(before, drafts.load(SettingsDrafts.SETTINGS))
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
