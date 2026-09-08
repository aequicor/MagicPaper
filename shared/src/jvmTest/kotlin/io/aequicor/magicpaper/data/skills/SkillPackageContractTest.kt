package io.aequicor.magicpaper.data.skills

import io.aequicor.magicpaper.domain.*
import java.nio.file.Files
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlin.test.*

class SkillPackageContractTest {
    private val host = SkillPackageHost("1.0.0", "desktop")
    private val validator = SkillPackageValidator(host)
    private val instructions = "Summarize the selected text.".encodeToByteArray()

    private fun manifest(id: String = "local.summarize", version: String = "1.0.0") = SkillPackageManifest(
        id = id, version = version, name = "Summarize", description = "For summaries",
        files = listOf(SkillPackageFile("SKILL.md", SkillPackageValidator.sha256(instructions), instructions.size.toLong())),
        origin = SkillPackageOrigin(SkillImportKind.LOCAL_DIRECTORY),
        compatibility = SkillCompatibility("1.0.0", "2.0.0", setOf("desktop")),
    )

    private fun entries(m: SkillPackageManifest = manifest()) = listOf(
        SkillArchiveEntry(SkillPackageFormat.MANIFEST, SkillPackageFormat.json.encodeToString(m).encodeToByteArray()),
        SkillArchiveEntry("SKILL.md", instructions.copyOf()),
    )

    private fun pkg(m: SkillPackageManifest = manifest()) = validator.validate(entries(m))

    private suspend fun verified(store: SkillReleaseStore, p: ValidatedSkillPackage) {
        store.install(p)
        store.review(p.key, SkillPackageReview(p.checksum, "local-user", "Source, license and instructions inspected", true, true, true))
    }

    private suspend fun consent(store: SkillReleaseStore, vararg packages: ValidatedSkillPackage, permissions: Set<SkillPermission> = emptySet()) =
        SkillActivationConsent(store.snapshot().generation, packages.associate { it.key to it.checksum }, true, permissions)

    @Test fun acceptsValidPackageAndCatalog() {
        val data = entries()
        val release = SkillCatalogRelease(manifest(), SkillPackageValidator.sha256(data.first().bytes), "https://example.org/skill.zip")
        assertEquals(manifest(), validator.validateRelease(data, release).manifest)
        assertEquals(1, validator.validateSet(listOf(pkg())).size)
    }

