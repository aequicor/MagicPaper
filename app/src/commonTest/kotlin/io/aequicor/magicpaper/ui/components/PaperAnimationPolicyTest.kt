package io.aequicor.magicpaper.ui.components

import io.aequicor.magicpaper.data.storage.InMemoryKeyValueStore
import io.aequicor.magicpaper.data.storage.JsonSettingsRepository
import io.aequicor.magicpaper.domain.AppSettings
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PaperAnimationPolicyTest {
    @Test
    fun preferenceSurvivesReloadAndOldSettingsRemainCompatible() = runTest {
        val store = InMemoryKeyValueStore()
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        store.write("settings", """{"onboardingDone":true,"activeLlmProfileId":"existing"}""")
        val old = JsonSettingsRepository(store, json).load()
        assertTrue(old.onboardingDone)
        assertTrue(old.paperAnimationEnabled)
        JsonSettingsRepository(store, json).save(old.copy(paperAnimationEnabled = false))
        assertFalse(JsonSettingsRepository(store, json).load().paperAnimationEnabled)
        assertTrue(AppSettings().paperAnimationEnabled)
    }
}
