package io.aequicor.magicpaper.domain

import kotlinx.serialization.Serializable

@Serializable enum class MediaKind { IMAGE, VIDEO }
@Serializable enum class MediaProtocol { OPENAI_IMAGES, DASHSCOPE_IMAGE, DASHSCOPE_VIDEO }

/** A media selection reuses a saved connection's credentials, never its chat parameters. */
@Serializable data class MediaModelSelection(
    val profileId: String,
    val modelId: String,
    val protocol: MediaProtocol = MediaProtocol.OPENAI_IMAGES,
    val baseUrl: String = "",
)

@Serializable data class MediaSettings(
    val image: MediaModelSelection? = null,
    val video: MediaModelSelection? = null,
) {
    fun selection(kind: MediaKind): MediaModelSelection? = if (kind == MediaKind.IMAGE) image else video
    fun withSelection(kind: MediaKind, value: MediaModelSelection?): MediaSettings =
        if (kind == MediaKind.IMAGE) copy(image = value) else copy(video = value)
}

@Serializable data class SessionMediaTools(val images: Boolean = true, val videos: Boolean = true) {
    fun enabled(kind: MediaKind): Boolean = if (kind == MediaKind.IMAGE) images else videos
    fun withEnabled(kind: MediaKind, enabled: Boolean): SessionMediaTools =
        if (kind == MediaKind.IMAGE) copy(images = enabled) else copy(videos = enabled)
}

@Serializable enum class MediaPhase { PENDING, GENERATING, DOWNLOADING, READY, FAILED, UNKNOWN, CANCELLED }

/** Immutable binary content lives outside the history JSON. IDs are SHA-256 digests. */
@Serializable data class MediaAsset(
    val id: String,
    val mimeType: String,
    val byteSize: Long,
    val width: Int = 0,
    val height: Int = 0,
    val durationSeconds: Double? = null,
)

/** Stable identity replaces a placeholder in place, independently of tool diagnostic visibility. */
@Serializable data class GeneratedMedia(
    val id: String,
    val kind: MediaKind,
    val phase: MediaPhase = MediaPhase.PENDING,
    val caption: String = "",
    val width: Int = 1024,
    val height: Int = 1024,
    val durationSeconds: Double? = null,
    val asset: MediaAsset? = null,
    val message: String = "",
)

@Serializable data class MediaGenerationRequest(
    val kind: MediaKind,
    val prompt: String,
    val caption: String = "",
    val width: Int = 1024,
    val height: Int = 1024,
    val durationSeconds: Int = 5,
)

@Serializable enum class MediaAvailability { UNCHECKED, CHECKING, AVAILABLE, UNAVAILABLE }

@Serializable data class MediaConnectionStatus(
    val kind: MediaKind,
    val availability: MediaAvailability = MediaAvailability.UNCHECKED,
    val fingerprint: String = "",
    val message: String = "",
    val preview: GeneratedMedia? = null,
    val checkedAt: Long = 0,
)

/** Only portable export archives contain binary data; ordinary history stores [MediaAsset]. */
@Serializable data class ExportedMediaAsset(val asset: MediaAsset, val dataBase64: String)

/** Known Alibaba hosts keep their region when switching from chat to media endpoints. */
fun suggestedMediaBaseUrl(profile: LlmProfile, protocol: MediaProtocol): String {
    val base = profile.baseUrl.trim().trimEnd('/')
    if (protocol == MediaProtocol.OPENAI_IMAGES) return base
    if (base.contains(".aliyuncs.com") && (base.startsWith("https://") || base.startsWith("http://"))) {
        val origin = base.substringBefore("://") + "://" + base.substringAfter("://").substringBefore('/')
        return "$origin/api/v1"
    }
    return base
}
