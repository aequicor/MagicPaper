package io.aequicor.magicpaper.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.decodeToImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import io.aequicor.magicpaper.designsystem.PaperAttachmentChip
import io.aequicor.magicpaper.designsystem.PaperAttachmentRow
import io.aequicor.magicpaper.designsystem.PaperImage
import io.aequicor.magicpaper.designsystem.PaperModal
import io.aequicor.magicpaper.designsystem.PaperText
import io.aequicor.magicpaper.designsystem.PaperTextRole
import io.aequicor.magicpaper.domain.Attachment
import io.aequicor.magicpaper.domain.AttachmentKind
import io.aequicor.magicpaper.domain.AttachmentMeta
import io.aequicor.magicpaper.domain.formatSize

/** Глиф типа вложения — в стилистике прочих значков приложения. */
fun attachmentGlyph(kind: AttachmentKind): String = when (kind) {
    AttachmentKind.IMAGE -> "🖼"
    AttachmentKind.TEXT -> "📄"
    AttachmentKind.FILE -> "📦"
}

/** Декодирование изображения вложения; не картинка или битые байты — null. */
@Composable
fun rememberAttachmentBitmap(attachment: Attachment) = remember(attachment.id) {
    if (attachment.kind == AttachmentKind.IMAGE) {
        runCatching { attachment.bytes.decodeToImageBitmap() }.getOrNull()
    } else {
        null
    }
}

/**
 * Ряд прикреплённых файлов над полем ввода: превью изображений,
 * имя и размер, крестик снятия.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PendingAttachmentsRow(
    attachments: List<Attachment>,
    onRemove: (Attachment) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (attachments.isEmpty()) return
    FlowRow(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        attachments.forEach { attachment ->
            AttachmentChip(attachment = attachment, onRemove = { onRemove(attachment) })
        }
    }
}

/** Чип одного вложения в композиции. */
@Composable
fun AttachmentChip(attachment: Attachment, onRemove: (() -> Unit)? = null) {
    val bitmap = rememberAttachmentBitmap(attachment)
    PaperAttachmentChip("${attachment.name} · ${formatSize(attachment.sizeBytes)}", onRemove?.let { { it() } }) {
        if (bitmap != null) {
            PaperImage(bitmap, attachment.name, Modifier.size(34.dp).clip(RoundedCornerShape(7.dp)))
        } else {
            PaperText(attachmentGlyph(attachment.kind), role = PaperTextRole.BODY)
        }
    }
}

/**
 * Вложения сообщения чата в бабле: миниатюры изображений (тап — крупный
 * просмотр), файлы — чипами.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MessageAttachments(attachments: List<Attachment>) {
    if (attachments.isEmpty()) return
    var preview by remember { mutableStateOf<Attachment?>(null) }
    Spacer(Modifier.height(6.dp))
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        attachments.forEach { attachment ->
            val bitmap = rememberAttachmentBitmap(attachment)
            if (bitmap != null) {
                Box(
                    modifier = Modifier
                        .widthIn(max = 220.dp)
                        .heightIn(max = 160.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { preview = attachment },
                ) {
                    PaperImage(bitmap, attachment.name, Modifier.fillMaxWidth().heightIn(max = 160.dp))
                }
            } else {
                AttachmentChip(attachment)
            }
        }
    }
    preview?.let { attachment ->
        ImagePreviewDialog(attachment) { preview = null }
    }
}

/** Полноэкранный просмотр изображения вложения. */
@Composable
fun ImagePreviewDialog(attachment: Attachment, onDismiss: () -> Unit) {
    val bitmap = rememberAttachmentBitmap(attachment)
    PaperModal(onDismissRequest = onDismiss,
        title = { PaperText("${attachment.name} · ${formatSize(attachment.sizeBytes)}", role = PaperTextRole.TITLE) },
        text = {
            Column(modifier = Modifier.padding(12.dp)) {
                if (bitmap != null) {
                    PaperImage(bitmap, attachment.name, Modifier.fillMaxWidth())
                } else {
                    PaperText("Не удалось показать изображение.")
                }
            }
        },
        confirmButton = { io.aequicor.magicpaper.designsystem.PaperAction(onDismiss) { PaperText("Закрыть", role = PaperTextRole.LABEL) } })
}

/** Чипы вложений записи журнала кодинг-сессии (файлы лежат на диске рантайма). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CodingAttachments(metas: List<AttachmentMeta>) {
    if (metas.isEmpty()) return
    Spacer(Modifier.height(6.dp))
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        metas.forEach { meta ->
            PaperAttachmentChip("${meta.name} · ${formatSize(meta.sizeBytes)}", null) {
                PaperText(attachmentGlyph(meta.kind), role = PaperTextRole.BODY)
            }
        }
    }
}
