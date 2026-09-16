package io.aequicor.magicpaper.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Long-form reading typography, scoped so surrounding navigation keeps its compact density. */
internal val LocalPaperResearchReading = staticCompositionLocalOf { false }

@Composable
public fun PaperResearchReading(content: @Composable () -> Unit) {
    val typography = LocalPaperTypography.current
    CompositionLocalProvider(
        LocalPaperTypography provides typography.copy(
            // A book page needs a calm measure and generous leading, not merely
            // enlarged chat typography. Display faces establish chapter hierarchy;
            // Literata remains the continuous-reading face.
            body = typography.body.copy(fontSize = 17.sp, lineHeight = 29.sp),
            headline = typography.headline.copy(
                fontFamily = PaperFonts.display,
                fontWeight = FontWeight.SemiBold,
                fontSize = 34.sp,
                lineHeight = 40.sp,
            ),
            title = typography.title.copy(
                fontFamily = PaperFonts.display,
                fontWeight = FontWeight.SemiBold,
                fontSize = 27.sp,
                lineHeight = 33.sp,
            ),
        ),
        LocalPaperResearchReading provides true,
        content = content,
    )
}

/** Comfortable line length for long-form prose; callers may still shrink with the window. */
public val PaperResearchReadingMeasure: Dp = 720.dp

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

/** A compact remnant of a collapsed research pane that keeps its frequent actions reachable. */
@Composable
public fun PaperResearchRail(
    title: String,
    count: Int,
    expandLabel: String,
    expandGlyph: String,
    onExpand: () -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable ColumnScope.() -> Unit = {},
) {
    PaperResearchPane(modifier) {
        Column(
            Modifier.fillMaxSize().padding(vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            PaperTooltip(expandLabel) {
                PaperIconButton(expandLabel, onExpand) {
                    PaperText(expandGlyph, role = PaperTextRole.TITLE, color = LocalPaperColors.current.action)
                }
            }
            Box(Modifier.fillMaxWidth().height(104.dp), contentAlignment = Alignment.Center) {
                PaperText(
                    title,
                    Modifier.rotate(-90f),
                    role = PaperTextRole.LABEL,
                    color = LocalPaperColors.current.secondaryText,
                    maxLines = 1,
                )
            }
            PaperResearchCountBadge(count.toString())
            PaperDivider(Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
            actions()
        }
    }
}

/** A labelled icon action intended for [PaperResearchRail]. */
@Composable
public fun PaperResearchRailAction(
    label: String,
    glyph: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    PaperTooltip(label, modifier) {
        PaperIconButton(label, onClick, enabled = enabled) {
            PaperText(glyph, role = PaperTextRole.CHROME, color = LocalPaperColors.current.action)
        }
    }
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
    val radius = 10.dp
    val shape = RoundedCornerShape(
        topStart = if (first) radius else 0.dp,
        topEnd = if (first) radius else 0.dp,
        bottomStart = if (last) radius else 0.dp,
        bottomEnd = if (last) radius else 0.dp,
    )
    // A question is a reader's note; the response is the page itself. Keeping the
    // answer unboxed makes long text scan like a chapter instead of a message bubble.
    val surface = if (user) LocalPaperColors.current.selected.copy(alpha = .62f)
        else androidx.compose.ui.graphics.Color.Transparent
    return padding(
        start = if (user) 28.dp else 12.dp,
        top = if (first) if (user) 10.dp else 18.dp else 0.dp,
        end = if (user) 28.dp else 12.dp,
        bottom = if (last) if (user) 10.dp else 18.dp else 0.dp,
    )
        .background(surface, shape)
        .padding(horizontal = if (user) 18.dp else 20.dp)
        .padding(top = if (first) if (user) 12.dp else 4.dp else 0.dp,
            bottom = if (last) if (user) 12.dp else 8.dp else 0.dp)
}

/** Keeps the same editor/focus while moving from the welcome page to the response dock.
 * Compose's MotionDurationScale applies to this finite, interruptible transition. */
@Composable
public fun paperResearchComposerAlignment(centered: Boolean): Alignment {
    val bias by animateFloatAsState(if (centered) 0f else 1f, tween(220), label = "Research composer position")
    return BiasAlignment(0f, bias)
}
