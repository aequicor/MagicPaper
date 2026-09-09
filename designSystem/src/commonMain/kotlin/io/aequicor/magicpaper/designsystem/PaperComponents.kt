@file:Suppress("LongParameterList")

package io.aequicor.magicpaper.designsystem

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp

@Composable
public fun PaperText(
    text: String,
    modifier: Modifier = Modifier,
    role: PaperTextRole = PaperTextRole.BODY,
    color: Color = LocalPaperColors.current.text,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    textAlign: TextAlign? = null,
    style: TextStyle? = null,
    fontWeight: FontWeight? = null,
    onTextLayout: (TextLayoutResult) -> Unit = {},
) {
    Text(
        text = text,
        modifier = modifier,
        color = color,
        style = style ?: paperTextStyle(role),
        fontWeight = fontWeight,
        maxLines = maxLines,
        overflow = overflow,
        textAlign = textAlign,
        onTextLayout = onTextLayout,
    )
}

@Composable
public fun PaperText(
    text: AnnotatedString,
    modifier: Modifier = Modifier,
    role: PaperTextRole = PaperTextRole.BODY,
    color: Color = LocalPaperColors.current.text,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    textAlign: TextAlign? = null,
    style: TextStyle? = null,
    fontWeight: FontWeight? = null,
) = Text(text, modifier, color, style = style ?: paperTextStyle(role), fontWeight = fontWeight, maxLines = maxLines, overflow = overflow, textAlign = textAlign)

/**
 * Public action host for inline, toolbar, and secondary actions.  Feature code
 * supplies semantics and content, while state, density and renderer ownership
 * remain in the design system.
 */
@Composable
public fun PaperAction(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
    content: @Composable RowScope.() -> Unit,
) {
    val policy = LocalPaperPlatformPolicy.current
    Surface(
        modifier = modifier.heightIn(min = policy.density.controlHeight)
            .semantics { role = Role.Button }
            .onPreviewKeyEvent { event ->
                val activates = event.key == Key.Enter && event.type == KeyEventType.KeyDown ||
                    event.key == Key.Spacebar && event.type == KeyEventType.KeyUp
                if (enabled && activates) { onClick(); true } else false
            }
            .clickable(enabled = enabled, onClick = onClick),
        color = Color.Transparent,
        shape = RoundedCornerShape(6.dp),
    ) {
        Row(Modifier.padding(contentPadding), verticalAlignment = Alignment.CenterVertically, content = content)
    }
}

@Composable
public fun PaperPanel(
    modifier: Modifier = Modifier,
    kind: PaperSurfaceKind = PaperSurfaceKind.PANEL,
    content: @Composable () -> Unit,
) = PaperSurface(modifier = modifier, kind = kind, content = content)

@Composable
public fun PaperPanel(
    modifier: Modifier = Modifier,
    kind: PaperSurfaceKind = PaperSurfaceKind.PANEL,
    color: Color? = null,
    shape: Shape? = null,
    shadowElevation: androidx.compose.ui.unit.Dp = 0.dp,
    content: @Composable () -> Unit,
) = PaperSurface(modifier, kind, color, shape, shadowElevation, content)

/** Material-compatible field affordance kept private to the DS renderer. */
@Composable
public fun PaperInput(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: @Composable (() -> Unit)? = null,
    enabled: Boolean = true,
    isError: Boolean = false,
    singleLine: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailingIcon: @Composable (() -> Unit)? = null,
) {
    val policy = LocalPaperPlatformPolicy.current
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.heightIn(min = policy.density.fieldHeight),
        enabled = enabled,
        isError = isError,
        label = label,
        singleLine = singleLine,
        visualTransformation = visualTransformation,
        trailingIcon = trailingIcon,
    )
}

@Composable
public fun PaperToggle(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) = Switch(checked = checked, onCheckedChange = onCheckedChange, modifier = modifier, enabled = enabled)

@Composable
public fun PaperCheck(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) = Checkbox(checked = checked, onCheckedChange = onCheckedChange, modifier = modifier, enabled = enabled)

@Composable
public fun PaperSurface(
    modifier: Modifier = Modifier,
    kind: PaperSurfaceKind = PaperSurfaceKind.PANEL,
    color: Color? = null,
    shape: Shape? = null,
    shadowElevation: androidx.compose.ui.unit.Dp = 0.dp,
    content: @Composable () -> Unit,
) {
    val colors = LocalPaperColors.current
    Surface(
        modifier = modifier,
        shape = shape ?: RoundedCornerShape(if (kind == PaperSurfaceKind.CANVAS) 0.dp else 10.dp),
        color = color ?: when (kind) {
            PaperSurfaceKind.CANVAS -> colors.canvas
            PaperSurfaceKind.PANEL -> colors.surface
            PaperSurfaceKind.RAISED -> colors.raisedSurface
            PaperSurfaceKind.SELECTED -> colors.selected
            PaperSurfaceKind.ERROR -> colors.errorSurface
        },
        shadowElevation = shadowElevation,
        content = content,
    )
}

