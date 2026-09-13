package io.aequicor.magicpaper.data.skills

import io.aequicor.magicpaper.domain.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.*

class SkillImportContractTest {
    private val host = SkillPackageHost("1.0.0", "desktop")
    private val metadata = SkillLocalMetadata("inline.skill", "1.0.1", "Inline", "Use inline text", SkillCompatibility("1.0.0", "2.0.0", setOf("desktop")))

    private fun packaged(version: String = "1.0.0"): List<SkillArchiveEntry> {
        val skill = "Instructions $version".encodeToByteArray()
        val manifest = SkillPackageManifest(
            id = "source.contract", version = version, name = "Contract", description = "Tests source reduction",
            files = listOf(SkillPackageFile("SKILL.md", SkillPackageValidator.sha256(skill), skill.size.toLong())),
            origin = SkillPackageOrigin(SkillImportKind.LOCAL_DIRECTORY, "author-claim"),
            compatibility = SkillCompatibility("1.0.0", "2.0.0", setOf("desktop")),
        )
        return listOf(
            SkillArchiveEntry(SkillPackageFormat.MANIFEST, SkillPackageFormat.json.encodeToString(manifest).encodeToByteArray()),
            SkillArchiveEntry("SKILL.md", skill),
        )
    }

    private fun zip(entries: List<SkillArchiveEntry>, prefix: String = ""): ByteArray = java.io.ByteArrayOutputStream().also { bytes ->
        ZipOutputStream(bytes).use { archive -> entries.forEach { entry ->
            archive.putNextEntry(ZipEntry(prefix + entry.path))
            archive.write(entry.bytes)
            archive.closeEntry()
        } }
    }.toByteArray()

    private fun workspace(block: (Path) -> Unit) {
        val root = Files.createTempDirectory("skill-import-contract-")
        try { block(root) } finally { Files.walk(root).use { it.sorted(Comparator.reverseOrder()).forEach(Files::delete) } }
    }

    @Test fun everySourcePreparesExactValidatedBytesAndImportOnlyQuarantines() = workspace { root -> runTest {
        val authored = packaged()
        val directory = Files.createDirectory(root.resolve("directory"))
        authored.forEach { Files.write(directory.resolve(it.path), it.bytes) }
        val archive = Files.write(root.resolve("package.zip"), zip(authored))
        val commit = "a".repeat(40)
        LocalSkillRepository(root.resolve("repo"), host).use { repository ->
            val importer = SkillPackageImporter(repository, host, setOf("https://github.com", "https://codeload.github.com")) { url ->
                SkillPublicDownload.Download(zip(packaged("1.0.2"), "repository-$commit/"), url)
            }
            val local = importer.prepareDirectory(directory)
            val zipped = importer.prepareZip(archive)
            val inline = importer.prepareSkillMarkdown("Remember this exact pattern.", metadata)

            assertEquals(local.pkg.checksum, zipped.pkg.checksum, "Equal package bytes have one checksum across directory and ZIP")
            assertEquals(SkillImportKind.LOCAL_DIRECTORY, local.source.kind)
            assertEquals(SkillImportKind.ZIP, zipped.source.kind)
            assertEquals(SkillImportKind.READY_TEXT, inline.source.kind)
            assertEquals("inline:sha256=${SkillPackageValidator.sha256("Remember this exact pattern.".encodeToByteArray())}", inline.source.location)
            val altered = inline.entries.toMutableList()
            val index = altered.indexOfFirst { it.path == "SKILL.md" }
            altered[index] = altered[index].copy(bytes = "changed".encodeToByteArray())
            assertContentEquals("Remember this exact pattern.".encodeToByteArray(), inline.entries.single { it.path == "SKILL.md" }.bytes)

            repository.install(local)
            repository.install(zipped)
            repository.install(inline)
            importer.git("https://github.com/owner/repository", commit, true)

            val snapshot = repository.snapshot()
            assertEquals(3, snapshot.installed.size, "Directory and ZIP deduplicate exact bytes")
            assertTrue(snapshot.active.isEmpty())
            assertTrue(snapshot.projects.isEmpty())
            assertTrue(repository.catalog().all { it.release.status == SkillCandidateStatus.QUARANTINED })
            assertEquals(SkillImportKind.GIT, repository.catalog().single { it.release.pkg.manifest.version == "1.0.2" }.source.kind)
        }
    } }

