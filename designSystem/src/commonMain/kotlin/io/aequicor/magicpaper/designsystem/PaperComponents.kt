@file:Suppress("LongParameterList")

package io.aequicor.magicpaper.designsystem

import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
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
import androidx.compose.ui.semantics.toggleableState
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
    softWrap: Boolean = true,
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
        softWrap = softWrap,
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
            .paperClickable(enabled = enabled, onClick = onClick),
        color = Color.Transparent,
        shape = RoundedCornerShape(6.dp),
    ) {
        Row(Modifier.padding(contentPadding), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically, content = content)
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

/** Compact field; the label stays visible while typing and scaling. */
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
    placeholder: @Composable (() -> Unit)? = null,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    textStyle: TextStyle = LocalPaperTypography.current.body,
) {
    val colors = LocalPaperColors.current
    val source = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(6.dp)
    BasicTextField(value, onValueChange, modifier = modifier.fillMaxWidth(),
        enabled = enabled, singleLine = singleLine, minLines = minLines, maxLines = maxLines,
        textStyle = textStyle.copy(color = colors.text),
        visualTransformation = visualTransformation, interactionSource = source,
        keyboardOptions = keyboardOptions, keyboardActions = keyboardActions, cursorBrush = SolidColor(colors.action),
        decorationBox = { inner ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                label?.invoke()
                Row(Modifier.fillMaxWidth().heightIn(min = LocalPaperPlatformPolicy.current.density.fieldHeight)
                    .paperFeedback(source, shape, enabled, showPress = false)
                    .background(if (enabled) colors.surface else colors.raisedSurface, shape)
                    .border(1.dp, if (isError) colors.error else colors.border, shape)
                    .hoverable(source, enabled)
                    .padding(horizontal = 8.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { if (value.isEmpty()) placeholder?.invoke(); inner() }
                    trailingIcon?.invoke()
                }
            }
        })
}

@Composable
public fun PaperToggle(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, modifier: Modifier = Modifier, enabled: Boolean = true) {
    PaperToggleMark(checked, onCheckedChange, modifier, enabled, true)
}

@Composable
public fun PaperCheck(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, modifier: Modifier = Modifier, enabled: Boolean = true) {
    PaperToggleMark(checked, onCheckedChange, modifier, enabled, false)
}

@Composable
private fun PaperToggleMark(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, modifier: Modifier, enabled: Boolean, switch: Boolean) {
    val colors = LocalPaperColors.current
    val source = remember { MutableInteractionSource() }
    val shape = if (switch) RoundedCornerShape(50) else RoundedCornerShape(4.dp)
    val height = LocalPaperPlatformPolicy.current.density.controlHeight
    Box(modifier.sizeIn(minWidth = height, minHeight = height)
        .paperFeedback(source, RoundedCornerShape(6.dp), enabled)
        .then(if (onCheckedChange == null) Modifier else Modifier.toggleable(checked, source, null, enabled, if (switch) Role.Switch else Role.Checkbox, onCheckedChange)),
        contentAlignment = Alignment.Center) {
        Box(Modifier.sizeIn(minWidth = if (switch) 32.dp else 18.dp, minHeight = 18.dp)
            .background(if (checked) colors.selected else colors.surface, shape)
            .border(1.dp, if (enabled) colors.action else colors.disabled, shape), contentAlignment = if (switch) { if (checked) Alignment.CenterEnd else Alignment.CenterStart } else Alignment.Center) {
            if (switch) Box(Modifier.padding(3.dp).sizeIn(minWidth = 12.dp, minHeight = 12.dp).background(if (enabled) colors.action else colors.disabled, RoundedCornerShape(50)))
            else if (checked) PaperText("✓", role = PaperTextRole.CHROME, color = colors.text)
        }
    }
}

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
    val loading = busy || state == PaperControlState.BUSY
    val active = enabled && !loading && state != PaperControlState.DISABLED
    val (container, foreground) = when (kind) {
        PaperButtonKind.PRIMARY -> colors.action to colors.actionOn
        PaperButtonKind.DESTRUCTIVE -> colors.error to colors.actionOn
        PaperButtonKind.SECONDARY -> colors.selected to colors.text
        PaperButtonKind.QUIET -> Color.Transparent to colors.action
    }
    val buttonModifier = modifier
            .heightIn(min = policy.density.controlHeight)
            .semantics { contentDescription = accessibilityLabel; role = Role.Button; if (loading) stateDescription = "Загрузка" }
    Surface(
        modifier = (if (focusRequester == null) buttonModifier else buttonModifier.focusRequester(focusRequester))
            .paperClickable(enabled = active, state = state, onClick = onClick),
        shape = RoundedCornerShape(6.dp), color = if (!active) colors.disabled else when (state) {
            PaperControlState.SELECTED -> colors.selected
            PaperControlState.ERROR -> colors.errorSurface
            else -> container
        },
        border = if (kind == PaperButtonKind.QUIET) BorderStroke(1.dp, colors.border) else null,
    ) {
        Box(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), contentAlignment = Alignment.Center) {
            if (loading) CircularProgressIndicator(Modifier.sizeIn(maxWidth = 16.dp, maxHeight = 16.dp), color = foreground, strokeWidth = 2.dp)
            else PaperText(label, role = PaperTextRole.CHROME, textAlign = androidx.compose.ui.text.style.TextAlign.Center, color = if (!active || state == PaperControlState.SELECTED || state == PaperControlState.ERROR) colors.text else foreground, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
            .semantics { contentDescription = label; role = Role.Button; this.selected = selected }
            .paperClickable(enabled = enabled, onClick = onClick),
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
    supportingText: String? = null,
    singleLine: Boolean = true,
    visualTransformation: VisualTransformation = VisualTransformation.None,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        PaperInput(value, onValueChange, Modifier.semantics { if (errorMessage != null) error(errorMessage) },
            label = { PaperText(label, role = PaperTextRole.LABEL) }, enabled = enabled,
            isError = errorMessage != null, singleLine = singleLine, visualTransformation = visualTransformation)
        supportingText?.let { PaperText(it, role = PaperTextRole.LABEL) }
        errorMessage?.let { PaperText(it, role = PaperTextRole.LABEL, color = LocalPaperColors.current.error) }
    }
}

