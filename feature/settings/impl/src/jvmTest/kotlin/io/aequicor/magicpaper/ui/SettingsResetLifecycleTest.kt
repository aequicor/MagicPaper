package io.aequicor.magicpaper.ui

import io.aequicor.magicpaper.data.storage.*
import io.aequicor.magicpaper.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.Json
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsResetLifecycleTest {
    @Test fun cancellationSurvivesFailedResumeAndCleanupRunsInAnActiveContext() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val cancellation = CancellationException("cancel reset")
        val cleanup = IllegalStateException("private cleanup detail")
        var completion: Throwable? = null
        var cleanupRan = false
        val service = service(clear = {
            currentCoroutineContext().job.invokeOnCompletion { completion = it }
            throw cancellation
        }, finish = {
            assertTrue(currentCoroutineContext().isActive)
            cleanupRan = true
            throw cleanup
        })
        try {
            service.wipeAll(); runCurrent()
            assertTrue(cleanupRan)
            assertSame(cancellation, completion)
            assertTrue(cleanup in cancellation.suppressed)
            assertNotEquals("Все данные удалены.", service.state.value.notice)
        } finally { service.close(); Dispatchers.resetMain() }
    }

    @Test fun successfulClearWithFailedResumeShowsFailureInsteadOfSuccess() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        var cleared = false
        val service = service(clear = { cleared = true }, finish = { error("private resume detail") })
        try {
            service.wipeAll(); runCurrent()
            assertTrue(cleared)
            assertEquals("Не удалось завершить удаление данных. Повторите действие.", service.state.value.notice)
            assertFalse(service.state.value.notice.orEmpty().contains("private"))
        } finally { service.close(); Dispatchers.resetMain() }
    }

    @Test fun successIsNotPublishedUntilResumeFinishes() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val resume = CompletableDeferred<Unit>()
        val service = service(clear = {}, finish = { resume.await() })
        try {
            service.wipeAll(); runCurrent()
            assertNotEquals("Все данные удалены.", service.state.value.notice)
            resume.complete(Unit); runCurrent()
            assertEquals("Все данные удалены.", service.state.value.notice)
        } finally { resume.complete(Unit); service.close(); Dispatchers.resetMain() }
    }

    private fun service(clear: suspend () -> Unit, finish: suspend () -> Unit): DefaultSettingsService {
        val store = InMemoryKeyValueStore()
        val json = Json { encodeDefaults = true }
        return DefaultSettingsService(DefaultSettingsConfiguration(store, InMemoryEventJournal(), store.secrets, json, dispatcher = Dispatchers.Main),
            JsonChatRepository(store, json), object : ProfileBridge {
                override val supportsFilePicker = false
                override suspend fun export(json: String) = false
                override suspend fun import(): String? = null
            }, store, json, clearApplicationData = { clear() }, finishApplicationReset = finish,
            usage = object : UsageLedger {
                override val state = MutableStateFlow(UsageArchive())
                override val failure = MutableStateFlow<String?>(null)
                override suspend fun start() = Unit
                override suspend fun captureObservation(): UsageObservation = error("Unexpected usage capture")
                override suspend fun exportArchive() = state.value
                override suspend fun record(observation: UsageObservation, record: UsageRecord, replacesId: String?) = error("Unexpected usage")
                override suspend fun context(observation: UsageObservation, snapshot: ContextUsageSnapshot) = error("Unexpected usage")
                override suspend fun cumulative(observation: UsageObservation, key: String, fingerprint: String, total: TokenUsage, last: TokenUsage, record: UsageRecord) = error("Unexpected usage")
                override suspend fun replace(archive: UsageArchive) = error("Unexpected usage")
                override suspend fun clear() = Unit
                override suspend fun <T> measure(profile: LlmProfile, block: suspend () -> T): T = error("Unexpected model call")
            }, pluginPreferences = TestPluginPreferences(), chatHistory = object : ChatHistoryCommands {
                override suspend fun importNotebooks(sessions: List<ChatSession>) = error("Unexpected import")
                override suspend fun unlinkProfile(profileId: String) = error("Unexpected profile")
                override suspend fun wipeHistory() = Unit
            })
    }
}
