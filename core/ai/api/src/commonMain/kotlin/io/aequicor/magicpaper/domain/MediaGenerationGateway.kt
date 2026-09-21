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
