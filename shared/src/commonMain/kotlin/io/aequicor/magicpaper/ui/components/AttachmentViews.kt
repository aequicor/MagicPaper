package io.aequicor.magicpaper.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.window.Dialog
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
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f))
            .padding(start = if (bitmap != null) 4.dp else 10.dp, top = 4.dp, end = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = attachment.name,
                modifier = Modifier.size(34.dp).clip(RoundedCornerShape(7.dp)),
                contentScale = ContentScale.Crop,
            )
            Spacer(Modifier.width(8.dp))
        } else {
            Text(attachmentGlyph(attachment.kind), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.width(6.dp))
        }
        Column {
            Text(
                attachment.name,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            Text(
                formatSize(attachment.sizeBytes),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (onRemove != null) {
            Spacer(Modifier.width(6.dp))
            Text(
                "✕",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(onClick = onRemove)
                    .padding(horizontal = 5.dp, vertical = 2.dp),
            )
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
                    Image(
                        bitmap = bitmap,
                        contentDescription = attachment.name,
                        modifier = Modifier.fillMaxWidth().heightIn(max = 160.dp),
                        contentScale = ContentScale.Fit,
                    )
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
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.widthIn(max = 720.dp),
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    "${attachment.name} · ${formatSize(attachment.sizeBytes)}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap,
                        contentDescription = attachment.name,
                        modifier = Modifier.fillMaxWidth(),
                        contentScale = ContentScale.Fit,
                    )
                } else {
                    Text("Не удалось показать изображение.", style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Закрыть") }
                }
            }
        }
    }
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
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.6f))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(attachmentGlyph(meta.kind), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.width(6.dp))
                Text(
                    "${meta.name} · ${formatSize(meta.sizeBytes)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
            }
        }
    }
}
