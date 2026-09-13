package io.aequicor.magicpaper.data.skills

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import io.aequicor.magicpaper.designsystem.PaperPanel
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.use
import io.aequicor.magicpaper.data.storage.*
import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.plugins.builtin.*
import io.aequicor.magicpaper.ui.theme.MagicPaperTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import java.nio.file.Files
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalComposeUiApi::class)
class SkillsFormPersistenceTest {
    @Test fun nativeFormsReopenWithoutInstallingOrAcceptingStoredConsents() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val directory = Files.createTempDirectory("skill-form-reopen-")
        try {
            val first = SkillsFormDrafts(desktopPersistenceStores(directory.toFile()).drafts, backgroundScope)
            val invalidImport = SkillImportForm(kind = SkillImportKind.GIT, location = "unfinished source", revision = "partial commit", version = "not a version", origins = "unfinished origin", network = true)
            val text = SkillTextForm(readyText = "---\nunfinished: [", skillId = "invalid id", version = "", name = "New draft")
            first.imports().update { invalidImport }
            first.text().update { text }
            first.backup().update { SkillBackupForm("unfinished path", "partial hash", true) }
            first.review("sample@1", "checksum-a").update { SkillReviewForm("unfinished evidence", origin = true, license = false, content = true) }
            first.project("project-a").update { ProjectSkillForm(mapOf("sample@1" to "checksum-a"), 7, true, true, true) }
            first.catalog("project-a").update { SkillCatalogForm("query", "unfinished URL", true, "sample@1", "checksum-a") }
            first.rollback("project-a").update { SkillActivationForm(mapOf("sample@1" to "checksum-a"), 7, mapOf("sample@1" to "checksum-a"), changes = true) }
            first.activation().update { SkillActivationForm(emptyMap(), 7, changes = true) }
            first.flush(); first.prepareForReset()

            val reopened = SkillsFormDrafts(desktopPersistenceStores(directory.toFile()).drafts, backgroundScope)
            assertEquals(invalidImport, loaded(reopened.imports()))
            assertEquals(text, loaded(reopened.text()))
            assertEquals(SkillBackupForm("unfinished path", "partial hash", true), loaded(reopened.backup()))
            assertEquals("unfinished evidence", loaded(reopened.review("sample@1", "checksum-a")).evidence)
            assertEquals("unfinished URL", loaded(reopened.catalog("project-a")).link)
            val project = loaded(reopened.project("project-a"))
            assertTrue(project.trustedTextConsent)
            assertFalse(project.matches(SkillReleaseSnapshot(generation = 8)))
            assertFalse(loaded(reopened.rollback("project-a")).matches(SkillReleaseSnapshot(generation = 8)))
            assertFalse(loaded(reopened.activation()).matches(SkillReleaseSnapshot(generation = 8)))
            LocalSkillRepository(directory.resolve("packages"), GithubSkillCatalog.HOST).use { packages ->
                assertTrue(packages.snapshot().installed.isEmpty())
                assertTrue(packages.snapshot().projects.isEmpty())
                assertTrue(packages.snapshot().active.isEmpty())
            }
        } finally { Dispatchers.resetMain(); directory.toFile().deleteRecursively() }
    }

    @Test fun successfulCapturedOperationCannotClearNewerInputAndDeletionRevokesOldPanels() = runTest {
        val directory = Files.createTempDirectory("skill-form-completion-")
        try {
            val persistence = desktopPersistenceStores(directory.toFile())
            val repository = persistence.drafts
            val forms = SkillsFormDrafts(repository, backgroundScope)
            val text = forms.text()
            loaded(text)
            text.update { it.copy(readyText = "first") }
            val captured = text.draft.state.value.version
            text.update { it.copy(readyText = "newer") }
            assertFalse(text.draft.clearIfUnchanged(captured))
            text.flushDrafts()
            assertEquals("newer", loaded(SkillsFormDrafts(desktopPersistenceStores(directory.toFile()).drafts, backgroundScope).text()).readyText)
            assertTrue(text.draft.clearIfUnchanged(text.draft.state.value.version))
            assertEquals(SkillTextForm(), loaded(SkillsFormDrafts(desktopPersistenceStores(directory.toFile()).drafts, backgroundScope).text()))

            val oldProject = forms.project("deleted")
            val oldCatalog = forms.catalog("deleted")
            val oldRollback = forms.rollback("deleted")
            oldProject.update { it.copy(catalogOpen = true) }
            oldCatalog.update { it.copy(link = "unfinished source") }
            oldRollback.update { it.copy(target = emptyMap(), changes = true) }
            forms.project("retained").update { it.copy(catalogOpen = true) }
            forms.flush()
            forms.removeProject("deleted")
            oldProject.update { it.copy(trustedTextConsent = true) }
            oldCatalog.update { it.copy(link = "late callback") }
            oldRollback.update { it.copy(changes = true) }
            assertFalse(forms.available("deleted"))
            assertTrue(repository.keys("skills:project:deleted:").isEmpty())
            assertTrue(loaded(SkillsFormDrafts(desktopPersistenceStores(directory.toFile()).drafts, backgroundScope).project("retained")).catalogOpen)
            forms.prepareForReset()
            forms.text().update { it.copy(readyText = "late callback after reset") }
            persistence.clearOwnedData()
            forms.resumeAfterReset()
            assertEquals(SkillTextForm(), loaded(forms.text()))
        } finally { directory.toFile().deleteRecursively() }
    }

    @Test fun actualPackagePanelRestoresInvalidImportAndTextFieldsWithoutWork() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val directory = Files.createTempDirectory("skill-form-render-")
        try {
            val persistence = desktopPersistenceStores(directory.toFile())
            val previous = SkillsFormDrafts(persistence.drafts, backgroundScope)
            previous.imports().update { it.copy(location = "unfinished local path", skillId = "invalid id") }
            previous.text().update { it.copy(readyText = "Unfinished SKILL.md", name = "Draft title") }
            previous.flush(); previous.prepareForReset()
            LocalSkillsPlugin(directory.resolve("packages"), desktopPersistenceStores(directory.toFile()).drafts, backgroundScope).use { plugin ->
                ImageComposeScene(1000, 860) { MagicPaperTheme { PaperPanel(Modifier.fillMaxSize()) { plugin.Content() } } }.use { scene ->
                    fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
                    fun fields() = scene.semanticsOwners.flatMap { walk(it.rootSemanticsNode) }.mapNotNull { it.config.getOrNull(SemanticsProperties.EditableText)?.text }
                    // Native file I/O and Compose frames use real dispatchers. A virtual
                    // withTimeout jumps ahead while the first IO continuation is pending.
                    val deadline = System.nanoTime() + 15_000_000_000L
                    var frame = 0L
                    while ("Unfinished SKILL.md" !in fields()) {
                        check(System.nanoTime() < deadline) { "Restored form was not rendered; fields=${fields()}" }
                        scene.render(++frame * 16_000_000L).close()
                        withContext(Dispatchers.Default) { delay(20) }
                        runCurrent()
                    }
                    assertContains(fields(), "unfinished local path")
                    assertContains(fields(), "invalid id")
                    assertContains(fields(), "Draft title")
                    assertTrue(plugin.repo().snapshot().installed.isEmpty())
                    val output = Files.createDirectories(java.nio.file.Path.of("build/reports/skills-drafts"))
                    scene.render(1_000_000_000L).use { image -> image.encodeToData()!!.use { Files.write(output.resolve("restored-forms.png"), it.bytes) } }
                }
                plugin.flushDrafts()
            }
        } finally { Dispatchers.resetMain(); directory.toFile().deleteRecursively() }
    }

    private suspend fun <T> loaded(owner: PersistentDraftValue<T>): T {
        owner.draft.awaitSaved()
        return owner.draft.state.value.value
    }
}
