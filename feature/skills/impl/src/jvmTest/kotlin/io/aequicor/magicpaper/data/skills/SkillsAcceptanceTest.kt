package io.aequicor.magicpaper.data.skills

import io.aequicor.magicpaper.domain.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import java.io.File
import java.nio.file.Files
import kotlin.test.*

/** Real temporary storage, deterministic text gateway; no provider credentials or network. */
class SkillsAcceptanceTest {
    private val host = SkillPackageHost("1.0.0", "desktop")
    private val profile = LlmProfile("fixture", "Fixture", baseUrl = "https://llm.invalid", modelId = "fixture", apiKey = "fixture-credential")

    private class Meter : LlmGateway {
        var calls = 0
        var inputChars = 0
        var outputChars = 0
        override suspend fun complete(profile: LlmProfile, messages: List<LlmMessage>): String {
            calls++
            inputChars += messages.sumOf { it.content.length }
            val result = if (messages.first().content.startsWith("Выбери")) "STRUCTURED"
            else ExperienceScenario.entries.flatMap { StrictExperienceCatalog.cases(it) }
                .single { messages.last().content.endsWith(it.prompt) }.expected
            outputChars += result.length
            return result
        }
    }

    private suspend fun consent(repo: LocalSkillRepository, key: String): SkillActivationConsent {
        val s = repo.snapshot()
        return SkillActivationConsent(s.generation, mapOf(key to s.installed.getValue(key).pkg.checksum), true, emptySet())
    }

    private suspend fun review(repo: LocalSkillRepository, key: String) {
        repo.review(key, SkillPackageReview(repo.snapshot().installed.getValue(key).pkg.checksum,
            "fixture-reviewer", "Reviewed synthetic fixture origin, license and content", true, true, true))
    }

    @Test fun measuredLifecycleAndPairedBackupRestoreForEverySupportedScenario() = runTest {
        val lines = mutableListOf("scenario\trepetition\toperation\tmilliseconds\tllm_calls\tinput_utf16_chars\toutput_utf16_chars")
        for (scenario in ExperienceScenario.entries) repeat(5) { repetition ->
            val root = Files.createTempDirectory("skills-acceptance-")
            val meter = Meter()
            suspend fun <T> measure(name: String, block: suspend () -> T): T {
                val calls = meter.calls; val input = meter.inputChars; val output = meter.outputChars
                val start = System.nanoTime()
                val result = block()
                val ms = (System.nanoTime() - start) / 1_000_000.0
                lines += "$scenario\t$repetition\t$name\t$ms\t${meter.calls - calls}\t${meter.inputChars - input}\t${meter.outputChars - output}"
                return result
            }
            try {
                val id = StrictExperienceCatalog.id(scenario)
                val baselineKey = "$id@1.0.0"
                val backup = root.resolve("packages.backup.json")
                var digest = ""
                var candidateKey = ""
                lateinit var baseline: SkillInstruction
                lateinit var improved: SkillInstruction
                lateinit var outcomes: List<ExperienceOutcome>
                LocalSkillRepository(root.resolve("original/packages"), host).use { repo ->
                    val manifest = StrictExperienceCatalog.manifest(scenario, ExperienceTemplate.CONCISE, "1.0.0")
                    measure("import_review_activate") {
                        repo.install(listOf(
                            SkillArchiveEntry(SkillPackageFormat.MANIFEST, SkillPackageFormat.json.encodeToString(manifest).encodeToByteArray()),
                            SkillArchiveEntry("SKILL.md", StrictExperienceCatalog.instruction(scenario, ExperienceTemplate.CONCISE).encodeToByteArray()),
                        ), SkillObservedSource(SkillImportKind.LOCAL_DIRECTORY, "synthetic-acceptance"))
                        assertTrue(repo.active().isEmpty())
                        assertFails { repo.activate(mapOf(id to baselineKey), consent(repo, baselineKey)) }
                        review(repo, baselineKey)
                        repo.activate(mapOf(id to baselineKey), consent(repo, baselineKey))
                        baseline = repo.active().single()
                    }
                    LocalSkillExperience(root.resolve("original/experience"), repo, meter, { listOf(profile.apiKey) }).use { journal ->
                        val ids = measure("record_search") {
                            val ids = setOf(journal.record(scenario, true, setOf(ExperienceFeature.TOO_LONG)).id,
                                journal.record(scenario, false, setOf(ExperienceFeature.MISSING_STEP)).id)
                            outcomes = journal.search()
                            assertEquals(2, outcomes.size)
                            ids
                        }
                        assertEquals(0, meter.calls)
                        val preview = measure("preview") { journal.preview(ids, profile) }
                        assertFails { journal.generate(preview.token, false) }
                        assertEquals(0, meter.calls)
                        val candidate = measure("generate_evaluate") { journal.generate(preview.token, true) }
                        assertTrue(candidate.passed)
                        assertEquals(15, meter.calls)
                        assertEquals(7, candidate.scores.size)
                        candidateKey = candidate.key
                        assertEquals(baseline, repo.active().single())
                        assertFails { repo.activate(mapOf(id to candidateKey), consent(repo, candidateKey)) }
                        measure("review_activate") {
                            review(repo, candidateKey)
                            repo.activate(mapOf(id to candidateKey), consent(repo, candidateKey))
                            improved = repo.active().single()
                        }
                        measure("apply") {
                            val result = SkillInstructionRuntime(repo, meter).answer(
                                "@skill:$id ${StrictExperienceCatalog.cases(scenario).first().prompt}", emptyList(), profile, emptyList())
                            assertNotNull(result)
                            assertTrue(improved.version in result && "ALPHA BETA" in result)
                        }
                        measure("package_backup") { digest = repo.backup(backup) }
                    }
                }
                // Both stores are closed: copy the journal from the same quiescent snapshot.
                val savedJournal = Files.readAllBytes(root.resolve("original/experience/experience.json"))
                Files.createDirectories(root.resolve("restored/experience"))
                Files.write(root.resolve("restored/experience/experience.json"), savedJournal)
                LocalSkillRepository(root.resolve("restored/packages"), host).use { repo ->
                    measure("package_restore") { repo.restore(backup, digest, true) }
                }
                LocalSkillRepository(root.resolve("restored/packages"), host).use { repo ->
                    LocalSkillExperience(root.resolve("restored/experience"), repo, meter, { listOf(profile.apiKey) }).use { journal ->
                        assertEquals(improved, repo.active().single())
                        assertEquals(outcomes, journal.search())
                        assertEquals(candidateKey, journal.candidates().single().key)
                        measure("rollback") { repo.rollback(consent(repo, baselineKey)) }
                        assertEquals(baseline, repo.active().single())
                        measure("delete_experience") { journal.deleteAll() }
                        assertFalse(candidateKey in repo.snapshot().installed)
                    }
                }
                LocalSkillRepository(root.resolve("restored/packages"), host).use { repo ->
                    LocalSkillExperience(root.resolve("restored/experience"), repo, meter, { listOf(profile.apiKey) }).use { journal ->
                        assertTrue(journal.search().isEmpty())
                        assertTrue(journal.candidates().isEmpty())
                        assertEquals(baseline, repo.active().single())
                    }
                }
                assertEquals(16, meter.calls, "Only consented generation/evaluation and one text application call")
            } finally {
                Files.walk(root).use { it.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
            }
        }
        File("build/reports/skills-final").mkdirs()
        File("build/reports/skills-final/measurements.tsv").writeText(lines.joinToString("\n", postfix = "\n"))
    }
}
