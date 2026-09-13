package io.aequicor.magicpaper.ui

import io.aequicor.magicpaper.data.storage.*
import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.ui.screens.AgentLimitField
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.*

class SettingsDraftsTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    @Test fun profileDeletionRemovesAllOwnedFormsAfterRestartAndRevokesOpenWriters() = runTest {
        val repository = InMemoryDraftRepository()
        val initial = SettingsDrafts(repository, backgroundScope, json)
        val deleted = LlmProfile("a", "Deleted", apiKey = "private")
        val retained = LlmProfile("ab", "Retained", apiKey = "keep")
        initial.profile(deleted).update { it.copy(profile = deleted) }
        initial.variant(deleted, "not-in-catalog").update { it.copy(fields = it.fields + ("timeout" to "-")) }
        initial.description(deleted, "old-model", null).update { it.copy(fields = mapOf("strengths" to "unfinished")) }
        initial.profile(retained).update { it.copy(profile = retained) }
        initial.awaitSaved()
        val restarted = SettingsDrafts(repository, backgroundScope, json)
        val open = restarted.profile(deleted)
        open.awaitSaved()
        restarted.removeProfile(deleted.id)
        open.update { it.copy(profile = deleted.copy(name = "late callback")) }
        open.awaitSaved()
        assertNull(restarted.existingProfile(deleted.id))
        assertTrue(restarted.isProfileDeleted(deleted.id))
        assertEquals(listOf("settings:profile:ab"), repository.keys("settings:"))
        val anotherRestart = SettingsDrafts(repository, backgroundScope, json)
        assertNull(anotherRestart.existingProfile(deleted.id))
        assertEquals(retained, anotherRestart.existingProfile(retained.id))
    }

    @Test fun invalidInputAndCredentialsRestoreWithoutApplyingSettings() = runTest {
        val repository = InMemoryDraftRepository()
        val saved = AppSettings()
        val drafts = SettingsDrafts(repository, backgroundScope, json)
        val form = drafts.settings(saved)
        form.update { it.copy(settings = saved.copy(queritApiKey = "private-key"),
            agentLimits = it.agentLimits.edited(AgentLimitField.TOKENS, "-2invalid")) }
        form.awaitSaved()
        val record = checkNotNull(repository.load(SettingsDrafts.SETTINGS))
        assertFalse("private-key" in record.payload)
        assertEquals("private-key", record.secrets["querit"])
        assertSame(form, drafts.settings(saved.copy(onboardingDone = true)))

        val restored = SettingsDrafts(repository, backgroundScope, json).settings(saved)
        restored.awaitSaved()
        assertEquals("-2invalid", restored.state.value.value.agentLimits[AgentLimitField.TOKENS])
        assertFalse(restored.state.value.value.agentLimits.valid)
        assertEquals("private-key", restored.state.value.value.settings?.queritApiKey)
        assertEquals("", saved.queritApiKey)
    }

    @Test fun successfulSaveRetainsInputEditedDuringTheOperation() = runTest {
        val repository = InMemoryDraftRepository()
        val drafts = SettingsDrafts(repository, backgroundScope, json)
        val form = drafts.settings(AppSettings())
        form.update { it.copy(fields = mapOf("raw" to "old")) }
        val point = drafts.capture(SettingsDrafts.SETTINGS)
        form.update { it.copy(fields = mapOf("raw" to "new")) }
        drafts.saved(point)
        form.awaitSaved()
        assertEquals("new", form.state.value.value.fields["raw"])
        assertNotNull(repository.load(SettingsDrafts.SETTINGS))
        drafts.saved(drafts.capture(SettingsDrafts.SETTINGS))
        assertNull(repository.load(SettingsDrafts.SETTINGS))
    }

    @Test fun unknownProfileNeverCreatesADraftAndExplicitDraftRestores() = runTest {
        val repository = InMemoryDraftRepository()
        val drafts = SettingsDrafts(repository, backgroundScope, json)
        assertNull(drafts.existingProfile("unknown"))
        assertNull(repository.load("settings:profile:unknown"))
        val profile = LlmProfile(id = "created", name = "Unsaved", apiKey = "private-key")
        val form = drafts.profile(profile)
        form.update { it.copy(profile = profile) }
        form.awaitSaved()
        val reopened = SettingsDrafts(repository, backgroundScope, json)
        assertEquals(profile, reopened.existingProfile("created"))
        assertFalse("private-key" in checkNotNull(repository.load("settings:profile:created")).payload)
    }

    @Test fun modelVariantPreservesUnparseableNumbers() = runTest {
        val repository = InMemoryDraftRepository()
        val profile = LlmProfile("provider", "Provider", modelId = "model")
        val form = SettingsDrafts(repository, backgroundScope, json).variant(profile, "model")
        form.update { it.copy(fields = it.fields + ("temperature" to "0..7") + ("timeout" to "-")) }
        form.awaitSaved()
        val reopened = SettingsDrafts(repository, backgroundScope, json).variant(profile, "model")
        reopened.awaitSaved()
        assertEquals("0..7", reopened.state.value.value.fields["temperature"])
        assertEquals("-", reopened.state.value.value.fields["timeout"])
    }
}
