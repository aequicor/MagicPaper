package io.aequicor.magicpaper.ui.components

import androidx.compose.foundation.Canvas
import io.aequicor.magicpaper.designsystem.paperClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.aequicor.magicpaper.domain.RequestPin
import io.aequicor.magicpaper.domain.RequestPinGroup
import io.aequicor.magicpaper.designsystem.LocalPaperColors
import io.aequicor.magicpaper.designsystem.PaperAction
import io.aequicor.magicpaper.designsystem.PaperDivider
import io.aequicor.magicpaper.designsystem.PaperModal
import io.aequicor.magicpaper.designsystem.PaperPanel
import io.aequicor.magicpaper.designsystem.PaperSurfaceKind
import io.aequicor.magicpaper.designsystem.PaperText
import io.aequicor.magicpaper.designsystem.PaperTextRole

internal data class RequestPinEntry(val pin: RequestPin, val isRequest: Boolean)

/** Count and navigation share the same list of messages that still exist in this chat. */
internal fun requestPinEntries(groups: List<RequestPinGroup>, messageIds: Set<String>): List<RequestPinEntry> =
    groups.flatMap { group ->
        listOf(RequestPinEntry(group.request, true)) + group.clarifications.map { RequestPinEntry(it, false) }
    }.filter { it.pin.messageId in messageIds }.distinctBy { it.pin.messageId }

internal fun requestPinNumbers(groups: List<RequestPinGroup>, messageIds: Set<String>): Map<String, Int> =
    requestPinEntries(groups, messageIds).mapIndexed { index, entry -> entry.pin.messageId to index + 1 }.toMap()

@Composable
internal fun MessagePinColumn(number: Int?, onClick: () -> Unit, modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit) {
    Box {
        Column(modifier, content = content)
        if (number != null) {
            // Matching the measured bubble keeps the marker out of content measurement.
            Box(Modifier.matchParentSize()) {
                MessagePinButton(number, onClick, Modifier.align(Alignment.BottomEnd).offset(y = (-3).dp))
            }
        }
    }
}

@Composable
internal fun MessagePinButton(number: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val ink = LocalPaperColors.current.text.copy(alpha = .6f)
    Box(
        modifier = modifier.size(20.dp).clip(CircleShape)
            .paperClickable(shape = CircleShape, role = Role.Button, onClickLabel = "Открыть список закреплений", onClick = onClick)
            .semantics { contentDescription = "Закреплённое сообщение №$number. Открыть список" },
        // The glyph fits in the bubble's existing right padding, beside the last line.
        contentAlignment = Alignment.CenterEnd,
    ) {
        Canvas(Modifier.size(16.dp)) {
            val path = Path().apply {
                moveTo(size.width * .3f, size.height * .12f)
                lineTo(size.width * .7f, size.height * .12f)
                lineTo(size.width * .64f, size.height * .44f)
                lineTo(size.width * .8f, size.height * .62f)
                lineTo(size.width * .2f, size.height * .62f)
                lineTo(size.width * .36f, size.height * .44f)
                close()
            }
            drawPath(path, ink, style = Stroke(width = 1.4.dp.toPx()))
            drawLine(ink, Offset(size.width * .5f, size.height * .62f),
                Offset(size.width * .5f, size.height * .92f), 1.4.dp.toPx(), StrokeCap.Round)
        }
    }
}

@Composable
internal fun RequestPinsDialog(entries: List<RequestPinEntry>, selectedId: String?,
    onNavigate: (RequestPin) -> Unit, onDismiss: () -> Unit) {
    PaperModal(onDismissRequest = onDismiss,
        title = { PaperText("Закреплённые сообщения", role = PaperTextRole.TITLE) },
        text = {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PaperText("Всего: ${entries.size}", Modifier.weight(1f), role = PaperTextRole.LABEL,
                        color = LocalPaperColors.current.secondaryText)
                }
                PaperDivider(Modifier.padding(vertical = 12.dp))
                val listState = rememberLazyListState(
                    initialFirstVisibleItemIndex = (entries.indexOfFirst { it.pin.messageId == selectedId } - 1).coerceAtLeast(0))
                LazyColumn(Modifier.weight(1f, fill = false).fillMaxWidth(), state = listState,
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    itemsIndexed(entries, key = { _, entry -> entry.pin.messageId }) { index, entry ->
                        val current = entry.pin.messageId == selectedId
                        PaperPanel(kind = if (current) PaperSurfaceKind.SELECTED else PaperSurfaceKind.PANEL) {
                            Column(Modifier.fillMaxWidth().semantics { selected = current }
                                .paperClickable(role = Role.Button, onClickLabel = "Перейти к сообщению") { onNavigate(entry.pin) }
                                .padding(12.dp)) {
                                PaperText("${if (entry.isRequest) "Запрос" else "Уточнение"} №${index + 1} · ${entry.pin.author}",
                                    role = PaperTextRole.LABEL,
                                    color = LocalPaperColors.current.secondaryText)
                                PaperText(entry.pin.summary, role = PaperTextRole.BODY,
                                    fontWeight = if (entry.isRequest) FontWeight.SemiBold else FontWeight.Normal,
                                    maxLines = 3, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { PaperAction(onDismiss) { PaperText("Закрыть", role = PaperTextRole.LABEL) } })
}
