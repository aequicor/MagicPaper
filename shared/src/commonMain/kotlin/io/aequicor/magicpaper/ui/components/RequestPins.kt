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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
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
    val fade = with(LocalDensity.current) { 16.dp.toPx() }
    val currentIndices by rememberUpdatedState(itemIndices)
    val entries = remember(groups, itemIndices) { requestPinEntries(groups, itemIndices.keys) }
    var browserOpen by remember(scroll) { mutableStateOf(false) }
    SideEffect { if (visible == null) scroll.requestPinsBounds = null }
    val navigate: (RequestPin) -> Unit = { pin ->
        browserOpen = false
        navigation?.cancel()
        navigation = scope.launch {
            scroll.navigateToMessage(pin.messageId,
                index = { currentIndices[pin.messageId] },
                topInset = { scroll.requestPinsBounds?.let { (it.bottom + fade).roundToInt() } ?: 0 })
        }
    }
    val selection = visible
    if (selection != null) {
        RequestPinsPanel(selection, onNavigate = navigate, pinCount = entries.size,
            onShowPins = { browserOpen = true }, modifier = modifier.onGloballyPositioned {
                val position = it.positionInParent()
                scroll.requestPinsBounds = Rect(position.x, position.y,
                    position.x + it.size.width, position.y + it.size.height)
            })
    } else if (entries.isNotEmpty()) {
        // Keep the list reachable even before the first request has scrolled offscreen.
        RequestPinsButton(entries.size, { browserOpen = true }, modifier.padding(horizontal = 12.dp, vertical = 4.dp))
    }
    if (entries.isEmpty()) {
        SideEffect { browserOpen = false }
    } else if (browserOpen) {
        RequestPinsDialog(entries, selection?.let { it.clarification ?: it.group.request }?.messageId,
            onNavigate = navigate, onDismiss = { browserOpen = false })
    }
}

/** Apply inside the list's offscreen layer, so only messages fade and paper stays continuous. */
internal fun Modifier.requestPinsShade(scroll: ChatScrollState): Modifier = drawWithContent {
    drawContent()
    val bounds = scroll.requestPinsBounds ?: return@drawWithContent
    val feather = 20.dp.toPx()
    val left = bounds.left - 8.dp.toPx()
    val right = bounds.right + 8.dp.toPx()
    val area = Rect(left, 0f, right, bounds.bottom + 16.dp.toPx())
    val sideStop = (feather / area.width).coerceAtMost(.5f)
    drawIntoCanvas { canvas ->
        // Multiplying two gradients gives a soft bottom edge and soft sides without
        // washing out the uncovered left half of a wide conversation.
        canvas.saveLayer(area, Paint().apply { blendMode = BlendMode.DstOut })
        drawRect(Brush.verticalGradient(listOf(Color.White, Color.Transparent),
            startY = bounds.bottom - 4.dp.toPx(), endY = area.bottom),
            topLeft = area.topLeft, size = area.size)
        drawRect(Brush.horizontalGradient(
            0f to Color.Transparent, sideStop to Color.White,
            1f - sideStop to Color.White, 1f to Color.Transparent,
            startX = left, endX = right), topLeft = area.topLeft, size = area.size,
            blendMode = BlendMode.DstIn)
        canvas.restore()
    }
}

@Composable
internal fun RequestPinsPanel(
    selection: VisibleRequestPins,
    onNavigate: (RequestPin) -> Unit,
    modifier: Modifier = Modifier,
    pinCount: Int = 0,
    onShowPins: () -> Unit = {},
) {
    Surface(
        modifier = modifier.widthIn(max = 680.dp).fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        shadowElevation = 2.dp,
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PinText(selection.group.request, title = true,
                    modifier = Modifier.weight(1f).clickable(role = Role.Button, onClickLabel = "Перейти к запросу") {
                        onNavigate(selection.group.request)
                    })
                if (pinCount > 0) RequestPinsButton(pinCount, onShowPins, Modifier.padding(end = 8.dp))
            }
            selection.clarification?.let { clarification ->
                HorizontalDivider(Modifier.padding(horizontal = 12.dp), color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = .12f))
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
                                        MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = if (index == selection.clarificationIndex) 1f else .22f),
                                        RoundedCornerShape(2.dp)))
                                }
                            }
                            if (count > 5) Text("${selection.clarificationIndex + 1}/$count", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer)
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
        Text(label, maxLines = 2, overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (title) FontWeight.SemiBold else FontWeight.Normal,
            color = MaterialTheme.colorScheme.onPrimaryContainer)
    }
}
