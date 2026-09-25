package io.aequicor.magicpaper.domain

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** «Anthropic (подписка Claude Code)»: a keyless connection that Claude Code sessions and the chat both use. */
class ClaudeSubscriptionProviderTest {
    private val profile = LlmProfile("p", "Anthropic (подписка Claude Code)", "", provider = ProviderType.ANTHROPIC_SUBSCRIPTION, modelId = "sonnet")

    @Test fun keylessSubscriptionConnectionIsConfiguredWithoutABaseUrl() {
        assertTrue(ProviderType.ANTHROPIC_SUBSCRIPTION.subscription)
        assertFalse(ProviderType.ANTHROPIC.subscription)
        assertTrue(profile.configured && profile.connectionConfigured && profile.operational)
        assertTrue(profile.supportsCoding)
    }

    @Test fun onlyClaudeCodeRunsItsModelsNatively() {
        assertTrue(profile.isNativeConnectionFor(CodingEngine.CLAUDE_CODE))
        assertFalse(profile.isNativeConnectionFor(CodingEngine.CODEX))
        assertFalse(profile.isNativeConnectionFor(CodingEngine.PI))
        assertEquals(profile, listOf(profile).nativeConnectionFor(CodingEngine.CLAUDE_CODE))
    }

    @Test fun catalogOffersTheSubscriptionAsADesktopSignIn() {
        val spec = ProviderCatalog.all.single { it.type == ProviderType.ANTHROPIC_SUBSCRIPTION }
        assertEquals("Anthropic (подписка Claude Code)", spec.displayName)
        assertTrue(spec.desktopOnly && spec.usesSubscription && !spec.requiresKey && spec.defaultBaseUrl.isEmpty())
        assertEquals(listOf("sonnet", "opus", "fable", "haiku"), spec.models.map { it.id })
    }

    @Test fun effortIsClaudeCodesOwnScale() {
        val capability = ModelDefaults.capability(profile)
        assertTrue(capability is ReasoningCapability.Controls)
        assertEquals(listOf("low", "medium", "high", "extra", "max", "ultracode"),
            capability.selectableLevels.map(capability::levelName))
        assertEquals("high", profile.copy(effort = EffortSelection.of(ReasoningEffort.HIGH)).resolveEffort(capability).level?.wire)
        assertEquals("max", capability.resolveEffort(EffortSelection.of(ReasoningEffort.MAX)).level?.wire)
        assertEquals("ultracode", capability.resolveEffort(EffortSelection.of(ReasoningEffort.ULTRACODE)).level?.wire)
        val selected = EffortSelection.of(ReasoningEffort.ULTRACODE)
        assertEquals("\"ultracode\"", Json.encodeToString(EffortSelection.serializer(), selected))
        assertEquals(selected, Json.decodeFromString(EffortSelection.serializer(), "\"ultracode\""))
    }
}
