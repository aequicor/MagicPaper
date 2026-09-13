package io.aequicor.magicpaper.data.skills

import io.aequicor.magicpaper.domain.*
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import java.nio.file.Files
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.*

class GithubSkillCatalogTest {
    private val sha = "1234567890abcdef1234567890abcdef12345678"
    private fun archive(): ByteArray = ByteArrayOutputStream().also { bytes ->
        ZipOutputStream(bytes).use { zip ->
            mapOf("skills/one/SKILL.md" to "---\nname: One\n---\nReview carefully", "skills/one/install.sh" to "touch /DO-NOT-EXECUTE", "skills/two/SKILL.md" to "Second package", "LICENSE" to "License evidence, not a trusted declaration").forEach { (path, text) ->
                zip.putNextEntry(ZipEntry("repo-$sha/$path")); zip.write(text.toByteArray()); zip.closeEntry()
            }
        }
    }.toByteArray()

    @Test fun selectionDedupeQuarantineReviewAndOfflineRestart() = runBlocking {
        val requests = mutableListOf<String>()
        val catalog = GithubSkillCatalog { url -> requests += url; SkillPublicDownload.Download(if ("api.github.com" in url) "{\"sha\":\"$sha\"}".toByteArray() else archive(), url) }
        val d = catalog.discover("https://github.com/owner/repo", true)
        assertEquals(2, d.packages.size)
        assertEquals(sha, d.commit)
        val p = catalog.preview(d, "skills/one/SKILL.md")
        assertNull(p.manifest.license)
        assertTrue(p.original.startsWith("---"))
        assertTrue(p.manifest.files.any { it.path == "install.sh" })
        assertFalse(p.manifest.files.any { it.path.contains("two") })
        val root = Files.createTempDirectory("catalog-test-")
        try {
            LocalSkillRepository(root, GithubSkillCatalog.HOST).use { repo ->
                val first = catalog.install(repo, p)
                assertEquals(first, catalog.install(repo, p))
                assertEquals(SkillCandidateStatus.QUARANTINED, first.installed.values.single().status)
                assertTrue(first.active.isEmpty()); assertTrue(first.projects.isEmpty()); assertTrue(first.projectTextConsents.isEmpty())
                val key = first.installed.keys.single()
                repo.review(key, SkillPackageReview(p.checksum, "test-reviewer", "Fixture evidence only", true, true, true))
                val s = repo.snapshot()
                repo.bindProject("A", mapOf(key to p.checksum), SkillActivationConsent(s.generation, mapOf(key to p.checksum), true, emptySet()))
                assertFalse(repo.projectCodingSelection("A").trustedText)
            }
            LocalSkillRepository(root, GithubSkillCatalog.HOST).use { repo ->
                val found = repo.catalog("one").single()
                assertEquals(sha, found.source.revision)
                assertEquals(p.checksum, found.release.pkg.checksum)
                assertEquals(p.original, repo.diff(found.release.pkg.key).newInstructions)
                assertFalse(repo.projectCodingSelection("A").trustedText)
            }
            assertEquals(listOf("https://api.github.com/repos/owner/repo/commits/HEAD", "https://codeload.github.com/owner/repo/zip/$sha"), requests)
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun newChecksumKeepsProjectPinAndRequiresNewReviewAndOptIn() = runBlocking {
        val catalog = GithubSkillCatalog { url -> SkillPublicDownload.Download(archive(), url) }
        val d = catalog.discover("https://github.com/owner/repo/tree/$sha", true)
        val p1 = catalog.preview(d, "skills/one/SKILL.md")
        val p2 = catalog.preview(d.copy(commit = "abcdef1234567890abcdef1234567890abcdef12",
            files = d.files.map { if (it.path == "skills/one/SKILL.md") it.copy(bytes = "Changed instruction".toByteArray()) else it }), "skills/one/SKILL.md")
        assertEquals(p1.manifest.id, p2.manifest.id); assertNotEquals(p1.checksum, p2.checksum)
        val root = Files.createTempDirectory("catalog-update-")
        try {
            LocalSkillRepository(root, GithubSkillCatalog.HOST).use { repo ->
                val first = catalog.install(repo, p1).installed.values.single().pkg
                repo.review(first.key, SkillPackageReview(first.checksum, "fixture", "Fixture only", true, true, true))
                repo.bindProject("A", mapOf(first.key to first.checksum), SkillActivationConsent(repo.snapshot().generation, mapOf(first.key to first.checksum), true, emptySet(), trustedCodingText = true))
                catalog.install(repo, p2)
                assertEquals(first.checksum, repo.projectCodingSelection("A").instructions.single().checksum)
                val second = repo.snapshot().installed.values.single { it.pkg.checksum == p2.checksum }.pkg
                assertFailsWith<IllegalArgumentException> { repo.bindProject("A", mapOf(second.key to second.checksum), SkillActivationConsent(repo.snapshot().generation, mapOf(second.key to second.checksum), true, emptySet())) }
                repo.review(second.key, SkillPackageReview(second.checksum, "fixture", "New fixture review", true, true, true))
                repo.bindProject("A", mapOf(second.key to second.checksum), SkillActivationConsent(repo.snapshot().generation, mapOf(second.key to second.checksum), true, emptySet()))
                assertFalse(repo.projectCodingSelection("A").trustedText)
            }
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun authoredPackageKeepsExactPayloadWhenRepositoryHasAnExternalLicense() = runBlocking {
        val text = "Authored instruction".encodeToByteArray()
        val manifest = SkillPackageManifest(id = "authored.skill", version = "1.0.0", name = "Authored", description = "Packaged skill",
            files = listOf(SkillPackageFile("SKILL.md", SkillPackageValidator.sha256(text), text.size.toLong())),
            origin = SkillPackageOrigin(SkillImportKind.GIT),
            compatibility = SkillCompatibility("1.0.0", "2.0.0", setOf("desktop")))
        val entries = listOf(SkillArchiveEntry("SKILL.md", text),
            SkillArchiveEntry(SkillPackageFormat.MANIFEST, SkillPackageFormat.json.encodeToString(manifest).encodeToByteArray()))
        val expected = SkillPackageValidator(GithubSkillCatalog.HOST).validate(entries)
        val files = entries.map { it.copy(path = "skills/authored/${it.path}") } +
            SkillArchiveEntry("LICENSE", "Repository license evidence".encodeToByteArray())
        val catalog = GithubSkillCatalog { error("No network needed") }
        val preview = catalog.preview(GithubSkillCatalog.Discovery("https://github.com/owner/repo", sha, files),
            "skills/authored/SKILL.md")
        assertEquals(expected.checksum, preview.checksum)
        assertEquals(manifest, preview.manifest)
        assertEquals("Repository license evidence", preview.licenseEvidence)
        val root = Files.createTempDirectory("catalog-authored-")
        try {
            LocalSkillRepository(root, GithubSkillCatalog.HOST).use { repository ->
                val installed = catalog.install(repository, preview).installed.getValue(expected.key)
                assertEquals(SkillCandidateStatus.QUARANTINED, installed.status)
                assertEquals(expected.checksum, installed.pkg.checksum)
                assertEquals("Authored instruction", repository.diff(expected.key).newInstructions)
            }
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun cancellationDuringBlockingFetchDiscardsLateDownload() = runBlocking {
        val started = java.util.concurrent.CountDownLatch(1)
        val finish = java.util.concurrent.CountDownLatch(1)
        var published = false
        val catalog = GithubSkillCatalog { url -> started.countDown(); check(finish.await(10, java.util.concurrent.TimeUnit.SECONDS)); SkillPublicDownload.Download(archive(), url) }
        val task = launch(Dispatchers.Default) { catalog.discover("https://github.com/owner/repo/tree/$sha", true); published = true }
        check(started.await(10, java.util.concurrent.TimeUnit.SECONDS))
        task.cancel(); finish.countDown(); task.join()
        assertFalse(published)
    }

    @Test fun failureAndCancellationNeverInstallAndUnapprovedRequestsAreNotSent() = runBlocking {
        var requests = 0
        val catalog = GithubSkillCatalog { requests++; error("network failure") }
        for (url in listOf("http://github.com/a/b", "https://user:secret@github.com/a/b", "https://github.com/a/b?token=secret", "https://localhost/a/b")) {
            assertFailsWith<IllegalArgumentException> { catalog.discover(url, true) }
        }
        assertFailsWith<IllegalArgumentException> { catalog.discover("https://github.com/a/b", false) }
        assertEquals(0, requests)
        assertFailsWith<IllegalStateException> { catalog.discover("https://github.com/a/b", true) }
        val root = Files.createTempDirectory("catalog-cancel-")
        try {
            LocalSkillRepository(root, GithubSkillCatalog.HOST).use { repo ->
                val s = repo.snapshot()
                val fixture = GithubSkillCatalog { url -> SkillPublicDownload.Download(archive(), url) }
                val p = fixture.preview(fixture.discover("https://github.com/a/b/tree/$sha", true), "skills/one/SKILL.md")
                val task = launch(start = CoroutineStart.LAZY) { fixture.install(repo, p) }
                task.cancel(); task.join()
                assertEquals(s, repo.snapshot())
            }
        } finally { root.toFile().deleteRecursively() }
    }
}
