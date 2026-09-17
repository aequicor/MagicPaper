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
     * Retained headers reserve space before the current branch, within the four-row limit. */
    val retainAfterBranch: Boolean = false,
)

internal data class PaperTreeVisibleEntry(val index: Int, val offset: Int)
internal data class PaperTreePin(val key: String, val offset: Int)

private fun List<String>.visiblePinLevels(limit: Int): List<String> = when {
    limit <= 0 -> emptyList()
    size <= limit -> this
    else -> listOf(first()) + takeLast(limit - 1)
}

internal fun paperTreePins(
    entries: List<PaperStickyTreeEntry>,
    visible: List<PaperTreeVisibleEntry>,
    heights: Map<String, Int>,
    retained: List<IndexedValue<PaperStickyTreeEntry>> = entries.withIndex()
        .filter { it.value.header && it.value.retainAfterBranch },
): List<PaperTreePin> {
    // Retained rows are independent of project boundaries. In particular, a
    // selected chat has no descendants, so ordinary tree ancestry cannot pin it.
    if (retained.isEmpty()) return paperBranchPins(entries, visible, heights)
    val retainedKeys = retained.map { it.value.key }.toSet()
    val first = visible.firstOrNull { it.index in entries.indices } ?: return emptyList()
    var top = 0
    val retainedPins = retained.filter { indexed ->
        indexed.index < first.index || visible.any {
            it.index == indexed.index && it.offset < indexed.value.ancestors.sumOf { key -> heights[key] ?: 0 }
        }
    }.takeLast(3).map { indexed ->
        PaperTreePin(indexed.value.key, top).also { top += heights[indexed.value.key] ?: 0 }
    }
    val branchVisible = visible.filter { entries.getOrNull(it.index)?.key !in retainedKeys }
        .map { it.copy(offset = it.offset - top) }
    return retainedPins + paperBranchPins(entries, branchVisible, heights, retainedKeys, 4 - retainedPins.size)
        .map { it.copy(offset = it.offset + top) }
}

private fun paperBranchPins(
    entries: List<PaperStickyTreeEntry>,
    visible: List<PaperTreeVisibleEntry>,
    heights: Map<String, Int>,
    retainedKeys: Set<String> = emptySet(),
    limit: Int = 4,
): List<PaperTreePin> {
    val first = visible.firstOrNull { it.index in entries.indices } ?: return emptyList()
    val firstEntry = entries[first.index]
    val activePeerHeader = if (firstEntry.header) firstEntry.key else entries
        .subList(0, first.index)
        .asReversed()
        .firstOrNull { firstEntry.ancestors.isNotEmpty() && it.header &&
            it.key !in retainedKeys && it.ancestors == firstEntry.ancestors }
        ?.key
    val candidates = (firstEntry.ancestors + listOfNotNull(activePeerHeader))
        .filterNot { it in retainedKeys }.visiblePinLevels(limit).toMutableList()
    // Include a descendant header as it reaches the bottom of the pinned stack.
    for (row in visible) {
        val entry = entries.getOrNull(row.index) ?: continue
        if (entry.header && entry.key !in retainedKeys && candidates.size < limit &&
            entry.ancestors.filterNot { it in retainedKeys } == candidates &&
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
        // Selection metadata only changes with the model, not with each scroll pixel.
        val retained = entries.withIndex().filter { it.value.header && it.value.retainAfterBranch }
        derivedStateOf {
            paperTreePins(entries, state.layoutInfo.visibleItemsInfo.map { PaperTreeVisibleEntry(it.index, it.offset) }, heights, retained)
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
