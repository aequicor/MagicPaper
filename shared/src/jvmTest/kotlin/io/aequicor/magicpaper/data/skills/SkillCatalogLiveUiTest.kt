package io.aequicor.magicpaper.data.skills

import androidx.compose.material3.Surface
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.use
import io.aequicor.magicpaper.plugins.builtin.ProjectSkillsPanel
import io.aequicor.magicpaper.ui.theme.MagicPaperTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds

/** Real Compose actions and real anonymous HTTP, not injected catalog callbacks or fixture bytes. */
@OptIn(ExperimentalComposeUiApi::class, ExperimentalCoroutinesApi::class)
class SkillCatalogLiveUiTest {
    @Test fun searchAndEnteredLinkThroughProjectCatalog() = runTest(timeout = 180.seconds) {
        org.junit.Assume.assumeTrue("Set MAGICPAPER_SKILL_CATALOG_LIVE=true", System.getenv("MAGICPAPER_SKILL_CATALOG_LIVE") == "true")
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val output = Files.createDirectories(Path.of("build/reports/skills-catalog-live-ui"))
        val root = output.resolve("repository-" + System.nanoTime())
        val trace = mutableListOf("Production Compose UI + real GitHub; headless semantics, not manual desktop")
        var installed = emptyMap<String, String>()
        suspend fun verifyStored(repo: LocalSkillRepository) {
            val entries = repo.catalog()
            assertEquals(2, entries.size)
            entries.forEach {
                assertEquals("95c6e1633630a1462679cf1284b95ec458a27a8e", it.source.revision)
                assertTrue(it.source.location.startsWith("https://github.com/obra/superpowers/tree/"))
                assertTrue(it.release.pkg.checksum.matches(Regex("[a-f0-9]{64}")))
                assertNull(it.release.pkg.manifest.license)
                assertEquals(io.aequicor.magicpaper.domain.SkillCandidateStatus.QUARANTINED, it.release.status)
                assertNull(it.release.review)
                assertTrue(repo.diff(it.release.pkg.key).newInstructions.isNotBlank())
            }
            val snapshot = repo.snapshot()
            assertTrue(snapshot.active.isEmpty() && snapshot.projects.isEmpty() && snapshot.projectTextConsents.isEmpty())
        }
        try {
            LocalSkillRepository(root, GithubSkillCatalog.HOST).use { repo ->
                ImageComposeScene(1000, 760) { MagicPaperTheme { Surface { ProjectSkillsPanel { repo }.Content("catalog-live-project") } } }.use { scene ->
                    var frame = 0L
                    suspend fun pump() {
                        scene.render(++frame * 16_000_000L).close()
                        withContext(Dispatchers.Default) { delay(30) }; runCurrent()
                    }
                    fun nodes(): List<SemanticsNode> {
                        fun walk(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::walk)
                        return scene.semanticsOwners.flatMap { walk(it.rootSemanticsNode) }
                    }
                    fun label(n: SemanticsNode) = n.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }.orEmpty()
                    suspend fun click(text: String) {
                        val node = nodes().single { label(it) == text && it.config.getOrNull(SemanticsActions.OnClick) != null }
                        assertNull(node.config.getOrNull(SemanticsProperties.Disabled), text)
                        assertTrue(node.config[SemanticsActions.OnClick].action!!.invoke(), text)
                        repeat(3) { pump() }
                        trace += "CLICK: $text"
                    }
                    suspend fun enter(index: Int, text: String) {
                        val node = nodes().filter { it.config.getOrNull(SemanticsActions.SetText) != null }[index]
                        assertTrue(node.config[SemanticsActions.SetText].action!!.invoke(AnnotatedString(text)))
                        repeat(3) { pump() }
                        trace += "INPUT[$index]: $text"
                    }
                    suspend fun consent() {
                        val node = nodes().first { it.config.getOrNull(SemanticsProperties.ToggleableState) != null }
                        assertTrue(node.config[SemanticsActions.OnClick].action!!.invoke())
                        repeat(3) { pump() }
                        trace += "CONSENT: anonymous source GET"
                    }
                    suspend fun await(check: suspend () -> Boolean) {
                        val deadline = System.nanoTime() + 90_000_000_000L
                        while (!check()) {
                            require(System.nanoTime() < deadline) { "UI timeout: " + nodes().joinToString("\n", transform = ::label) }
                            pump()
                        }
                        repeat(4) { pump() }
                    }
                    repeat(8) { pump() }
                    click("Добавить скилы из репозиториев")
                    enter(0, "debugging")
                    click("Выбрать источник")
                    consent()
                    click("Найти пакеты по ссылке")
                    await { nodes().any { label(it) == "skills/systematic-debugging/SKILL.md" } }
                    click("skills/systematic-debugging/SKILL.md")
                    await { nodes().any { label(it) == "Импортировать без подключения" } }
                    click("Импортировать без подключения")
                    await { repo.catalog().size == 1 }
                    trace += "SEARCH IMPORT: installed=1"
                    val firstSnapshot = repo.snapshot()
                    click("skills/systematic-debugging/SKILL.md")
                    await { nodes().any { label(it) == "Импортировать без подключения" } }
                    click("Импортировать без подключения")
                    await { nodes().none { label(it) == "Импортировать без подключения" } }
                    assertEquals(firstSnapshot, repo.snapshot())
                    trace += "REPEAT UI IMPORT: complete snapshot unchanged; installed=1"
                    // The second source is entered into the production link field, not the controller.
                    enter(0, "")
                    enter(1, "https://github.com/obra/superpowers/tree/v4.0.0")
                    consent()
                    click("Найти пакеты по ссылке")
                    await { nodes().any { label(it) == "skills/verification-before-completion/SKILL.md" } }
                    click("skills/verification-before-completion/SKILL.md")
                    await { nodes().any { label(it) == "Импортировать без подключения" } }
                    click("Импортировать без подключения")
                    await { repo.catalog().size == 2 }
                    verifyStored(repo)
                    installed = repo.catalog().associate { it.release.pkg.key to it.release.pkg.checksum }
                    trace += "TYPED LINK IMPORT: installed=2; full SHA/source/checksum persisted; license=null; QUARANTINED; no review/active/project/opt-in"
                    Files.writeString(output.resolve("imports.txt"), repo.catalog().joinToString("\n") { "${it.release.pkg.key} ${it.release.pkg.checksum} ${it.source} ${it.release.status} license=${it.release.pkg.manifest.license ?: "UNKNOWN-BLOCKED-UNTIL-REVIEW"}" })
                    click("Назад к проекту")
                    await { nodes().any { label(it).contains("systematic-debugging") } }
                    scene.render(++frame * 16_000_000L).use { image -> image.encodeToData()!!.use { Files.write(output.resolve("project-after-imports.png"), it.bytes) } }
                }
            }
            LocalSkillRepository(root, GithubSkillCatalog.HOST).use { repo ->
                assertEquals(1, repo.catalog("systematic-debugging").size)
                assertEquals(1, repo.catalog("verification-before-completion").size)
                verifyStored(repo)
                assertEquals(installed, repo.catalog().associate { it.release.pkg.key to it.release.pkg.checksum })
                trace += "OFFLINE REOPEN: new repository without network transport; both searches=1; exact keys/checksums preserved; instructions read; no active/project/opt-in"
            }
            Files.writeString(output.resolve("ui-actions.txt"), trace.joinToString("\n") + "\nPASS\n")
        } finally { Dispatchers.resetMain() }
    }
}
