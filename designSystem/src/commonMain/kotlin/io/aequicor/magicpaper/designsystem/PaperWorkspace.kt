package io.aequicor.magicpaper.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.graphics.drawscope.clipRect

/** Shared reading measure and message geometry; lazy fragments keep their own identity. */
@Composable
public fun Modifier.paperConversationMessage(user: Boolean, first: Boolean, last: Boolean): Modifier {
    val shape = RoundedCornerShape(
        topStart = if (first) 12.dp else 0.dp, topEnd = if (first) 12.dp else 0.dp,
        bottomStart = if (last) 12.dp else 0.dp, bottomEnd = if (last) 12.dp else 0.dp,
    )
    return this.shadow(if (first && last) 2.dp else 0.dp, shape).clip(shape)
        .background(if (user) LocalPaperColors.current.userMessageSurface else LocalPaperColors.current.agentMessageSurface)
        .padding(horizontal = 12.dp, vertical = 0.dp)
        .padding(top = if (first) (if (user) 8.dp else 4.dp) else 0.dp,
            bottom = if (last) (if (user) 8.dp else 4.dp) else 0.dp)
}

/** Quiet disclosure surface. Status and output belong to the calling feature. */
@Composable
public fun PaperWorkSurface(modifier: Modifier = Modifier, expanded: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
        .background(LocalPaperColors.current.surface.copy(alpha = if (expanded) .94f else .55f)), content = content)
}

private val LocalComposerInteraction = staticCompositionLocalOf<MutableInteractionSource?> { null }

/** A raised writing surface; focus belongs to the whole composer, not an inner rectangle. */
@Composable
public fun PaperWorkspaceComposer(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val colors = LocalPaperColors.current
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val surface by animateColorAsState(if (focused) colors.composerFocused else colors.composerSurface)
    val shape = RoundedCornerShape(16.dp)
    CompositionLocalProvider(LocalComposerInteraction provides source) {
        Column(modifier.fillMaxWidth().padding(8.dp)
            .dropShadow(shape) {
                radius = 5.dp.toPx()
                spread = 0f
                color = colors.depthShadow.copy(alpha = .36f)
                offset = Offset(0f, 1.dp.toPx())
            }
            .background(Brush.verticalGradient(listOf(colors.composerHighlight, surface)), shape)
            .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp), content = content)
    }
}

/** The shared interaction source lets the outer writing surface indicate focus without a nested outline. */
@Composable
public fun PaperPromptField(value: String, onValueChange: (String) -> Unit, placeholder: String,
    modifier: Modifier = Modifier, maxLines: Int = 6, enabled: Boolean = true,
    visualTransformation: VisualTransformation = VisualTransformation.None) {
    val source = LocalComposerInteraction.current ?: remember { MutableInteractionSource() }
    BasicTextField(value, onValueChange, modifier.fillMaxWidth().semantics { contentDescription = placeholder }
        .paperFeedback(source, RoundedCornerShape(6.dp), enabled, PaperControlState.NORMAL, showFocus = LocalComposerInteraction.current == null, showPress = false)
        .padding(horizontal = 8.dp, vertical = 8.dp),
        textStyle = LocalPaperTypography.current.body.copy(color = LocalPaperColors.current.text),
        cursorBrush = SolidColor(LocalPaperColors.current.action), maxLines = maxLines,
        interactionSource = source, enabled = enabled, visualTransformation = visualTransformation,
        decorationBox = { inner -> Box {
            if (value.isEmpty()) PaperText(placeholder, color = LocalPaperColors.current.secondaryText)
            inner()
        } })
}

@Composable
public fun PaperWorkspaceHeading(title: String, subtitle: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        PaperText(title, style = LocalPaperTypography.current.chrome.copy(fontWeight = FontWeight.SemiBold),
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        PaperText(subtitle, style = LocalPaperTypography.current.chrome,
            color = LocalPaperColors.current.secondaryText, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Blend blurred messages into the sharp transcript from the top across effectHeight. Place pinned surfaces above this layer. */
@Composable
public fun Modifier.paperTranscriptFade(topShadowVisible: Boolean = false, effectHeight: Dp = 32.dp): Modifier {
    val blurred = rememberGraphicsLayer()
    return graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            if (topShadowVisible) {
                val start = 0f
                val edge = effectHeight.toPx().coerceAtLeast(1f)
                blurred.record { this@drawWithContent.drawContent() }
                blurred.renderEffect = BlurEffect(6.dp.toPx(), 6.dp.toPx(), TileMode.Clamp)
                clipRect(top = start, bottom = edge.coerceAtMost(size.height)) {
                    drawRect(
                        Brush.verticalGradient(
                            0f to Color.Transparent,
                            .25f to Color.White.copy(alpha = .15f),
                            .5f to Color.White.copy(alpha = .5f),
                            .75f to Color.White.copy(alpha = .85f),
                            1f to Color.White,
                            startY = start, endY = edge),
                        blendMode = BlendMode.DstIn,
                    )
                    // Add complementary masks without leaving sharp glyphs behind transparent blur.
                    drawContext.canvas.saveLayer(Rect(Offset.Zero, size), Paint().apply { blendMode = BlendMode.Plus })
                    drawLayer(blurred)
                    drawRect(
                        Brush.verticalGradient(
                            0f to Color.White,
                            .25f to Color.White.copy(alpha = .85f),
                            .5f to Color.White.copy(alpha = .5f),
                            .75f to Color.White.copy(alpha = .15f),
                            1f to Color.Transparent,
                            startY = start, endY = edge),
                        blendMode = BlendMode.DstIn,
                    )
                    drawContext.canvas.restore()
                }
            }
        }
}

/** A shadow starting at the viewport top, extending below any overlaid cards. */
@Composable
public fun Modifier.paperChatTopShadow(visible: Boolean, effectHeight: Dp = 32.dp): Modifier {
    val color = LocalPaperColors.current.depthShadow
    return drawWithContent {
        drawContent()
        if (visible) drawRect(
            Brush.verticalGradient(
                0f to color.copy(alpha = .24f),
                .25f to color.copy(alpha = .13f),
                .5f to color.copy(alpha = .05f),
                .75f to color.copy(alpha = .01f),
                1f to Color.Transparent,
                startY = 0f, endY = effectHeight.toPx().coerceAtLeast(1f)),
            size = androidx.compose.ui.geometry.Size(size.width, effectHeight.toPx().coerceAtLeast(1f)),
        )
    }
}
