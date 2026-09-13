package io.aequicor.magicpaper.domain

import io.aequicor.magicpaper.data.storage.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class ComposerDraftDataTest {
    @Test fun restartRestoresExactAttachmentBytesFromSeparateBlobStorage() = runTest {
        val repository = InMemoryDraftRepository()
        val blobs = InMemoryDraftBlobStore()
        val bytes = byteArrayOf(0, 1, 2, -1, 0)
        val attachment = Attachment.fromBytes("image.png", "image/png", bytes)
        val draft = composerDraftSession(repository, blobs, "chat:one", backgroundScope)
        draft.update(ComposerDraftData("unsent", listOf(attachment)))
        draft.awaitSaved()
        val metadata = checkNotNull(repository.load("chat:one"))
        assertFalse(attachment.dataBase64 in metadata.payload)
        assertEquals(listOf(attachment.id), metadata.blobIds)
        assertContentEquals(bytes, blobs.read(attachment.id))
        val restarted = composerDraftSession(repository, blobs, "chat:one", backgroundScope)
        restarted.awaitSaved()
        assertEquals("unsent", restarted.state.value.value.text)
        assertContentEquals(bytes, restarted.state.value.value.attachments.single().bytes)
        restarted.clearIfUnchanged(restarted.state.value.version)
        assertNull(repository.load("chat:one"))
    }

    @Test fun missingBlobIsAnErrorAndNeverAnEmptyAttachment() = runTest {
        val repository = InMemoryDraftRepository()
        val blobs = InMemoryDraftBlobStore()
        val attachment = Attachment.fromBytes("test.bin", "application/octet-stream", byteArrayOf(1, 2, 3))
        val draft = composerDraftSession(repository, blobs, "coding:one", backgroundScope)
        draft.update(ComposerDraftData(attachments = listOf(attachment)))
        draft.awaitSaved()
        blobs.delete(attachment.id)
        val restarted = composerDraftSession(repository, blobs, "coding:one", backgroundScope)
        assertFailsWith<StorageException> { restarted.awaitSaved() }
        assertNotNull(restarted.state.value.error)
        assertNotNull(repository.load("coding:one"))
    }
}