@Composable
public fun PaperButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: PaperButtonKind = PaperButtonKind.PRIMARY,
    enabled: Boolean = true,
    busy: Boolean = false,
    state: PaperControlState = if (enabled) PaperControlState.NORMAL else PaperControlState.DISABLED,
    accessibilityLabel: String = label,
    focusRequester: FocusRequester? = null,
) {
    val colors = LocalPaperColors.current
    val policy = LocalPaperPlatformPolicy.current
    val active = enabled && !busy
    val (container, foreground) = when (kind) {
        PaperButtonKind.PRIMARY -> colors.action to colors.actionOn
        PaperButtonKind.DESTRUCTIVE -> colors.error to colors.actionOn
        PaperButtonKind.SECONDARY -> colors.selected to colors.text
        PaperButtonKind.QUIET -> Color.Transparent to colors.action
    }
    val buttonModifier = modifier
            .heightIn(min = policy.density.controlHeight)
            .semantics { contentDescription = accessibilityLabel; role = Role.Button }
            .onPreviewKeyEvent { event ->
                val activates = event.key == Key.Enter && event.type == KeyEventType.KeyDown ||
                    event.key == Key.Spacebar && event.type == KeyEventType.KeyUp
                if (active && activates) {
                    onClick()
                    true
                } else {
                    false
                }
            }
    Surface(
        modifier = (if (focusRequester == null) buttonModifier else buttonModifier.focusRequester(focusRequester))
            .clickable(enabled = active, onClick = onClick),
        shape = RoundedCornerShape(6.dp), color = if (active) container else colors.disabled,
        border = if (kind == PaperButtonKind.QUIET) BorderStroke(1.dp, colors.border) else null,
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            if (busy) CircularProgressIndicator(Modifier.sizeIn(maxWidth = 16.dp, maxHeight = 16.dp), color = foreground, strokeWidth = 2.dp)
            else PaperText(label, role = PaperTextRole.LABEL, color = foreground, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
public fun PaperIconButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    selected: Boolean = false,
    content: @Composable () -> Unit,
) {
    val policy = LocalPaperPlatformPolicy.current
    val colors = LocalPaperColors.current
    Surface(
        modifier = modifier.sizeIn(minWidth = policy.density.controlHeight, minHeight = policy.density.controlHeight)
            .semantics { contentDescription = label; role = Role.Button }
            .onPreviewKeyEvent { event ->
                val activates = event.key == Key.Enter && event.type == KeyEventType.KeyDown ||
                    event.key == Key.Spacebar && event.type == KeyEventType.KeyUp
                if (enabled && activates) { onClick(); true } else false
            }
            .clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(6.dp), color = if (selected) colors.selected else Color.Transparent,
    ) { Box(contentAlignment = Alignment.Center) { content() } }
}

/**
 * Makes an opener focusable and registers it for [PaperDialog] dismissal.
 * The caller may install several anchors; a removed opener falls back to the
 * newest remaining one instead of trying to focus a disposed node.
 */
@Composable
public fun PaperFocusAnchor(
    restorer: PaperFocusRestorer,
    key: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val requester = remember { FocusRequester() }
    DisposableEffect(restorer, key) {
        restorer.register(key) { requester.requestFocus() }
        onDispose { restorer.unregister(key) }
    }
    Box(modifier.focusRequester(requester).focusable(), content = { content() })
}

@Composable
public fun PaperField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    errorMessage: String? = null,
    singleLine: Boolean = true,
    visualTransformation: VisualTransformation = VisualTransformation.None,
) {
    val policy = LocalPaperPlatformPolicy.current
    OutlinedTextField(value, onValueChange, modifier.heightIn(min = policy.density.fieldHeight).semantics {
        if (errorMessage != null) error(errorMessage)
    }, enabled = enabled, isError = errorMessage != null, label = { PaperText(label, role = PaperTextRole.LABEL) }, singleLine = singleLine, visualTransformation = visualTransformation)
}

@Composable
public fun PaperSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Row(modifier.semantics { contentDescription = label }, verticalAlignment = Alignment.CenterVertically) {
        Switch(checked, onCheckedChange, enabled = enabled)
        PaperText(label, Modifier.padding(start = 8.dp))
    }
}

