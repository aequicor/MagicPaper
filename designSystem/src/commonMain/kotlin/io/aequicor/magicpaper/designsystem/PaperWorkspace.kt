package io.aequicor.magicpaper.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
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
internal val LocalComposerCornerRadius = staticCompositionLocalOf { 10.dp }

/** A raised writing surface; focus belongs to the whole composer, not an inner rectangle.
 * [corner] attaches to the physical top-right frame corner, outside content padding.
 * Content should reserve enough room for that action beside its editor. */
@Composable
public fun PaperWorkspaceComposer(
    modifier: Modifier = Modifier,
    document: Boolean = false,
    options: (@Composable () -> Unit)? = null,
    corner: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = LocalPaperColors.current
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val surface by animateColorAsState(
        if (focused) colors.composerFocused else colors.composerSurface,
        animationSpec = tween(durationMillis = 180),
        label = "Composer focus surface",
    )
    val entrance = remember { Animatable(0f) }
    LaunchedEffect(Unit) { entrance.animateTo(1f, tween(durationMillis = 180)) }
    val cornerRadius = if (document) 10.dp else 16.dp
    val shape = RoundedCornerShape(cornerRadius)
    val frame = if (document) {
        Modifier.background(colors.surface.copy(alpha = .96f), shape)
            .border(1.dp, colors.border.copy(alpha = .72f), shape)
    } else {
        Modifier.dropShadow(shape) {
            radius = 5.dp.toPx()
            spread = 0f
            color = colors.depthShadow.copy(alpha = .36f)
            offset = Offset(0f, 1.dp.toPx())
        }.background(Brush.verticalGradient(listOf(colors.composerHighlight, surface)), shape)
    }
    CompositionLocalProvider(LocalComposerInteraction provides source, LocalComposerCornerRadius provides cornerRadius) {
        val outerPadding = if (document) 12.dp else 8.dp
        val innerVerticalPadding = 8.dp
        Box(modifier.fillMaxWidth().padding(outerPadding)
            // Alpha alone keeps the final geometry from the first layout pass, so the
            // transcript never jumps while the writing surface gently appears.
            .graphicsLayer { alpha = entrance.value }
            .then(frame)) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = innerVerticalPadding),
                verticalArrangement = if (options == null) Arrangement.spacedBy(6.dp) else Arrangement.Top) {
                if (options == null) content()
                else {
                    // Keep the editor branch stable while the options reveal below it.
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp), content = content)
                    options()
                }
            }
            // The affordance belongs to the surface, not a padded editor row. Its bounds
            // and the border therefore share an origin at every density and content height.
            if (corner != null) Box(Modifier.align(AbsoluteAlignment.TopRight)) {
                corner()
            }
        }
    }
}

