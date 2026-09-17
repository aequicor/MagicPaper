package io.aequicor.magicpaper.designsystem

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.interaction.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.v2.ScrollbarAdapter
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.*
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop

@Composable
internal actual fun PaperScrollbar(state: ScrollState, modifier: Modifier, horizontal: Boolean) =
    PaperScrollbar(rememberScrollbarAdapter(state), modifier, horizontal, state)

@Composable
internal actual fun PaperScrollbar(state: LazyListState, modifier: Modifier, reverseLayout: Boolean) =
    PaperScrollbar(rememberScrollbarAdapter(state), modifier, false, state, reverseLayout)

/** Foundation owns thumb geometry, drag arbitration and page clicks, including variable lazy rows. */
@Composable
internal fun PaperScrollbar(adapter: ScrollbarAdapter, modifier: Modifier, horizontal: Boolean,
    scrollState: ScrollableState, reverseLayout: Boolean = false) {
    val colors = LocalPaperColors.current
    val interactions = remember { MutableInteractionSource() }
    val hovered by interactions.collectIsHoveredAsState()
    val dragged by interactions.collectIsDraggedAsState()
    val overflow by remember(adapter) { derivedStateOf { adapter.contentSize > adapter.viewportSize && adapter.viewportSize > 0 } }
    var recentlyScrolled by remember(adapter) { mutableStateOf(false) }
    LaunchedEffect(adapter) {
        // Offset changes also cover wheel ticks, keyboard moves and cursor-follow in editors.
        snapshotFlow { adapter.scrollOffset to scrollState.isScrollInProgress }.drop(1).collectLatest {
            recentlyScrolled = true
            if (!scrollState.isScrollInProgress) { delay(650); recentlyScrolled = false }
        }
    }
    Box(modifier.then(ScrollbarOverlay)) {
        if (overflow) {
            val visible = hovered || dragged || recentlyScrolled
            val track = if (horizontal) Modifier.align(Alignment.BottomStart).fillMaxWidth().height(12.dp)
                else Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(12.dp)
            val orientation = if (horizontal) Orientation.Horizontal else Orientation.Vertical
            // The overlay is a sibling of the content, so forward wheel input through the
            // same native state instead of letting the hover lane become a scroll dead zone.
            Box(track.hoverable(interactions).clearAndSetSemantics {
                contentDescription = if (horizontal) "Горизонтальная прокрутка" else "Вертикальная прокрутка"
                // The content already exposes its scroll range/actions to accessibility.
                // This pointer overlay must not create a second scrollable region.
                hideFromAccessibility()
            }.scrollable(scrollState, orientation,
                reverseDirection = ScrollableDefaults.reverseDirection(LocalLayoutDirection.current, orientation, reverseLayout))) {
                val bar = if (horizontal) Modifier.align(Alignment.Center).fillMaxWidth().height(6.dp)
                    else Modifier.align(Alignment.Center).fillMaxHeight().width(6.dp)
                val style = ScrollbarStyle(24.dp, 6.dp, RoundedCornerShape(3.dp), 0,
                    colors.secondaryText, colors.action)
                // A draw-only visibility change leaves all scroll content and hit areas stable.
                val indicator = bar.graphicsLayer { alpha = if (visible) 1f else 0f }
                if (horizontal) HorizontalScrollbar(adapter, indicator, reverseLayout, style, interactions)
                else VerticalScrollbar(adapter, indicator, reverseLayout, style, interactions)
            }
        }
    }
}

/** An overlay contributes no intrinsic size. Native popup menus query intrinsics before
 * measurement; Foundation's scrollbar expects a bounded viewport and cannot answer them. */
private object ScrollbarOverlay : LayoutModifier {
    override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
        val placeable = measurable.measure(constraints)
        return layout(placeable.width, placeable.height) { placeable.placeRelative(0, 0) }
    }
    override fun IntrinsicMeasureScope.minIntrinsicWidth(measurable: IntrinsicMeasurable, height: Int) = 0
    override fun IntrinsicMeasureScope.maxIntrinsicWidth(measurable: IntrinsicMeasurable, height: Int) = 0
    override fun IntrinsicMeasureScope.minIntrinsicHeight(measurable: IntrinsicMeasurable, width: Int) = 0
    override fun IntrinsicMeasureScope.maxIntrinsicHeight(measurable: IntrinsicMeasurable, width: Int) = 0
}
