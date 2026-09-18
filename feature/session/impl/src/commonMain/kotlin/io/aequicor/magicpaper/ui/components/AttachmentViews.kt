package io.aequicor.magicpaper.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.dp
import io.aequicor.magicpaper.designsystem.PaperAttachmentChip
import io.aequicor.magicpaper.designsystem.PaperAttachmentRow
import io.aequicor.magicpaper.designsystem.PaperAttachmentThumbnail
import io.aequicor.magicpaper.designsystem.PaperAttachmentThumbnailState
import io.aequicor.magicpaper.designsystem.PaperImage
import io.aequicor.magicpaper.designsystem.PaperImageScale
import io.aequicor.magicpaper.designsystem.PaperExpandableImage
import io.aequicor.magicpaper.designsystem.PaperModal
import io.aequicor.magicpaper.designsystem.PaperText
import io.aequicor.magicpaper.designsystem.PaperTextRole
import io.aequicor.magicpaper.domain.Attachment
import io.aequicor.magicpaper.domain.AttachmentKind
import io.aequicor.magicpaper.domain.AttachmentMeta
import io.aequicor.magicpaper.domain.CodingImageLocator
import io.aequicor.magicpaper.domain.CodingImageReference
import io.aequicor.magicpaper.domain.CodingMessage
import io.aequicor.magicpaper.domain.CodingStep
import io.aequicor.magicpaper.domain.formatSize
import io.aequicor.magicpaper.domain.isInputFor
import io.aequicor.magicpaper.domain.isResultFor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

private const val MAX_THUMBNAIL_SOURCE_BYTES = 4 * 1024 * 1024
private const val MAX_PREVIEW_SOURCE_BYTES = 12 * 1024 * 1024
private const val MAX_CACHED_THUMBNAILS = 8
// Keys retain payloads for collision-free identity, so bound that retention too.
internal const val MAX_CACHED_THUMBNAIL_KEY_CHARS = 2 * 1024 * 1024

private data class AttachmentThumbnail(val bitmap: ImageBitmap?, val failed: Boolean = false)
internal data class AttachmentThumbnailKey(
    val id: String,
    val mimeType: String,
    val sizeBytes: Long,
    val dataBase64: String,
)

/** Per-composition generation gate: only the newest payload may publish a decode result. */
internal class AttachmentThumbnailRequestGate {
    private var generation = 0L
    private var activeKey: AttachmentThumbnailKey? = null

    fun begin(key: AttachmentThumbnailKey): Long {
        activeKey = key
        return ++generation
    }

    fun accepts(key: AttachmentThumbnailKey, requestGeneration: Long): Boolean =
        activeKey == key && generation == requestGeneration

    fun clear(key: AttachmentThumbnailKey, requestGeneration: Long) {
        if (accepts(key, requestGeneration)) activeKey = null
    }
}

/** A small LRU prevents repeated image decoding while keeping only composer-sized previews alive. */
private object AttachmentThumbnailCache {
    private val mutex = Mutex()
    private val entries = mutableMapOf<AttachmentThumbnailKey, ImageBitmap>()
    private var retainedKeyChars = 0

    suspend fun get(key: AttachmentThumbnailKey): ImageBitmap? = mutex.withLock {
        entries.remove(key)?.also {
            retainedKeyChars -= key.dataBase64.length
            entries[key] = it
            retainedKeyChars += key.dataBase64.length
        }
    }

    suspend fun put(key: AttachmentThumbnailKey, bitmap: ImageBitmap) = mutex.withLock {
        if (!thumbnailKeyCanBeCached(key)) return@withLock
        entries.remove(key)?.also { retainedKeyChars -= key.dataBase64.length }
        entries[key] = bitmap
        retainedKeyChars += key.dataBase64.length
        while (entries.size > MAX_CACHED_THUMBNAILS || retainedKeyChars > MAX_CACHED_THUMBNAIL_KEY_CHARS) {
            entries.entries.iterator().run {
                val oldest = next()
                retainedKeyChars -= oldest.key.dataBase64.length
                remove()
            }
        }
    }
}

/** Cache admission is bounded by retained identity data, not merely bitmap count. */
internal fun thumbnailKeyCanBeCached(key: AttachmentThumbnailKey): Boolean =
    key.dataBase64.length <= MAX_CACHED_THUMBNAIL_KEY_CHARS

/** Restricts image work so multiple pasted files never monopolize the worker pool. */
private val thumbnailDecoders = Semaphore(2)

/** Глиф типа вложения — в стилистике прочих значков приложения. */


/**
 * Decodes only small, signature-checked raster images off the UI thread. The
 * effect key includes the payload so a late decode can never replace a newer file
 * that happens to reuse an attachment id.
 */