    @Test fun newImportVersionDoesNotChangeExistingProjectPin() = workspace { root -> runTest {
        val first = packaged("1.0.0")
        val next = packaged("1.0.1")
        val one = Files.createDirectory(root.resolve("one"))
        val two = Files.createDirectory(root.resolve("two"))
        first.forEach { Files.write(one.resolve(it.path), it.bytes) }
        next.forEach { Files.write(two.resolve(it.path), it.bytes) }
        LocalSkillRepository(root.resolve("repo"), host).use { repository ->
            val importer = SkillPackageImporter(repository, host)
            val original = importer.prepareDirectory(one)
            repository.install(original)
            repository.review(original.pkg.key, SkillPackageReview(original.pkg.checksum, "test", "reviewed", true, true, true))
            val beforeBind = repository.snapshot()
            val pins = mapOf(original.pkg.key to original.pkg.checksum)
            repository.bindProject("project", pins, SkillActivationConsent(beforeBind.generation, pins, true, emptySet()))

            val update = importer.prepareDirectory(two)
            repository.install(update)
            assertEquals(pins, repository.snapshot().projects.getValue("project"))
            assertEquals(original.pkg.checksum, repository.projectInstructions("project").single().checksum)
            assertEquals(SkillCandidateStatus.QUARANTINED, repository.catalog().single { it.release.pkg.key == update.pkg.key }.release.status)
        }
    } }

    @Test fun readyTextPreviewParsesFrontmatterBeforeAnyWriteAndConfirmationOnlyQuarantines() = workspace { root -> runTest {
        val markdown = """
            ---
            name: "Local summary"
            description: Summarize selected text
            ---
            # Summary
            Keep these bytes exactly.
        """.trimIndent().encodeToByteArray()
        LocalSkillRepository(root.resolve("repo"), host).use { repository ->
            val importer = SkillPackageImporter(repository, host)
            val prepared = importer.prepareSkillMarkdown(markdown.decodeToString(), metadata)

            assertEquals(mapOf("name" to "Local summary", "description" to "Summarize selected text"),
                SkillPackageImporter.parseSkillMarkdownFrontmatter(markdown.decodeToString())!!.fields)
            assertTrue(repository.snapshot().installed.isEmpty(), "Preview/cancellation must not write a release")

            repository.install(prepared) // This is the UI's separate explicit confirmation step.
            val release = repository.catalog().single()
            assertEquals(SkillCandidateStatus.QUARANTINED, release.release.status)
            assertTrue(repository.snapshot().active.isEmpty())
            assertTrue(repository.snapshot().projects.isEmpty())
            assertContentEquals(markdown, LocalSkillRepository.readLimited(root.resolve("repo/releases/${prepared.pkg.checksum}/SKILL.md"), markdown.size + 1))
        }
    } }

    @Test fun invalidReadyTextFrontmatterAndInvalidUtf8StringAreRejectedBeforePreviewWrite() = workspace { root -> runTest {
        LocalSkillRepository(root.resolve("repo"), host).use { repository ->
            val importer = SkillPackageImporter(repository, host)
            assertFailsWith<IllegalArgumentException> {
                importer.prepareSkillMarkdown("---\nname: missing closing delimiter", metadata)
            }
            assertFailsWith<IllegalArgumentException> {
                importer.prepareSkillMarkdown("---\nname: one\nname: two\n---\nbody", metadata)
            }
            assertFailsWith<IllegalArgumentException> {
                importer.prepareSkillMarkdown("bad \ud800 surrogate", metadata)
            }
            assertTrue(repository.snapshot().installed.isEmpty())
        }
    } }
}
