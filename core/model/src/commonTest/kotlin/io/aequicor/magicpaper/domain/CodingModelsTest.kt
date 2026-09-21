package io.aequicor.magicpaper.domain

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CodingModelsTest {
    private val qwen = CodingModel(
        provider = "qwen-token-plan",
        id = "qwen3.8-max",
        levels = listOf("low", "medium", "xhigh"),
        defaultLevel = "medium",
        maxTokens = 131072,
    )
    private val plain = CodingModel(provider = "openai", id = "gpt-plain")
    private val snapshot = CodingModelSnapshot(CodingEngine.PI, listOf(qwen, plain), refreshedAt = 10)

    private fun pick(provider: String, id: String, level: String? = null, engine: CodingEngine = CodingEngine.PI) =
        CodingModelSelection(engine, provider, id, level)

    @Test fun defaultSelectionTakesTheEnginesOwnDefault() {
        val resolved = snapshot.resolve(pick("qwen-token-plan", "qwen3.8-max"))
        assertEquals(CodingModelResolution.Available(qwen, effectiveLevel = "medium"), resolved)
    }

    @Test fun defaultOnAModelWithoutDeclaredDefaultSendsNoLevel() {
        val resolved = snapshot.resolve(pick("openai", "gpt-plain")) as CodingModelResolution.Available
        assertNull(resolved.effectiveLevel)
        assertFalse(resolved.model.supportsLevels)
    }

    @Test fun explicitNativeLevelIsKeptEvenWhenTheAppLadderHasNoOrderForIt() {
        val resolved = snapshot.resolve(pick("qwen-token-plan", "qwen3.8-max", "xhigh")) as CodingModelResolution.Available
        assertEquals("xhigh", resolved.effectiveLevel)
    }

    @Test fun unsupportedLevelIsReportedInsteadOfClampedToANeighbour() {
        // `high` sits between `medium` and `xhigh` on our ladder, but the engine declares it null for this model.
        val resolved = snapshot.resolve(pick("qwen-token-plan", "qwen3.8-max", "high"))
        assertEquals(CodingModelResolution.LevelUnsupported(qwen, "high"), resolved)
    }

    @Test fun modelThatLeftTheCatalogIsReportedMissing() {
        assertEquals(CodingModelResolution.ModelMissing, snapshot.resolve(pick("qwen-token-plan", "glm-5.3")))
    }

    @Test fun modelIdentityIncludesTheProvider() {
        assertEquals(CodingModelResolution.ModelMissing, snapshot.resolve(pick("qwen-token-plan-individual", "qwen3.8-max")))
    }

    @Test fun snapshotOfAnotherEngineDoesNotAnswerForThisSelection() {
        val resolved = snapshot.resolve(pick("openai", "gpt-plain", engine = CodingEngine.CODEX))
        assertEquals(CodingModelResolution.WrongEngine(CodingEngine.PI), resolved)
    }

    @Test fun levelLabelShowsTheEnginesRealDefaultInsteadOfABareDefault() {
        assertEquals("по умолчанию: medium", qwen.levelLabel(null))
        assertEquals("xhigh", qwen.levelLabel("xhigh"))
        assertEquals("по умолчанию", qwen.copy(defaultLevel = null).levelLabel(null))
        assertNull(plain.levelLabel(null), "a model without thinking has no level to show")
    }

    @Test fun sessionsAndProjectsSavedBeforeNativeSelectionStillDecode() {
        val json = Json { ignoreUnknownKeys = true }
        val session = json.decodeFromString<CodingSession>("""{"id":"s","projectId":"p","name":"old","createdAt":1}""")
        val project = json.decodeFromString<CodingProject>("""{"id":"p","name":"old","path":"/x","createdAt":1}""")
        assertNull(session.codingModel)
        assertNull(project.codingModel)
    }

    @Test fun nativeSelectionAndSnapshotSurviveARoundTrip() {
        val json = Json { encodeDefaults = true }
        val selection = pick("qwen-token-plan", "qwen3.8-max", "xhigh")
        val session = CodingSession("s", "p", "n", 1, codingModel = selection)
        assertEquals(selection, json.decodeFromString<CodingSession>(json.encodeToString(CodingSession.serializer(), session)).codingModel)
        assertEquals(snapshot, json.decodeFromString<CodingModelSnapshot>(json.encodeToString(CodingModelSnapshot.serializer(), snapshot)))
        assertTrue(snapshot.find("openai", "gpt-plain") != null)
    }
}
