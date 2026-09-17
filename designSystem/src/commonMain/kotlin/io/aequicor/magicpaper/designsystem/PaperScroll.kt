package io.aequicor.magicpaper.designsystem

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Overlay indicators never reserve space or remeasure the scrolling content when they appear. */
@Composable
public fun PaperScrollViewport(state: ScrollState, modifier: Modifier = Modifier, horizontal: Boolean = false,
    content: @Composable () -> Unit) {
    Box(modifier, propagateMinConstraints = true) {
        content()
        Box(Modifier.matchParentSize()) { PaperScrollbar(state, Modifier.matchParentSize(), horizontal) }
    }
}

@Composable
public fun PaperScrollColumn(modifier: Modifier = Modifier, state: ScrollState = rememberScrollState(),
    contentPadding: PaddingValues = PaddingValues(0.dp),
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    content: @Composable ColumnScope.() -> Unit) {
    PaperScrollViewport(state, modifier) {
        Column(Modifier.verticalScroll(state).padding(contentPadding), verticalArrangement, horizontalAlignment, content)
    }
}

@Composable
public fun PaperScrollRow(modifier: Modifier = Modifier, state: ScrollState = rememberScrollState(),
    horizontalArrangement: Arrangement.Horizontal = Arrangement.Start,
    verticalAlignment: Alignment.Vertical = Alignment.Top,
    content: @Composable RowScope.() -> Unit) {
    PaperScrollViewport(state, modifier, horizontal = true) {
        Row(Modifier.horizontalScroll(state), horizontalArrangement, verticalAlignment, content)
    }
}

/** Keeps the caller's lazy state, keys, restoration and scroll-input modifiers intact. */
@Composable
public fun PaperLazyColumn(modifier: Modifier = Modifier, state: LazyListState = rememberLazyListState(),
    contentPadding: PaddingValues = PaddingValues(0.dp), reverseLayout: Boolean = false,
    verticalArrangement: Arrangement.Vertical = if (reverseLayout) Arrangement.Bottom else Arrangement.Top,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    flingBehavior: FlingBehavior = ScrollableDefaults.flingBehavior(), userScrollEnabled: Boolean = true,
    content: LazyListScope.() -> Unit) {
    Box(modifier, propagateMinConstraints = true) {
        LazyColumn(state = state, contentPadding = contentPadding, reverseLayout = reverseLayout,
            verticalArrangement = verticalArrangement, horizontalAlignment = horizontalAlignment,
            flingBehavior = flingBehavior, userScrollEnabled = userScrollEnabled, content = content)
        if (userScrollEnabled) {
            Box(Modifier.matchParentSize()) { PaperScrollbar(state, Modifier.matchParentSize(), reverseLayout) }
        }
    }
}

@Composable internal expect fun PaperScrollbar(state: ScrollState, modifier: Modifier, horizontal: Boolean)
@Composable internal expect fun PaperScrollbar(state: LazyListState, modifier: Modifier, reverseLayout: Boolean)
