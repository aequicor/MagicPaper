package io.aequicor.magicpaper.data.skills

import io.aequicor.magicpaper.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.*

class ExperienceSuggestionsTest {
    private val host = SkillPackageHost("1.0.0", "desktop")
    private val profile = LlmProfile("p", "Test", baseUrl = "https://llm.invalid", modelId = "test", apiKey = "private-profile-key")
    private class Gateway : LlmGateway {
        val calls = mutableListOf<List<LlmMessage>>()
        var respond: suspend (Int, List<LlmMessage>) -> String = { _, messages -> answer(messages) }
        override suspend fun complete(profile: LlmProfile, messages: List<LlmMessage>): String {
            calls += messages
            return respond(calls.lastIndex, messages)
        }
        companion object {
            fun answer(messages: List<LlmMessage>) = if (messages.first().content.startsWith("Выбери")) "STRUCTURED"
                else ExperienceScenario.entries.flatMap { StrictExperienceCatalog.cases(it) }
                    .single { messages.last().content.endsWith(it.prompt) }.expected
        }
    }
    private fun workspace(block: (Path) -> Unit) {
        val root = Files.createTempDirectory("suggestions-")
        try { block(root) } finally { Files.walk(root).use { it.sorted(Comparator.reverseOrder()).forEach(Files::delete) } }
    }
    private fun journal(root: Path, repo: LocalSkillRepository, gateway: Gateway, now: () -> Long = { 1_000_000L }) =
        LocalSkillExperience(root.resolve("experience"), repo, gateway, { emptyList() }, now)
    private suspend fun verified(j: LocalSkillExperience, id: String = UUID.randomUUID().toString()): ExperienceOutcome =
        requireNotNull(j.completeRun(j.beginRun(id), ExperienceResult.UNKNOWN, SkillRunVerifier {
            SkillRunVerification(ExperienceVerification.PASSED, ExperienceScenario.SUMMARY, setOf(ExperienceFeature.TOO_LONG))
        }))
    private suspend fun seed(j: LocalSkillExperience) = List(3) { verified(j).id }.toSet()
    private suspend fun review(repo: LocalSkillRepository, key: String) {
        val pkg = repo.snapshot().installed.getValue(key).pkg
        repo.review(key, SkillPackageReview(pkg.checksum, "user", "Reviewed exact local instructions and origin", true, true, true))
    }
    private suspend fun activate(repo: LocalSkillRepository, key: String, confirmed: Boolean) {
        val s = repo.snapshot()
        val p = s.installed.getValue(key).pkg
        repo.activate(mapOf(p.manifest.id to key), SkillActivationConsent(s.generation, mapOf(key to p.checksum), confirmed, emptySet()))
    }

    @Test fun thresholdRequiresDistinctConfirmedSuccessesWithExactPattern() = workspace { root -> runTest {
        val g = Gateway()
        LocalSkillRepository(root.resolve("repo"), host).use { repo -> journal(root, repo, g).use { j ->
            val first = verified(j)
            repeat(3) { verified(j, first.id) }
            verified(j)
            assertTrue(j.suggestions().isEmpty())
            for (result in listOf(ExperienceResult.UNKNOWN, ExperienceResult.FAILURE, ExperienceResult.CANCELLATION)) {
                repeat(3) { j.completeRun(j.beginRun(UUID.randomUUID().toString()), result, SkillRunVerifier { SkillRunVerification() }) }
            }
            repeat(3) { j.completeRun(j.beginRun(UUID.randomUUID().toString()), ExperienceResult.UNKNOWN, SkillRunVerifier {
                SkillRunVerification(ExperienceVerification.FAILED, ExperienceScenario.SUMMARY, setOf(ExperienceFeature.TOO_LONG))
            }) }
            j.record(ExperienceScenario.SUMMARY, true, emptySet())
            j.record(ExperienceScenario.CHECKLIST, true, setOf(ExperienceFeature.TOO_LONG))
            assertTrue(j.suggestions().isEmpty())
            verified(j)
            val suggestion = j.suggestions().single()
            assertEquals(3, suggestion.confirmedCount)
            assertEquals(3, suggestion.sources.size)
            assertTrue(suggestion.explanation.contains("порог: 3"))
            assertTrue(j.search().filter { it.id in suggestion.sources }.all { it.verification == ExperienceVerification.PASSED && it.success })
            assertTrue(g.calls.isEmpty())
            assertTrue(repo.snapshot().installed.isEmpty())
        } }
    } }

