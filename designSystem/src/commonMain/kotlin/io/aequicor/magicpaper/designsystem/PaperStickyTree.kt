package io.aequicor.magicpaper.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset

/** Flattened visible tree: ancestors are header keys, ordered from root to parent.
 * A header owns the contiguous rows that name it as an ancestor. Independent rows
 * have no ancestors and release the entire pinned stack (for example a chat).
 */
public data class PaperStickyTreeEntry(
    val key: String,
    val ancestors: List<String> = emptyList(),
    val header: Boolean = false,
)

internal data class PaperTreeVisibleEntry(val index: Int, val offset: Int)
internal data class PaperTreePin(val key: String, val offset: Int)

internal fun paperTreePins(
    entries: List<PaperStickyTreeEntry>,
    visible: List<PaperTreeVisibleEntry>,
    heights: Map<String, Int>,
): List<PaperTreePin> {
    val first = visible.firstOrNull { it.index in entries.indices } ?: return emptyList()
    val firstEntry = entries[first.index]
    val activePeerHeader = if (firstEntry.header) firstEntry.key else entries
        .subList(0, first.index)
        .asReversed()
        .firstOrNull { firstEntry.ancestors.isNotEmpty() && it.header && it.ancestors == firstEntry.ancestors }
        ?.key
    val candidates = (firstEntry.ancestors + listOfNotNull(activePeerHeader))
        .takeLast(4).toMutableList()
    // Include a descendant header as it reaches the bottom of the pinned stack.
    for (row in visible) {
        val entry = entries.getOrNull(row.index) ?: continue
        if (entry.header && candidates.size < 4 && entry.ancestors == candidates &&
            row.offset <= candidates.sumOf { heights[it] ?: 0 }
        ) candidates += entry.key
    }
    var top = 0
    return candidates.mapIndexedNotNull { level, key ->
        val height = heights[key] ?: 0
        val natural = visible.firstOrNull { entries.getOrNull(it.index)?.key == key }?.offset
        val stackTop = top
        top += height
        if (natural != null && natural >= stackTop) return@mapIndexedNotNull null
        // At a branch boundary, that branch and all its descendants move out
        // together. Ancestors keep their position until their own boundary.
        var offset = stackTop
        for (ancestorLevel in 0..level) {
            val ancestor = candidates[ancestorLevel]
            val boundary = visible.firstOrNull { row ->
                val entry = entries.getOrNull(row.index)
                entry != null && row.index > first.index && entry.key != ancestor &&
                    entry.key !in candidates.take(ancestorLevel) && ancestor !in entry.ancestors
            }?.offset ?: continue
            val branchBottom = candidates.sumOf { heights[it] ?: 0 }
            offset = minOf(offset, stackTop + boundary - branchBottom)
        }
        PaperTreePin(key, offset)
    }
}

/** Four-level sticky context over a lazy tree, with variable-height headers.
 * Foundation's single stickyHeader cannot keep ancestors pinned concurrently.
 * Only visible rows and up to four headers are composed. Pinned rows replace
 * their original with a size placeholder. Callbacks and state belong to callers.
 * The row's second argument retains its current position before a disclosure
 * changes the visible entries, including when the row is currently pinned.
 */
@Composable
public fun PaperStickyTree(
    entries: List<PaperStickyTreeEntry>,
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    row: @Composable (key: String, retainPosition: () -> Unit) -> Unit,
) {
    val heights = remember { mutableStateMapOf<String, Int>() }
    val density = LocalDensity.current
    val pins by remember(entries, state) {
        derivedStateOf {
            paperTreePins(entries, state.layoutInfo.visibleItemsInfo.map { PaperTreeVisibleEntry(it.index, it.offset) }, heights)
        }
    }
    LaunchedEffect(entries) { heights.keys.retainAll(entries.filter { it.header }.map { it.key }.toSet()) }
    fun retainPosition(key: String) {
        val index = entries.indexOfFirst { it.key == key }
        if (index < 0) return
        val offset = pins.firstOrNull { it.key == key }?.offset
            ?: state.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key }?.offset ?: 0
        state.requestScrollToItem(index, -offset.coerceAtLeast(0))
    }
    Box(modifier.clipToBounds()) {
        LazyColumn(state = state, modifier = Modifier.fillMaxSize()) {
            items(entries, key = { it.key }, contentType = { it.header }) { entry ->
                val pinned = pins.any { it.key == entry.key }
                if (pinned) Spacer(Modifier.fillMaxWidth().height(with(density) { (heights[entry.key] ?: 0).toDp() }))
                else Box(Modifier.fillMaxWidth()
                    .then(if (entry.header) Modifier.onSizeChanged { heights[entry.key] = it.height } else Modifier)
                ) { row(entry.key) { retainPosition(entry.key) } }
            }
        }
        // Draw ancestors last so an outgoing child slides underneath its parent.
        pins.asReversed().forEach { pin ->
            key(pin.key) {
                Box(Modifier.fillMaxWidth().offset { IntOffset(0, pin.offset) }
                    .background(LocalPaperColors.current.surface)
                    .scrollable(state, Orientation.Vertical, reverseDirection = true)
                    .onSizeChanged { heights[pin.key] = it.height }
                ) { row(pin.key) { retainPosition(pin.key) } }
            }
        }
    }
}