@Composable
public fun PaperChoice(
    selected: Boolean,
    onSelect: () -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    description: String? = null,
) {
    val colors = LocalPaperColors.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    val focused by interaction.collectIsFocusedAsState()
    val alpha = when {
        !enabled -> 0f
        pressed -> .14f
        focused -> .10f
        hovered -> .08f
        else -> 0f
    }
    val overlay = colors.text.copy(alpha = alpha)
    CompositionLocalProvider(LocalRippleConfiguration provides null) {
        FilterChip(
            selected = selected,
            onClick = onSelect,
            modifier = modifier.heightIn(min = LocalPaperPlatformPolicy.current.density.rowHeight).semantics {
                role = Role.RadioButton
                this.selected = selected
                contentDescription = label
            },
            enabled = enabled,
            interactionSource = interaction,
            label = {
                Column {
                    PaperText(label, role = PaperTextRole.LABEL)
                    description?.let { PaperText(it, role = PaperTextRole.BODY, color = colors.secondaryText) }
                }
            },
            colors = FilterChipDefaults.filterChipColors(
                containerColor = if (alpha == 0f) Color.Transparent else overlay,
                selectedContainerColor = overlay.compositeOver(colors.selected),
            ),
        )
    }
}

public data class PaperMenuItem(val label: String, val enabled: Boolean = true, val destructive: Boolean = false, val onClick: () -> Unit)

@Composable
public fun PaperMenu(expanded: Boolean, onDismissRequest: () -> Unit, items: List<PaperMenuItem>, modifier: Modifier = Modifier) {
    DropdownMenu(expanded, onDismissRequest, modifier) {
        items.forEach { item -> DropdownMenuItem(text = { PaperText(item.label, role = PaperTextRole.LABEL, color = if (item.destructive) LocalPaperColors.current.error else LocalPaperColors.current.text) }, onClick = item.onClick, enabled = item.enabled) }
    }
}

@Composable
public fun PaperDialog(
    title: String,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    confirmLabel: String? = null,
    onConfirm: (() -> Unit)? = null,
    dismissLabel: String = "Закрыть",
    focusRestorer: PaperFocusRestorer? = null,
    content: @Composable () -> Unit,
) {
    val dismiss = {
        onDismissRequest()
        focusRestorer?.restore()
        Unit
    }
    AlertDialog(dismiss, modifier = modifier, title = { PaperText(title, role = PaperTextRole.TITLE) }, text = { Column { content() } },
        confirmButton = { if (confirmLabel != null && onConfirm != null) PaperButton(confirmLabel, onConfirm) },
        dismissButton = { PaperButton(dismissLabel, dismiss, kind = PaperButtonKind.QUIET) })
}

@Composable
public fun PaperTooltip(text: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    TooltipBox(positionProvider = androidx.compose.material3.TooltipDefaults.rememberPlainTooltipPositionProvider(), tooltip = { PlainTooltip { PaperText(text, role = PaperTextRole.LABEL) } }, state = rememberTooltipState(), modifier = modifier, content = content)
}

@Composable
public fun PaperProgress(modifier: Modifier = Modifier, progress: Float? = null, kind: PaperProgressKind = PaperProgressKind.LINEAR, label: String? = null) {
    val value = progress?.coerceIn(0f, 1f)
    Row(modifier.semantics { if (label != null) contentDescription = label }, verticalAlignment = Alignment.CenterVertically) {
        when (kind) {
            PaperProgressKind.LINEAR -> if (value == null) LinearProgressIndicator(Modifier.fillMaxWidth()) else LinearProgressIndicator({ value }, Modifier.fillMaxWidth())
            PaperProgressKind.CIRCULAR -> if (value == null) CircularProgressIndicator() else CircularProgressIndicator({ value })
        }
    }
}

@Composable public fun PaperDivider(modifier: Modifier = Modifier, color: Color = LocalPaperColors.current.border) { HorizontalDivider(modifier, color = color) }

@Composable
public fun PaperList(modifier: Modifier = Modifier, contentPadding: PaddingValues = PaddingValues(0.dp), content: @Composable () -> Unit) {
    Column(modifier.verticalScroll(rememberScrollState()).padding(contentPadding)) { content() }
}

/** A scroll container that stays usable on every target; desktop hosts may add scrollbars outside it. */
@Composable
public fun PaperScrollArea(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier.verticalScroll(rememberScrollState()), content = { content() })
}