    @Test fun consentQuarantineReviewAndDeduplicationSurviveRestart() = workspace { root -> runTest {
        val g = Gateway()
        lateinit var sources: Set<String>
        LocalSkillRepository(root.resolve("repo"), host).use { repo -> journal(root, repo, g).use { j ->
            sources = seed(j)
            assertEquals(sources, j.suggestions().single().sources)
        } }
        LocalSkillRepository(root.resolve("repo"), host).use { repo -> journal(root, repo, g).use { j ->
            val before = repo.snapshot()
            val p = j.previewSuggestion(j.suggestions().single().sources, profile)
            assertFails { j.generate(p.token, false) }
            assertTrue(g.calls.isEmpty())
            assertEquals(before, repo.snapshot())
            val c = j.generate(p.token, true)
            assertTrue(c.passed)
            assertEquals(15, g.calls.size)
            assertEquals(7, c.scores.size)
            assertEquals(3, c.scores.count { it.heldOut })
            assertEquals(SkillCandidateStatus.QUARANTINED, repo.snapshot().installed.getValue(c.key).status)
            assertNull(repo.snapshot().installed.getValue(c.key).review)
            assertEquals(before.projects, repo.snapshot().projects)
            assertEquals(before.projectTextConsents, repo.snapshot().projectTextConsents)
            assertTrue(repo.active().isEmpty())
            assertFails { activate(repo, c.key, true) }
            assertTrue(j.suggestions().isEmpty())
            assertFails { j.previewSuggestion(sources, profile) }
            val messages = g.calls.flatten().joinToString { it.content }
            assertTrue(sources.none { it in messages })
            assertFalse(profile.apiKey in Files.readString(root.resolve("experience/experience.json")))
        } }
        LocalSkillRepository(root.resolve("repo"), host).use { repo -> journal(root, repo, g).use { j ->
            assertTrue(j.suggestions().isEmpty())
            val c = j.candidates().single()
            review(repo, c.key)
            assertFails { activate(repo, c.key, false) }
            activate(repo, c.key, true)
            assertEquals(c.key, repo.snapshot().active.values.single())
            verified(j); verified(j)
            assertTrue(j.suggestions().isEmpty())
            verified(j)
            assertTrue(j.suggestions().single().sources.intersect(sources).isEmpty())
        } }
    } }

    @Test fun heldOutRegressionBlocksActivationAndProjectBindingAfterReviewAndReopen() = workspace { root -> runTest {
        val g = Gateway().apply { respond = { index, m -> if (index == 14) "WRONG" else Gateway.answer(m) } }
        LocalSkillRepository(root.resolve("repo"), host).use { repo -> journal(root, repo, g).use { j ->
            seed(j)
            val c = j.generate(j.previewSuggestion(j.suggestions().single().sources, profile).token, true)
            assertFalse(c.passed)
            assertEquals(1, c.scores.last().baseline)
            assertEquals(0, c.scores.last().candidate)
            review(repo, c.key)
        } }
        LocalSkillRepository(root.resolve("repo"), host).use { repo -> journal(root, repo, g).use { j ->
            val c = j.candidates().single()
            assertTrue(j.suggestions().isEmpty())
            assertFails { activate(repo, c.key, true) }
            val s = repo.snapshot()
            val pins = mapOf(c.key to s.installed.getValue(c.key).pkg.checksum)
            assertFails { repo.bindProject("project", pins, SkillActivationConsent(s.generation, pins, true, emptySet(), true)) }
            assertTrue(repo.snapshot().projects.isEmpty())
        } }
    } }

