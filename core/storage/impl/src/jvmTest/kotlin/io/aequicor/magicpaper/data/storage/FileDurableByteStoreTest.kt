package io.aequicor.magicpaper.data.storage

import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.serializer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileDurableByteStoreTest {
    @Test
    fun presentationPayloadsUseSeparateFilesAndReopenAcrossJournalForks() = runTest {
        val root = Files.createTempDirectory("magicpaper-navigation-views-").toFile()
        try {
            val first = desktopPersistenceStores(root, "first")
            first.navigation.saveWithPresentations("journal refs", mapOf("shared" to "scroll and expansion"))
            val journalFile = root.resolve("persistence/navigation").listFiles()!!.single()
            assertFalse(journalFile.readText().contains("scroll and expansion"))
            assertTrue(root.resolve("persistence/view-states").listFiles()!!.single().readText().contains("scroll and expansion"))
            val fork = desktopPersistenceStores(root, "fork", fallbackJournalId = "first")
            val captured = checkNotNull(fork.navigation.loadWithPresentations())
            first.navigation.saveWithPresentations("first moved", mapOf("new" to "new state"))
            fork.navigation.saveWithPresentations(captured.snapshot, captured.presentations)
            assertEquals(captured, desktopPersistenceStores(root, "fork").navigation.loadWithPresentations())
            assertEquals(2, root.resolve("persistence/view-states").listFiles()!!.size)
            fork.navigation.saveWithPresentations("fork cleared", emptyMap())
            assertEquals(1, root.resolve("persistence/view-states").listFiles()!!.size)
            assertEquals(mapOf("new" to "new state"), first.navigation.loadWithPresentations()?.presentations)
        } finally { root.deleteRecursively() }
    }

    @Test
    fun journalWithManyRecordFilesReopensWhole() = runTest {
        val root = Files.createTempDirectory("magicpaper-journal-scan-").toFile()
        try {
            val first = desktopPersistenceStores(root)
            val appended = (1..300).map { first.events.append("stream-${it % 3}", "op", it.toLong(), "detail-$it") }
            first.navigation.saveWithPresentations("journal", (0 until 40).associate { "view-$it" to "state-$it" })
            val reopened = desktopPersistenceStores(root)
            val snapshots = reopened.events.snapshotAll()
            assertEquals(appended.groupBy { it.stream }, snapshots.mapValues { it.value.records })
            assertEquals(40, reopened.navigation.loadWithPresentations()?.presentations?.size)
        } finally { root.deleteRecursively() }
    }

    @Test
    fun resetEpochSurvivesReopeningAndRejectsWritersFromAnotherFileStore() = runTest {
        val root = Files.createTempDirectory("magicpaper-reset-epoch-").toFile()
        try {
            val first = desktopPersistenceStores(root, "first")
            val second = desktopPersistenceStores(root, "second")
            first.navigation.save("old journal")
            val old = DraftSession(first.drafts, "draft", String.serializer(), "", backgroundScope)
            old.update("old")
            old.awaitSaved()
            second.clearOwnedData()
            old.update("late")
            assertFailsWith<StorageException> { old.awaitSaved() }
            assertFailsWith<StorageException> { first.navigation.save("late") }
            val reopened = desktopPersistenceStores(root, "first")
            assertNull(reopened.navigation.load())
            assertNull(reopened.drafts.load("draft"))
            val fresh = DraftSession(reopened.drafts, "draft", String.serializer(), "", backgroundScope)
            fresh.update("new")
            fresh.awaitSaved()
            assertEquals("\"new\"", reopened.drafts.load("draft")?.payload)
            assertEquals(1L, reopened.drafts.resetEpoch())
        } finally { root.deleteRecursively() }
    }

    @Test
    fun independentFileStoresKeepSharedAttachmentDuringConcurrentDeletionAndAfterReopen() = runTest {
        val root = Files.createTempDirectory("magicpaper-attachment-gc-").toFile()
        try {
            val first = desktopPersistenceStores(root)
            val second = desktopPersistenceStores(root)
            val bytes = byteArrayOf(2, 3, 5, 7)
            first.drafts.saveWithBlobs(DraftRecord("chat:a", 1, "a", blobIds = listOf("shared"))) {
                first.blobs.write("shared", bytes)
            }
            val staging = CompletableDeferred<Unit>()
            val continueSave = CompletableDeferred<Unit>()
            val save = async {
                second.drafts.saveWithBlobs(DraftRecord("chat:b", 1, "b", blobIds = listOf("shared"))) {
                    staging.complete(Unit)
                    continueSave.await()
                    second.blobs.write("shared", bytes)
                }
            }
            staging.await()
            val remove = async { first.drafts.remove("chat:a") }
            runCurrent()
            assertFalse(remove.isCompleted)
            continueSave.complete(Unit)
            assertTrue(save.await())
            remove.await()

            val reopened = desktopPersistenceStores(root)
            assertNull(reopened.drafts.load("chat:a"))
            assertEquals("b", reopened.drafts.load("chat:b")?.payload)
            assertContentEquals(bytes, reopened.blobs.read("shared"))
            reopened.drafts.remove("chat:b")
            assertNull(desktopPersistenceStores(root).blobs.read("shared"))
        } finally { root.deleteRecursively() }
    }

    @Test
    fun committedDataSurvivesReopeningAndIsSeparatedFromOrdinaryStorage() = runTest {
        val root = Files.createTempDirectory("magicpaper-persistence-").toFile()
        try {
            val first = desktopPersistenceStores(root)
            first.secrets.write("provider/key", "plaintext-secret")
            first.blobs.write("photo", byteArrayOf(-1, 0, 1))
            first.drafts.save(DraftRecord("chat/a", 12, "text", mapOf("answer" to "secret-draft"), listOf("photo")))
            first.navigation.save("navigation")
            val next = desktopPersistenceStores(root)
            assertEquals("plaintext-secret", next.secrets.read("provider/key"))
            assertEquals("secret-draft", next.drafts.load("chat/a")?.secrets?.get("answer"))
            assertEquals("navigation", next.navigation.load())
            assertContentEquals(byteArrayOf(-1, 0, 1), next.blobs.read("photo"))
            val draftFile = root.resolve("persistence/drafts").listFiles()!!.single()
            assertFalse(draftFile.readText().contains("secret-draft"))
            val secretFiles = root.resolve("persistence/secrets").listFiles()!!
            assertTrue(secretFiles.any { it.readText() == "plaintext-secret" })
            if (Files.getFileStore(root.toPath()).supportsFileAttributeView("posix")) {
                secretFiles.forEach { assertEquals(setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE), Files.getPosixFilePermissions(it.toPath())) }
            }
            next.secrets.delete("provider/key")
            assertNull(desktopPersistenceStores(root).secrets.read("provider/key"))
            assertFalse(root.walkTopDown().any { it.extension == "pending" })
        } finally { root.deleteRecursively() }
    }
}
