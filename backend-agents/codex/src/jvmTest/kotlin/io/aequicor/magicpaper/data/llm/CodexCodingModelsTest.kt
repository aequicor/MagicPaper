package io.aequicor.magicpaper.data.llm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CodexCodingModelsTest {
    private fun items(body: String) = (Json.parseToJsonElement(body) as JsonArray).map { it.jsonObject }

    private val listing = items(
        """
        [
          {
            "id": "gpt-6-astra", "model": "gpt-6-astra", "displayName": "GPT-6 Astra", "hidden": false,
            "supportedReasoningEfforts": [
              {"reasoningEffort": "low"}, {"reasoningEffort": "medium"}, {"reasoningEffort": "high"},
              {"reasoningEffort": "xhigh"}, {"reasoningEffort": "max"}, {"reasoningEffort": "ultra"}
            ],
            "defaultReasoningEffort": "medium", "inputModalities": ["text", "image"]
          },
          {
            "id": "gpt-reserve", "model": "gpt-reserve", "hidden": true,
            "supportedReasoningEfforts": [{"reasoningEffort": "low"}], "defaultReasoningEffort": "low"
          },
          {
            "id": "gpt-text", "model": "gpt-text", "displayName": "Text only", "hidden": false,
            "supportedReasoningEfforts": [], "inputModalities": ["text"]
          }
        ]
        """,
    )

    @Test fun levelsAreKeptVerbatimSoMaxAndUltraStayDistinct() {
        val astra = codexCodingModels(listing, emptyMap()).first { it.id == "gpt-6-astra" }
        assertEquals(listOf("low", "medium", "high", "xhigh", "max", "ultra"), astra.levels)
        assertEquals("medium", astra.defaultLevel)
        assertEquals("GPT-6 Astra", astra.name)
        assertEquals("openai", astra.provider)
    }

    @Test fun hiddenModelsAreNotOffered() {
        assertEquals(listOf("gpt-6-astra", "gpt-text"), codexCodingModels(listing, emptyMap()).map { it.id })
    }

    @Test fun modelWithoutDeclaredLevelsHasNoThinkingAndNoDefault() {
        val text = codexCodingModels(listing, emptyMap()).first { it.id == "gpt-text" }
        assertFalse(text.supportsLevels)
        assertNull(text.defaultLevel)
        assertFalse(text.acceptsImages)
    }

    @Test fun imageSupportFollowsDeclaredModalitiesAndDefaultsToCodexOwnReading() {
        val models = codexCodingModels(listing + items("""[{"id": "gpt-plain", "model": "gpt-plain"}]"""), emptyMap())
        assertTrue(models.first { it.id == "gpt-6-astra" }.acceptsImages)
        assertTrue(models.first { it.id == "gpt-plain" }.acceptsImages)
    }

    @Test fun contextWindowComesFromTheNativeCacheOnlyWhenKnown() {
        val models = codexCodingModels(listing, mapOf("gpt-6-astra" to 258400))
        assertEquals(258400, models.first { it.id == "gpt-6-astra" }.contextWindow)
        assertNull(models.first { it.id == "gpt-text" }.contextWindow)
    }

    @Test fun entryWithoutModelKeyFallsBackToIdAndDuplicatesCollapse() {
        val models = codexCodingModels(items("""[{"id": "only-id"}, {"model": "dup"}, {"id": "dup"}, {"displayName": "no key"}]"""), emptyMap())
        assertEquals(listOf("only-id", "dup"), models.map { it.id })
        assertEquals("only-id", models.first().name)
    }
}
