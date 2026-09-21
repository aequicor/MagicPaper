package io.aequicor.magicpaper.domain

import kotlinx.serialization.Serializable

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

