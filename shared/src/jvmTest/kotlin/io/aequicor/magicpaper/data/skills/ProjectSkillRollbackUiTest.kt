package io.aequicor.magicpaper.data.skills

import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.*
import androidx.compose.ui.use
import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.plugins.builtin.ProjectSkillsPanel
import io.aequicor.magicpaper.ui.theme.MagicPaperTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.encodeToString
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalComposeUiApi::class, ExperimentalCoroutinesApi::class)
class ProjectSkillRollbackUiTest {
    @Test fun explicitRollbackCancellationStaleAndUnavailableReleaseThenRestartAndProjectRoundTrip() = runTest(timeout = 90.seconds) {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val output = Files.createDirectories(Path.of("build/reports/skills-catalog-rollback"))
        val root = Files.createTempDirectory(output, "repository-")
        lateinit var v1: ValidatedSkillPackage
        lateinit var v2: ValidatedSkillPackage
        try {
            LocalSkillRepository(root, GithubSkillCatalog.HOST).use { repo ->
                suspend fun install(version: String, permissions: Set<SkillPermission>): ValidatedSkillPackage {
                    val text = "Fixture instruction $version".encodeToByteArray()
                    val m = SkillPackageManifest(id = "test.rollback", name = "Rollback fixture", description = "UI test only", version = version,
                        files = listOf(SkillPackageFile("SKILL.md", SkillPackageValidator.sha256(text), text.size.toLong())),
                        permissions = permissions, license = "MIT", origin = SkillPackageOrigin(SkillImportKind.LOCAL_DIRECTORY),
                        compatibility = SkillCompatibility("1.0.0", "2.0.0", setOf("desktop")))
                    val entries = listOf(SkillArchiveEntry("SKILL.md", text), SkillArchiveEntry(SkillPackageFormat.MANIFEST, SkillPackageFormat.json.encodeToString(m).encodeToByteArray()))
                    val p = SkillPackageValidator(GithubSkillCatalog.HOST).validate(entries)
                    repo.install(entries, SkillObservedSource(SkillImportKind.LOCAL_DIRECTORY, "local-ui-fixture"))
                    repo.review(p.key, SkillPackageReview(p.checksum, "fixture", "Synthetic test evidence", true, true, true))
                    return p
                }
                suspend fun bind(project: String, p: ValidatedSkillPackage) {
                    val target = mapOf(p.key to p.checksum)
                    repo.bindProject(project, target, SkillActivationConsent(repo.snapshot().generation, target, true, p.manifest.permissions, trustedCodingText = true))
                }
                v1 = install("1.0.0", setOf(SkillPermission.NETWORK))
                v2 = install("2.0.0", emptySet())
                bind("A", v1); bind("A", v2); bind("B", v2)
                val selectionBefore = repo.projectCodingSelection("A")
                val project = mutableStateOf("A")
                ImageComposeScene(1000, 760) { MagicPaperTheme { Surface { ProjectSkillsPanel { repo }.Content(project.value) } } }.use { scene ->
                    var frame = 0L
                    suspend fun pump() { scene.render(++frame * 16_000_000L).close(); withContext(Dispatchers.Default) { delay(25) }; runCurrent() }
                    fun nodes(): List<SemanticsNode> {
                        fun walk(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::walk)
                        return scene.semanticsOwners.flatMap { walk(it.rootSemanticsNode) }
                    }
                    fun label(n: SemanticsNode) = n.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }.orEmpty()
                    fun button(text: String) = nodes().single { label(it) == text && it.config.getOrNull(SemanticsActions.OnClick) != null }
                    suspend fun click(text: String) {
                        val n = button(text); assertNull(n.config.getOrNull(SemanticsProperties.Disabled), text)
                        assertTrue(n.config[SemanticsActions.OnClick].action!!.invoke()); repeat(3) { pump() }
                    }
                    suspend fun await(predicate: () -> Boolean) {
                        val deadline = System.nanoTime() + 15_000_000_000L
                        while (!predicate()) { require(System.nanoTime() < deadline) { nodes().joinToString("\n", transform = ::label) }; pump() }
                        repeat(3) { pump() }
                    }
                    suspend fun toggle(index: Int) {
                        val n = nodes().filter { it.config.getOrNull(SemanticsProperties.ToggleableState) != null }[index]
                        assertTrue(n.config[SemanticsActions.OnClick].action!!.invoke()); repeat(3) { pump() }
                    }
                    suspend fun preview() {
                        click("Откатить состав…")
                        await { nodes().any { label(it).startsWith("Предпросмотр проектного отката") } }
                        val text = nodes().joinToString("\n", transform = ::label)
                        assertTrue(text.contains(v1.checksum) && text.contains(v2.checksum) && text.contains("NETWORK"))
                        assertNotNull(button("Подтвердить откат без opt-in").config.getOrNull(SemanticsProperties.Disabled))
                    }
                    repeat(8) { pump() }
                    val original = repo.snapshot()
                    preview(); click("Отмена отката"); assertEquals(original, repo.snapshot())
                    preview(); toggle(0)
                    assertNotNull(button("Подтвердить откат без opt-in").config.getOrNull(SemanticsProperties.Disabled))
                    toggle(1)
                    // Change generation externally while UI retains the exact older preview.
                    repo.review(v1.key, SkillPackageReview(v1.checksum, "fixture", "Fresh evidence", true, true, true))
                    val changed = repo.snapshot()
                    click("Подтвердить откат без opt-in")
                    await { nodes().any { label(it).startsWith("Откат не выполнен:") } }
                    assertEquals(changed, repo.snapshot())
                    preview(); toggle(0); toggle(1)
                    val payload = root.resolve("releases/${v1.checksum}/SKILL.md")
                    val saved = Files.readAllBytes(payload)
                    Files.delete(payload)
                    click("Подтвердить откат без opt-in")
                    await { nodes().any { label(it).startsWith("Откат не выполнен:") } }
                    assertEquals(changed, repo.snapshot())
                    Files.write(payload, saved)
                    preview(); toggle(0); toggle(1); click("Подтвердить откат без opt-in")
                    await { nodes().none { label(it) == "Подтвердить откат без opt-in" } }
                    assertEquals(mapOf(v1.key to v1.checksum), repo.snapshot().projects["A"])
                    assertEquals(mapOf(v2.key to v2.checksum), repo.snapshot().projects["B"])
                    assertFalse(repo.projectCodingSelection("A").trustedText)
                    assertEquals(v2.checksum, selectionBefore.instructions.single().checksum)
                    project.value = "B"; repeat(8) { pump() }
                    assertTrue(nodes().any { label(it) == "Проект: B" })
                    project.value = "A"; repeat(8) { pump() }
                    assertTrue(nodes().any { label(it) == "Проект: A" })
                    assertEquals(v1.checksum, repo.projectCodingSelection("A").instructions.single().checksum)
                    scene.render(++frame * 16_000_000L).use { image -> image.encodeToData()!!.use { Files.write(output.resolve("after-rollback.png"), it.bytes) } }
                }
            }
            LocalSkillRepository(root, GithubSkillCatalog.HOST).use { repo ->
                assertEquals(v1.checksum, repo.projectCodingSelection("A").instructions.single().checksum)
                assertEquals(v2.checksum, repo.projectCodingSelection("B").instructions.single().checksum)
                assertFalse(repo.projectCodingSelection("A").trustedText)
                assertEquals(mapOf(v2.key to v2.checksum), repo.snapshot().previousProjects["A"])
            }
        } finally { Dispatchers.resetMain() }
    }
}
