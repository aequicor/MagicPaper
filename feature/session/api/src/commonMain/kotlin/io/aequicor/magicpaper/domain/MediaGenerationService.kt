package io.aequicor.magicpaper.domain

import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

/** Ownership comes from the application run, never from model-supplied arguments. */
@Serializable data class MediaGenerationOwner(
    val sessionId: String,
    val requestId: String,
    val callId: String,
    val projectId: String? = null,
    val runtimeGeneration: Long = 0,
)

interface MediaGenerationService {
    val state: StateFlow<Map<MediaKind, MediaConnectionStatus>>
    val operations: StateFlow<Map<String, GeneratedMedia>>
    suspend fun refreshAvailability()
    /** A real, separately metered sample; it never creates a conversation response. */
    suspend fun check(kind: MediaKind, selection: MediaModelSelection, profile: LlmProfile): MediaConnectionStatus
    suspend fun available(kind: MediaKind): Boolean
    suspend fun generate(owner: MediaGenerationOwner, operationId: String, request: MediaGenerationRequest,
                         authorize: suspend () -> Unit = {},
                         onUpdate: suspend (GeneratedMedia) -> Unit = {}): GeneratedMedia
    /** Poll only an existing provider operation; recovery never submits a replacement. */
    suspend fun recover(operationId: String, onUpdate: suspend (GeneratedMedia) -> Unit = {}): GeneratedMedia?
    suspend fun recoverMedia(mediaId: String): GeneratedMedia?
    suspend fun recoverPending()
    suspend fun deleteSession(sessionId: String)
    suspend fun read(asset: MediaAsset): ByteArray
    suspend fun localPath(asset: MediaAsset): String?
    suspend fun prepareForReset()
    suspend fun resumeAfterReset()
    suspend fun close()
}
