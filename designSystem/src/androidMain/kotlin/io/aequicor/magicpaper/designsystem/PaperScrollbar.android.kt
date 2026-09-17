package io.aequicor.magicpaper.designsystem

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

// Desktop/web use Foundation's pointer-operated scrollbars; Android retains native touch scrolling.
@Composable internal actual fun PaperScrollbar(state: ScrollState, modifier: Modifier, horizontal: Boolean) = Unit
@Composable internal actual fun PaperScrollbar(state: LazyListState, modifier: Modifier, reverseLayout: Boolean) = Unit
