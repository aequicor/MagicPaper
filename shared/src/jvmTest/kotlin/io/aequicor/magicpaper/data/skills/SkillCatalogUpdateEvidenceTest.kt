package io.aequicor.magicpaper.data.skills

import androidx.compose.material3.Surface
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.*
import androidx.compose.ui.use
import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.plugins.builtin.SkillCatalogPanel
import io.aequicor.magicpaper.ui.theme.MagicPaperTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds

/** Fixture network; actual production update action, comparison rendering and import. */
@OptIn(ExperimentalComposeUiApi::class, ExperimentalCoroutinesApi::class)
class SkillCatalogUpdateEvidenceTest {
    @Test fun updateDisplaysOldAndNewChecksumsInstructionsAndResourceDiffBeforeImport() = runTest(timeout = 60.seconds) {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val output = Files.createDirectories(Path.of("build/reports/skills-catalog-update"))
        val root = Files.createTempDirectory(output, "repository-")
        val sha1 = "1".repeat(40); val sha2 = "2".repeat(40)
        val path = "skills/example/SKILL.md"
        fun archive(updated: Boolean): ByteArray = ByteArrayOutputStream().also { bytes ->
            ZipOutputStream(bytes).use { zip ->
                val files = mapOf(path to if (updated) "NEW INSTRUCTION" else "OLD INSTRUCTION",
                    "skills/example/changed.txt" to if (updated) "new resource" else "old resource",
                    "skills/example/${if (updated) "added" else "removed"}.txt" to "resource")
                files.forEach { (p, text) -> zip.putNextEntry(ZipEntry("repo/$p")); zip.write(text.toByteArray()); zip.closeEntry() }
            }
        }.toByteArray()
        val requests = mutableListOf<String>()
        val catalog = GithubSkillCatalog { url ->
            requests += url
            SkillPublicDownload.Download(if ("api.github.com" in url) "{\"sha\":\"$sha2\"}".toByteArray() else archive(url.endsWith(sha2)), url)
        }
        try {
            LocalSkillRepository(root, GithubSkillCatalog.HOST).use { repo ->
                val old = catalog.preview(catalog.discover("https://github.com/owner/repo/tree/$sha1", true), path)
                val first = catalog.install(repo, old).installed.values.single().pkg
                repo.review(first.key, SkillPackageReview(first.checksum, "fixture", "Fixture evidence only", true, true, true))
                repo.bindProject("A", mapOf(first.key to first.checksum), SkillActivationConsent(repo.snapshot().generation,
                    mapOf(first.key to first.checksum), true, emptySet(), trustedCodingText = true))
                val before = repo.snapshot()
                requests.clear()
                ImageComposeScene(1000, 760) { MagicPaperTheme { Surface { SkillCatalogPanel({ repo }, { catalog }) {} } } }.use { scene ->
                    var frame = 0L
                    suspend fun pump() { scene.render(++frame * 16_000_000L).close(); withContext(Dispatchers.Default) { delay(20) }; runCurrent() }
                    fun nodes(): List<SemanticsNode> {
                        fun walk(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::walk)
                        return scene.semanticsOwners.flatMap { walk(it.rootSemanticsNode) }
                    }
                    fun label(n: SemanticsNode) = n.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }.orEmpty()
                    fun text() = nodes().joinToString("\n", transform = ::label)
                    suspend fun click(label: String) {
                        val n = nodes().single { label(it) == label && it.config.getOrNull(SemanticsActions.OnClick) != null }
                        assertNull(n.config.getOrNull(SemanticsProperties.Disabled)); assertTrue(n.config[SemanticsActions.OnClick].action!!.invoke())
                        repeat(3) { pump() }
                    }
                    suspend fun await(check: suspend () -> Boolean) {
                        val deadline = System.nanoTime() + 10_000_000_000L
                        while (!check()) { kotlin.check(System.nanoTime() < deadline) { text() }; pump() }
                        repeat(4) { pump() }
                    }
                    repeat(8) { pump() }
                    click("Проверить обновление…")
                    assertTrue(requests.isEmpty(), "Update selection must not send before network consent")
                    val checkbox = nodes().single { it.config.getOrNull(SemanticsProperties.ToggleableState) != null }
                    checkbox.config[SemanticsActions.OnClick].action!!.invoke(); repeat(3) { pump() }
                    click("Найти пакеты по ссылке"); await { nodes().any { label(it) == path } }
                    click(path); await { text().contains("Добавлены:") }
                    val rendered = text()
                    val next = catalog.preview(catalog.discover("https://github.com/owner/repo/tree/$sha2", true), path)
                    assertTrue(rendered.contains("До:") && rendered.contains("После (метаданные адаптера"))
                    for (expected in listOf(first.checksum, next.checksum, "OLD INSTRUCTION", "NEW INSTRUCTION",
                        "Добавлены: [added.txt]", "удалены: [removed.txt]", "изменены: [SKILL.md, changed.txt]")) assertTrue(rendered.contains(expected), expected)
                    assertEquals(before, repo.snapshot(), "Comparison is read-only")
                    Files.writeString(output.resolve("rendered-diff.txt"), rendered)
                    click("Импортировать без подключения"); await { repo.catalog().size == 2 }
                    val second = repo.snapshot().installed.values.single { it.pkg.checksum == next.checksum }
                    assertEquals(SkillCandidateStatus.QUARANTINED, second.status); assertNull(second.review)
                    assertEquals(before.projects, repo.snapshot().projects)
                    assertEquals(before.projectTextConsents, repo.snapshot().projectTextConsents)
                    val target = mapOf(second.pkg.key to next.checksum)
                    assertFailsWith<IllegalArgumentException> { repo.bindProject("A", target, SkillActivationConsent(repo.snapshot().generation, target, true, emptySet())) }
                    repo.review(second.pkg.key, SkillPackageReview(next.checksum, "fixture", "New checksum review", true, true, true))
                    repo.bindProject("A", target, SkillActivationConsent(repo.snapshot().generation, target, true, emptySet()))
                    assertFalse(repo.projectCodingSelection("A").trustedText)
                    Files.writeString(output.resolve("requests.txt"), requests.joinToString("\n"))
                }
            }
        } finally { Dispatchers.resetMain() }
    }
}
