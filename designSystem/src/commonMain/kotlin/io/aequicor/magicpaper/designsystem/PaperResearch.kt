package io.aequicor.magicpaper.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Long-form reading typography, scoped so surrounding navigation keeps its compact density. */
internal val LocalPaperResearchReading = staticCompositionLocalOf { false }

@Composable
public fun PaperResearchReading(content: @Composable () -> Unit) {
    val typography = LocalPaperTypography.current
    CompositionLocalProvider(
        LocalPaperTypography provides typography.copy(
            body = typography.body.copy(fontSize = 18.sp, lineHeight = 30.sp),
            headline = typography.headline.copy(fontSize = 32.sp, lineHeight = 40.sp),
            title = typography.title.copy(fontSize = 24.sp, lineHeight = 32.sp),
        ),
        LocalPaperResearchReading provides true,
        content = content,
    )
}

/** A bordered paper sheet used by the three-column research workspace. */
@Composable
public fun PaperResearchPane(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = LocalPaperColors.current
    val shape = RoundedCornerShape(10.dp)
    Column(
        modifier = modifier.clip(shape)
            .background(colors.surface.copy(alpha = .94f), shape)
            .border(1.dp, colors.border.copy(alpha = .55f), shape),
        content = content,
    )
}

/** Small numerical marker shared by panel counters, sources and citations. */
@Composable
public fun PaperResearchCountBadge(
    text: String,
    modifier: Modifier = Modifier,
    selected: Boolean = true,
) {
    Box(
        modifier = modifier.sizeIn(minWidth = 26.dp, minHeight = 26.dp)
            .background(
                if (selected) LocalPaperColors.current.selected else LocalPaperColors.current.raisedSurface,
                CircleShape,
            ).padding(horizontal = 7.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center,
    ) {
        PaperText(text, role = PaperTextRole.LABEL)
    }
}

/** Folded-page brand mark rendered with vectors so every desktop host gets the same glyph. */
@Composable
public fun PaperBrandMark(modifier: Modifier = Modifier) {
    val colors = LocalPaperColors.current
    Canvas(modifier.sizeIn(minWidth = 20.dp, minHeight = 20.dp)) {
        val left = Path().apply {
            moveTo(size.width * .12f, size.height * .10f)
            lineTo(size.width * .48f, size.height * .28f)
            lineTo(size.width * .48f, size.height * .90f)
            lineTo(size.width * .12f, size.height * .70f)
            close()
        }
        val right = Path().apply {
            moveTo(size.width * .88f, size.height * .10f)
            lineTo(size.width * .52f, size.height * .28f)
            lineTo(size.width * .52f, size.height * .90f)
            lineTo(size.width * .88f, size.height * .70f)
            close()
        }
        drawPath(left, colors.accentSurface)
        drawPath(right, colors.action.copy(alpha = .72f))
        drawLine(
            colors.text.copy(alpha = .24f),
            Offset(size.width * .5f, size.height * .28f),
            Offset(size.width * .5f, size.height * .9f),
            1.dp.toPx(),
        )
    }
}

/** The user gets a compact prompt card; the answer reads directly on the surrounding paper pane. */
@Composable
public fun Modifier.paperResearchMessage(first: Boolean, last: Boolean, user: Boolean): Modifier {
    val radius = 12.dp
    val shape = RoundedCornerShape(
        topStart = if (first) radius else 0.dp,
        topEnd = if (first) radius else 0.dp,
        bottomStart = if (last) radius else 0.dp,
        bottomEnd = if (last) radius else 0.dp,
    )
    val surface = if (user) LocalPaperColors.current.selected else androidx.compose.ui.graphics.Color.Transparent
    return padding(start = 12.dp, top = if (first) 6.dp else 0.dp,
        end = 12.dp, bottom = if (last) 6.dp else 0.dp)
        .background(surface, shape)
        .padding(horizontal = 16.dp)
        .padding(top = if (first) 12.dp else 0.dp, bottom = if (last) 12.dp else 0.dp)
}

/** Keeps the same editor/focus while moving from the welcome page to the response dock.
 * Compose's MotionDurationScale applies to this finite, interruptible transition. */
@Composable
public fun paperResearchComposerAlignment(centered: Boolean): Alignment {
    val bias by animateFloatAsState(if (centered) 0f else 1f, tween(220), label = "Research composer position")
    return BiasAlignment(0f, bias)
}
