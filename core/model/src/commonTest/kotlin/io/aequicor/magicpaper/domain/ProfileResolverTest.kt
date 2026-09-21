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

    private val subscription = LlmProfile(id = "chatgpt", name = "ChatGPT", provider = ProviderType.OPENAI_SUBSCRIPTION, modelId = "profile-default")
    private val codexSession = CodingSession("s", "p", "n", 1, engine = CodingEngine.CODEX)
    private fun native(level: String? = null, engine: CodingEngine = CodingEngine.CODEX) =
        codexSession.copy(codingModel = CodingModelSelection(engine, "openai", "gpt-6-astra", level))

    private val claudeSession = CodingSession("s", "p", "n", 1, engine = CodingEngine.CLAUDE_CODE,
        codingModel = CodingModelSelection(CodingEngine.CLAUDE_CODE, "anthropic", "opus", "high"))

    @Test
    fun claudeCodeRunsOnItsOwnSignInWhenNoAnthropicProfileExists() {
        val resolved = ProfileResolver.coding(claudeSession, null, AppSettings(), listOf(configured, subscription))
        assertEquals(ProviderType.ANTHROPIC, resolved?.provider)
        assertEquals("opus", resolved?.modelId)
        assertEquals(ReasoningEffort.HIGH, resolved?.effort?.level)
        assertEquals("", resolved?.apiKey, "the engine's own sign-in carries no secret of the application")
        assertNotNull(resolved?.takeIf { it.configured })
    }

    @Test
    fun claudeCodePrefersAnAnthropicProfileOverItsOwnConnection() {
        val anthropic = LlmProfile("claude", "Anthropic", "https://api.anthropic.com", "sk-1", ProviderType.ANTHROPIC, modelId = "profile-default")
        val resolved = ProfileResolver.coding(claudeSession, null, AppSettings(), listOf(configured, anthropic))
        assertEquals("claude", resolved?.id)
        assertEquals("sk-1", resolved?.apiKey)
        assertEquals("opus", resolved?.modelId)
        assertEquals("claude", listOf(anthropic).nativeConnectionFor(CodingEngine.CLAUDE_CODE)?.id)
        assertEquals(CodingEngine.CLAUDE_CODE.ownConnection()?.id, emptyList<LlmProfile>().nativeConnectionFor(CodingEngine.CLAUDE_CODE)?.id)
    }

    @Test
    fun onlyAnEngineThatSignsInItselfHasAConnectionWithoutAProfile() {
        assertNull(CodingEngine.CODEX.ownConnection())
        assertNull(CodingEngine.PI.ownConnection())
        assertNull(emptyList<LlmProfile>().nativeConnectionFor(CodingEngine.CODEX))
    }

    @Test
    fun nativeCodexChoiceRunsThroughTheSubscriptionWithTheCatalogModel() {
        val resolved = ProfileResolver.coding(native("high"), null, AppSettings(), listOf(configured, subscription))
        assertEquals("chatgpt", resolved?.id)
        assertEquals("gpt-6-astra", resolved?.modelId)
        assertEquals("gpt-6-astra", resolved?.forCoding()?.modelId, "the coding contour must not fall back to the profile's own model")
    }

    @Test
    fun nativeChoiceWinsOverTheLegacyModelSelectionOfTheSession() {
        val session = native().copy(modelSelection = ModelSelection("a", "m"), llmProfileId = "a")
        assertEquals("gpt-6-astra", ProfileResolver.coding(session, null, AppSettings(), listOf(configured, subscription))?.modelId)
    }

    @Test
    fun nativeChoiceWithoutAWorkingSubscriptionHasNoConnectionInsteadOfAForeignOne() {
        assertNull(ProfileResolver.coding(native(), null, AppSettings(), listOf(configured)))
        assertNull(ProfileResolver.coding(native(), null, AppSettings(), listOf(subscription.copy(enabled = false), configured)))
    }

    @Test
    fun nativeChoiceOfAnEngineWithoutANativeConnectionIsNotGuessed() {
        assertNull(ProfileResolver.coding(native(engine = CodingEngine.PI), null, AppSettings(), listOf(configured, subscription)))
    }

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
        val resolved = ProfileResolver.resolve(null as ChatSession?, AppSettings(), listOf(unconfigured))
        assertNull(resolved)
    }

    @Test
    fun disabledProfileIsUnavailableAndFallsBackWithoutLosingConfiguration() {
        val disabled = configured.copy(enabled = false)
        val settings = AppSettings(activeLlmProfileId = disabled.id, defaultModel = ModelSelection(disabled.id, disabled.modelId))

        assertEquals("b", ProfileResolver.resolve(null as ChatSession?, settings, listOf(disabled, other))?.id)
        assertNull(ProfileResolver.selection(ModelSelection(disabled.id, disabled.modelId), listOf(disabled, other)))
        assertEquals("m", disabled.modelId)
    }

    @Test
    fun profileIdOverrideWinsOverGlobal() {
        // Порядок для кодинг-сессии тот же, что для свитка: переопределение важнее глобального.
        val settings = AppSettings(activeLlmProfileId = "a")
        val resolved = ProfileResolver.resolve("b", settings, profiles)
        assertEquals("b", resolved?.id)
    }

    @Test
    fun profileIdFallsBackToFirstConfigured() {
        val settings = AppSettings(activeLlmProfileId = "нет-такого")
        val resolved = ProfileResolver.resolve(null as String?, settings, profiles)
        assertEquals("a", resolved?.id)
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
