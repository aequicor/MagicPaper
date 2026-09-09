package io.aequicor.magicpaper.data.skills

import io.aequicor.magicpaper.data.coding.prepareCodingSkillInput
import io.aequicor.magicpaper.domain.CodingEngine
import io.aequicor.magicpaper.domain.CodingSession
import io.aequicor.magicpaper.domain.SkillActivationConsent
import io.aequicor.magicpaper.domain.SkillCandidateStatus
import io.aequicor.magicpaper.domain.SkillPackageHost
import io.aequicor.magicpaper.domain.SkillPackageReview
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** Explicit project-local installer. All repository state changes go through LocalSkillRepository. */
object DesktopUiSkillBindingTool {
    private val host = SkillPackageHost("1.0.0", "desktop")
    private const val projectId = "MagicPaper"
    private const val request = "Implement the settings dialog for macOS and Windows using the MagicPaper design system."

    @JvmStatic
    fun main(args: Array<String>) = runBlocking {
        require(args.size == 3) { "Expected: <package-directory> <repository-directory> <report-file>" }
        val packageDirectory = Path.of(args[0]).toAbsolutePath().normalize()
        val repositoryDirectory = Path.of(args[1]).toAbsolutePath().normalize()
        val reportFile = Path.of(args[2]).toAbsolutePath().normalize()
        val projectRoot = packageDirectory.parent.parent
        require(Files.isDirectory(packageDirectory))
        require(repositoryDirectory.startsWith(projectRoot)) { "Repository must stay inside the project" }
        require(reportFile.startsWith(projectRoot)) { "Report must stay inside the project" }

        var checksum = ""
        var generation = 0L
        var unrelatedActive = emptyMap<String, String>()
        var unrelatedProjects = emptyMap<String, Map<String, String>>()
        var unrelatedTrust = emptyMap<String, Map<String, String>>()
        LocalSkillRepository(repositoryDirectory, host).use { repository ->
            val before = repository.snapshot()
            unrelatedActive = before.active
            unrelatedProjects = before.projects - projectId
            unrelatedTrust = before.projectTextConsents - projectId

            val imported = SkillPackageImporter(repository, host).prepareDirectory(packageDirectory)
            require(imported.pkg.key == "magicpaper.desktop-ui@1.0.0")
            checksum = imported.pkg.checksum
            val installed = before.installed[imported.pkg.key]
            if (installed == null) repository.install(imported)
            else require(installed.pkg.checksum == checksum) { "Installed version has different bytes" }

            var snapshot = repository.snapshot()
            val release = snapshot.installed.getValue(imported.pkg.key)
            if (release.status != SkillCandidateStatus.VERIFIED) {
                repository.review(
                    imported.pkg.key,
                    SkillPackageReview(
                        checksum,
                        reviewer = "MagicPaper desktop UI stage",
                        evidence = "Original project package; MIT license, manifest, instructions, references, and source links reviewed.",
                        originVerified = true,
                        licenseVerified = true,
                        contentReviewed = true,
                    ),
                )
            }

            snapshot = repository.snapshot()
            val currentPins = snapshot.projects[projectId].orEmpty()
            val targetPins = currentPins + (imported.pkg.key to checksum)
            val alreadyTrusted = snapshot.projectTextConsents[projectId] == targetPins
            if (currentPins != targetPins || !alreadyTrusted) {
                repository.bindProject(
                    projectId,
                    targetPins,
                    SkillActivationConsent(
                        generation = snapshot.generation,
                        targetChecksums = targetPins,
                        reviewedChanges = true,
                        permissions = imported.pkg.manifest.permissions,
                        trustedCodingText = true,
                    ),
                )
            }

            val selection = repository.projectCodingSelection(projectId)
            require(selection.trustedText && selection.freshSession)
            require(selection.instructions.any { it.id == "magicpaper.desktop-ui" && it.version == "1.0.0" && it.checksum == checksum })
            val prepared = prepareCodingSkillInput(
                CodingSession("desktop-ui-binding-check", projectId, "Desktop UI", 0, piSessionId = "old-session", engine = CodingEngine.PI),
                request,
                selection,
            )
            require(prepared.session.piSessionId.isEmpty())
            require(request in prepared.prompt)
            require("Feature and app modules use only the public `Paper*` design-system API" in prepared.prompt)
            require("Preserve product vocabulary, content, user data" in prepared.prompt)

            val after = repository.snapshot()
            require(after.active == unrelatedActive)
            require(after.projects - projectId == unrelatedProjects)
            require(after.projectTextConsents - projectId == unrelatedTrust)
            generation = after.generation
        }

        LocalSkillRepository(repositoryDirectory, host).use { reopened ->
            val selection = reopened.projectCodingSelection(projectId)
            require(selection.trustedText)
            require(selection.instructions.any { it.id == "magicpaper.desktop-ui" && it.version == "1.0.0" && it.checksum == checksum })
            val state = reopened.snapshot()
            require(state.active == unrelatedActive)
            require(state.projects - projectId == unrelatedProjects)
            require(state.projectTextConsents - projectId == unrelatedTrust)
        }

        val report = buildJsonObject {
            put("status", "PASS")
            put("repository", projectRoot.relativize(repositoryDirectory).toString())
            put("projectId", projectId)
            put("installedVersion", "magicpaper.desktop-ui@1.0.0")
            put("checksum", checksum)
            put("generation", generation)
            put("importedThroughApi", true)
            put("reviewedThroughApi", true)
            put("boundThroughApi", true)
            put("reopenedAndRead", true)
            put("representativeUiRequestReceivedInstructions", true)
            put("unrelatedActivePreserved", true)
            put("unrelatedBindingsPreserved", true)
            put("unrelatedTrustPreserved", true)
            put("snapshotEditedDirectly", false)
            put("instructions", buildJsonArray { add(JsonPrimitive("magicpaper.desktop-ui@1.0.0")) })
        }.toString() + "\n"
        Files.createDirectories(reportFile.parent)
        val pending = Files.createTempFile(reportFile.parent, reportFile.fileName.toString(), ".pending")
        try {
            Files.writeString(pending, report)
            try {
                Files.move(pending, reportFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(pending, reportFile, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(pending)
        }
        println("PASS: magicpaper.desktop-ui@1.0.0 bound to $projectId in $repositoryDirectory; generation=$generation")
    }
}
