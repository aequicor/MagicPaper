package io.aequicor.magicpaper.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.DpOffset
import io.aequicor.magicpaper.domain.RequestPin
import io.aequicor.magicpaper.domain.RequestPinGroup
import io.aequicor.magicpaper.designsystem.LocalPaperColors
import io.aequicor.magicpaper.designsystem.PaperDivider
import io.aequicor.magicpaper.designsystem.PaperPanel
import io.aequicor.magicpaper.designsystem.PaperSurfaceKind
import io.aequicor.magicpaper.designsystem.PaperText
import io.aequicor.magicpaper.designsystem.PaperTextRole
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

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
    browserMessageId: String? = null,
    onCloseBrowser: () -> Unit = {},
    itemKeys: Map<String, Any> = emptyMap(),
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
    DisposableEffect(scroll) { onDispose {
        navigation?.cancel()
        scroll.requestPinsBounds = null
    } }
    val clearance = with(LocalDensity.current) { 16.dp.toPx() }
    val currentIndices by rememberUpdatedState(itemIndices)
    val currentKeys by rememberUpdatedState(itemKeys)
    val entries = remember(groups, itemIndices) { requestPinEntries(groups, itemIndices.keys) }
    SideEffect { if (visible == null) scroll.requestPinsBounds = null }
    val navigate: (RequestPin) -> Unit = { pin ->
        onCloseBrowser()
        navigation?.cancel()
        navigation = scope.launch {
            scroll.navigateToMessage({ currentKeys[pin.messageId] ?: pin.messageId },
                index = { currentIndices[pin.messageId] },
                topInset = { scroll.requestPinsBounds?.let { (it.bottom + clearance).roundToInt() } ?: 0 })
        }
    }
    val selection = visible
    if (selection != null) {
        RequestPinsPanel(selection, onNavigate = navigate, modifier = modifier.onGloballyPositioned {
                val position = it.positionInParent()
                scroll.requestPinsBounds = Rect(position.x, position.y,
                    position.x + it.size.width, position.y + it.size.height)
            })
    }
    if (browserMessageId != null) {
        if (entries.any { it.pin.messageId == browserMessageId }) {
            RequestPinsDialog(entries, browserMessageId, onNavigate = navigate, onDismiss = onCloseBrowser)
        } else {
            SideEffect { onCloseBrowser() }
        }
    }
}

@Composable
internal fun RequestPinsPanel(
    selection: VisibleRequestPins,
    onNavigate: (RequestPin) -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(16.dp)
    PaperPanel(
        modifier = modifier.widthIn(max = 680.dp).fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
            // Blur the panel's shadow without changing the message's opacity.
            .dropShadow(shape, Shadow(radius = 12.dp, color = Color.Black.copy(alpha = .18f), offset = DpOffset(0.dp, 3.dp))),
        shape = shape,
        kind = PaperSurfaceKind.SELECTED,
        shadowElevation = 0.dp,
    ) {
        Column {
            PinText(selection.group.request, title = true,
                modifier = Modifier.clickable(role = Role.Button, onClickLabel = "Перейти к запросу") {
                    onNavigate(selection.group.request)
                })
            selection.clarification?.let { clarification ->
                PaperDivider(Modifier.padding(horizontal = 12.dp), color = LocalPaperColors.current.secondaryText.copy(alpha = .12f))
                Row(Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = "Перейти к уточнению") {
                    onNavigate(clarification)
                }, verticalAlignment = Alignment.CenterVertically) {
                    val count = selection.group.clarifications.size
                    if (count > 1) {
                        Column(Modifier.padding(start = 12.dp).widthIn(min = 12.dp)
                            .semantics { contentDescription = "Уточнение ${selection.clarificationIndex + 1} из $count" },
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Column(Modifier.height(30.dp).width(3.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                pinIndicatorWindow(selection.clarificationIndex, count).forEach { index ->
                                    Box(Modifier.weight(1f).fillMaxWidth().background(
                                        LocalPaperColors.current.text.copy(alpha = if (index == selection.clarificationIndex) 1f else .22f),
                                        RoundedCornerShape(2.dp)))
                                }
                            }
                            if (count > 5) PaperText("${selection.clarificationIndex + 1}/$count", role = PaperTextRole.LABEL,
                                color = LocalPaperColors.current.text)
                        }
                    }
                    PinText(clarification, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun PinText(pin: RequestPin,
    modifier: Modifier = Modifier, title: Boolean = false) {
    val label = if (pin.author == "Пользователь") pin.summary else "${pin.author} · ${pin.summary}"
    Box(modifier.fillMaxWidth()
        .heightIn(min = 44.dp).padding(horizontal = 12.dp, vertical = 8.dp), contentAlignment = Alignment.CenterStart) {
        PaperText(label, maxLines = 2, overflow = TextOverflow.Ellipsis,
            role = PaperTextRole.BODY,
            fontWeight = if (title) FontWeight.SemiBold else FontWeight.Normal,
            color = LocalPaperColors.current.text)
    }
}
