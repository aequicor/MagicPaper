package io.aequicor.magicpaper.ui

import io.aequicor.magicpaper.data.storage.*
import io.aequicor.magicpaper.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.Json
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsSkillImportTest {
    @Test fun filePickerCannotRecaptureSkillRevisionAfterAnotherOwnerClearsTheLibrary() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val json = Json { encodeDefaults = true }
        val values = InMemoryKeyValueStore()
        val before = SkillCatalogRevision("before", 1)
        val after = SkillCatalogRevision("after-reset", 2)
        var submitted: SkillCatalogRevision? = null
        val commands = object : SkillCommands {
            override val catalog = MutableStateFlow(SkillCatalogSnapshot(initialized = true, revision = before))
            override suspend fun start() = Unit
            override suspend fun reload() = Unit
            override suspend fun install(skill: Skill, expected: SkillInstallBasis): SkillInstallOutcome = error("Unexpected install")
            override suspend fun setEnabled(expected: SkillRef, enabled: Boolean): Unit = error("Unexpected toggle")
            override suspend fun delete(expected: SkillRef): Unit = error("Unexpected delete")
            override suspend fun importSkills(skills: List<Skill>, expected: SkillCatalogRevision) {
                submitted = expected
                check(expected == catalog.value.revision) { "Stale import" }
            }
            override suspend fun clearSkills(expected: SkillCatalogRevision): Unit = error("Unexpected clear")
            override suspend fun prepareForReset() = Unit
            override suspend fun finishReset() = Unit
        }
        val entered = CompletableDeferred<Unit>()
        val file = CompletableDeferred<String>()
        val service = DefaultSettingsService(
            DefaultSettingsConfiguration(values, InMemoryEventJournal(), values.secrets, json, dispatcher = Dispatchers.Main),
            JsonChatRepository(values, json), object : ProfileBridge {
                override val supportsFilePicker = true
                override suspend fun export(json: String) = false
                override suspend fun import(): String { entered.complete(Unit); return file.await() }
            }, values, json, skills = object : SkillRepository { override suspend fun all() = emptyList<Skill>() },
            skillCommands = commands, usage = object : UsageLedger {
                override val state = MutableStateFlow(UsageArchive())
                override val failure = MutableStateFlow<String?>(null)
                override suspend fun start() = Unit
                override suspend fun captureObservation(): UsageObservation = error("Unexpected capture")
                override suspend fun exportArchive() = state.value
                override suspend fun record(observation: UsageObservation, record: UsageRecord, replacesId: String?) = error("Unexpected usage")
                override suspend fun context(observation: UsageObservation, snapshot: ContextUsageSnapshot) = error("Unexpected usage")
                override suspend fun cumulative(observation: UsageObservation, key: String, fingerprint: String, total: TokenUsage, last: TokenUsage, record: UsageRecord) = error("Unexpected usage")
                override suspend fun replace(archive: UsageArchive) { state.value = archive }
                override suspend fun clear() = Unit
                override suspend fun <T> measure(profile: LlmProfile, block: suspend () -> T): T = error("Unexpected model call")
            }, pluginPreferences = TestPluginPreferences(), chatHistory = object : ChatHistoryCommands {
                override suspend fun importNotebooks(sessions: List<ChatSession>) = Unit
                override suspend fun unlinkProfile(profileId: String) = error("Unexpected profile")
                override suspend fun wipeHistory() = Unit
            })
        try {
            service.importProfile()
            entered.await()
            commands.catalog.value = commands.catalog.value.copy(revision = after)
            file.complete(json.encodeToString(ProfileBundle.serializer(), ProfileBundle(exportedAt = 1,
                settings = AppSettings(), plugins = emptyList(), sessions = emptyList(),
                skills = listOf(Skill("old", "Old", "description", "instruction")))))
            service.state.first { !it.settingsSaving }
            assertEquals(before, submitted)
            assertEquals(after, commands.catalog.value.revision)
            assertTrue(service.state.value.notice.orEmpty().startsWith("Не удалось завершить импорт"))
        } finally { file.complete(""); service.close(); Dispatchers.resetMain() }
    }
}
