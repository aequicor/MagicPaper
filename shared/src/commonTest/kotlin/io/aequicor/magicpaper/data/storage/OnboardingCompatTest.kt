package io.aequicor.magicpaper.data.storage

import io.aequicor.magicpaper.domain.AppSettings
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Совместимость онбординг-флага со старыми сохранёнными профилями. */
class OnboardingCompatTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun legacySettingsWithoutOnboardingFlagDecodeToTourShown() = runTest {
        val store = InMemoryKeyValueStore()
        // Профиль, сохранённый до появления onboardingDone.
        store.write(
            "settings",
            """{"llmBaseUrl":"http://x/v1","llmApiKey":"","llmModel":"m",""" +
                """"searchProvider":"AUTO","queritApiKey":"","googleApiKey":"","googleSearchEngineId":""}""",
        )
        val loaded = JsonSettingsRepository(store, json).load()
        assertFalse(loaded.onboardingDone, "старый профиль должен показать тур")
        assertTrue(loaded.llmConfigured)
    }

    @Test
    fun flagRoundTrips() = runTest {
        val repo = JsonSettingsRepository(InMemoryKeyValueStore(), json)
        repo.save(AppSettings(onboardingDone = true))
        assertTrue(repo.load().onboardingDone)
    }
}
