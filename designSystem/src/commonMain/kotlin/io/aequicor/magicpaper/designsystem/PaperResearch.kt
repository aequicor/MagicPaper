package io.aequicor.magicpaper.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Long-form reading typography, scoped so surrounding navigation keeps its compact density. */
@Composable
public fun PaperResearchReading(content: @Composable () -> Unit) {
    val typography = LocalPaperTypography.current
    CompositionLocalProvider(LocalPaperTypography provides typography.copy(
        body = typography.body.copy(fontSize = 18.sp, lineHeight = 30.sp),
        title = typography.title.copy(fontSize = 22.sp, lineHeight = 32.sp),
    ), content = content)
}

/** Both authors share the same reading guide; a compact rounded sheet separates the document from the canvas. */
@Composable
public fun Modifier.paperResearchMessage(first: Boolean, last: Boolean, user: Boolean): Modifier {
    val radius = 12.dp
    val shape = RoundedCornerShape(
        topStart = if (first) radius else 0.dp,
        topEnd = if (first) radius else 0.dp,
        bottomStart = if (last) radius else 0.dp,
        bottomEnd = if (last) radius else 0.dp,
    )
    return padding(start = 8.dp, top = if (first) 4.dp else 0.dp,
        end = 8.dp, bottom = if (last) 4.dp else 0.dp)
        .background(if (user) LocalPaperColors.current.selected else LocalPaperColors.current.surface, shape)
        .padding(horizontal = 16.dp)
        .padding(top = if (first) 14.dp else 0.dp, bottom = if (last) 14.dp else 0.dp)
}

/** Keeps the same editor/focus while moving from the welcome page to the response dock.
 * Compose's MotionDurationScale applies to this finite, interruptible transition. */
@Composable
public fun paperResearchComposerAlignment(centered: Boolean): Alignment {
    val bias by animateFloatAsState(if (centered) 0f else 1f, tween(220), label = "Research composer position")
    return BiasAlignment(0f, bias)
}