/** The shared interaction source lets the outer writing surface indicate focus without a nested outline. */
@Composable
public fun PaperPromptField(value: String, onValueChange: (String) -> Unit, placeholder: String,
    modifier: Modifier = Modifier, maxLines: Int = 6, enabled: Boolean = true,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    singleLine: Boolean = false, onSubmit: (() -> Unit)? = null, label: String? = null) {
    val source = LocalComposerInteraction.current ?: remember { MutableInteractionSource() }
    val field: @Composable () -> Unit = {
        PaperBasicTextField(value, onValueChange, modifier.fillMaxWidth()
            .onPreviewKeyEvent { event ->
                if (enabled && onSubmit != null && event.key == Key.Enter) {
                    if (event.type == KeyEventType.KeyDown && value.isNotBlank()) onSubmit()
                    true
                } else false
            }
            .paperFeedback(source, RoundedCornerShape(6.dp), enabled, PaperControlState.NORMAL, showFocus = LocalComposerInteraction.current == null, showPress = false)
            .padding(horizontal = 8.dp, vertical = 8.dp),
            textStyle = LocalPaperTypography.current.body.copy(color = LocalPaperColors.current.text), singleLine = singleLine,
            contentDescription = placeholder, cursorBrush = SolidColor(LocalPaperColors.current.action), maxLines = if (singleLine) 1 else maxLines,
            interactionSource = source, enabled = enabled, visualTransformation = visualTransformation,
            decorationBox = { inner -> Box {
                if (value.isEmpty()) PaperText(placeholder, color = LocalPaperColors.current.secondaryText)
                inner()
            } })
    }
    if (label == null) field() else Column(
        Modifier.fillMaxWidth().background(LocalPaperColors.current.surface, RoundedCornerShape(10.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        PaperText(label, role = PaperTextRole.LABEL, color = LocalPaperColors.current.action)
        field()
    }
}

/** Selection-aware prompt field for callers that handle editing commands at the cursor. */
@Composable
public fun PaperPromptField(value: TextFieldValue, onValueChange: (TextFieldValue) -> Unit, placeholder: String,
    modifier: Modifier = Modifier, maxLines: Int = 6, enabled: Boolean = true,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    style: androidx.compose.ui.text.TextStyle = LocalPaperTypography.current.body) {
    val source = LocalComposerInteraction.current ?: remember { MutableInteractionSource() }
    PaperBasicTextField(value, onValueChange, modifier.fillMaxWidth()
        .paperFeedback(source, RoundedCornerShape(6.dp), enabled, PaperControlState.NORMAL, showFocus = LocalComposerInteraction.current == null, showPress = false)
        .padding(horizontal = 8.dp, vertical = 8.dp),
        textStyle = style.copy(color = LocalPaperColors.current.text),
        contentDescription = placeholder, cursorBrush = SolidColor(LocalPaperColors.current.action), maxLines = maxLines,
        interactionSource = source, enabled = enabled, visualTransformation = visualTransformation,
        decorationBox = { inner -> Box {
            if (value.text.isEmpty()) PaperText(placeholder, style = style, color = LocalPaperColors.current.secondaryText)
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

/**
 * Breathing lane between the title bar hairline and the surfaces pinned below it
 * (pinned requests, status headers): chrome and transcript never touch each other.
 */
public val PaperTitleBarLaneGap: Dp = 6.dp

/** A shadow starting at [topOffset] (the hairline below an overlaid title bar band), extending below any overlaid cards. */
@Composable
public fun Modifier.paperChatTopShadow(visible: Boolean, effectHeight: Dp = 32.dp, topOffset: Dp = 0.dp): Modifier {
    val color = LocalPaperColors.current.depthShadow
    if (!visible) return this
    return drawWithCache {
        val start = topOffset.toPx().coerceIn(0f, size.height)
        val extent = effectHeight.toPx().coerceAtLeast(1f)
        val shadowHeight = extent.coerceAtMost((size.height - start).coerceAtLeast(0f))
        val brush = Brush.verticalGradient(
            0f to color.copy(alpha = .24f),
            .25f to color.copy(alpha = .13f),
            .5f to color.copy(alpha = .05f),
            .75f to color.copy(alpha = .01f),
            1f to Color.Transparent,
            startY = start,
            endY = start + extent,
        )
        onDrawWithContent {
            drawContent()
            // The gradient is fully transparent after its extent. Restricting the draw
            // to that strip avoids blending the whole transcript on every scroll frame.
            if (shadowHeight > 0f) {
                drawRect(
                    brush,
                    topLeft = Offset(0f, start),
                    size = androidx.compose.ui.geometry.Size(size.width, shadowHeight),
                )
            }
        }
    }
}

/**
 * Frosted band of the window title bar: content that scrolls behind the bar
 * (message transcripts) is replaced inside the band by its blur plus a surface
 * scrim, so the chrome buttons stay legible, and a hairline at the band bottom
 * cuts the chrome from the sharp transcript below it. Nothing is half-blurred
 * under the bar: the blur lives strictly inside the band, and pinned surfaces
 * keep their own lane below the hairline (see [PaperTitleBarLaneGap]).
 * Inset pages set [blurContent] to false to retain the scrim and divider without
 * capturing a page that never passes behind this band.
 */
@Composable
public fun Modifier.paperTitleBarFrost(height: Dp, blurContent: Boolean = true): Modifier {
    // Inset pages have no content behind the title bar. Do not replay/blur their
    // entire surface on every scroll frame just to shade an empty strip.
    val blurred = if (blurContent) rememberGraphicsLayer() else null
    val scrim = LocalPaperColors.current.surface
    val hairline = LocalPaperColors.current.border
    return drawWithContent {
        drawContent()
        val strip = height.toPx().coerceIn(0f, size.height)
        if (strip <= 0f) return@drawWithContent
        val line = 1f.coerceAtMost(strip)
        if (blurred != null) {
            blurred.record { this@drawWithContent.drawContent() }
            blurred.renderEffect = BlurEffect(10.dp.toPx(), 10.dp.toPx(), TileMode.Clamp)
        }
        clipRect(top = 0f, bottom = strip) {
            if (blurred != null) drawLayer(blurred)
            drawRect(
                Brush.verticalGradient(
                    0f to scrim.copy(alpha = .62f),
                    1f to scrim.copy(alpha = .5f),
                    startY = 0f, endY = strip),
            )
        }
        drawRect(
            hairline.copy(alpha = .55f),
            topLeft = Offset(0f, strip - line),
            size = androidx.compose.ui.geometry.Size(size.width, line),
        )
    }
}
