package io.aequicor.magicpaper.ui.components

import io.aequicor.magicpaper.designsystem.paperClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
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
import io.aequicor.magicpaper.designsystem.PaperPinnedMessage
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
    PaperPinnedMessage(number, onClick, modifier, content)
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