    @Test fun sha256UsesStandardDigest() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", SkillPackageValidator.sha256("abc".encodeToByteArray()))
    }

    @Test fun rejectsCorruptionAndWrongCatalogChecksum() {
        assertFails { validator.validate(entries().dropLast(1) + SkillArchiveEntry("SKILL.md", "changed".encodeToByteArray())) }
        assertFails { validator.validate(entries(), "0".repeat(64)) }
        assertFails { validator.validateRelease(entries(), SkillCatalogRelease(manifest().copy(description = "forged"), pkg().checksum, "https://example.org/a.zip")) }
    }

    @Test fun rejectsUnsupportedSchemaPlatformAndVersion() {
        listOf(
            manifest().copy(schemaVersion = 2), manifest().copy(version = "01.0.0"),
            manifest().copy(version = "1.0.0-beta"), manifest().copy(version = "999999999999.0.0"),
            manifest().copy(compatibility = SkillCompatibility("2.0.0", "3.0.0", setOf("desktop"))),
            manifest().copy(compatibility = SkillCompatibility("0.0.0", "1.0.0", setOf("desktop"))),
            manifest().copy(compatibility = SkillCompatibility("1.0.0", "2.0.0", setOf("android"))),
        ).forEach { m -> assertFails { pkg(m) } }
    }

    @Test fun rejectsTraversalAliasesLinksAndSpecialFilesBeforeExtraction() {
        listOf("../escape", "/tmp/escape", "a/../../escape", "a\\b", "C:/file", "a//b", "./SKILL.md", "%2e%2e/escape", "a/CON.txt", "a.").forEach { path ->
            assertFails(path) { validator.validate(entries() + SkillArchiveEntry(path, byteArrayOf())) }
        }
        assertFails { validator.validate(entries().dropLast(1) + SkillArchiveEntry("SKILL.md", instructions, regularFile = false)) }
        assertFails { validator.validate(entries() + SkillArchiveEntry("skill.MD", instructions)) }
    }

    @Test fun rejectsUnlistedMissingOversizedAndInvalidInstructions() {
        assertFails { validator.validate(entries() + SkillArchiveEntry("extra.txt", byteArrayOf())) }
        assertFails { validator.validate(entries().take(1)) }
        assertFails { pkg(manifest().copy(files = listOf(manifest().files.single().copy(size = Long.MAX_VALUE)))) }
        assertFails { pkg(manifest().copy(instructions = "absent.md")) }
        val blank = "  ".encodeToByteArray()
        val m = manifest().copy(files = listOf(SkillPackageFile("SKILL.md", SkillPackageValidator.sha256(blank), 2)))
        assertFails { validator.validate(entries(m).take(1) + SkillArchiveEntry("SKILL.md", blank)) }
    }

    @Test fun rejectsFileDirectoryCollisionsAndPublisherTrustFlags() {
        val m = manifest().copy(files = manifest().files + SkillPackageFile("SKILL.md/child", SkillPackageValidator.sha256(byteArrayOf()), 0))
        assertFails { validator.validate(entries(m) + SkillArchiveEntry("SKILL.md/child", byteArrayOf())) }
        val original = entries()
        val forged = original.first().bytes.decodeToString().dropLast(1) + ",\"verified\":true}"
        assertFails { validator.validate(listOf(SkillArchiveEntry(SkillPackageFormat.MANIFEST, forged.encodeToByteArray()), original.last())) }
    }

    @Test fun rejectsMissingConflictingAndCyclicDependencies() {
        val a = pkg(manifest("a").copy(dependencies = listOf(SkillPackageDependency("b", "1.0.0"))))
        val b = pkg(manifest("b").copy(dependencies = listOf(SkillPackageDependency("a", "1.0.0"))))
        assertFails { validator.validateSet(listOf(a)) }
        assertFails { validator.validateSet(listOf(a, b)) }
        assertFails { validator.validateSet(listOf(a, pkg(manifest("b", "2.0.0")))) }
        assertFails { validator.validateSet(listOf(pkg(), pkg(manifest(version = "2.0.0")))) }
        assertFails { validator.validateSet(listOf(pkg(manifest("a").copy(dependencies = listOf(SkillPackageDependency("a", "1.0.0")))))) }
        assertEquals(2, validator.validateSet(listOf(a, pkg(manifest("b")))).size)
    }

    @Test fun rejectsInvalidCatalogSourcesAndDuplicates() {
        val r = SkillCatalogRelease(manifest(), pkg().checksum, "https://example.org/a")
        assertFails { validator.validateCatalog(SkillPackageCatalog(schemaVersion = 2, releases = listOf(r))) }
        assertFails { validator.validateCatalog(SkillPackageCatalog(releases = listOf(r, r))) }
        listOf("http://example.org/a", "file:///a", "https://user:password@example.org/a").forEach {
            assertFails { validator.validateCatalog(SkillPackageCatalog(releases = listOf(r.copy(download = it)))) }
        }
    }

    @Test fun directoryVerifierRejectsEscapingSymlink() {
        val root = Files.createTempDirectory("skill-contract-")
        val outside = Files.createTempFile("skill-outside-", ".md")
        try {
            entries().forEach { Files.write(root.resolve(it.path), it.bytes) }
            assertEquals(pkg().checksum, validator.validateDirectory(root).checksum)
            Files.delete(root.resolve("SKILL.md"))
            Files.createSymbolicLink(root.resolve("SKILL.md"), outside)
            assertFails { validator.validateDirectory(root) }
        } finally {
            Files.deleteIfExists(root.resolve("SKILL.md"))
            Files.deleteIfExists(root.resolve(SkillPackageFormat.MANIFEST))
            Files.deleteIfExists(root)
            Files.deleteIfExists(outside)
        }
    }

    @Test fun checksumsAndDeclaredLicenseNeverGrantTrust() = runTest {
        val store = SkillReleaseStore(host)
        val p = pkg(manifest().copy(license = "MIT", origin = SkillPackageOrigin(SkillImportKind.HTTPS_PACKAGE, "https://example.org/a", "publisher")))
        val installed = store.install(p)
        assertTrue(installed.active.isEmpty())
        assertEquals(SkillCandidateStatus.QUARANTINED, installed.installed.getValue(p.key).status)
        assertFails { store.activate(mapOf(p.manifest.id to p.key), consent(store, p)) }
    }

    @Test fun unknownOriginOrLicenseBlocksUntilLocalReview() = runTest {
        val store = SkillReleaseStore(host)
        val p = pkg()
        store.install(p)
        for ((origin, license) in listOf(false to true, true to false)) {
            store.review(p.key, SkillPackageReview(p.checksum, "user", "pending investigation", origin, license, true))
            assertFails { store.activate(mapOf(p.manifest.id to p.key), consent(store, p)) }
        }
        verified(store, p)
        assertEquals(p.key, store.activate(mapOf(p.manifest.id to p.key), consent(store, p)).active[p.manifest.id])
    }

    @Test fun reviewAndConsentAreBoundToExactRelease() = runTest {
        val store = SkillReleaseStore(host)
        val p = pkg()
        store.install(p)
        assertFails { store.review(p.key, SkillPackageReview("0".repeat(64), "user", "review", true, true, true)) }
        verified(store, p)
        val approval = consent(store, p)
        assertFails { store.activate(mapOf(p.manifest.id to p.key), approval.copy(targetChecksums = emptyMap())) }
        assertFails { store.activate(mapOf(p.manifest.id to p.key), approval.copy(reviewedChanges = false)) }
        store.install(pkg(manifest("other")))
        assertFails { store.activate(mapOf(p.manifest.id to p.key), approval) }
    }

    @Test fun updateKeepsActiveVersionAndRequiresNewPermissions() = runTest {
        val store = SkillReleaseStore(host)
        val old = pkg()
        verified(store, old)
        store.activate(mapOf(old.manifest.id to old.key), consent(store, old))
        val update = pkg(manifest(version = "1.1.0").copy(permissions = setOf(SkillPermission.NETWORK)))
        verified(store, update)
        val before = store.snapshot()
        assertEquals(old.key, before.active[old.manifest.id])
        assertEquals(2, before.installed.size)
        assertFails { store.activate(mapOf(update.manifest.id to update.key), consent(store, update)) }
        assertEquals(before, store.snapshot())
        val activated = store.activate(mapOf(update.manifest.id to update.key), consent(store, update, permissions = setOf(SkillPermission.NETWORK)))
        assertEquals(update.key, activated.active[old.manifest.id])
        assertEquals(before.active, store.rollback(consent(store, old)).active)
    }

    @Test fun refusesReplacingVersionWithDifferentBytes() = runTest {
        val store = SkillReleaseStore(host)
        val p = pkg()
        store.install(p)
        val before = store.snapshot()
        assertFails { store.install(pkg(manifest().copy(description = "changed"))) }
        assertEquals(before, store.snapshot())
    }

    @Test fun permissionsCannotBeBorrowedFromAnotherSkillOrRestoredSilently() = runTest {
        val store = SkillReleaseStore(host)
        val a = pkg(manifest("a").copy(permissions = setOf(SkillPermission.NETWORK)))
        val b = pkg(manifest("b").copy(permissions = setOf(SkillPermission.NETWORK)))
        verified(store, a)
        verified(store, b)
        store.activate(mapOf("a" to a.key), consent(store, a, permissions = setOf(SkillPermission.NETWORK)))
        assertFails { store.activate(mapOf("a" to a.key, "b" to b.key), consent(store, a, b)) }
        store.activate(emptyMap(), consent(store))
        val before = store.snapshot()
        assertFails { store.rollback(consent(store, a)) }
        assertEquals(before, store.snapshot())
        assertEquals(mapOf("a" to a.key), store.rollback(consent(store, a, permissions = setOf(SkillPermission.NETWORK))).active)
    }

    @Test fun activationAndRollbackSwitchEntireDependencyGraphAtomically() = runTest {
        val store = SkillReleaseStore(host)
        val b1 = pkg(manifest("b"))
        val a1 = pkg(manifest("a").copy(dependencies = listOf(SkillPackageDependency("b", "1.0.0"))))
        val b2 = pkg(manifest("b", "2.0.0"))
        val a2 = pkg(manifest("a", "2.0.0").copy(dependencies = listOf(SkillPackageDependency("b", "2.0.0"))))
        listOf(a1, b1, a2, b2).forEach { verified(store, it) }
        val first = mapOf("a" to a1.key, "b" to b1.key)
        store.activate(first, consent(store, a1, b1))
        val before = store.snapshot()
        assertFails { store.activate(mapOf("a" to a2.key, "b" to b1.key), consent(store, a2, b1)) }
        assertEquals(before, store.snapshot())
        store.activate(mapOf("a" to a2.key, "b" to b2.key), consent(store, a2, b2))
        assertEquals(first, store.rollback(consent(store, a1, b1)).active)
    }

    @Test fun concurrentActivationRejectsStaleApproval() = runTest {
        val store = SkillReleaseStore(host)
        val p = pkg()
        verified(store, p)
        val approval = consent(store, p)
        val results = List(2) { async { runCatching { store.activate(mapOf(p.manifest.id to p.key), approval) } } }.awaitAll()
        assertEquals(1, results.count { it.isSuccess })
        assertEquals(1, results.count { it.isFailure })
    }
}
