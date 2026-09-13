package io.aequicor.magicpaper.data.storage

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.serializer
import kotlin.test.*

class DraftGarbageCollectionTest {
    @Test fun successfulSecretAutosavesKeepCleanupMetadataBounded() = runTest {
        val backend = InMemoryDurableByteStore()
        val repository = persistenceStores(backend).drafts
        repository.save(DraftRecord("draft", 1, "text", mapOf("secret" to "value")))
        val firstSize = checkNotNull(backend.read(StorageArea.DRAFTS, "draft")).size
        repeat(100) { revision ->
            repository.save(DraftRecord("draft", revision + 2L, "text", mapOf("secret" to "value")))
        }
        assertTrue(checkNotNull(backend.read(StorageArea.DRAFTS, "draft")).size <= firstSize + 64)
        assertEquals(1, backend.values(StorageArea.SECRETS).size)
        assertEquals("value", repository.load("draft")?.secrets?.get("secret"))
    }

    @Test fun committedClearKeepsSentTextClearedOnCleanupFailureAndRetryNeverResurrectsIt() = runTest {
        val actual = InMemoryDurableByteStore()
        var failDeletion = true
        val backend = object : DurableByteStore by actual {
            override suspend fun delete(area: StorageArea, key: String) {
                if (area == StorageArea.BLOBS && failDeletion) throw StorageException("delete attachment", StorageException.Kind.WRITE)
                actual.delete(area, key)
            }
        }
        val stores = persistenceStores(backend)
        stores.blobs.write("attachment", byteArrayOf(1))
        val session = DraftSession(stores.drafts, "composer", String.serializer(), "", backgroundScope,
            blobIds = { if (it.isEmpty()) emptyList() else listOf("attachment") })
        session.update("accepted message")
        session.awaitSaved()
        val failure = assertFailsWith<StorageException> { session.clearIfUnchanged(session.state.value.version) }
        assertTrue(failure.committed)
        assertEquals("", session.state.value.value)
        assertNull(stores.drafts.load("composer"))
        assertNotNull(session.state.value.error)
        failDeletion = false
        session.retry()
        session.awaitSaved()
        assertNull(session.state.value.error)
        assertEquals("", session.state.value.value)
        assertNull(stores.drafts.load("composer"))
        assertNull(stores.blobs.read("attachment"))
    }

    @Test fun failedStagedSecretWriteKeepsOldSecretAndReopeningCanCollectOrphanReference() = runTest {
        val backend = InMemoryDurableByteStore()
        val actual = DurableSecretStore(backend)
        val repository = DurableDraftRepository(backend, actual)
        repository.save(DraftRecord("secret", 1, "old", mapOf("answer" to "old secret")))
        val interrupted = object : SecretStore by actual {
            override suspend fun write(reference: String, value: String) {
                actual.write(reference, value)
                throw StorageException("interrupted after secret write", StorageException.Kind.WRITE)
            }
        }
        assertFailsWith<StorageException> {
            DurableDraftRepository(backend, interrupted).save(DraftRecord("secret", 2, "new", mapOf("answer" to "orphan secret")))
        }
        assertEquals("old secret", repository.load("secret")?.secrets?.get("answer"))
        assertEquals(2, backend.values(StorageArea.SECRETS).size)
        DurableDraftRepository(backend, actual).retryCleanup()
        assertEquals(listOf("old secret"), backend.values(StorageArea.SECRETS).map { it.decodeToString() })
    }

    @Test fun resetInAnotherRepositoryFencesOldDraftAndNavigationWritersButNewOwnersCanWrite() = runTest {
        val backend = InMemoryDurableByteStore()
        val first = persistenceStores(backend, "first")
        val second = persistenceStores(backend, "second")
        first.navigation.load()
        first.navigation.save("old journal")
        second.navigation.load()
        val old = DraftSession(first.drafts, "old", String.serializer(), "", backgroundScope)
        old.update("old draft")
        old.awaitSaved()
        second.clearOwnedData()
        old.update("late old edit")
        assertFailsWith<StorageException> { old.awaitSaved() }
        assertNotNull(old.state.value.error)
        assertFailsWith<StorageException> { first.navigation.save("late old journal") }
        assertNull(first.drafts.load("old"))
        val freshOnExistingRepository = DraftSession(first.drafts, "fresh", String.serializer(), "", backgroundScope)
        freshOnExistingRepository.update("explicit new draft")
        freshOnExistingRepository.awaitSaved()
        assertEquals("\"explicit new draft\"", first.drafts.load("fresh")?.payload)
        second.navigation.save("new journal")
        val restarted = persistenceStores(backend, "second")
        assertEquals("new journal", restarted.navigation.load())
        val reopened = DraftSession(restarted.drafts, "fresh", String.serializer(), "", backgroundScope)
        reopened.awaitSaved()
        assertEquals("explicit new draft", reopened.state.value.value)
        reopened.update("new edit after restart")
        reopened.awaitSaved()
        assertEquals("\"new edit after restart\"", restarted.drafts.load("fresh")?.payload)
    }

    @Test fun clearWithTheSameRevisionAsAnotherOwnersSaveCannotDeleteItsValue() = runTest {
        val durable = persistenceStores(InMemoryDurableByteStore()).drafts
        for (repository in listOf(durable, InMemoryDraftRepository())) {
            assertTrue(repository.save(DraftRecord("shared", 1, "original")))
            // Both owners observed revision 1; the other owner's revision 2 save wins first.
            assertTrue(repository.save(DraftRecord("shared", 2, "newer value")))
            assertFalse(repository.deleteIfOwned("shared", 2, 0))
            assertEquals("newer value", repository.load("shared")?.payload)
            assertTrue(repository.deleteIfOwned("shared", 3, 0))
            assertTrue(repository.deleteIfOwned("shared", 3, 0))
            assertNull(repository.load("shared"))
        }
    }

