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
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.IntOffset

/** Flattened visible tree: ancestors are header keys, ordered from root to parent.
 * A header owns the contiguous rows that name it as an ancestor. Independent rows
 * have no ancestors and release branch headers. Retained headers, such as the
 * current selection, survive that boundary until their entry changes or is removed.
 */
public data class PaperStickyTreeEntry(
    val key: String,
    val ancestors: List<String> = emptyList(),
    val header: Boolean = false,
    /** Keep this header after leaving its branch, e.g. the selected session.
     * Retained headers keep their list order and a slot within the four-row limit. */
    val retainAfterBranch: Boolean = false,
)

internal data class PaperTreeVisibleEntry(val key: String, val offset: Int)
internal data class PaperTreePin(val key: String, val offset: Int)

internal class PaperTreeIndex(entries: List<PaperStickyTreeEntry>) {
    val byKey = entries.associateBy { it.key }
    val positions = entries.withIndex().associate { it.value.key to it.index }
    val retained = entries.filter { it.header && it.retainAfterBranch }.map { it.key }.toSet()
}

private fun List<String>.visiblePinLevels(limit: Int, retained: Set<String> = emptySet()): List<String> {
    if (limit <= 0) return emptyList()
    if (size <= limit) return this
    val reserved = (listOf(first()) + filter { it in retained }).distinct().take(limit)
    val chosen = reserved + filterNot { it in reserved }.takeLast(limit - reserved.size)
    return filter { it in chosen }
}

internal fun paperTreePins(
    entries: List<PaperStickyTreeEntry>,
    visible: List<PaperTreeVisibleEntry>,
    heights: Map<String, Int>,
    index: PaperTreeIndex = PaperTreeIndex(entries),
): List<PaperTreePin> {
    // Layout can still describe the previous model during collapse/filter/reorder.
    // Never interpret an old item index as a different entry in the new model.
    val byKey = index.byKey
    val rows = visible.filter { it.key in byKey }
    val first = rows.firstOrNull() ?: return emptyList()
    val firstEntry = byKey.getValue(first.key)
    val firstIndex = index.positions.getValue(first.key)
    val preferred = index.retained
    fun headers(keys: List<String>) = keys.filter { byKey[it]?.header == true }
    val retained = preferred.filter { key ->
        index.positions.getValue(key) < firstIndex || rows.any { row ->
            row.key == key && row.offset < headers(byKey.getValue(key).ancestors)
                .visiblePinLevels(3, preferred).sumOf { heights[it] ?: 0 }
        }
    }.toSet()
    var branch = headers(firstEntry.ancestors) + listOfNotNull(first.key.takeIf { firstEntry.header })
    fun orderedPins(path: List<String>): List<String> {
        val keys = path.toSet() + retained
        return keys.sortedBy { index.positions.getValue(it) }
            .visiblePinLevels(4, preferred + path.take(1))
    }
    // Resolve each incoming header's slot from its complete ancestry, even when
    // the four-row cap hides intermediate levels, before its natural row disappears.
    for (row in rows) {
        val entry = byKey.getValue(row.key)
        if (!entry.header || entry.key in branch) continue
        val next = headers(entry.ancestors) + entry.key
        val shown = orderedPins(next)
        val slot = shown.takeWhile { it != entry.key }.sumOf { heights[it] ?: 0 }
        if (entry.key in shown && row.offset <= slot) branch = next
    }
    val candidates = orderedPins(branch)
    val branchBottom = candidates.sumOf { heights[it] ?: 0 }
    var top = 0
    return candidates.mapIndexedNotNull { level, key ->
        val height = heights[key] ?: 0
        val natural = rows.firstOrNull { it.key == key }?.offset
        val stackTop = top
        top += height
        if (natural != null && natural >= stackTop) return@mapIndexedNotNull null
        // At a branch boundary, that branch and all its descendants move out
        // together. Ancestors keep their position until their own boundary.
        var offset = stackTop
        for (ancestorLevel in 0..level) {
            val ancestor = candidates[ancestorLevel]
            if (ancestor in preferred) continue
            val boundary = rows.drop(1).firstOrNull { row ->
                val entry = byKey.getValue(row.key)
                index.positions.getValue(entry.key) > index.positions.getValue(ancestor) &&
                    ancestor !in entry.ancestors
            }?.offset ?: continue
            offset = minOf(offset, stackTop + boundary - branchBottom)
        }
        // A retained selection follows its ancestors upwards at their boundary,
        // then stays visible. It never jumps ahead of its project within a branch.
        if (key in preferred) offset = offset.coerceAtLeast(0)
        PaperTreePin(key, offset)
    }
}

/** Four-level sticky context over a lazy tree, with variable-height headers.
 * Foundation's single stickyHeader cannot keep ancestors pinned concurrently.
 * Only visible rows and up to four headers are composed. A pinned row overlays
 * its natural copy so the transition has no empty measurement frame; the copy's
 * semantics are hidden while it remains underneath. Callbacks and state belong to callers.
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
    val pins by remember(entries, state) {
        val index = PaperTreeIndex(entries)
        derivedStateOf {
            paperTreePins(entries, state.layoutInfo.visibleItemsInfo.map { PaperTreeVisibleEntry(it.key as String, it.offset) }, heights, index)
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
        PaperLazyColumn(state = state, modifier = Modifier.fillMaxSize()) {
            items(entries, key = { it.key }, contentType = { it.header }) { entry ->
                val pinned = pins.any { it.key == entry.key }
                Box(Modifier.fillMaxWidth()
                    .then(if (pinned) Modifier.clearAndSetSemantics { } else Modifier)
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
