package io.aequicor.magicpaper.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.aequicor.magicpaper.domain.RequestPin
import io.aequicor.magicpaper.domain.RequestPinGroup
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal data class VisibleRequestPins(
    val group: RequestPinGroup,
    val clarificationIndex: Int = -1,
) {
    val clarification get() = group.clarifications.getOrNull(clarificationIndex)
}

/** Selection is spatial, not a read/dismissed state. The title always belongs to the same group. */
internal fun visibleRequestPins(groups: List<RequestPinGroup>, passed: (String) -> Boolean): VisibleRequestPins? {
    val group = groups.lastOrNull { passed(it.request.messageId) } ?: return null
    return VisibleRequestPins(group, group.clarifications.indexOfLast { passed(it.messageId) })
}

internal fun pinIndicatorWindow(index: Int, count: Int): IntRange {
    val start = (index - 2).coerceIn(0, (count - 5).coerceAtLeast(0))
    return start until minOf(start + 5, count)
}

/** An overlay keeps both the list's viewport and the activation boundary independent of its height. */
@Composable
internal fun RequestPinsOverlay(
    groups: List<RequestPinGroup>,
    itemIndices: Map<String, Int>,
    listState: LazyListState,
    scroll: ChatScrollState,
    modifier: Modifier = Modifier,
) {
    val visible by remember(groups, itemIndices, listState) {
        derivedStateOf {
            val layout = listState.layoutInfo
            visibleRequestPins(groups) { id ->
                val index = itemIndices[id]
                if (index == null || layout.visibleItemsInfo.isEmpty()) false
                else layout.visibleItemsInfo.firstOrNull { it.index == index }?.let {
                    it.offset < layout.viewportStartOffset
                } ?: (index < listState.firstVisibleItemIndex)
            }
        }
    }
    val scope = rememberCoroutineScope()
    var navigation by remember(scroll) { mutableStateOf<Job?>(null) }
    var height by remember(scroll) { mutableIntStateOf(0) }
    DisposableEffect(scroll) { onDispose { navigation?.cancel() } }
    val currentIndices by rememberUpdatedState(itemIndices)
    val selection = visible ?: return
    RequestPinsPanel(selection, onNavigate = { pin ->
        navigation?.cancel()
        navigation = scope.launch {
            scroll.navigateToMessage(pin.messageId,
                index = { currentIndices[pin.messageId] }, topInset = { height })
        }
    }, modifier = modifier.onSizeChanged { height = it.height })
}

@Composable
internal fun RequestPinsPanel(
    selection: VisibleRequestPins,
    onNavigate: (RequestPin) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.widthIn(max = 680.dp).fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        shadowElevation = 2.dp,
    ) {
        Column {
            PinText(selection.group.request, "Перейти к запросу", onNavigate, title = true)
            selection.clarification?.let { clarification ->
                HorizontalDivider(Modifier.padding(horizontal = 12.dp), color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = .12f))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val count = selection.group.clarifications.size
                    if (count > 1) {
                        Column(Modifier.padding(start = 12.dp).widthIn(min = 12.dp)
                            .semantics { contentDescription = "Уточнение ${selection.clarificationIndex + 1} из $count" },
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Column(Modifier.height(30.dp).width(3.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                pinIndicatorWindow(selection.clarificationIndex, count).forEach { index ->
                                    Box(Modifier.weight(1f).fillMaxWidth().background(
                                        MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = if (index == selection.clarificationIndex) 1f else .22f),
                                        RoundedCornerShape(2.dp)))
                                }
                            }
                            if (count > 5) Text("${selection.clarificationIndex + 1}/$count", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                    }
                    PinText(clarification, "Перейти к уточнению", onNavigate, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun PinText(pin: RequestPin, action: String, onNavigate: (RequestPin) -> Unit,
    modifier: Modifier = Modifier, title: Boolean = false) {
    val label = if (pin.author == "Пользователь") pin.summary else "${pin.author} · ${pin.summary}"
    Box(modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = action) { onNavigate(pin) }
        .heightIn(min = 44.dp).padding(horizontal = 12.dp, vertical = 8.dp), contentAlignment = Alignment.CenterStart) {
        Text(label, maxLines = 2, overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (title) FontWeight.SemiBold else FontWeight.Normal,
            color = MaterialTheme.colorScheme.onPrimaryContainer)
    }
}
