package io.aequicor.magicpaper.data.skills

import io.aequicor.magicpaper.domain.*
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

/** Explicit network acceptance, never silently reported as an offline PASS. */
class GithubSkillCatalogLiveTest {
    @Test fun liveSearchAndTypedLinkImport() = runBlocking {
        org.junit.Assume.assumeTrue("Set MAGICPAPER_SKILL_CATALOG_LIVE=true", System.getenv("MAGICPAPER_SKILL_CATALOG_LIVE") == "true")
        val catalog = GithubSkillCatalog()
        val output = Files.createDirectories(Path.of("build/reports/skills-catalog-live"))
        val root = output.resolve("repository-" + System.nanoTime())
        val source = catalog.search("debugging").single()
        val fromSearch = catalog.discover(source.url, true)
        val first = catalog.preview(fromSearch, "skills/systematic-debugging/SKILL.md")
        // Independent entered mutable-ref URL resolves through the real public API to a full SHA.
        val fromLink = catalog.discover("https://github.com/obra/superpowers/tree/v4.0.0", true)
        val second = catalog.preview(fromLink, "skills/verification-before-completion/SKILL.md")
        LocalSkillRepository(root, GithubSkillCatalog.HOST).use { repo ->
            catalog.install(repo, first); catalog.install(repo, second)
            val state = repo.snapshot()
            assertEquals(state, catalog.install(repo, first))
            assertEquals(2, state.installed.size)
            assertTrue(state.active.isEmpty() && state.projects.isEmpty() && state.projectTextConsents.isEmpty())
            assertTrue(state.installed.values.all { it.status == SkillCandidateStatus.QUARANTINED })
            Files.writeString(output.resolve("imports.txt"), repo.catalog().joinToString("\n") { "${it.release.pkg.key}\n${it.source}\nchecksum=${it.release.pkg.checksum}\nlicense=${it.release.pkg.manifest.license ?: "UNKNOWN-BLOCKED-UNTIL-REVIEW"}\n" })
        }
        // Reopened storage is given no network transport.
        LocalSkillRepository(root, GithubSkillCatalog.HOST).use { repo ->
            assertEquals(1, repo.catalog("systematic-debugging").size)
            assertEquals(1, repo.catalog("verification-before-completion").size)
            assertEquals(first.original, repo.diff(repo.catalog("systematic-debugging").single().release.pkg.key).newInstructions)
        }
    }
}
