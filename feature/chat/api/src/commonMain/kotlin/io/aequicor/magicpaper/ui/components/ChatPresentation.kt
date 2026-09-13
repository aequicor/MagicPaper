package io.aequicor.magicpaper.ui.components

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import io.aequicor.magicpaper.domain.*

/** Feature-owned Paper content. Defaults live in wrappers to keep the Compose JS ABI stable. */
interface ChatPresentation {
    @Composable fun rememberAttachmentBitmap(attachment: Attachment): ImageBitmap?
    @Composable fun PendingAttachmentsRow(
    attachments: List<Attachment>,
    onRemove: (Int) -> Unit,
    modifier: Modifier,
)
    @Composable fun AttachmentChip(attachment: Attachment, onRemove: (() -> Unit)?, onOpen: (() -> Unit)?)
    @Composable fun MessageAttachments(attachments: List<Attachment>)
    @Composable fun ImagePreviewDialog(attachment: Attachment, onDismiss: () -> Unit)
    @Composable fun CodingInputImages(message: CodingMessage)
    @Composable fun CodingResultImages(message: CodingMessage, step: CodingStep)
    @Composable fun CodingAttachments(metas: List<AttachmentMeta>)
    @Composable fun RequestPinsOverlay(
    groups: List<RequestPinGroup>,
    itemIndices: Map<String, Int>,
    listState: LazyListState,
    scroll: PaperChatScrollState,
    modifier: Modifier,
    browserMessageId: String?,
    onCloseBrowser: () -> Unit,
    itemKeys: Map<String, Any>,
    compact: Boolean,
)
    @Composable fun MessagePinColumn(number: Int?, onClick: () -> Unit, modifier: Modifier,
    content: @Composable ColumnScope.() -> Unit)
}

val LocalChatPresentation = staticCompositionLocalOf<ChatPresentation> { error("ChatPresentation is not installed") }

@Composable
fun rememberAttachmentBitmap(attachment: Attachment): ImageBitmap? = LocalChatPresentation.current.rememberAttachmentBitmap(attachment)

@Composable
fun PendingAttachmentsRow(
    attachments: List<Attachment>,
    onRemove: (Int) -> Unit,
    modifier: Modifier = Modifier,
) = LocalChatPresentation.current.PendingAttachmentsRow(attachments, onRemove, modifier)

@Composable
fun AttachmentChip(attachment: Attachment, onRemove: (() -> Unit)? = null, onOpen: (() -> Unit)? = null) = LocalChatPresentation.current.AttachmentChip(attachment, onRemove, onOpen)

@Composable
fun MessageAttachments(attachments: List<Attachment>) = LocalChatPresentation.current.MessageAttachments(attachments)

@Composable
fun ImagePreviewDialog(attachment: Attachment, onDismiss: () -> Unit) = LocalChatPresentation.current.ImagePreviewDialog(attachment, onDismiss)

@Composable
fun CodingInputImages(message: CodingMessage) = LocalChatPresentation.current.CodingInputImages(message)

@Composable
fun CodingResultImages(message: CodingMessage, step: CodingStep) = LocalChatPresentation.current.CodingResultImages(message, step)

@Composable
fun CodingAttachments(metas: List<AttachmentMeta>) = LocalChatPresentation.current.CodingAttachments(metas)

@Composable
fun RequestPinsOverlay(
    groups: List<RequestPinGroup>,
    itemIndices: Map<String, Int>,
    listState: LazyListState,
    scroll: PaperChatScrollState,
    modifier: Modifier = Modifier,
    browserMessageId: String? = null,
    onCloseBrowser: () -> Unit = {},
    itemKeys: Map<String, Any> = emptyMap(),
    compact: Boolean = false,
) = LocalChatPresentation.current.RequestPinsOverlay(groups, itemIndices, listState, scroll, modifier, browserMessageId, onCloseBrowser, itemKeys, compact)

@Composable
fun MessagePinColumn(number: Int?, onClick: () -> Unit, modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit) = LocalChatPresentation.current.MessagePinColumn(number, onClick, modifier, content)
