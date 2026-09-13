package io.aequicor.magicpaper.ui.components

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import io.aequicor.magicpaper.domain.*

object DefaultChatPresentation : ChatPresentation {
    @Composable override fun rememberAttachmentBitmap(attachment: Attachment): ImageBitmap? = renderRememberAttachmentBitmap(attachment)
    @Composable override fun PendingAttachmentsRow(attachments: List<Attachment>, onRemove: (Int) -> Unit, modifier: Modifier) = renderPendingAttachmentsRow(attachments, onRemove, modifier)
    @Composable override fun AttachmentChip(attachment: Attachment, onRemove: (() -> Unit)?, onOpen: (() -> Unit)?) = renderAttachmentChip(attachment, onRemove, onOpen)
    @Composable override fun MessageAttachments(attachments: List<Attachment>) = renderMessageAttachments(attachments)
    @Composable override fun ImagePreviewDialog(attachment: Attachment, onDismiss: () -> Unit) = renderImagePreviewDialog(attachment, onDismiss)
    @Composable override fun CodingInputImages(message: CodingMessage) = renderCodingInputImages(message)
    @Composable override fun CodingResultImages(message: CodingMessage, step: CodingStep) = renderCodingResultImages(message, step)
    @Composable override fun CodingAttachments(metas: List<AttachmentMeta>) = renderCodingAttachments(metas)
    @Composable override fun RequestPinsOverlay(groups: List<RequestPinGroup>, itemIndices: Map<String, Int>, listState: LazyListState, scroll: PaperChatScrollState, modifier: Modifier, browserMessageId: String?, onCloseBrowser: () -> Unit, itemKeys: Map<String, Any>, compact: Boolean) = renderRequestPinsOverlay(groups, itemIndices, listState, scroll, modifier, browserMessageId, onCloseBrowser, itemKeys, compact)
    @Composable override fun MessagePinColumn(number: Int?, onClick: () -> Unit, modifier: Modifier, content: @Composable ColumnScope.() -> Unit) = renderMessagePinColumn(number, onClick, modifier, content)
}