@Composable
public fun PaperSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Row(modifier.semantics { contentDescription = label }.then(if (onCheckedChange == null) Modifier else Modifier.paperClickable(enabled = enabled, role = Role.Switch, onClick = { onCheckedChange(!checked) })).semantics { toggleableState = if (checked) androidx.compose.ui.state.ToggleableState.On else androidx.compose.ui.state.ToggleableState.Off }, verticalAlignment = Alignment.CenterVertically) {
        PaperToggle(checked, null, enabled = enabled)
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
    PaperChoice(selected, onSelect, modifier, enabled) {
        Column {
            PaperText(label, role = PaperTextRole.LABEL)
            description?.let { PaperText(it, color = LocalPaperColors.current.secondaryText) }
        }
    }
}

@Composable
public fun PaperChoice(selected: Boolean, onSelect: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable () -> Unit) {
    PaperSurface(modifier.heightIn(min = LocalPaperPlatformPolicy.current.density.rowHeight)
        .semantics { this.selected = selected }
        .paperClickable(enabled = enabled, role = Role.RadioButton, onClick = onSelect),
        color = if (selected) LocalPaperColors.current.selected else LocalPaperColors.current.surface,
        shape = RoundedCornerShape(6.dp)) {
        Box(Modifier.padding(horizontal = 10.dp, vertical = 5.dp), contentAlignment = Alignment.Center) { content() }
    }
}

public data class PaperMenuItem(val label: String, val enabled: Boolean = true, val destructive: Boolean = false, val onClick: () -> Unit)

@Composable
public fun PaperMenu(expanded: Boolean, onDismissRequest: () -> Unit, items: List<PaperMenuItem>, modifier: Modifier = Modifier) {
    DropdownMenu(expanded, onDismissRequest, modifier) {
        items.forEach { item -> PaperAction(modifier = Modifier.fillMaxWidth(), onClick = { onDismissRequest(); item.onClick() }, enabled = item.enabled) { PaperText(item.label, role = PaperTextRole.LABEL, color = if (item.destructive) LocalPaperColors.current.error else LocalPaperColors.current.text) } }
    }
}

@Composable
public fun PaperDialog(
    title: String,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    confirmLabel: String? = null,
    onConfirm: (() -> Unit)? = null,
    confirmEnabled: Boolean = true,
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
        confirmButton = { if (confirmLabel != null && onConfirm != null) PaperButton(confirmLabel, onConfirm, enabled = confirmEnabled) },
        dismissButton = { PaperButton(dismissLabel, dismiss, kind = PaperButtonKind.QUIET) })
}

@Composable
public fun PaperTooltip(text: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    PaperTooltipHost(tooltip = { PaperText(text, role = PaperTextRole.CHROME) }, modifier = modifier, content = content)
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
