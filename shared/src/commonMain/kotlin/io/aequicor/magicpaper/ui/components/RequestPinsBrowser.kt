package io.aequicor.magicpaper.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.aequicor.magicpaper.domain.RequestPin
import io.aequicor.magicpaper.domain.RequestPinGroup

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
    val ink = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = .6f)
    Box(
        modifier = modifier.size(20.dp).clip(CircleShape)
            .clickable(role = Role.Button, onClickLabel = "Открыть список закреплений", onClick = onClick)
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
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.widthIn(max = 680.dp).fillMaxWidth(.94f).heightIn(max = 560.dp),
            shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(16.dp)) {
                Text("Закреплённые сообщения", style = MaterialTheme.typography.titleMedium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Всего: ${entries.size}", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = onDismiss) { Text("Закрыть") }
                }
                HorizontalDivider(Modifier.padding(vertical = 12.dp))
                val listState = rememberLazyListState(
                    initialFirstVisibleItemIndex = (entries.indexOfFirst { it.pin.messageId == selectedId } - 1).coerceAtLeast(0))
                LazyColumn(Modifier.weight(1f, fill = false).fillMaxWidth(), state = listState,
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    itemsIndexed(entries, key = { _, entry -> entry.pin.messageId }) { index, entry ->
                        val current = entry.pin.messageId == selectedId
                        Surface(shape = RoundedCornerShape(12.dp),
                            color = if (current) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface) {
                            Column(Modifier.fillMaxWidth().semantics { selected = current }
                                .clickable(role = Role.Button, onClickLabel = "Перейти к сообщению") { onNavigate(entry.pin) }
                                .padding(12.dp)) {
                                Text("${if (entry.isRequest) "Запрос" else "Уточнение"} №${index + 1} · ${entry.pin.author}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(entry.pin.summary, style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (entry.isRequest) FontWeight.SemiBold else FontWeight.Normal,
                                    maxLines = 3, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
    }
}