@Composable
private fun rememberAttachmentThumbnail(attachment: Attachment): AttachmentThumbnail {
    val key = remember(attachment.id, attachment.mimeType, attachment.sizeBytes, attachment.dataBase64) {
        AttachmentThumbnailKey(attachment.id, attachment.mimeType, attachment.sizeBytes, attachment.dataBase64)
    }
    var thumbnail by remember(key) { mutableStateOf(AttachmentThumbnail(null)) }
    val requestGate = remember { AttachmentThumbnailRequestGate() }
    val requestGeneration = remember(key) { requestGate.begin(key) }
    DisposableEffect(key, requestGeneration) {
        onDispose { requestGate.clear(key, requestGeneration) }
    }
    LaunchedEffect(key, requestGeneration) {
        thumbnail = AttachmentThumbnail(null)
        val bitmap = withContext(Dispatchers.Default) {
            AttachmentThumbnailCache.get(key) ?: thumbnailDecoders.withPermit { decodeAttachmentThumbnail(attachment) }?.also {
                AttachmentThumbnailCache.put(key, it)
            }
        }
        ensureActive()
        if (requestGate.accepts(key, requestGeneration)) {
            thumbnail = AttachmentThumbnail(bitmap, failed = bitmap == null)
        }
    }
    return thumbnail
}

/** Backward-compatible image consumer API; decoding remains asynchronous and bounded. */
@Composable
fun renderRememberAttachmentBitmap(attachment: Attachment): ImageBitmap? = rememberAttachmentThumbnail(attachment).bitmap

/**
 * A full-size dialog is deliberately not backed by the thumbnail cache: it has a
 * larger, separately bounded budget and releases its bitmap when the dialog closes.
 */
@Composable
private fun rememberAttachmentPreviewBitmap(attachment: Attachment): ImageBitmap? {
    val key = remember(attachment.id, attachment.mimeType, attachment.sizeBytes, attachment.dataBase64) {
        AttachmentThumbnailKey(attachment.id, attachment.mimeType, attachment.sizeBytes, attachment.dataBase64)
    }
    var bitmap by remember(key) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(key) {
        bitmap = withContext(Dispatchers.Default) {
            thumbnailDecoders.withPermit { decodeAttachmentPreview(attachment) }
        }
        ensureActive()
    }
    return bitmap
}

internal fun decodeAttachmentThumbnail(attachment: Attachment): ImageBitmap? = runCatching {
    if (attachment.kind != AttachmentKind.IMAGE || attachment.sizeBytes !in 1..MAX_THUMBNAIL_SOURCE_BYTES) return null
    if (attachment.dataBase64.length > ((MAX_THUMBNAIL_SOURCE_BYTES + 2) / 3) * 4) return null
    val bytes = attachment.bytes
    if (bytes.size.toLong() != attachment.sizeBytes) return null
    io.aequicor.magicpaper.designsystem.decodePaperThumbnailImage(bytes, attachment.mimeType)
}.getOrNull()

internal fun decodeAttachmentPreview(attachment: Attachment): ImageBitmap? = runCatching {
    if (attachment.kind != AttachmentKind.IMAGE || attachment.sizeBytes !in 1..MAX_PREVIEW_SOURCE_BYTES) return null
    if (attachment.dataBase64.length > ((MAX_PREVIEW_SOURCE_BYTES + 2) / 3) * 4) return null
    val bytes = attachment.bytes
    if (bytes.size.toLong() != attachment.sizeBytes) return null
    io.aequicor.magicpaper.designsystem.decodePaperMediaImage(bytes, attachment.mimeType)
}.getOrNull()

/** A generated image has an explicit error owner; never turn a decode failure into an empty result. */
internal fun decodeGeneratedMediaBitmap(bytes: ByteArray, mimeType: String): ImageBitmap =
    io.aequicor.magicpaper.designsystem.decodePaperMediaImage(bytes, mimeType)

/**
 * Ряд прикреплённых файлов над полем ввода: превью изображений,
 * имя и размер, крестик снятия.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun renderPendingAttachmentsRow(
    attachments: List<Attachment>,
    onRemove: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (attachments.isEmpty()) return
    var preview by remember { mutableStateOf<Attachment?>(null) }
    FlowRow(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        attachments.forEachIndexed { index, attachment ->
            key(attachment.id, attachment.mimeType, attachment.sizeBytes, attachment.dataBase64) {
                AttachmentChip(attachment = attachment, onRemove = { onRemove(index) }, onOpen = { preview = attachment })
            }
        }
    }
    preview?.let { attachment -> ImagePreviewDialog(attachment) { preview = null } }
}

/** Чип одного вложения в композиции. */
@Composable
fun renderAttachmentChip(attachment: Attachment, onRemove: (() -> Unit)? = null, onOpen: (() -> Unit)? = null) {
    val thumbnail = rememberAttachmentThumbnail(attachment)
    val state = when {
        attachment.kind != AttachmentKind.IMAGE -> null
        thumbnail.bitmap != null -> PaperAttachmentThumbnailState.READY
        thumbnail.failed -> PaperAttachmentThumbnailState.ERROR
        else -> PaperAttachmentThumbnailState.LOADING
    }
    if (state != null) PaperAttachmentThumbnail(
        label = "${attachment.name} · ${formatSize(attachment.sizeBytes)}",
        description = attachment.name,
        state = state,
        bitmap = thumbnail.bitmap,
        onRemove = onRemove,
        onOpen = if (thumbnail.bitmap != null) onOpen else null,
    ) else PaperAttachmentChip("${attachment.name} · ${formatSize(attachment.sizeBytes)}", onRemove) {
        PaperText(attachmentGlyph(attachment.kind), role = PaperTextRole.BODY)
    }
}