    @Test fun sharedBlobSurvivesUntilItsLastDraftClearsAcrossRepositories() = runTest {
        val backend = InMemoryDurableByteStore()
        val first = persistenceStores(backend)
        val second = persistenceStores(backend)
        first.blobs.write("shared", byteArrayOf(1, 2))
        first.drafts.save(DraftRecord("one", 1, "one", blobIds = listOf("shared")))
        second.drafts.save(DraftRecord("two", 1, "two", blobIds = listOf("shared")))
        first.drafts.delete("one", 2)
        assertContentEquals(byteArrayOf(1, 2), second.blobs.read("shared"))
        second.drafts.delete("two", 2)
        assertNull(persistenceStores(backend).blobs.read("shared"))
    }

    @Test fun replacementCollectsOnlyRemovedUnreferencedAttachments() = runTest {
        val stores = persistenceStores(InMemoryDurableByteStore())
        listOf("old", "kept", "shared").forEach { stores.blobs.write(it, byteArrayOf(1)) }
        stores.drafts.save(DraftRecord("one", 1, "old", blobIds = listOf("old", "kept", "shared")))
        stores.drafts.save(DraftRecord("two", 1, "other", blobIds = listOf("shared")))
        stores.drafts.save(DraftRecord("one", 2, "edited", blobIds = listOf("kept")))
        assertNull(stores.blobs.read("old"))
        assertNotNull(stores.blobs.read("kept"))
        assertNotNull(stores.blobs.read("shared"))
        assertFailsWith<StorageException> { stores.blobs.write("shared", byteArrayOf(2)) }
    }

    @Test fun clearingCannotRaceBetweenBlobStagingAndReferenceCommit() = runTest {
        val backend = InMemoryDurableByteStore()
        val first = persistenceStores(backend)
        val second = persistenceStores(backend)
        first.blobs.write("shared", byteArrayOf(1))
        first.drafts.save(DraftRecord("one", 1, "old", blobIds = listOf("shared")))
        val staged = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val saving = async {
            second.drafts.saveWithBlobs(DraftRecord("two", 1, "new", blobIds = listOf("shared"))) {
                second.blobs.write("shared", byteArrayOf(1))
                staged.complete(Unit)
                release.await()
            }
        }
        staged.await()
        val clearing = async { first.drafts.delete("one", 2) }
        runCurrent()
        assertFalse(clearing.isCompleted)
        release.complete(Unit)
        assertTrue(saving.await())
        assertTrue(clearing.await())
        assertNotNull(second.drafts.load("two"))
        assertContentEquals(byteArrayOf(1), second.blobs.read("shared"))
    }

    @Test fun failedStagingLeavesRetryableDurableCleanupIntent() = runTest {
        val backend = InMemoryDurableByteStore()
        val stores = persistenceStores(backend)
        assertFailsWith<StorageException> {
            stores.drafts.saveWithBlobs(DraftRecord("failed", 1, "uncommitted", blobIds = listOf("orphan"))) {
                stores.blobs.write("orphan", byteArrayOf(1))
                throw StorageException("injected failure", StorageException.Kind.WRITE)
            }
        }
        assertNull(stores.drafts.load("failed"))
        assertNotNull(stores.blobs.read("orphan"))
        val restarted = persistenceStores(backend)
        restarted.drafts.save(DraftRecord("trigger", 1, "next transaction"))
        assertNull(restarted.blobs.read("orphan"))
    }

    @Test fun incompleteReferenceScanNeverDeletesBytesAndCanRetry() = runTest {
        val backend = InMemoryDurableByteStore()
        val stores = persistenceStores(backend)
        stores.blobs.write("blob", byteArrayOf(1))
        stores.drafts.save(DraftRecord("one", 1, "value", blobIds = listOf("blob")))
        backend.write(StorageArea.DRAFTS, "corrupt", "{broken".encodeToByteArray())
        val failure = assertFailsWith<StorageException> { stores.drafts.delete("one", 2) }
        assertTrue(failure.committed)
        assertNotNull(stores.blobs.read("blob"))
        backend.delete(StorageArea.DRAFTS, "corrupt")
        stores.drafts.save(DraftRecord("trigger", 1, "next"))
        assertNull(stores.blobs.read("blob"))
    }

    @Test fun ownerRemovalRevokesEveryOldRevisionButAllowsAnExplicitNewSession() = runTest {
        val stores = persistenceStores(InMemoryDurableByteStore())
        stores.drafts.save(DraftRecord("profile", 1, "old", secrets = mapOf("key" to "private")))
        stores.drafts.remove("profile")
        assertFalse(stores.drafts.save(DraftRecord("profile", 1000, "stale")))
        assertFalse(stores.drafts.deleteIfOwned("profile", 1000, 0))
        assertNull(stores.drafts.load("profile"))
        val fresh = DraftSession(stores.drafts, "profile", String.serializer(), "", backgroundScope)
        fresh.update("new explicit draft")
        fresh.awaitSaved()
        assertEquals("\"new explicit draft\"", stores.drafts.load("profile")?.payload)
        assertEquals(listOf("profile"), stores.drafts.keys("profile"))
    }
}
