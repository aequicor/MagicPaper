package io.aequicor.magicpaper.domain

import kotlinx.serialization.Serializable

/** One provider attempt. The session service owns durable jobs, polling and retry decisions. */
interface MediaGenerationGateway {
    suspend fun submit(profile: LlmProfile, selection: MediaModelSelection, request: MediaGenerationRequest): MediaSubmission
    suspend fun poll(profile: LlmProfile, selection: MediaModelSelection, jobId: String, kind: MediaKind): MediaPollResult
    /** Downloads public/signed output URLs without forwarding the provider credential. */
    suspend fun download(output: MediaRemoteOutput): DownloadedMedia
}

sealed interface MediaSubmission {
    data class Accepted(val jobId: String) : MediaSubmission
    data class Completed(val output: MediaRemoteOutput) : MediaSubmission
}

sealed interface MediaPollResult {
    data object Pending : MediaPollResult
    data class Completed(val output: MediaRemoteOutput) : MediaPollResult
    data class Failed(val failure: MediaGatewayException) : MediaPollResult
}

/** Short-lived transport result, never a user-facing URI. Persist until its bytes are saved. */
@Serializable
data class MediaRemoteOutput(
    val kind: MediaKind,
    val url: String = "",
    val dataBase64: String = "",
    val mimeType: String = if (kind == MediaKind.IMAGE) "image/png" else "video/mp4",
    val width: Int = 0,
    val height: Int = 0,
    val durationSeconds: Double? = null,
) {
    override fun toString(): String = "MediaRemoteOutput(kind=$kind, mimeType=$mimeType)"
}

data class DownloadedMedia(
    val bytes: ByteArray,
    val mimeType: String,
    val width: Int = 0,
    val height: Int = 0,
    val durationSeconds: Double? = null,
) {
    override fun toString(): String = "DownloadedMedia(byteSize=${bytes.size}, mimeType=$mimeType)"
}

enum class MediaFailureKind { VALIDATION, AUTHENTICATION, UNAVAILABLE, REJECTED, TRANSIENT, INVALID_RESPONSE, DOWNLOAD, UNKNOWN_OUTCOME }

/** Safe message + typed outcome; the reporting owner logs sanitized metadata, never raw causes. */
class MediaGatewayException(
    val kind: MediaFailureKind,
    message: String,
    val statusCode: Int? = null,
    cause: Throwable? = null,
) : Exception(message, cause) {
    val outcomeUnknown: Boolean get() = kind == MediaFailureKind.UNKNOWN_OUTCOME
}
