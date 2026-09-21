package io.aequicor.magicpaper.data.skills

import io.aequicor.magicpaper.data.storage.*
import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.plugins.builtin.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ExperienceFormOwnerTest {
    private val profile = LlmProfile("profile-a", "Test", baseUrl = "https://llm.invalid", modelId = "test", apiKey = "private-form-test-key")
    private val profiles = object : LlmProfileRepository {
        override suspend fun load() = listOf(profile)
    }
    private class Gateway : LlmGateway {
        var calls = 0
        var entered: CompletableDeferred<Unit>? = null
        var release: CompletableDeferred<Unit>? = null
        override suspend fun complete(profile: LlmProfile, messages: List<LlmMessage>): String {
            calls++
            if (calls == 1) { entered?.complete(Unit); release?.await() }
            return if (messages.first().content.startsWith("Выбери")) "STRUCTURED"
            else ExperienceScenario.entries.flatMap { StrictExperienceCatalog.cases(it) }
                .single { messages.last().content.endsWith(it.prompt) }.expected
        }
    }

    @Test fun reopeningKeepsInvalidFieldsAndPreviewButNeverRestoresASendCapability() = runTest {
        val root = Files.createTempDirectory("experience-form-reopen-")
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val gateway = Gateway()
        try {
            LocalSkillRepository(root.resolve("packages"), SkillPackageHost("1.0.0", "desktop")).use { packages ->
                LocalSkillExperience(root.resolve("journal"), packages, gateway, { listOf(profile.apiKey) }).use { journal ->
                    val ids = seed(journal)
                    val first = ExperienceFormOwner(journal, profiles, drafts(root), scope)
                    first.start(); first.view.first { it.loaded }
                    first.result.update { ExperienceResultForm(ExperienceScenario.SUMMARY, setOf(ExperienceFeature.MISSING_STEP), false) }
                    first.retention.update { ExperienceRetentionForm("unfinished days") }
                    first.searchDraft.update { ExperienceSearchForm("unfinished search") }
                    first.updateSelection { it.copy(selected = ids, profileId = profile.id, deleteConfirmed = true) }
                    first.makePreview(); first.actions.awaitIdle()
                    assertTrue(first.view.value.previewReady)
                    first.flushDrafts(); first.prepareForReset()

                    val reopened = ExperienceFormOwner(journal, profiles, drafts(root), scope)
                    reopened.start(); reopened.view.first { it.loaded }
                    assertEquals("unfinished days", reopened.retention.draft.state.value.value.days)
                    assertEquals("unfinished search", reopened.searchDraft.draft.state.value.value.query)
                    assertEquals(setOf(ExperienceFeature.MISSING_STEP), reopened.result.draft.state.value.value.features)
                    assertFalse(reopened.result.draft.state.value.value.success)
                    assertEquals(ids, reopened.selection.draft.state.value.value.selected)
                    assertEquals(profile.id, reopened.selection.draft.state.value.value.profileId)
                    assertNotNull(reopened.selection.draft.state.value.value.preview)
                    assertFalse(reopened.view.value.previewReady)
                    reopened.generate()
                    assertEquals(0, gateway.calls)
                    assertTrue(packages.snapshot().installed.isEmpty())
                    val serialized = drafts(root).load("experience:selection")!!.payload
                    assertFalse(serialized.contains(profile.apiKey))
                    assertFalse(serialized.contains("token"))
                    reopened.applyRetention(); reopened.actions.awaitIdle()
                    assertEquals(30, journal.retentionDays())
                    assertEquals("unfinished days", reopened.retention.draft.state.value.value.days)
                    reopened.prepareForReset()
                }
            }
        } finally { scope.cancel(); root.toFile().deleteRecursively() }
    }

    @Test fun acceptedRecordCleanupFailureKeepsNewInputAndRetryDoesNotRecordTwice() = runTest {
        val root = Files.createTempDirectory("experience-form-cleanup-")
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        try {
            LocalSkillRepository(root.resolve("packages"), SkillPackageHost("1.0.0", "desktop")).use { packages ->
                LocalSkillExperience(root.resolve("journal"), packages, Gateway(), { emptyList() }).use { journal ->
                    val actual = drafts(root)
                    var failClear = true
                    val repository = object : DraftRepository by actual {
                        override suspend fun deleteIfOwned(key: String, revision: Long, ownerEpoch: Long, resetEpoch: Long): Boolean {
                            if (key == "experience:result" && failClear) throw StorageException("clear draft", StorageException.Kind.WRITE)
                            return actual.deleteIfOwned(key, revision, ownerEpoch, resetEpoch)
                        }
                    }
                    val owner = ExperienceFormOwner(journal, profiles, repository, scope)
                    owner.start(); owner.view.first { it.loaded }
                    owner.result.update { it.copy(features = setOf(ExperienceFeature.TOO_LONG)) }
                    owner.record()
                    owner.actions.state.first { it.cleanupPending && !it.busy }
                    assertEquals(1, journal.search().size)
                    owner.record()
                    assertEquals(1, journal.search().size)
                    owner.result.update { it.copy(features = setOf(ExperienceFeature.MISSING_STEP), success = false) }
                    failClear = false
                    owner.actions.retryCleanup(); owner.actions.awaitIdle(); owner.flushDrafts()
                    assertFalse(owner.actions.state.value.cleanupPending)
                    assertEquals(1, journal.search().size)
                    assertEquals(setOf(ExperienceFeature.MISSING_STEP), owner.result.draft.state.value.value.features)
                    owner.prepareForReset()
                    val reopened = ExperienceFormOwner(journal, profiles, drafts(root), scope)
                    reopened.result.draft.awaitSaved()
                    assertEquals(setOf(ExperienceFeature.MISSING_STEP), reopened.result.draft.state.value.value.features)
                    assertFalse(reopened.result.draft.state.value.value.success)
                    reopened.prepareForReset()
                }
            }
        } finally { scope.cancel(); root.toFile().deleteRecursively() }
    }

    @Test fun acceptedGenerationSurvivesPanelDisposalAndResetRevokesOldFormWriters() = runTest {
        val root = Files.createTempDirectory("experience-form-lifetime-")
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val gateway = Gateway().apply { entered = CompletableDeferred(); release = CompletableDeferred() }
        try {
            LocalSkillRepository(root.resolve("packages"), SkillPackageHost("1.0.0", "desktop")).use { packages ->
                LocalSkillExperience(root.resolve("journal"), packages, gateway, { listOf(profile.apiKey) }).use { journal ->
                    val persistence = desktopPersistenceStores(root.resolve("drafts").toFile())
                    val owner = ExperienceFormOwner(journal, profiles, persistence.drafts, scope)
                    owner.start(); owner.view.first { it.loaded }
                    val ids = seed(journal)
                    owner.updateSelection { it.copy(selected = ids, profileId = profile.id) }
                    owner.makePreview(); owner.actions.awaitIdle()
                    val panel = launch { owner.view.collect { } }
                    owner.generate()
                    gateway.entered!!.await()
                    panel.cancelAndJoin()
                    owner.updateSelection { it.copy(selected = emptySet(), profileId = "new-profile") }
                    gateway.release!!.complete(Unit)
                    owner.actions.awaitIdle()
                    assertEquals(15, gateway.calls)
                    assertTrue(journal.candidates().single().passed)
                    assertTrue(packages.snapshot().active.isEmpty())
                    assertEquals("new-profile", owner.selection.draft.state.value.value.profileId)
                    val staleSession = owner.selection.draft
                    owner.prepareForReset()
                    persistence.clearOwnedData()
                    staleSession.update { it.copy(profileId = "late callback") }
                    owner.resumeAfterReset()
                    owner.selection.draft.awaitSaved()
                    assertEquals(ExperienceSelectionForm(), owner.selection.draft.state.value.value)
                    assertNull(persistence.drafts.load("experience:selection"))
                    owner.prepareForReset()
                }
            }
        } finally { scope.cancel(); root.toFile().deleteRecursively() }
    }

    private fun drafts(root: Path) = desktopPersistenceStores(root.resolve("drafts").toFile()).drafts
    @Test fun maintenanceFailureStaysVisibleUntilSuccessfulExplicitRetry() = runTest {
        val root = Files.createTempDirectory("experience-maintenance-")
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        try {
            LocalSkillRepository(root.resolve("packages"), SkillPackageHost("1.0.0", "desktop")).use { packages ->
                LocalSkillExperience(root.resolve("journal"), packages, Gateway(), { emptyList() }).use { journal ->
                    val owner = ExperienceFormOwner(journal, profiles, drafts(root), scope)
                    owner.start(); owner.view.first { it.loaded }
                    owner.reportMaintenanceFailure(IllegalStateException("private provider detail"))
                    assertNotNull(owner.maintenanceFailure.value)
                    assertFalse(owner.maintenanceFailure.value.orEmpty().contains("private provider detail"))
                    owner.result.update { it.copy(success = false) }
                    assertNotNull(owner.maintenanceFailure.value)
                    owner.search(); owner.actions.awaitIdle()
                    assertNull(owner.maintenanceFailure.value)
                    owner.prepareForReset()
                }
            }
        } finally { scope.cancel(); root.toFile().deleteRecursively() }
    }

    private suspend fun seed(journal: LocalSkillExperience) = setOf(
        journal.record(ExperienceScenario.SUMMARY, true, setOf(ExperienceFeature.TOO_LONG)).id,
        journal.record(ExperienceScenario.SUMMARY, false, setOf(ExperienceFeature.MISSING_STEP)).id,
    )
}
