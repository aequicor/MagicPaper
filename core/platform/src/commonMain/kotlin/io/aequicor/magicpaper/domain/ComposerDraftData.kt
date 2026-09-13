package io.aequicor.magicpaper.domain

import io.aequicor.magicpaper.data.storage.DraftBlobStore
import io.aequicor.magicpaper.data.storage.DraftRecord
import io.aequicor.magicpaper.data.storage.DraftRepository
import io.aequicor.magicpaper.data.storage.DraftSession
import io.aequicor.magicpaper.data.storage.StorageException
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

@Serializable
data class ComposerDraftData(val text: String = "", val attachments: List<Attachment> = emptyList())

/** Durable metadata references attachment bytes stored outside the ordinary draft record. */
fun composerDraftSession(
    repository: DraftRepository,
    blobs: DraftBlobStore,
    key: String,
    scope: CoroutineScope,
    json: Json = Json { ignoreUnknownKeys = true },
): DraftSession<ComposerDraftData> = DraftSession(
    repository = ComposerBlobRepository(repository, blobs, json),
    key = key,
    serializer = ComposerDraftData.serializer(),
    initial = ComposerDraftData(),
    scope = scope,
    json = json,
    blobIds = { it.attachments.map(Attachment::id) },
)

private class ComposerBlobRepository(
    private val delegate: DraftRepository,
    private val blobs: DraftBlobStore,
    private val json: Json,
) : DraftRepository by delegate {
    private val writtenAttachments = mutableMapOf<String, String>()
    override suspend fun save(draft: DraftRecord): Boolean {
        // Check the revision before writing potentially large attachment data for an obsolete edit.
        if (draft.generation != delegate.generation || delegate.revision(draft.key) >= draft.revision) return false
        val content = json.decodeFromString(ComposerDraftData.serializer(), draft.payload)
        return delegate.saveWithBlobs(draft.copy(payload = json.encodeToString(ComposerDraftData.serializer(),
            content.copy(attachments = content.attachments.map { it.copy(dataBase64 = "") })))) {
            for (attachment in content.attachments) {
                // A shared attachment may have been collected after this session's previous clear.
                if (writtenAttachments[attachment.id] != attachment.dataBase64 || blobs.read(attachment.id) == null) {
                    blobs.write(attachment.id, attachment.bytes)
                    writtenAttachments[attachment.id] = attachment.dataBase64
                }
            }
        }
    }

    @OptIn(ExperimentalEncodingApi::class)
    override suspend fun load(key: String): DraftRecord? {
        val draft = delegate.load(key) ?: return null
        val content = json.decodeFromString(ComposerDraftData.serializer(), draft.payload)
        val hydrated = content.copy(attachments = content.attachments.map { metadata ->
            val bytes = blobs.read(metadata.id) ?: throw StorageException("read attachment", StorageException.Kind.CORRUPT)
            if (bytes.size.toLong() != metadata.sizeBytes) throw StorageException("read attachment size", StorageException.Kind.CORRUPT)
            val encoded = Base64.encode(bytes)
            writtenAttachments[metadata.id] = encoded
            metadata.copy(dataBase64 = encoded)
        })
        return draft.copy(payload = json.encodeToString(ComposerDraftData.serializer(), hydrated))
    }
}
