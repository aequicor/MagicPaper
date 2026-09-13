package io.aequicor.magicpaper.data.skills

import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.plugins.builtin.LocalSkillsPlugin
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.*

class LocalSkillRepositoryTest {
    private val host = SkillPackageHost("1.0.0", "desktop")
    private val source = SkillObservedSource(SkillImportKind.LOCAL_DIRECTORY, "/chosen/skill")
    private fun entries(version: String = "1.0.0", permissions: Set<SkillPermission> = emptySet(), license: String? = null, executionMarker: Path? = null): List<SkillArchiveEntry> {
        val text = "Summarize $version".encodeToByteArray()
        val marker = executionMarker?.toAbsolutePath()?.toString() ?: "SHOULD_NEVER_EXIST"
        val quotedMarker = "'" + marker.replace("'", "'\\''") + "'"
        val script = "#!/bin/sh\ntouch $quotedMarker\n".encodeToByteArray()
        val m = SkillPackageManifest(id = "local.summary", version = version, name = "Summary", description = "Selected text",
            files = listOf(SkillPackageFile("SKILL.md", SkillPackageValidator.sha256(text), text.size.toLong()), SkillPackageFile("install.sh", SkillPackageValidator.sha256(script), script.size.toLong())),
            origin = SkillPackageOrigin(SkillImportKind.LOCAL_DIRECTORY), license = license, permissions = permissions,
            compatibility = SkillCompatibility("1.0.0", "2.0.0", setOf("desktop")))
        return listOf(SkillArchiveEntry(SkillPackageFormat.MANIFEST, SkillPackageFormat.json.encodeToString(m).encodeToByteArray()), SkillArchiveEntry("SKILL.md", text), SkillArchiveEntry("install.sh", script))
    }
    private fun archive(entries: List<SkillArchiveEntry>, prefix: String = ""): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        ZipOutputStream(out).use { zip -> entries.forEach { zip.putNextEntry(ZipEntry(prefix + it.path)); zip.write(it.bytes); zip.closeEntry() } }
        return out.toByteArray()
    }
    private suspend fun review(repo: LocalSkillRepository, version: String = "1.0.0") {
        val p = repo.snapshot().installed.getValue("local.summary@$version").pkg
        repo.review(p.key, SkillPackageReview(p.checksum, "tester", "Author and license independently established; contents read", true, true, true))
    }
    private suspend fun consent(repo: LocalSkillRepository, version: String, permissions: Set<SkillPermission> = emptySet()): SkillActivationConsent {
        val s = repo.snapshot()
        val p = s.installed.getValue("local.summary@$version").pkg
        return SkillActivationConsent(s.generation, mapOf(p.key to p.checksum), true, permissions)
    }
    private suspend fun activate(repo: LocalSkillRepository, version: String, permissions: Set<SkillPermission> = emptySet()) = repo.activate(mapOf("local.summary" to "local.summary@$version"), consent(repo, version, permissions))
    private fun workspace(block: (Path) -> Unit) {
        val dir = Files.createTempDirectory("skill-local-test-")
        try { block(dir) } finally { Files.walk(dir).use { it.sorted(Comparator.reverseOrder()).forEach(Files::delete) } }
    }

    @Test fun quarantinedMetadataAndLocalSearchSurviveRestart() = workspace { base -> runTest {
        LocalSkillRepository(base.resolve("repo"), host).use { repo ->
            repo.install(entries(license = "MIT"), source)
            assertEquals(SkillCandidateStatus.QUARANTINED, repo.catalog("summary MIT").single().release.status)
            assertTrue(repo.activeInstructions().isEmpty())
        }
        LocalSkillRepository(base.resolve("repo"), host).use { repo ->
            val item = repo.catalog("summary").single()
            assertEquals(source, item.source)
            assertEquals("MIT", item.release.pkg.manifest.license)
            assertEquals("1.0.0", item.release.pkg.manifest.version)
            assertFails { activate(repo, "1.0.0") }
            assertFalse(Files.exists(base.resolve("SHOULD_NEVER_EXIST")))
        }
    } }

    @Test fun unknownOriginAndLicenseIndependentlyStayQuarantinedAfterRestart() = workspace { base -> runTest {
        val root = base.resolve("repo")
        for ((origin, license) in listOf(false to true, true to false)) {
            LocalSkillRepository(root, host).use { repo ->
                repo.install(entries(), source)
                val p = repo.snapshot().installed.values.single().pkg
                repo.review(p.key, SkillPackageReview(p.checksum, "tester", "Incomplete review", origin, license, true))
            }
            LocalSkillRepository(root, host).use { repo ->
                assertEquals(SkillCandidateStatus.QUARANTINED, repo.catalog().single().release.status)
                assertFails { activate(repo, "1.0.0") }
            }
        }
    } }

    @Test fun networkUpgradeDenialAndConsentPersistAndRollbackWorksOffline() = workspace { base -> runTest {
        val root = base.resolve("repo")
        LocalSkillRepository(root, host).use { repo ->
            repo.install(entries(), source); review(repo); activate(repo, "1.0.0")
            repo.install(entries("2.0.0", setOf(SkillPermission.NETWORK)), source); review(repo, "2.0.0")
        }
        LocalSkillRepository(root, host).use { repo ->
            val before = repo.snapshot()
            assertFails { activate(repo, "2.0.0") }
            assertEquals(before, repo.snapshot())
            val diff = repo.diff("local.summary@2.0.0")
            assertEquals(setOf("SKILL.md"), diff.changedFiles)
            assertEquals(setOf(SkillPermission.NETWORK), diff.after.permissions)
            activate(repo, "2.0.0", setOf(SkillPermission.NETWORK))
        }
        LocalSkillRepository(root, host).use { repo ->
            assertEquals("Summarize 2.0.0", repo.activeInstructions().getValue("local.summary"))
            repo.rollback(consent(repo, "1.0.0"))
        }
        LocalSkillRepository(root, host).use { assertEquals("Summarize 1.0.0", it.activeInstructions().getValue("local.summary")) }
    } }

    @Test fun rollbackRestoresExactInstructionAndDifferentNonEmptyPermissionsAcrossRestarts() = workspace { base -> runTest {
        val root = base.resolve("repo")
        val oldPermissions = setOf(SkillPermission.READ_PROJECT)
        val newPermissions = setOf(SkillPermission.NETWORK)
        lateinit var original: SkillInstruction
        LocalSkillRepository(root, host).use { repo ->
            repo.install(entries("1.0.0", oldPermissions), source)
            review(repo); activate(repo, "1.0.0", oldPermissions)
            original = repo.active().single()
            repo.install(entries("2.0.0", newPermissions), source)
            review(repo, "2.0.0")
            assertFails { activate(repo, "2.0.0") }
            activate(repo, "2.0.0", newPermissions)
        }
        LocalSkillRepository(root, host).use { repo ->
            val current = repo.active().single()
            assertEquals("2.0.0", current.version)
            assertEquals("Summarize 2.0.0", current.text)
            assertEquals(newPermissions, current.permissions)
            assertNotEquals(original.checksum, current.checksum)
            val before = repo.snapshot()
            val bytesBefore = Files.readAllBytes(root.resolve("snapshot.json"))
            // Restoring a removed permission needs explicit consent even during rollback.
            assertFails { repo.rollback(consent(repo, "1.0.0")) }
            assertEquals(before, repo.snapshot())
            assertContentEquals(bytesBefore, Files.readAllBytes(root.resolve("snapshot.json")))
            repo.rollback(consent(repo, "1.0.0", oldPermissions))
            assertEquals(original, repo.active().single())
        }
        LocalSkillRepository(root, host).use { repo ->
            // Equality includes version, checksum, instruction, metadata and exact permission set.
            assertEquals(original, repo.active().single())
            assertEquals(oldPermissions, repo.active().single().permissions)
            assertFalse(SkillPermission.NETWORK in repo.active().single().permissions)
            assertEquals(mapOf("local.summary" to "local.summary@1.0.0"), repo.snapshot().active)
        }
    } }

    @Test fun panelChangeApprovalCannotGrantNewPermissionsWithoutSeparatePermissionApproval() = workspace { base -> runTest {
        LocalSkillRepository(base.resolve("repo"), host).use { repo ->
            repo.install(entries(), source); review(repo); activate(repo, "1.0.0")
            repo.install(entries("2.0.0", setOf(SkillPermission.NETWORK)), source); review(repo, "2.0.0")
            val preview = consent(repo, "2.0.0", setOf(SkillPermission.NETWORK))
            val target = mapOf("local.summary" to "local.summary@2.0.0")
            val before = repo.snapshot()
            val bytes = Files.readAllBytes(base.resolve("repo/snapshot.json"))
            for ((changes, permissions) in listOf(false to false, true to false, false to true)) {
                assertFails { repo.activate(target, LocalSkillsPlugin.activationApproval(preview, changes, permissions)) }
                assertEquals(before, repo.snapshot())
                assertContentEquals(bytes, Files.readAllBytes(base.resolve("repo/snapshot.json")))
            }
            repo.activate(target, LocalSkillsPlugin.activationApproval(preview, true, true))
            assertEquals(setOf(SkillPermission.NETWORK), repo.active().single().permissions)
            assertEquals("2.0.0", repo.active().single().version)
        }
    } }

    @Test fun interruptedCommitKeepsEntirePreviousSnapshot() = workspace { base -> runTest {
        val root = base.resolve("repo")
        var fail = false
        LocalSkillRepository(root, host, { if (fail) error("Injected interruption before commit") }).use { repo ->
            repo.install(entries(), source); review(repo); activate(repo, "1.0.0")
            val before = repo.snapshot()
            fail = true
            assertFails { repo.install(entries("2.0.0"), source) }
            assertEquals(before, repo.snapshot())
        }
        LocalSkillRepository(root, host).use { repo ->
            assertEquals(setOf("local.summary@1.0.0"), repo.snapshot().installed.keys)
            assertEquals("Summarize 1.0.0", repo.activeInstructions().getValue("local.summary"))
        }
    } }

    @Test fun interruptedActivationRetainsActiveAndRollbackSnapshot() = workspace { base -> runTest {
        val root = base.resolve("repo")
        var fail = false
        var previous = emptyMap<String, String>()
        LocalSkillRepository(root, host, { if (fail) error("Injected interrupted activation") }).use { repo ->
            repo.install(entries(), source); review(repo); activate(repo, "1.0.0")
            repo.install(entries("2.0.0"), source); review(repo, "2.0.0")
            val before = repo.snapshot(); previous = before.active
            fail = true
            assertFails { activate(repo, "2.0.0") }
            assertEquals(before, repo.snapshot())
        }
        LocalSkillRepository(root, host).use { assertEquals(previous, it.snapshot().active) }
    } }

    @Test fun backupRestoresExactFilesReviewsQuarantineAndBothVersionMaps() = workspace { base -> runTest {
        val backup = base.resolve("backup.json")
        var digest = ""
        var stale: SkillActivationConsent? = null
        LocalSkillRepository(base.resolve("repo"), host).use { repo ->
            repo.install(entries(), source); review(repo); activate(repo, "1.0.0")
            repo.install(entries("2.0.0"), source); review(repo, "2.0.0"); activate(repo, "2.0.0")
            repo.install(entries("3.0.0"), source)
            stale = consent(repo, "1.0.0")
            digest = repo.backup(backup)
        }
        val restoredRoot = base.resolve("restored")
        LocalSkillRepository(restoredRoot, host).use { repo ->
            assertFails { repo.restore(backup, digest, false) }
            assertFails { repo.restore(backup, "0".repeat(64), true) }
            repo.restore(backup, digest, true)
            assertFails { repo.rollback(stale!!) }
            assertEquals(3, repo.catalog().size)
        }
        LocalSkillRepository(restoredRoot, host).use { repo ->
            assertEquals(SkillCandidateStatus.QUARANTINED, repo.catalog().single { it.release.pkg.manifest.version == "3.0.0" }.release.status)
            assertEquals("Summarize 2.0.0", repo.activeInstructions().getValue("local.summary"))
            repo.rollback(consent(repo, "1.0.0"))
            assertEquals("Summarize 1.0.0", repo.activeInstructions().getValue("local.summary"))
        }
    } }

    @Test fun corruptSnapshotAndPayloadFailClosed() = workspace { base -> runTest {
        val root = base.resolve("repo")
        var checksum = ""
        LocalSkillRepository(root, host).use { repo -> repo.install(entries(), source); checksum = repo.catalog().single().release.pkg.checksum }
        val snapshot = Files.readAllBytes(root.resolve("snapshot.json"))
        Files.writeString(root.resolve("snapshot.json"), "broken")
        assertFails { LocalSkillRepository(root, host).close() }
        assertEquals("broken", Files.readString(root.resolve("snapshot.json")))
        Files.write(root.resolve("snapshot.json"), snapshot)
        Files.writeString(root.resolve("releases/$checksum/SKILL.md"), "tampered")
        assertFails { LocalSkillRepository(root, host).close() }
    } }

    @Test fun explicitRecoveryRepairsCorruptSnapshotAndPayloadWithoutExposingEmptyLibrary() = workspace { base -> runTest {
        val root = base.resolve("repo")
        val backup = base.resolve("backup.json")
        var digest = ""
        var checksum = ""
        LocalSkillRepository(root, host).use { repo ->
            repo.install(entries(), source); review(repo); activate(repo, "1.0.0")
            checksum = repo.catalog().single().release.pkg.checksum
            digest = repo.backup(backup)
        }
        Files.writeString(root.resolve("snapshot.json"), "corrupt")
        Files.writeString(root.resolve("releases/$checksum/SKILL.md"), "corrupt")
        LocalSkillRepository(root, host, allowRecovery = true).use { repo ->
            assertFails { repo.catalog() }
            assertFails { repo.snapshot() }
            assertFails { repo.install(entries("2.0.0"), source) }
            assertFails { repo.activeInstructions() }
            assertFails { repo.restore(backup, digest, false) }
            repo.restore(backup, digest, true)
            assertEquals("Summarize 1.0.0", repo.activeInstructions().getValue("local.summary"))
        }
        LocalSkillRepository(root, host).use { repo -> assertEquals(1, repo.catalog().size) }
        assertTrue(Files.isDirectory(root.resolve("damaged")))
    } }

    @Test fun nestedDirectoryImportReadsOnlyRegularFilesAndRejectsLinkedParent() = workspace { base ->
        val root = Files.createDirectory(base.resolve("input"))
        val nested = Files.createDirectory(root.resolve("resources"))
        Files.writeString(root.resolve("SKILL.md"), "Local instructions")
        Files.writeString(nested.resolve("example.txt"), "Resource")
        assertEquals(setOf("SKILL.md", "resources/example.txt"), SkillPackageImporter.readDirectory(root).map { it.path }.toSet())
        Files.delete(nested.resolve("example.txt")); Files.delete(nested)
        Files.createSymbolicLink(nested, base)
        assertFails { SkillPackageImporter.readDirectory(root) }
    }

    @Test fun secondWriterIsRejectedAndVersionBytesAreImmutable() = workspace { base -> runTest {
        LocalSkillRepository(base.resolve("repo"), host).use { repo ->
            assertFails { LocalSkillRepository(base.resolve("repo"), host).close() }
            repo.install(entries(), source)
            val before = repo.snapshot()
            repo.install(entries(), source)
            assertEquals(before, repo.snapshot())
            assertFails { repo.install(entries(permissions = setOf(SkillPermission.NETWORK)), source) }
            assertEquals(before, repo.snapshot())
        }
    } }

    @Test fun localDirectoryAndZipPassSameValidatorWithoutExecutingScripts() = workspace { base -> runTest {
        val dir = Files.createDirectory(base.resolve("input"))
        val marker = base.resolve("executed-by-import")
        val directoryEntries = entries(executionMarker = marker)
        directoryEntries.forEach { Files.write(dir.resolve(it.path), it.bytes) }
        val zipEntries = entries("2.0.0", executionMarker = marker)
        val zip = Files.write(base.resolve("input.zip"), archive(zipEntries))
        LocalSkillRepository(base.resolve("repo"), host).use { repo ->
            val importer = SkillPackageImporter(repo, host)
            importer.directory(dir)
            importer.zip(zip)
            assertEquals(setOf(SkillImportKind.LOCAL_DIRECTORY, SkillImportKind.ZIP), repo.catalog().map { it.source.kind }.toSet())
            assertTrue(repo.catalog().all { it.release.status == SkillCandidateStatus.QUARANTINED })
            assertFalse(Files.exists(marker), "Import must not execute install.sh, regardless of working directory")
            for (original in listOf(directoryEntries, zipEntries)) {
                val pkg = SkillPackageValidator(host).validate(original)
                assertContentEquals(original.single { it.path == "install.sh" }.bytes,
                    Files.readAllBytes(base.resolve("repo/releases/${pkg.checksum}/install.sh")))
            }
        }
    } }

    @Test fun looseSkillRequiresPreviewAndKeepsUnknownMetadata() = workspace { base -> runTest {
        val dir = Files.createDirectory(base.resolve("input"))
        Files.writeString(dir.resolve("SKILL.md"), "Instructions")
        LocalSkillRepository(base.resolve("repo"), host).use { repo ->
            val importer = SkillPackageImporter(repo, host)
            assertFails { importer.directory(dir) }
            importer.directory(dir, SkillLocalMetadata("local.loose", "1.0.0", "Loose", "Use locally", SkillCompatibility("1.0.0", "2.0.0", setOf("desktop"))))
            assertNull(repo.catalog().single().release.pkg.manifest.license)
        }
    } }

    @Test fun directoryRejectsSymlinkAndZipRejectsTraversalDuplicatesAndUnixLinks() = workspace { base ->
        val dir = Files.createDirectory(base.resolve("input"))
        Files.createSymbolicLink(dir.resolve("SKILL.md"), Files.writeString(base.resolve("outside"), "secret"))
        assertFails { SkillPackageImporter.readDirectory(dir) }
        assertFails { SkillPackageImporter.unzip(archive(listOf(SkillArchiveEntry("../escape", byteArrayOf())))) }
        assertFails { SkillPackageImporter.unzip(archive(listOf(SkillArchiveEntry("a", byteArrayOf()), SkillArchiveEntry("A", byteArrayOf())))) }
        val bytes = archive(entries())
        val central = (0..bytes.size - 4).first { bytes[it] == 0x50.toByte() && bytes[it + 1] == 0x4b.toByte() && bytes[it + 2] == 1.toByte() && bytes[it + 3] == 2.toByte() }
        bytes[central + 41] = 0xa0.toByte()
        assertFails { SkillPackageImporter.unzip(bytes) }
    }

    @Test fun zipRejectsExpansionBeyondActualStreamBudget() {
        assertFails { SkillPackageImporter.unzip(archive(listOf(SkillArchiveEntry("SKILL.md", ByteArray(SkillPackageFormat.MAX_PAYLOAD_BYTES.toInt() + 1))))) }
    }

    @Test fun httpsAndPinnedGitUseAnonymousSourceOnlyRequestsAndRemainQuarantined() = workspace { base -> runTest {
        val requests = mutableListOf<String>()
        val marker = base.resolve("executed-by-network-import")
        val data = entries(executionMarker = marker)
        val gitData = entries("2.0.0", executionMarker = marker)
        val pkg = SkillPackageValidator(host).validate(data)
        val commit = "a".repeat(40)
        LocalSkillRepository(base.resolve("repo"), host).use { repo ->
            val importer = SkillPackageImporter(repo, host, setOf("https://skills.example", "https://github.com", "https://codeload.github.com")) { url ->
                requests += url
                SkillPublicDownload.Download(if ("codeload" in url) archive(gitData, "repo-$commit/") else archive(data), url)
            }
            val release = SkillCatalogRelease(pkg.manifest, pkg.checksum, "https://skills.example/package.zip")
            assertFails { importer.https(release, false) }
            assertFails { importer.git("https://github.com/owner/repo", "main", true) }
            assertTrue(requests.isEmpty())
            importer.https(release, true)
            importer.git("https://github.com/owner/repo", commit, true)
            assertEquals(listOf(release.download, "https://codeload.github.com/owner/repo/zip/$commit"), requests)
            assertEquals(commit, repo.catalog().single { it.source.kind == SkillImportKind.GIT }.source.revision)
            assertFalse(Files.exists(marker), "HTTPS/Git imports must not execute install.sh")
            for (original in listOf(data, gitData)) {
                val validated = SkillPackageValidator(host).validate(original)
                assertContentEquals(original.single { it.path == "install.sh" }.bytes,
                    Files.readAllBytes(base.resolve("repo/releases/${validated.checksum}/install.sh")))
            }
            assertTrue(repo.catalog().all { it.release.status == SkillCandidateStatus.QUARANTINED })
        }
    } }

    @Test fun networkPolicyRejectsCredentialsQueriesUnapprovedOriginsAndNonPublicAddresses() {
        val client = SkillPublicDownload(setOf("https://example.org"))
        listOf("http://example.org/a", "https://user:secret@example.org/a", "https://example.org/a?token=secret", "https://example.org/a#x", "https://other.org/a", "https://example.org:8443/a").forEach {
            assertFails(it) { client.validateUrl(java.net.URI(it)) }
        }
        listOf("127.0.0.1", "10.0.0.1", "100.64.0.1", "169.254.169.254", "192.168.1.1", "192.0.2.1", "198.18.0.1", "224.0.0.1", "::1", "::ffff:127.0.0.1", "fc00::1", "fe80::1", "2001:db8::1", "2002:7f00:1::").forEach {
            assertFalse(SkillPublicDownload.isPublicAddress(java.net.InetAddress.getByName(it)), it)
        }
        assertTrue(SkillPublicDownload.isPublicAddress(java.net.InetAddress.getByName("8.8.8.8")))
        assertTrue(SkillPublicDownload.isPublicAddress(java.net.InetAddress.getByName("2606:4700:4700::1111")))
    }
}
