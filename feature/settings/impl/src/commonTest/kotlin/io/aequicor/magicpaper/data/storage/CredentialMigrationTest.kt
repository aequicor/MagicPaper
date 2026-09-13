package io.aequicor.magicpaper.data.storage

import io.aequicor.magicpaper.domain.AppSettings
import io.aequicor.magicpaper.domain.LlmProfile
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertNull
import kotlin.test.assertNotNull

class CredentialMigrationTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test fun failedProfileSecretCleanupSurvivesReopenAndRetriesWithoutRestoringDeletedProfile() = runTest {
        val store = InMemoryKeyValueStore()
        val actual = InMemorySecretStore()
        val references = mutableListOf<String>()
        var failDelete = false
        val secrets = object : SecretStore by actual {
            override suspend fun write(reference: String, value: String) { references += reference; actual.write(reference, value) }
            override suspend fun delete(reference: String) {
                if (failDelete) throw StorageException("delete secret", StorageException.Kind.WRITE)
                actual.delete(reference)
            }
        }
        val repository = JsonLlmProfileRepository(store, json, secrets)
        repository.save(LlmProfile("a", "A", apiKey = "private-value"))
        failDelete = true
        val failure = assertFailsWith<StorageException> { repository.delete("a") }
        assertTrue(failure.committed)
        assertNull(store.read("llm_profiles"))
        val journal = assertNotNull(store.read("profiles-credential-cleanup"))
        assertFalse(journal.contains("private-value"))
        assertNotNull(actual.read(references.single()))
        failDelete = false
        assertTrue(JsonLlmProfileRepository(store, json, secrets).load().isEmpty())
        assertNull(actual.read(references.single()))
        assertNull(store.read("profiles-credential-cleanup"))
    }

    @Test
    fun settingsMigrationScrubsOnlyAfterVerifiedWriteAndRehydratesExportModel() = runTest {
        val store = InMemoryKeyValueStore()
        val secrets = InMemorySecretStore()
        val settings = AppSettings(queritApiKey = "querit-secret", queritContentApiKey = "content-secret", googleApiKey = "google-secret", llmApiKey = "legacy-secret")
        store.write("settings", json.encodeToString(AppSettings.serializer(), settings))
        val repository = JsonSettingsRepository(store, json, secrets)
        assertEquals(settings, repository.load())
        val persisted = store.read("settings")!!
        listOf("querit-secret", "content-secret", "google-secret", "legacy-secret").forEach { assertFalse(persisted.contains(it)) }
        assertTrue(persisted.contains("secretReferences"))
        assertEquals(settings, JsonSettingsRepository(store, json, secrets).load())
        assertEquals(persisted, store.read("settings"))
        // ProfileBundle keeps using this hydrated model, with its existing version-2 schema.
        assertTrue(json.encodeToString(AppSettings.serializer(), repository.load()).contains("querit-secret"))
    }

    @Test
    fun partialMigrationKeepsLegacySourceAndCanBeRetried() = runTest {
        val store = InMemoryKeyValueStore()
        val backing = InMemorySecretStore()
        var writes = 0
        val interrupted = object : SecretStore by backing {
            override suspend fun write(reference: String, value: String) {
                if (++writes == 2) throw StorageException("write", StorageException.Kind.WRITE)
                backing.write(reference, value)
            }
        }
        val original = json.encodeToString(AppSettings.serializer(), AppSettings(queritApiKey = "one", googleApiKey = "two"))
        store.write("settings", original)
        assertFailsWith<StorageException> { JsonSettingsRepository(store, json, interrupted).load() }
        assertEquals(original, store.read("settings"))
        val restored = JsonSettingsRepository(store, json, backing).load()
        assertEquals("one", restored.queritApiKey)
        assertEquals("two", restored.googleApiKey)
        assertFalse(store.read("settings")!!.contains("\"one\""))
    }

    @Test
    fun verificationFailureDoesNotScrubOrLoseKeys() = runTest {
        val store = InMemoryKeyValueStore()
        val raw = """[{"id":"a","name":"Provider","apiKey":"do-not-lose"}]"""
        store.write("llm_profiles", raw)
        val discarding = object : SecretStore {
            override suspend fun read(reference: String): String? = null
            override suspend fun write(reference: String, value: String) { }
            override suspend fun delete(reference: String) { }
        }
        assertFailsWith<StorageException> { JsonLlmProfileRepository(store, json, discarding).load() }
        assertEquals(raw, store.read("llm_profiles"))
    }

    @Test
    fun profileMigrationPreservesAllProfilesAndBlankImportClearsKey() = runTest {
        val store = InMemoryKeyValueStore()
        val secrets = InMemorySecretStore()
        store.write("llm_profiles", """[{"id":"a","name":"A","apiKey":"old-key"},{"id":"b","name":"B","apiKey":"second-key"}]""")
        val repository = JsonLlmProfileRepository(store, json, secrets)
        assertEquals(listOf("old-key", "second-key"), repository.load().map { it.apiKey })
        assertFalse(store.read("llm_profiles")!!.contains("old-key"))
        repository.save(LlmProfile("a", "A", apiKey = ""))
        assertEquals("", repository.load().single { it.id == "a" }.apiKey)
        assertEquals("second-key", repository.load().single { it.id == "b" }.apiKey)
    }

    @Test
    fun missingSecretIsAnErrorInsteadOfBlankCredential() = runTest {
        val store = InMemoryKeyValueStore()
        val secrets = InMemorySecretStore()
        store.write("settings", """{"secretReferences":{"googleApiKey":"missing"}}""")
        val failure = assertFailsWith<StorageException> { JsonSettingsRepository(store, json, secrets).load() }
        assertEquals(StorageException.Kind.MISSING_SECRET, failure.kind)
        assertTrue(store.read("settings")!!.contains("missing"))
    }

    @Test
    fun failedNewCredentialWriteLeavesPreviousCommittedSettingsReadable() = runTest {
        val store = InMemoryKeyValueStore()
        val backing = InMemorySecretStore()
        val repository = JsonSettingsRepository(store, json, backing)
        repository.save(AppSettings(googleApiKey = "original"))
        val previous = store.read("settings")
        val failing = object : SecretStore by backing {
            override suspend fun write(reference: String, value: String) { throw StorageException("write", StorageException.Kind.QUOTA) }
        }
        assertFailsWith<StorageException> { JsonSettingsRepository(store, json, failing).save(AppSettings(googleApiKey = "replacement")) }
        assertEquals(previous, store.read("settings"))
        assertEquals("original", repository.load().googleApiKey)
    }
}
