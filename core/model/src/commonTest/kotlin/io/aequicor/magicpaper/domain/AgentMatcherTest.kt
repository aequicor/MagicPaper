package io.aequicor.magicpaper.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgentMatcherTest {

    private fun profile(id: String, name: String) = LlmProfile(
        id = id,
        name = name,
        baseUrl = "http://x/v1",
        modelId = "m-$id",
    )

    private val coder = profile("p-coder", "Кодер")
    private val writer = profile("p-writer", "Писатель")
    private val dossiers = listOf(
        ModelDossier(id = "d1", profileId = "p-coder", strengths = "программирование, код, рефакторинг, тесты", rating = 5),
        ModelDossier(id = "d2", profileId = "p-writer", strengths = "тексты, документация, переводы, редактура", rating = 3),
    )

    @Test
    fun bestMatchesCodingStepToCoder() {
        val best = AgentMatcher.best("написать код функции и тесты", dossiers, listOf(writer, coder))
        assertEquals(coder, best)
    }

    @Test
    fun bestMatchesDocsStepToWriter() {
        val best = AgentMatcher.best("написать документацию к модулю", dossiers, listOf(writer, coder))
        assertEquals(writer, best)
    }

    @Test
    fun ratingBreaksTieWithoutSimilarity() {
        // Без пересечения слов решает оценка: у Кодера 5/5.
        val score = AgentMatcher.score(coder, "совершенно несвязный набор слов", dossiers)
        assertTrue(score > AgentMatcher.score(writer, "совершенно несвязный набор слов", dossiers))
    }

    @Test
    fun scoreIsZeroWithoutDossier() {
        val bare = profile("p-bare", "Без досье")
        assertEquals(0.0, AgentMatcher.score(bare, "код и тесты", dossiers))
    }

    @Test
    fun unconfiguredCandidatesAreIgnored() {
        val unconfigured = coder.copy(baseUrl = "", modelId = "")
        assertNull(AgentMatcher.best("код", dossiers, listOf(unconfigured)))
    }
}