    @Test fun concurrentGenerationAndStaleSuggestionCannotReuseEvidence() = workspace { root -> runTest {
        val g = Gateway()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        g.respond = { i, m -> if (i == 0) { entered.complete(Unit); release.await() }; Gateway.answer(m) }
        LocalSkillRepository(root.resolve("repo"), host).use { repo -> journal(root, repo, g).use { j ->
            val ids = seed(j)
            val first = j.previewSuggestion(ids, profile)
            val task = async { j.generate(first.token, true) }
            entered.await()
            val second = j.previewSuggestion(ids, profile)
            assertFails { j.generate(second.token, true) }
            assertEquals(1, g.calls.size)
            release.complete(Unit)
            task.await()
            assertFails { j.generate(second.token, true) }
            assertEquals(1, j.candidates().size)
            assertEquals(15, g.calls.size)
        } }
    } }

    @Test fun interruptedEvaluationRetainsQuarantineAndConsumesEvidenceAfterRestart() = workspace { root -> runTest {
        val g = Gateway().apply { respond = { index, m -> if (index > 0) error("Evaluation unavailable") else Gateway.answer(m) } }
        LocalSkillRepository(root.resolve("repo"), host).use { repo -> journal(root, repo, g).use { j ->
            seed(j)
            val p = j.previewSuggestion(j.suggestions().single().sources, profile)
            assertFails { j.generate(p.token, true) }
            assertEquals(1, j.candidates().size)
        } }
        LocalSkillRepository(root.resolve("repo"), host).use { repo -> journal(root, repo, g).use { j ->
            assertTrue(j.suggestions().isEmpty())
            val c = j.candidates().single()
            assertFalse(c.passed)
            assertTrue(c.scores.isEmpty())
            assertEquals(SkillCandidateStatus.QUARANTINED, repo.snapshot().installed.getValue(c.key).status)
            review(repo, c.key)
            assertFails { activate(repo, c.key, true) }
        } }
    } }

    @Test fun deletionAndRetentionInvalidateSuggestionsAndConsent() = workspace { root -> runTest {
        val g = Gateway()
        var time = 1_000_000L
        LocalSkillRepository(root.resolve("repo"), host).use { repo -> journal(root, repo, g) { time }.use { j ->
            val ids = seed(j)
            val p = j.previewSuggestion(ids, profile)
            val revision = j.changes.value
            j.delete(setOf(ids.first()))
            assertTrue(j.changes.value > revision)
            assertTrue(j.suggestions().isEmpty())
            assertFails { j.generate(p.token, true) }
            verified(j)
            assertEquals(1, j.suggestions().size)
            val next = j.previewSuggestion(j.suggestions().single().sources, profile)
            j.retention(1)
            time += 86_400_000L
            assertTrue(j.suggestions().isEmpty())
            assertFails { j.generate(next.token, true) }
            assertTrue(g.calls.isEmpty())
        } }
    } }

    @Test fun manualConfirmedEvidenceIsBoundedAndManualGenerationRemainsAvailable() = workspace { root -> runTest {
        val g = Gateway()
        LocalSkillRepository(root.resolve("repo"), host).use { repo -> journal(root, repo, g).use { j ->
            repeat(8) { j.record(ExperienceScenario.SUMMARY, true, emptySet()) }
            val suggestion = j.suggestions().single()
            assertEquals(8, suggestion.confirmedCount)
            assertEquals(6, suggestion.sources.size)
            assertEquals(suggestion, j.suggestions().single())
            j.generate(j.previewSuggestion(suggestion.sources, profile).token, true)
            assertTrue(j.suggestions().isEmpty())
            // Explicit manual retries retain the existing 2–6 outcome workflow.
            j.generate(j.preview(suggestion.sources.take(2).toSet(), profile).token, true)
            assertEquals(2, j.candidates().size)
        } }
    } }
}
