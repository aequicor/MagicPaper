package io.aequicor.magicpaper.data.skills

import io.aequicor.magicpaper.data.coding.prepareCodingSkillInput
import io.aequicor.magicpaper.domain.CodingEngine
import io.aequicor.magicpaper.domain.CodingSession
import io.aequicor.magicpaper.domain.SkillActivationConsent
import io.aequicor.magicpaper.domain.SkillCompatibility
import io.aequicor.magicpaper.domain.SkillImportKind
import io.aequicor.magicpaper.domain.SkillPackageFile
import io.aequicor.magicpaper.domain.SkillPackageFormat
import io.aequicor.magicpaper.domain.SkillPackageHost
import io.aequicor.magicpaper.domain.SkillPackageManifest
import io.aequicor.magicpaper.domain.SkillPackageOrigin
import io.aequicor.magicpaper.domain.SkillPackageReview
import io.aequicor.magicpaper.domain.SkillPermission
import io.aequicor.magicpaper.domain.SkillCandidateStatus
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopUiSkillIntegrationTest {
    private val host = SkillPackageHost("1.0.0", "desktop")

    @Test
    fun packageUsesImportReviewAndMagicPaperBindingWithoutReplacingOtherDecisions() = runTest {
        val packageDirectory = projectRoot().resolve("skills/magicpaper-desktop-ui")
        val repositoryDirectory = Files.createTempDirectory("desktop-ui-skill-")
        try {
            LocalSkillRepository(repositoryDirectory, host).use { repository ->
                val foreign = installForeignRelease(repository)
                val foreignPins = mapOf(foreign.key to foreign.checksum)
                repository.activate(
                    mapOf(foreign.manifest.id to foreign.key),
                    SkillActivationConsent(repository.snapshot().generation, foreignPins, true, emptySet()),
                )
                repository.bindProject(
                    "OtherProject",
                    foreignPins,
                    SkillActivationConsent(repository.snapshot().generation, foreignPins, true, emptySet(), trustedCodingText = true),
                )
                val unrelatedBefore = repository.snapshot()

                val prepared = SkillPackageImporter(repository, host).prepareDirectory(packageDirectory)
                assertEquals("magicpaper.desktop-ui@1.0.0", prepared.pkg.key)
                repository.install(prepared)
                var snapshot = repository.snapshot()
                assertEquals(SkillCandidateStatus.QUARANTINED, snapshot.installed.getValue(prepared.pkg.key).status)
                assertFalse(prepared.pkg.key in snapshot.active.values)
                assertEquals(unrelatedBefore.active, snapshot.active)
                assertEquals(unrelatedBefore.projects, snapshot.projects)
                assertEquals(unrelatedBefore.projectTextConsents, snapshot.projectTextConsents)

                repository.review(
                    prepared.pkg.key,
                    SkillPackageReview(
                        prepared.pkg.checksum,
                        reviewer = "MagicPaper desktop UI stage",
                        evidence = "Original project package; MIT file, manifest, instructions, references, and source links reviewed.",
                        originVerified = true,
                        licenseVerified = true,
                        contentReviewed = true,
                    ),
                )
                snapshot = repository.snapshot()
                assertEquals(SkillCandidateStatus.VERIFIED, snapshot.installed.getValue(prepared.pkg.key).status)

                val magicPaperPins = mapOf(prepared.pkg.key to prepared.pkg.checksum)
                repository.bindProject(
                    "MagicPaper",
                    magicPaperPins,
                    SkillActivationConsent(
                        snapshot.generation,
                        magicPaperPins,
                        reviewedChanges = true,
                        permissions = setOf(SkillPermission.READ_PROJECT, SkillPermission.WRITE_PROJECT, SkillPermission.RUN_PROCESS),
                        trustedCodingText = true,
                    ),
                )

                val selection = repository.projectCodingSelection("MagicPaper")
                assertTrue(selection.trustedText && selection.freshSession)
                assertEquals(listOf("magicpaper.desktop-ui@1.0.0"), selection.instructions.map { "${it.id}@${it.version}" })
                val request = "Implement the settings dialog for macOS and Windows using the MagicPaper design system."
                val preparedRequest = prepareCodingSkillInput(
                    CodingSession("ui-request", "MagicPaper", "Desktop UI", 0, piSessionId = "old-session", engine = CodingEngine.PI),
                    request,
                    selection,
                )
                assertEquals("", preparedRequest.session.piSessionId)
                assertContains(preparedRequest.prompt, request)
                assertContains(preparedRequest.prompt, "magicpaper.desktop-ui")
                assertContains(preparedRequest.prompt, "Feature and app modules use only the public `Paper*` design-system API")
                assertContains(preparedRequest.prompt, "Preserve product vocabulary, content, user data")
                assertContains(preparedRequest.prompt, "macOS and Windows")

                val after = repository.snapshot()
                assertEquals(unrelatedBefore.active, after.active)
                assertEquals(foreignPins, after.projects.getValue("OtherProject"))
                assertEquals(foreignPins, after.projectTextConsents.getValue("OtherProject"))
                assertEquals(magicPaperPins, after.projects.getValue("MagicPaper"))
                assertEquals(magicPaperPins, after.projectTextConsents.getValue("MagicPaper"))
            }
        } finally {
            Files.walk(repositoryDirectory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }

    private suspend fun installForeignRelease(repository: LocalSkillRepository) = run {
        val instructions = "Keep existing unrelated binding and trust decisions."
        val bytes = instructions.encodeToByteArray()
        val manifest = SkillPackageManifest(
            id = "foreign.skill",
            version = "4.2.0",
            name = "Foreign fixture",
            description = "Represents a pre-existing user decision",
            files = listOf(SkillPackageFile("SKILL.md", SkillPackageValidator.sha256(bytes), bytes.size.toLong())),
            origin = SkillPackageOrigin(SkillImportKind.LOCAL_DIRECTORY, "fixture"),
            license = "MIT",
            compatibility = SkillCompatibility("1.0.0", "2.0.0", setOf("desktop")),
        )
        val manifestBytes = SkillPackageFormat.json.encodeToString(manifest).encodeToByteArray()
        repository.install(
            listOf(
                SkillArchiveEntry(SkillPackageFormat.MANIFEST, manifestBytes),
                SkillArchiveEntry("SKILL.md", bytes),
            ),
            SkillObservedSource(SkillImportKind.LOCAL_DIRECTORY, "fixture"),
        )
        val pkg = repository.snapshot().installed.getValue("foreign.skill@4.2.0").pkg
        repository.review(pkg.key, SkillPackageReview(pkg.checksum, "fixture", "Existing reviewed release", true, true, true))
        pkg
    }

    private fun projectRoot(): Path {
        var current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize()
        while (!Files.exists(current.resolve("settings.gradle.kts"))) {
            current = current.parent ?: error("MagicPaper project root not found")
        }
        return current
    }
}
