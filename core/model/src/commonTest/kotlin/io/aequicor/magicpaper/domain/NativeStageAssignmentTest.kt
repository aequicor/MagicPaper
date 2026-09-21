package io.aequicor.magicpaper.domain

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NativeStageAssignmentTest {
    private val astra = CodingModel("openai", "gpt-6-astra", "GPT-6 Astra",
        levels = listOf("low", "medium", "high", "xhigh", "max", "ultra"), defaultLevel = "medium")
    private val qwenLike = CodingModel("openai", "gpt-sparse", "Sparse", levels = listOf("low", "medium", "xhigh"))
    private val plain = CodingModel("openai", "gpt-plain", "Plain")
    private val snapshot = CodingModelSnapshot(CodingEngine.CODEX, listOf(astra, qwenLike, plain), 1)
    private val subscription = LlmProfile("chatgpt", "ChatGPT", provider = ProviderType.OPENAI_SUBSCRIPTION,
        modelId = "profile-default", favoriteModels = listOf("old-favorite"))
    private val api = LlmProfile("api", "API", baseUrl = "http://x/v1", modelId = "m", provider = ProviderType.OPENAI_COMPATIBLE,
        favoriteModels = listOf("m"))
    private val roster = listOf(api, subscription)
    private fun assignment(model: CodingModel = astra, level: String? = "ultra") =
        nativeStageAssignment(subscription, CodingEngine.CODEX, model, level)

    @Test fun nativeAssignmentCarriesTheChoiceAndOnlyShowsItOnTheAppScale() {
        val a = assignment(level = "ultra")
        assertEquals(CodingModelSelection(CodingEngine.CODEX, "openai", "gpt-6-astra", "ultra"), a.native)
        assertEquals("GPT-6 Astra", a.displayName)
        assertEquals(EffortSelection.of(ReasoningEffort.MAX), a.effort, "shown as max: the app ladder has no ultra")
        assertEquals("chatgpt", a.profileId)
    }

    @Test fun executionProfileRunsTheCatalogModelThroughTheSubscription() {
        val profile = assignment().executionProfile(roster, snapshot)
        assertEquals("chatgpt", profile.id)
        assertEquals("gpt-6-astra", profile.modelId)
        assertEquals("gpt-6-astra", profile.forCoding().modelId, "the coding contour must not fall back to a favorite")
    }

    @Test fun admissionAgainstTheSnapshotNamesAVanishedModelAndAskstoReassign() {
        val failure = assertFailsWith<IllegalStateException> {
            assignment(astra.copy(id = "gpt-gone")).executionProfile(roster, snapshot)
        }
        assertContains(failure.message.orEmpty(), "gpt-gone")
        assertContains(failure.message.orEmpty(), "переназначьте этап")
    }

    @Test fun admissionRefusesALevelTheModelNoLongerDeclaresInsteadOfClampingIt() {
        val failure = assertFailsWith<IllegalStateException> {
            assignment(qwenLike, level = "high").executionProfile(roster, snapshot)
        }
        assertContains(failure.message.orEmpty(), "не поддерживает уровень high")
    }

    @Test fun anAlreadyAdmittedAttemptKeepsItsFrozenAssignmentWhenTheCatalogMoves() {
        // No snapshot is passed on continuation: the frozen assignment must not change with the catalog.
        val frozen = assignment(astra.copy(id = "gpt-gone"))
        assertEquals("gpt-gone", frozen.executionProfile(roster).modelId)
    }

    @Test fun onlyTheEnginesOwnConnectionCanRunANativeAssignment() {
        assertFailsWith<IllegalStateException> { assignment().copy(profileId = "api").executionProfile(roster, snapshot) }
        assertFailsWith<IllegalStateException> { assignment().executionProfile(listOf(api), snapshot) }
        assertFailsWith<IllegalStateException> { assignment().executionProfile(listOf(subscription.copy(enabled = false)), snapshot) }
    }

    @Test fun legacyAssignmentsAreValidatedByTheOldRulesRegardlessOfTheSnapshot() {
        val legacy = StageAssignment("api", "m")
        assertEquals("m", legacy.executionProfile(roster, snapshot).modelId)
        assertFailsWith<IllegalArgumentException> { StageAssignment("api", "not-a-favorite").executionProfile(roster, snapshot) }
    }

    @Test fun nearestLevelPicksAmongDeclaredLevelsAndPrefersTheLowerOnATie() {
        assertEquals("medium", qwenLike.nearestLevel(ReasoningEffort.HIGH), "high sits between medium and xhigh: tie goes down")
        assertEquals("xhigh", qwenLike.nearestLevel(ReasoningEffort.MAX))
        assertEquals("max", astra.nearestLevel(ReasoningEffort.MAX))
        assertEquals("high", astra.nearestLevel(ReasoningEffort.HIGH))
        assertNull(plain.nearestLevel(ReasoningEffort.HIGH))
        assertNull(astra.copy(levels = listOf("weird")).nearestLevel(ReasoningEffort.HIGH), "unknown vocabulary is not guessed")
    }

    @Test fun plannerRosterGetsTheCatalogOnlyOnTheEnginesOwnConnection() {
        val expanded = roster.withNativeCatalog(snapshot)
        assertEquals(listOf("gpt-6-astra", "gpt-sparse", "gpt-plain"), expanded.single { it.id == "chatgpt" }.displayModels)
        assertEquals(api, expanded.single { it.id == "api" })
        assertEquals(roster, roster.withNativeCatalog(snapshot.copy(engine = CodingEngine.PI)), "no native connection for this engine")
    }

    @Test fun workerOfANativeStageRunsTheStagesCatalogModel() {
        val stage = Milestone("stage", "Проверка", assignment = assignment(level = "max"))
        val plan = Plan("plan", "project", "Цель", milestones = listOf(stage))
        val worker = CodingSession("worker", "project", "n", 1, planId = "plan", stageId = "stage", engine = CodingEngine.CODEX)
        val resolved = ProfileResolver.coding(worker, null, AppSettings(), roster, plan)
        assertEquals("gpt-6-astra", resolved?.modelId)
        assertEquals("chatgpt", resolved?.id)
        assertNull(ProfileResolver.coding(worker, null, AppSettings(), listOf(api), plan), "no subscription, no connection")
    }

    @Test fun plansSavedBeforeNativeAssignmentsStillDecodeAndNativeOnesRoundTrip() {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val old = json.decodeFromString<StageAssignment>("""{"profileId":"chatgpt","modelId":"gpt-5.5"}""")
        assertNull(old.native)
        val a = assignment()
        assertEquals(a, json.decodeFromString<StageAssignment>(json.encodeToString(StageAssignment.serializer(), a)))
        assertTrue(a.native?.level == "ultra", "ultra survives serialization verbatim")
        assertFalse(a.manual)
    }
}
