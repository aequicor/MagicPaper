package io.aequicor.magicpaper.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull

class ProfileResolverTest {

    private val configured = LlmProfile(id = "a", name = "A", baseUrl = "http://x/v1", modelId = "m")
    private val other = LlmProfile(id = "b", name = "B", baseUrl = "http://y/v1", modelId = "n")
    private val unconfigured = LlmProfile(id = "c", name = "C", baseUrl = "", modelId = "")
    private val profiles = listOf(unconfigured, configured, other)

    private fun session(id: String) = ChatSession(id = "s", title = "t", createdAt = 1, updatedAt = 1)

    @Test
    fun sessionOverrideWinsOverGlobal() {
        val settings = AppSettings(activeLlmProfileId = "a")
        val resolved = ProfileResolver.resolve(session("s").copy(llmProfileId = "b"), settings, profiles)
        assertEquals("b", resolved?.id)
    }

    @Test
    fun globalActiveWhenNoOverride() {
        val settings = AppSettings(activeLlmProfileId = "a")
        val resolved = ProfileResolver.resolve(session("s"), settings, profiles)
        assertEquals("a", resolved?.id)
    }

    @Test
    fun firstConfiguredWhenActiveUnknown() {
        val settings = AppSettings(activeLlmProfileId = "нет-такого")
        val resolved = ProfileResolver.resolve(session("s"), settings, profiles)
        assertEquals("a", resolved?.id)
    }

    @Test
    fun unconfiguredSkipped() {
        val settings = AppSettings(activeLlmProfileId = "c")
        val resolved = ProfileResolver.resolve(session("s"), settings, profiles)
        assertEquals("a", resolved?.id, "ненастроенный активный профиль пропускается")
    }

    @Test
    fun nullWhenNothingConfigured() {
        val resolved = ProfileResolver.resolve(null, AppSettings(), listOf(unconfigured))
        assertNull(resolved)
    }

    @Test
    fun migratorCreatesLegacyProfile() {
        val legacy = AppSettings(llmBaseUrl = "http://localhost:11434/v1", llmModel = "llama3.2")
        val profile = ProfileMigrator.legacyProfile(legacy)
        assertNotNull(profile)
        assertEquals(ProfileMigrator.LEGACY_ID, profile.id)
        assertEquals("llama3.2", profile.modelId)
        assertEquals("Ollama (локально)", profile.name)
    }

    @Test
    fun migratorSkipsEmptyLegacy() {
        val profile = ProfileMigrator.legacyProfile(AppSettings(llmBaseUrl = "", llmModel = ""))
        assertNull(profile)
    }
}
