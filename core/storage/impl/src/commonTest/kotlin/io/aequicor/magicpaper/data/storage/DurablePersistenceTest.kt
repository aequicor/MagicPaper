package io.aequicor.magicpaper.data.storage

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DurablePersistenceTest {
    @Test fun resetInvalidatesAlreadyCreatedDraftWriters() = runTest {
        val stores = persistenceStores(InMemoryDurableByteStore())
        val old = DraftSession(stores.drafts, "draft", String.serializer(), "", backgroundScope)
        old.update("before reset")
        old.awaitSaved()
        stores.clearOwnedData()
        old.update("late callback after reset")
        old.awaitSaved()
        assertNull(stores.drafts.load("draft"))
        val fresh = DraftSession(stores.drafts, "draft", String.serializer(), "", backgroundScope)
        fresh.update("new session")
        fresh.awaitSaved()
        assertEquals("\"new session\"", stores.drafts.load("draft")?.payload)
    }
    @Test
    fun restartRestoresPayloadSecretsBlobsAndIsolatedJournals() = runTest {
        val backend = InMemoryDurableByteStore()
        val stores = persistenceStores(backend, "tab-a")
        val bytes = byteArrayOf(0, 1, -1, 45, 0)
        stores.blobs.write("attachment", bytes)
        val draft = DraftRecord("chat:a", 2, "invalid raw input: 1e-", mapOf("password" to "private-value"), listOf("attachment"))
        assertTrue(stores.drafts.save(draft))
        stores.navigation.save("visit-a → visit-b → visit-a; cursor=1")
        val reopened = persistenceStores(backend, "tab-b", fallbackJournalId = "tab-a")
        assertEquals(draft, reopened.drafts.load("chat:a"))
        assertContentEquals(bytes, reopened.blobs.read("attachment"))
        assertFalse(backend.read(StorageArea.DRAFTS, "chat:a")!!.decodeToString().contains("private-value"))
        assertEquals("visit-a → visit-b → visit-a; cursor=1", reopened.navigation.load())
        reopened.navigation.save("tab-b")
        assertEquals("visit-a → visit-b → visit-a; cursor=1", stores.navigation.load())
    }

    @Test
    fun olderAutosavesCannotOverwriteOrResurrectDeletedDraft() = runTest {
        val backend = InMemoryDurableByteStore()
        val repository = persistenceStores(backend).drafts
        assertTrue(repository.save(DraftRecord("a", 4, "new")))
        assertFalse(repository.save(DraftRecord("a", 3, "old")))
        assertFalse(repository.delete("a", 3))
        assertEquals("new", repository.load("a")?.payload)
        assertFalse(repository.delete("a", 4))
        assertTrue(repository.delete("a", 5))
        val reopened = persistenceStores(backend).drafts
        assertNull(reopened.load("a"))
        assertEquals(5, reopened.revision("a"))
        assertFalse(reopened.save(DraftRecord("a", 5, "late")))
        assertTrue(reopened.save(DraftRecord("a", 6, "next")))
    }

    @Test
    fun failedSecretCommitPreservesPreviousDraftAndSurfacesFailure() = runTest {
        val backend = InMemoryDurableByteStore()
        val working = DurableSecretStore(backend)
        val repository = DurableDraftRepository(backend, working)
        val original = DraftRecord("a", 1, "before", mapOf("password" to "before-secret"))
        repository.save(original)
        val failing = object : SecretStore by working {
            override suspend fun write(reference: String, value: String) { throw StorageException("write", StorageException.Kind.QUOTA) }
        }
        assertFailsWith<StorageException> { DurableDraftRepository(backend, failing).save(original.copy(revision = 2, payload = "after")) }
        assertEquals(original, repository.load("a"))
    }

    @Test
    fun corruptDraftDoesNotBecomeAnEmptyDraft() = runTest {
        val backend = InMemoryDurableByteStore()
        backend.write(StorageArea.DRAFTS, "a", "{broken".encodeToByteArray())
        assertFailsWith<StorageException> { persistenceStores(backend).drafts.load("a") }
        assertEquals("{broken", backend.read(StorageArea.DRAFTS, "a")!!.decodeToString())
    }

    @Test
    fun editBeforeHydrationWinsAndResumesSavedRevision() = runTest {
        val backend = InMemoryDurableByteStore()
        val repository = persistenceStores(backend).drafts
        repository.save(DraftRecord("a", 30, "\"restored\""))
        val session = DraftSession(repository, "a", String.serializer(), "initial", backgroundScope)
        session.update("typed before load")
        session.awaitSaved()
        assertEquals("typed before load", session.state.value.value)
        assertEquals("\"typed before load\"", repository.load("a")?.payload)
        assertTrue(repository.revision("a") > 30)
        assertTrue(session.state.value.loaded)
        assertFalse(session.state.value.saving)
    }

    @Test
    fun successfulSendDoesNotClearNewerInput() = runTest {
        val repository = persistenceStores(InMemoryDurableByteStore()).drafts
        val session = DraftSession(repository, "a", String.serializer(), "", backgroundScope)
        session.update("sent")
        session.awaitSaved()
        val sentVersion = session.state.value.version
        session.update("next message")
        assertFalse(session.clearIfUnchanged(sentVersion))
        session.awaitSaved()
        assertEquals("\"next message\"", repository.load("a")?.payload)
    }

    @Test
    fun typingDuringSlowClearIsSavedAfterTombstone() = runTest {
        val backend = InMemoryDurableByteStore()
        val actual = persistenceStores(backend).drafts
        val deleting = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val repository = object : DraftRepository by actual {
            override suspend fun deleteIfOwned(key: String, revision: Long, ownerEpoch: Long, resetEpoch: Long): Boolean {
                deleting.complete(Unit)
                release.await()
                return actual.deleteIfOwned(key, revision, ownerEpoch, resetEpoch)
            }
        }
        val session = DraftSession(repository, "a", String.serializer(), "", backgroundScope)
        session.update("sent")
        session.awaitSaved()
        val clear = async { session.clearIfUnchanged(session.state.value.version) }
        deleting.await()
        session.update("typed during delete")
        release.complete(Unit)
        clear.await()
        session.awaitSaved()
        assertEquals("typed during delete", session.state.value.value)
        assertEquals("\"typed during delete\"", actual.load("a")?.payload)
    }

    @Test
    fun sessionRedactsAndRestoresSecretFields() = runTest {
        val repository = persistenceStores(InMemoryDurableByteStore()).drafts
        fun session() = DraftSession(repository, "settings", Form.serializer(), Form("", ""), backgroundScope,
            redact = { it.copy(password = "") }, extractSecrets = { mapOf("password" to it.password) },
            hydrateSecrets = { value, fields -> value.copy(password = fields["password"].orEmpty()) })
        val first = session()
        first.update(Form("1e-", "draft-secret"))
        first.awaitSaved()
        assertFalse(repository.load("settings")!!.payload.contains("draft-secret"))
        val reopened = session()
        reopened.awaitSaved()
        assertEquals(Form("1e-", "draft-secret"), reopened.state.value.value)
    }

    @Serializable private data class Form(val rawNumber: String, val password: String)
}
