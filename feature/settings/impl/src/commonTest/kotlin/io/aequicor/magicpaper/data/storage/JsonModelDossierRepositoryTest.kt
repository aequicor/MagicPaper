package io.aequicor.magicpaper.data.storage

import io.aequicor.magicpaper.domain.ModelDossier
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.*

class JsonModelDossierRepositoryTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test fun readsExistingRecordsAndReplacesOnlyTheSameModelAndProfile() = runTest {
        val store = InMemoryKeyValueStore()
        store.write("model-dossiers", """[{"id":"old","profileId":"source","modelId":"first","strengths":"Saved description"}]""")
        val repository = JsonModelDossierRepository(store, json)
        val old = repository.dossiers().single()
        assertEquals("Saved description", old.strengths)
        val otherModel = ModelDossier("second", "source", "second")
        val otherProfile = ModelDossier("other", "other-source", "first")
        repository.saveDossier(otherModel)
        repository.saveDossier(otherProfile)
        val updated = old.copy(strengths = "Edited description")
        repository.saveDossier(updated)
        assertEquals(setOf(updated, otherModel, otherProfile), JsonModelDossierRepository(store, json).dossiers().toSet())
    }

    @Test fun corruptRecordsRemainIntactAndCannotBeOverwrittenByAnEdit() = runTest {
        val store = InMemoryKeyValueStore()
        store.write("model-dossiers", "{broken")
        val repository = JsonModelDossierRepository(store, json)
        assertFailsWith<StorageException> { repository.dossiers() }
        assertFailsWith<StorageException> { repository.saveDossier(ModelDossier("d", "p", "m")) }
        assertEquals("{broken", store.read("model-dossiers"))
    }

    @Test fun clearingModelDescriptionsDoesNotDeletePlans() = runTest {
        val store = InMemoryKeyValueStore()
        store.write("coding-plans", "preserved plan bytes")
        val repository = JsonModelDossierRepository(store, json)
        repository.saveDossier(ModelDossier("d", "p", "m"))
        repository.clearDossiers()
        assertTrue(repository.dossiers().isEmpty())
        assertEquals("preserved plan bytes", store.read("coding-plans"))
    }
}
