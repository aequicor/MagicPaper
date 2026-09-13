package io.aequicor.magicpaper.data.skills

import io.aequicor.magicpaper.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.encodeToString
import java.nio.file.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class LocalSkillExperienceTest {
    private val host = SkillPackageHost("1.0.0", "desktop")
    private val profile = LlmProfile("p", "Test", baseUrl = "https://llm.invalid", modelId = "test", apiKey = "configured-private-value")
    private val suite = StrictExperienceCatalog.cases(ExperienceScenario.SUMMARY)
    private class Gateway(var respond: suspend (Int, List<LlmMessage>) -> String = { _, m -> answer(m) }) : LlmGateway {
        companion object {
            fun answer(messages: List<LlmMessage>): String =
                if (messages.first().content.startsWith("Выбери")) "STRUCTURED"
                else ExperienceScenario.entries.flatMap { StrictExperienceCatalog.cases(it) }.single { messages.last().content.endsWith(it.prompt) }.expected
        }
        val calls = mutableListOf<List<LlmMessage>>()
        override suspend fun complete(profile: LlmProfile, messages: List<LlmMessage>): String {
            val index = calls.size
            calls += messages
            return respond(index, messages)
        }
    }
    private fun workspace(block: (Path) -> Unit) {
        val dir = Files.createTempDirectory("experience-test-")
        try { block(dir) } finally { Files.walk(dir).use { it.sorted(Comparator.reverseOrder()).forEach(Files::delete) } }
    }
    private fun journal(root: Path, repo: LocalSkillRepository, gateway: Gateway, now: () -> Long = { 1_000_000L }) =
        LocalSkillExperience(root.resolve("experience"), repo, gateway, { listOf(profile.apiKey, "other-profile-secret") }, now)
    private suspend fun seed(j: LocalSkillExperience) = setOf(
        j.record(ExperienceScenario.SUMMARY, true, setOf(ExperienceFeature.TOO_LONG)).id,
        j.record(ExperienceScenario.SUMMARY, false, setOf(ExperienceFeature.MISSING_STEP)).id,
    )
    private suspend fun candidate(j: LocalSkillExperience, ids: Set<String>) =
        j.generate(j.preview(ids, profile).token, true)
    private suspend fun review(repo: LocalSkillRepository, key: String) {
        val p = repo.snapshot().installed.getValue(key).pkg
        repo.review(key, SkillPackageReview(p.checksum, "user", "Origin, license and instructions reviewed locally", true, true, true))
    }
    private suspend fun activate(repo: LocalSkillRepository, key: String, confirmed: Boolean = true) {
        val s = repo.snapshot()
        val p = s.installed.getValue(key).pkg
        repo.activate(mapOf(p.manifest.id to key), SkillActivationConsent(s.generation, mapOf(key to p.checksum), confirmed, emptySet()))
    }

    @Test fun localRetentionSearchAndIndependentDeletionSurviveRestart() = workspace { root -> runTest {
        var time = 1_000_000L
        val g = Gateway()
        LocalSkillRepository(root.resolve("repo"), host).use { repo ->
            journal(root, repo, g) { time }.use { j ->
                seed(j)
                assertEquals(2, j.recurring()[ExperienceScenario.SUMMARY])
                assertEquals(1, j.search("Пропущен шаг").size)
                j.retention(2)
                assertFails { j.retention(0) }
            }
            journal(root, repo, g) { time }.use { j ->
                assertEquals(2, j.search().size)
                time += 2 * 86_400_000L
                assertTrue(j.search().isEmpty())
            }
        }
        assertTrue(g.calls.isEmpty())
        assertFalse(Files.readString(root.resolve("experience/experience.json")).contains("conclusion"))
        assertFalse(Files.exists(root.resolve("experience/experience.tmp")))
    } }

    @Test fun recordingSearchingAndReopeningUseOnlyLocalJournalWithoutAnyLlmRequest() = workspace { root -> runTest {
        val g = Gateway { _, _ -> error("No model request is allowed for journal operations") }
        val file = root.resolve("experience/experience.json")
        lateinit var expected: List<ExperienceOutcome>
        LocalSkillRepository(root.resolve("repo"), host).use { repo -> journal(root, repo, g).use { j ->
            seed(j)
            expected = j.search()
            assertTrue(Files.isRegularFile(file))
            val raw = Files.readString(file)
            assertTrue("SUMMARY" in raw && "TOO_LONG" in raw && "MISSING_STEP" in raw)
            assertFalse("fragments" in raw || "apiKey" in raw || "baseUrl" in raw)
            assertEquals(setOf("experience.json", "experience.lock"), Files.list(file.parent).use { it.map { path -> path.fileName.toString() }.toList().toSet() })
        } }
        LocalSkillRepository(root.resolve("repo"), host).use { repo -> journal(root, repo, g).use { j ->
            assertEquals(expected, j.search())
            assertEquals(expected.filter { ExperienceFeature.MISSING_STEP in it.features }, j.search("Пропущен шаг"))
            j.deleteAll()
            assertTrue(j.search().isEmpty())
            assertFalse(Files.readString(file).contains("MISSING_STEP"))
            assertEquals(0, g.calls.size)
        } }
    } }

    @Test fun closedEnumsAndUnapprovedTransportMakeZeroRequests() = workspace { root -> runTest {
        val g = Gateway()
        LocalSkillRepository(root.resolve("repo"), host).use { repo -> journal(root, repo, g).use { j ->
            for (secret in listOf(profile.apiKey, "Незнакомая секретная фраза", "q9z7K2v8P4")) {
                assertFails { ExperienceScenario.valueOf(secret) }
                assertFails { ExperienceFeature.valueOf(secret) }
            }
            val ids = seed(j)
            assertFails { j.preview(ids, profile.copy(provider = ProviderType.OPENAI_SUBSCRIPTION)) }
            val p = j.preview(ids, profile)
            assertFails { j.generate(p.token, false) }
            assertTrue(g.calls.isEmpty())
            assertFalse(Files.readString(root.resolve("experience/experience.json")).contains(profile.apiKey))
        } }
    } }

    @Test fun candidateAutomaticallyEvaluatesButNeedsReviewAndActivationAfterRestart() = workspace { root -> runTest {
        val g = Gateway()
        LocalSkillRepository(root.resolve("repo"), host).use { repo -> journal(root, repo, g).use { j ->
            val ids = seed(j)
            val p = j.preview(ids, profile)
            assertTrue(p.context.contains(suite[0].prompt))
            assertFalse(p.context.contains(suite[4].prompt))
            assertTrue(p.evaluation.contains(suite[4].prompt))
            val c = j.generate(p.token, true)
            assertTrue(c.passed)
            assertEquals(15, g.calls.size)
            assertFalse(g.calls.first().joinToString { it.content }.contains(suite[4].prompt))
            assertEquals(7, c.scores.size)
            assertEquals(4, c.scores.count { !it.heldOut })
            assertEquals(3, c.scores.count { it.heldOut })
            suite.forEachIndexed { i, scenario ->
                assertTrue(g.calls[1 + i * 2].last().content.endsWith(scenario.prompt))
                assertTrue(g.calls[2 + i * 2].last().content.endsWith(scenario.prompt))
            }
            assertTrue(c.scores.all { it.baseline == 1 && it.candidate == 1 })
            assertTrue(repo.active().isEmpty())
            assertEquals(SkillCandidateStatus.QUARANTINED, repo.snapshot().installed.getValue(c.key).status)
            assertFails { activate(repo, c.key) }
            assertFails { j.generate(p.token, true) }
        } }
        LocalSkillRepository(root.resolve("repo"), host).use { repo -> journal(root, repo, g).use { j ->
            val c = j.candidates().single()
            assertEquals(1, c.suiteVersion)
            assertTrue(repo.snapshot().installed.getValue(c.key).improvement!!.passed)
            review(repo, c.key)
            assertFails { activate(repo, c.key, false) }
            activate(repo, c.key)
            assertEquals("1.0.1", repo.active().single().version)
            assertEquals(StrictExperienceCatalog.instruction(ExperienceScenario.SUMMARY, ExperienceTemplate.STRUCTURED), repo.diff(c.key).newInstructions)
        } }
    } }

    @Test fun heldOutRegressionBlocksEvenReviewedCandidateAndSurvivesReimport() = workspace { root -> runTest {
        val g = Gateway { i, m -> if (i == 14) "ALPHA" else Gateway.answer(m) }
        LocalSkillRepository(root.resolve("repo"), host).use { repo -> journal(root, repo, g).use { j ->
            val c = candidate(j, seed(j))
            assertFalse(c.passed)
            assertEquals(0, c.scores.last().candidate)
            assertEquals(1, c.scores.last().baseline)
            review(repo, c.key)
            assertFails { activate(repo, c.key) }
        } }
        LocalSkillRepository(root.resolve("repo"), host).use { repo ->
            val release = repo.snapshot().installed.values.single()
            val dir = root.resolve("repo/releases/${release.pkg.checksum}")
            val entries = listOf(SkillPackageFormat.MANIFEST, "SKILL.md").map { SkillArchiveEntry(it, Files.readAllBytes(dir.resolve(it))) }
            repo.install(entries, SkillObservedSource(SkillImportKind.LOCAL_DIRECTORY, "import"))
            assertFails { activate(repo, release.pkg.key) }
        }
    } }

    @Test fun deletionCancelsInFlightAndLateNonCooperativeResponseCannotResurrect() = workspace { root -> runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val g = Gateway { _, _ -> withContext(NonCancellable) { entered.complete(Unit); release.await(); "STRUCTURED" } }
        LocalSkillRepository(root.resolve("repo"), host).use { repo -> journal(root, repo, g).use { j ->
            val ids = seed(j)
            val token = j.preview(ids, profile).token
            val job = launch { j.generate(token, true) }
            entered.await()
            j.deleteAll()
            release.complete(Unit)
            job.join()
            assertTrue(job.isCancelled)
            assertTrue(j.search().isEmpty())
            assertTrue(j.candidates().isEmpty())
            assertTrue(repo.snapshot().installed.isEmpty())
        } }
    } }

    @Test fun deleteDuringEvaluationRemovesCandidatePayloadAndReport() = workspace { root -> runTest {
        val entered = CompletableDeferred<Unit>()
        val g = Gateway { i, _ -> if (i == 0) "STRUCTURED" else { entered.complete(Unit); awaitCancellation() } }
        LocalSkillRepository(root.resolve("repo"), host).use { repo -> journal(root, repo, g).use { j ->
            val ids = seed(j)
            val p = j.preview(ids, profile)
            val job = launch { j.generate(p.token, true) }
            entered.await()
            assertEquals(1, repo.snapshot().installed.size)
            j.delete(ids)
            job.join()
            assertTrue(repo.snapshot().installed.isEmpty())
            assertTrue(j.candidates().isEmpty())
            assertEquals(0L, Files.list(root.resolve("repo/releases")).use { it.count() })
        } }
        LocalSkillRepository(root.resolve("repo"), host).use { repo -> journal(root, repo, g).use { j ->
            assertTrue(j.search().isEmpty()); assertTrue(repo.active().isEmpty())
        } }
    } }

    @Test fun timeoutAndSecretOutputLeaveNoActiveRelease() = workspace { root -> runTest {
        val g = Gateway { _, _ -> awaitCancellation() }
        LocalSkillRepository(root.resolve("repo"), host).use { repo -> journal(root, repo, g).use { j ->
            val ids = seed(j)
            val p = j.preview(ids, profile)
            val task = async { runCatching { j.generate(p.token, true) } }
            advanceTimeBy(60_001); runCurrent()
            assertTrue(task.await().exceptionOrNull() is TimeoutCancellationException)
            g.respond = { _, _ -> profile.apiKey }
            assertFails { candidate(j, ids) }
            assertTrue(repo.snapshot().installed.isEmpty())
            assertTrue(j.candidates().isEmpty())
        } }
    } }

    @Test fun rollbackRestoresVersionAndPermissionsAndDeletionCannotRestoreExperience() = workspace { root -> runTest {
        val g = Gateway()
        LocalSkillRepository(root.resolve("repo"), host).use { repo -> journal(root, repo, g).use { j ->
            val ids = seed(j)
            val a = candidate(j, ids)
            review(repo, a.key); activate(repo, a.key)
            g.respond = { _, m -> Gateway.answer(m) }
            val b = candidate(j, ids)
            review(repo, b.key); activate(repo, b.key)
            assertEquals("1.0.2", repo.active().single().version)
            val s = repo.snapshot()
            val old = s.installed.getValue(a.key).pkg
            repo.rollback(SkillActivationConsent(s.generation, mapOf(a.key to old.checksum), true, emptySet()))
            assertEquals("1.0.1", repo.active().single().version)
            assertEquals(emptySet(), repo.active().single().permissions)
            j.deleteAll()
            assertTrue(repo.snapshot().installed.isEmpty())
            assertNull(repo.snapshot().previousActive)
            assertTrue(repo.active().isEmpty())
        } }
    } }

    @Test fun interruptedDeletionResumesFromTombstoneAfterRestart() = workspace { root -> runTest {
        val g = Gateway()
        var fail = false
        LocalSkillRepository(root.resolve("repo"), host, beforeSnapshotCommit = { if (fail) error("disk failure") }).use { repo ->
            journal(root, repo, g).use { j ->
                candidate(j, seed(j))
                fail = true
                assertFails { j.deleteAll() }
                assertTrue(Files.readString(root.resolve("experience/experience.json")).contains("pendingDelete"))
            }
        }
        LocalSkillRepository(root.resolve("repo"), host).use { repo -> journal(root, repo, g).use { j ->
            assertTrue(j.search().isEmpty())
            assertTrue(j.candidates().isEmpty())
            assertTrue(repo.snapshot().installed.isEmpty())
            assertEquals(0L, Files.list(root.resolve("repo/releases")).use { it.count() })
        } }
    } }

    @Test fun moreThanSixSelectedObservationsAndDeletedPreviewAreRejected() = workspace { root -> runTest {
        val g = Gateway()
        LocalSkillRepository(root.resolve("repo"), host).use { repo -> journal(root, repo, g).use { j ->
            val tooMany = (1..7).map { j.record(ExperienceScenario.SUMMARY, true, emptySet()).id }.toSet()
            assertFails { j.preview(tooMany, profile) }
            val ids = seed(j)
            val p = j.preview(ids, profile)
            j.delete(ids)
            assertFails { j.generate(p.token, true) }
            assertTrue(g.calls.isEmpty())
        } }
    } }

    @Test fun baselineChangeBlocksPromotionWithFreshActivationConsent() = workspace { root -> runTest {
        val g = Gateway()
        LocalSkillRepository(root.resolve("repo"), host).use { repo -> journal(root, repo, g).use { j ->
            val ids = seed(j)
            val a = candidate(j, ids)
            g.respond = { _, m -> Gateway.answer(m) }
            val b = candidate(j, ids) // Both compared with no active skill.
            review(repo, a.key); activate(repo, a.key)
            review(repo, b.key)
            assertFails { activate(repo, b.key) }
            assertEquals("1.0.1", repo.active().single().version)
        } }
    } }

    @Test fun deletingActiveImprovementDoesNotReactivatePreviousVersion() = workspace { root -> runTest {
        val g = Gateway()
        LocalSkillRepository(root.resolve("repo"), host).use { repo -> journal(root, repo, g).use { j ->
            val a = candidate(j, seed(j))
            review(repo, a.key); activate(repo, a.key)
            val secondSources = seed(j)
            g.respond = { _, m -> Gateway.answer(m) }
            val b = candidate(j, secondSources)
            review(repo, b.key); activate(repo, b.key)
            j.delete(secondSources)
            assertTrue(repo.active().isEmpty())
            assertNull(repo.snapshot().previousActive)
            assertEquals(setOf(a.key), repo.snapshot().installed.keys)
        } }
    } }

    @Test fun deletionAlsoRemovesInternalDamagedCopyCreatedByExplicitRecovery() = workspace { root -> runTest {
        val g = Gateway()
        LocalSkillRepository(root.resolve("repo"), host).use { repo -> journal(root, repo, g).use { j ->
            val c = candidate(j, seed(j))
            val backup = root.resolve("explicit-backup.json")
            val hash = repo.backup(backup)
            val checksum = repo.snapshot().installed.getValue(c.key).pkg.checksum
            Files.writeString(root.resolve("repo/releases/$checksum/SKILL.md"), "damaged experience payload")
            repo.restore(backup, hash, true)
            assertEquals(1L, Files.list(root.resolve("repo/damaged")).use { it.count() })
            j.deleteAll()
            assertEquals(0L, Files.list(root.resolve("repo/damaged")).use { it.count() })
            assertTrue(Files.exists(backup)) // Explicit external backup is outside deletion scope.
        } }
    } }

    @Test fun arbitraryReviewedBaselineIsNeverSentOrCopied() = workspace { root -> runTest {
        val secrets = listOf("An unknown confidential phrase", "q7V9b2M8", profile.apiKey)
        for ((index, secret) in secrets.withIndex()) {
            val base = Files.createDirectory(root.resolve(index.toString()))
            val g = Gateway()
            LocalSkillRepository(base.resolve("repo"), host).use { repo -> journal(base, repo, g).use { j ->
                val text = StrictExperienceCatalog.instruction(ExperienceScenario.SUMMARY, ExperienceTemplate.CONCISE).encodeToByteArray()
                val manifest = StrictExperienceCatalog.manifest(ExperienceScenario.SUMMARY, ExperienceTemplate.CONCISE, "1.0.1").copy(description = secret)
                repo.install(listOf(
                    SkillArchiveEntry(SkillPackageFormat.MANIFEST, SkillPackageFormat.json.encodeToString(manifest).encodeToByteArray()),
                    SkillArchiveEntry("SKILL.md", text),
                ), SkillObservedSource(SkillImportKind.LOCAL_DIRECTORY, "fixture"))
                val key = "${manifest.id}@${manifest.version}"
                review(repo, key); activate(repo, key)
                val before = repo.snapshot()
                assertFails { candidate(j, seed(j)) }
                assertEquals(before, repo.snapshot())
                assertTrue(j.candidates().isEmpty())
                assertTrue(g.calls.isEmpty())
                assertFalse(secret in Files.readString(base.resolve("experience/experience.json")))
            } }
        }
    } }

    @Test fun unknownModelProseIsNeitherStoredNorForwarded() = workspace { root -> runTest {
        val g = Gateway()
        LocalSkillRepository(root.resolve("repo"), host).use { repo -> journal(root, repo, g).use { j ->
            val ids = seed(j)
            for (secret in listOf("An unknown confidential phrase", "q7V9b2M8", "STRUCTURED\nprivate text")) {
                g.respond = { _, _ -> secret }
                assertFails { candidate(j, ids) }
                assertTrue(j.candidates().isEmpty())
                assertTrue(repo.snapshot().installed.isEmpty())
                assertTrue(g.calls.flatten().none { secret in it.content })
                assertFalse(secret in Files.readString(root.resolve("experience/experience.json")))
            }
        } }
    } }

    @Test fun legacyJournalBlocksUntilExplicitDeletionWithoutImportingText() = workspace { root -> runTest {
        val secret = "An unknown confidential phrase"
        val g = Gateway()
        val dir = Files.createDirectories(root.resolve("experience"))
        val file = dir.resolve("experience.json")
        val legacyJson = """{"retentionDays":30,"outcomes":[{"scenario":"$secret","fragments":["$secret"]}]}"""
        Files.writeString(file, legacyJson)
        LocalSkillRepository(root.resolve("repo"), host).use { repo -> journal(root, repo, g).use { j ->
            assertTrue(j.hasLegacyData())
            assertFails { j.search() }
            assertFails { seed(j) }
            assertFails { j.preview(emptySet(), profile) }
            assertEquals(legacyJson, Files.readString(file))
            assertTrue(g.calls.isEmpty())
            j.deleteAll()
            assertFalse(j.hasLegacyData())
            candidate(j, seed(j))
            assertTrue(g.calls.flatten().none { secret in it.content })
            assertFalse(secret in Files.readString(file))
        } }
        LocalSkillRepository(root.resolve("repo"), host).use { repo -> journal(root, repo, g).use { j ->
            assertFalse(j.hasLegacyData()); assertEquals(1, j.candidates().size)
        } }
    } }

    @Test fun unknownEvaluationTextIsOnlyAFailedScoreAndNeverEntersNextRequestOrDisk() = workspace { root -> runTest {
        val secret = "confidential natural language without any secret marker"
        val g = Gateway { i, _ -> if (i == 0) "STRUCTURED" else secret }
        LocalSkillRepository(root.resolve("repo"), host).use { repo -> journal(root, repo, g).use { j ->
            val c = candidate(j, seed(j))
            assertFalse(c.passed)
            assertTrue(c.scores.all { it.baseline == 0 && it.candidate == 0 })
            assertEquals(15, g.calls.size)
            assertTrue(g.calls.flatten().none { secret in it.content })
            Files.walk(root).use { paths -> paths.filter { Files.isRegularFile(it) }.forEach {
                assertFalse(secret in Files.readString(it))
            } }
            review(repo, c.key)
            assertFails { activate(repo, c.key) }
        } }
    } }

    @Test fun legacyDeletionRemovesDerivedPackagesAndRetriesWithoutCopyingOldKeys() = workspace { root -> runTest {
        val secret = "privatelegacyvalue"
        val g = Gateway()
        var fail = false
        val dir = Files.createDirectories(root.resolve("experience"))
        val file = dir.resolve("experience.json")
        val key = "$secret@1.0.0"
        val oldJson = """{"outcomes":[{"fragments":["$secret"]}],"candidates":[{"key":"$key"}]}"""
        Files.writeString(file, oldJson)
        LocalSkillRepository(root.resolve("repo"), host, beforeSnapshotCommit = { if (fail) error("disk failure") }).use { repo ->
            val text = "legacy instruction $secret".encodeToByteArray()
            val m = StrictExperienceCatalog.manifest(ExperienceScenario.SUMMARY, ExperienceTemplate.CONCISE, "1.0.0").copy(
                id = secret, files = listOf(SkillPackageFile("SKILL.md", SkillPackageValidator.sha256(text), text.size.toLong())))
            repo.install(listOf(SkillArchiveEntry(SkillPackageFormat.MANIFEST, SkillPackageFormat.json.encodeToString(m).encodeToByteArray()),
                SkillArchiveEntry("SKILL.md", text)), SkillObservedSource(SkillImportKind.LOCAL_DIRECTORY, "local-experience"),
                improvement = SkillImprovementCheck(null, "0".repeat(64)))
            journal(root, repo, g).use { j ->
                fail = true
                assertFails { j.deleteAll() }
                assertTrue(j.hasLegacyData())
                assertEquals(oldJson, Files.readString(file))
            }
        }
        LocalSkillRepository(root.resolve("repo"), host).use { repo -> journal(root, repo, g).use { j ->
            j.deleteAll()
            assertTrue(repo.snapshot().installed.isEmpty())
            assertTrue(j.search().isEmpty())
            assertEquals(0L, Files.list(root.resolve("repo/releases")).use { it.count() })
            assertFalse(secret in Files.readString(file))
            assertTrue(g.calls.isEmpty())
        } }
    } }

    @Test fun everyClosedScenarioAndTemplateProducesOnlyCanonicalPackages() = workspace { root -> runTest {
        LocalSkillRepository(root.resolve("repo"), host).use { repo ->
            val g = Gateway()
            journal(root, repo, g).use { j ->
                for (scenario in ExperienceScenario.entries) {
                    val ids = (1..2).map { j.record(scenario, true, ExperienceFeature.entries.toSet()).id }.toSet()
                    for (template in ExperienceTemplate.entries) {
                        g.respond = { _, m -> if (m.first().content.startsWith("Выбери")) template.name else Gateway.answer(m) }
                        val c = j.generate(j.preview(ids, profile).token, true)
                        assertTrue(c.passed)
                        val release = repo.snapshot().installed.getValue(c.key)
                        assertEquals(StrictExperienceCatalog.manifest(scenario, template, release.pkg.manifest.version), release.pkg.manifest)
                        assertEquals(StrictExperienceCatalog.instruction(scenario, template), repo.diff(c.key).newInstructions)
                        assertEquals(emptySet(), release.pkg.manifest.permissions)
                        assertEquals(SkillCandidateStatus.QUARANTINED, release.status)
                        assertTrue(g.calls.flatten().none { m -> ids.any { it in m.content } })
                    }
                }
                assertTrue(repo.active().isEmpty())
            }
        }
    } }

    @Test fun deletionLeavesNoDerivedFilesReferencesOrSearchResultsAfterReopen() = workspace { root -> runTest {
        val g = Gateway()
        lateinit var ids: Set<String>
        lateinit var key: String
        lateinit var checksum: String
        LocalSkillRepository(root.resolve("repo"), host).use { repo -> journal(root, repo, g).use { j ->
            ids = seed(j)
            val c = candidate(j, ids)
            key = c.key
            checksum = repo.snapshot().installed.getValue(key).pkg.checksum
            review(repo, key); activate(repo, key)
            assertEquals(2, j.search("резюме").size)
            assertTrue(Files.exists(root.resolve("repo/releases/$checksum/SKILL.md")))
            j.delete(ids)
            assertTrue(j.search("резюме").isEmpty())
            assertTrue(j.recurring().isEmpty())
            assertTrue(j.candidates().isEmpty())
        } }
        LocalSkillRepository(root.resolve("repo"), host).use { repo -> journal(root, repo, g).use { j ->
            assertTrue(j.search().isEmpty())
            assertTrue(j.candidates().isEmpty())
            assertTrue(repo.snapshot().installed.isEmpty())
            assertTrue(repo.active().isEmpty())
            assertNull(repo.snapshot().previousActive)
            // This is the complete remaining file inventory: no index, payload, report or temporary copy.
            val files = Files.walk(root).use { paths -> paths.filter { Files.isRegularFile(it) }.toList() }
            assertEquals(setOf("experience/experience.json", "experience/experience.lock", "repo/snapshot.json", "repo/repository.lock"),
                files.map { root.relativize(it).toString() }.toSet())
            files.forEach { file ->
                val text = Files.readString(file)
                (ids + key + checksum).forEach { assertFalse(it in text) }
            }
            assertEquals(0L, Files.list(root.resolve("repo/releases")).use { it.count() })
        } }
    } }

    @Test fun fixedAndHeldOutRegressionsCannotReplaceActualActiveBaselineEvenAfterReviewAndRestart() = workspace { root -> runTest {
        for (caseIndex in listOf(0, 6)) {
            val base = Files.createDirectory(root.resolve(caseIndex.toString()))
            val g = Gateway()
            lateinit var baseline: SkillInstruction
            lateinit var failedKey: String
            LocalSkillRepository(base.resolve("repo"), host).use { repo -> journal(base, repo, g).use { j ->
                val ids = seed(j)
                val first = candidate(j, ids)
                review(repo, first.key); activate(repo, first.key)
                baseline = repo.active().single()
                g.respond = { i, m -> if (i % 15 == 2 + 2 * caseIndex) "WRONG" else Gateway.answer(m) }
                val failed = candidate(j, ids)
                failedKey = failed.key
                assertFalse(failed.passed)
                assertEquals(baseline.checksum, failed.baselineChecksum)
                assertEquals(1, failed.scores[caseIndex].baseline)
                assertEquals(0, failed.scores[caseIndex].candidate)
                assertEquals(caseIndex >= 4, failed.scores[caseIndex].heldOut)
                review(repo, failedKey)
                val before = repo.snapshot()
                val bytes = Files.readAllBytes(base.resolve("repo/snapshot.json"))
                assertFails { activate(repo, failedKey) }
                assertEquals(before, repo.snapshot())
                assertContentEquals(bytes, Files.readAllBytes(base.resolve("repo/snapshot.json")))
                assertEquals(baseline, repo.active().single())
            } }
            LocalSkillRepository(base.resolve("repo"), host).use { repo -> journal(base, repo, g).use { j ->
                assertFalse(j.candidates().single { it.key == failedKey }.passed)
                assertFails { activate(repo, failedKey) }
                assertEquals(baseline, repo.active().single())
            } }
        }
    } }

    @Test fun corruptJournalFailsClosedAndDoesNotOverwrite() = workspace { root -> runTest {
        val g = Gateway()
        LocalSkillRepository(root.resolve("repo"), host).use { repo ->
            journal(root, repo, g).use { seed(it) }
            val path = root.resolve("experience/experience.json")
            Files.writeString(path, "{\"schemaVersion\":2,\"outcomes\":[{\"scenario\":\"arbitrary secret\"}]}")
            assertFails { journal(root, repo, g) }
            assertTrue("arbitrary secret" in Files.readString(path))
        }
    } }
}