/**
 * Вложения сообщения чата в бабле: миниатюры изображений (тап — крупный
 * просмотр), файлы — чипами.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun renderMessageAttachments(attachments: List<Attachment>) {
    if (attachments.isEmpty()) return
    Spacer(Modifier.height(6.dp))
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        attachments.forEach { attachment ->
            val bitmap = rememberAttachmentThumbnail(attachment).bitmap
            if (bitmap != null) {
                ExpandableAttachmentImage(attachment, bitmap)
            } else {
                AttachmentChip(attachment)
            }
        }
    }
}

/** Полноэкранный просмотр изображения вложения. */
@Composable
fun renderImagePreviewDialog(attachment: Attachment, onDismiss: () -> Unit) {
    val bitmap = rememberAttachmentPreviewBitmap(attachment)
    PaperModal(onDismissRequest = onDismiss,
        title = { PaperText("${attachment.name} · ${formatSize(attachment.sizeBytes)}", role = PaperTextRole.TITLE) },
        text = {
            Column(modifier = Modifier.padding(12.dp)) {
                if (bitmap != null) {
                    PaperImage(bitmap, attachment.name, Modifier.fillMaxWidth(), scale = PaperImageScale.FIT)
                } else {
                    PaperText("Не удалось показать изображение.")
                }
            }
        },
        confirmButton = { io.aequicor.magicpaper.designsystem.PaperAction(onDismiss) { PaperText("Закрыть", role = PaperTextRole.LABEL) } })
}

/**
 * Coding history uses the same bounded thumbnail loader as the composer. A
 * ManagedBlob has no common-platform reader, so it is deliberately explicit
 * instead of falling back to an attachment with the same name.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun renderCodingInputImages(message: CodingMessage) {
    CodingImageAttachments(message.images.filter { it.isInputFor(message) }, "Входные изображения")
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun renderCodingResultImages(message: CodingMessage, step: CodingStep) {
    CodingImageAttachments(step.images.filter { it.isResultFor(message, step) }, "Результаты изображений")
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CodingImageAttachments(images: List<CodingImageReference>, heading: String) {
    if (images.isEmpty()) return
    Spacer(Modifier.height(6.dp))
    PaperText(heading, role = PaperTextRole.LABEL)
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        images.forEach { image ->
            key(image.imageId, image.locator) {
                val attachment = image.asInlineAttachment()
                if (attachment == null) {
                    PaperAttachmentThumbnail(
                        label = "${image.name} · недоступно",
                        description = "${image.name}: источник изображения недоступен",
                        state = PaperAttachmentThumbnailState.ERROR,
                        onRemove = null,
                    )
                } else {
                    val bitmap = rememberAttachmentThumbnail(attachment)
                    if (bitmap.bitmap != null) {
                        ExpandableAttachmentImage(attachment, bitmap.bitmap)
                    } else {
                        PaperAttachmentThumbnail(
                            label = "${image.name} · ${formatSize(image.sizeBytes)}",
                            description = image.name,
                            state = if (bitmap.failed) PaperAttachmentThumbnailState.ERROR else PaperAttachmentThumbnailState.LOADING,
                            onRemove = null,
                        )
                    }
                }
            }
        }
    }
}

/** The compact bitmap keeps the transcript light; full pixels exist only while the image is open. */
@Composable
private fun ExpandableAttachmentImage(attachment: Attachment, thumbnail: ImageBitmap) {
    var expanded by rememberSaveable(attachment.id) { mutableStateOf(false) }
    val preview = if (expanded) rememberAttachmentPreviewBitmap(attachment) else null
    PaperExpandableImage(preview ?: thumbnail, attachment.name, expanded, { expanded = !expanded })
}

private fun CodingImageReference.asInlineAttachment(): Attachment? =
    (locator as? CodingImageLocator.InlineBase64)?.dataBase64?.takeIf { it.isNotBlank() }?.let { data ->
        Attachment(imageId, name, mimeType, sizeBytes, data, AttachmentKind.IMAGE)
    }

/** Чипы вложений записи журнала кодинг-сессии (файлы лежат на диске рантайма). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun renderCodingAttachments(metas: List<AttachmentMeta>) {
    if (metas.isEmpty()) return
    Spacer(Modifier.height(6.dp))
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        metas.forEach { meta ->
            if (meta.kind == AttachmentKind.IMAGE) PaperAttachmentThumbnail(
                label = "${meta.name} · недоступно",
                description = "${meta.name}: источник изображения недоступен",
                state = PaperAttachmentThumbnailState.ERROR,
                onRemove = null,
            ) else PaperAttachmentChip("${meta.name} · ${formatSize(meta.sizeBytes)}", null) {
                PaperText(attachmentGlyph(meta.kind), role = PaperTextRole.BODY)
            }
        }
    }
}
