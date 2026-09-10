@file:Suppress("LongParameterList")

package io.aequicor.magicpaper.designsystem

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownTypography

/** Semantic transcript shell. It deliberately owns only visual treatment; features retain list state and anchors. */
@Composable
public fun PaperChatTranscript(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    PaperSurface(modifier = modifier, kind = PaperSurfaceKind.CANVAS, content = content)
}

/** Composer frame. Keyboard dispatch remains in the feature so IME and domain send rules stay intact. */
@Composable
public fun PaperComposer(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = modifier.background(LocalPaperColors.current.surface, RoundedCornerShape(14.dp)).padding(8.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        content = content,
    )
}

@Composable
public fun PaperMarkdown(modifier: Modifier = Modifier, content: @Composable () -> Unit) = Box(modifier, content = { content() })

/** Markdown renderer boundary: features supply content; the M3 renderer stays private to the DS. */
@Composable
public fun PaperMarkdown(text: String, modifier: Modifier = Modifier, compact: Boolean = false) {
    val body = if (compact) PaperTextRole.LABEL else PaperTextRole.BODY
    Markdown(
        content = text,
        modifier = modifier,
        typography = markdownTypography(
            h1 = paperTextStyle(if (compact) body else PaperTextRole.TITLE),
            h2 = paperTextStyle(if (compact) body else PaperTextRole.TITLE),
            h3 = paperTextStyle(if (compact) body else PaperTextRole.TITLE),
            text = paperTextStyle(body),
                paragraph = paperTextStyle(body), ordered = paperTextStyle(body), bullet = paperTextStyle(body), list = paperTextStyle(body), table = paperTextStyle(body),
            code = paperTextStyle(PaperTextRole.CODE),
            inlineCode = paperTextStyle(PaperTextRole.CODE),
        ),
    )
}

@Composable
public fun PaperCodeBlock(modifier: Modifier = Modifier, content: @Composable () -> Unit) =
    PaperPanel(modifier, PaperSurfaceKind.RAISED, content)

@Composable
public fun PaperReader(modifier: Modifier = Modifier, content: @Composable () -> Unit) =
    PaperScrollArea(modifier, content)

@Composable
public fun PaperStatus(
    label: String,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
    content: (@Composable RowScope.() -> Unit)? = null,
) {
    Row(
        modifier = modifier.semantics { contentDescription = label },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        PaperText(label, role = PaperTextRole.LABEL, color = if (isError) LocalPaperColors.current.error else LocalPaperColors.current.secondaryText)
        content?.invoke(this)
    }
}

@Composable
public fun PaperListRow(
    label: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    secondary: String? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val colors = LocalPaperColors.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(if (selected) colors.selected else colors.surface, RoundedCornerShape(6.dp))
            .then(if (onClick == null) Modifier else Modifier.paperClickable(enabled = enabled, onClick = onClick))
            .semantics { role = Role.Button; contentDescription = label }
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            PaperText(label, role = PaperTextRole.BODY)
            secondary?.let { PaperText(it, role = PaperTextRole.LABEL, color = colors.secondaryText) }
        }
        trailing?.invoke(this)
    }
}

@Composable
public fun PaperTreeRow(
    label: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    expanded: Boolean? = null,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    content: (@Composable RowScope.() -> Unit)? = null,
) {
    PaperListRow(label, modifier, selected, enabled, onClick, trailing = content)
}

@Composable
public fun PaperTab(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) =
    PaperChoice(selected = selected, onSelect = onClick, label = label, modifier = modifier)

@Composable
public fun PaperMenuHost(expanded: Boolean, onDismissRequest: () -> Unit, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismissRequest, modifier = modifier, content = content)
}

@Composable
public fun PaperMenuAction(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, destructive: Boolean = false) {
    PaperRichMenuAction(text = { PaperText(label, role = PaperTextRole.BODY, color = if (destructive) LocalPaperColors.current.error else LocalPaperColors.current.text) }, onClick = onClick, enabled = enabled, modifier = modifier)
}

/** Slot-based menu item for feature menus with icons and multi-line labels. */
@Composable
public fun PaperRichMenuAction(
    text: @Composable () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
) {
    Row(modifier.fillMaxWidth().paperClickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        leadingIcon?.invoke()
        Box(Modifier.weight(1f)) { text() }
        trailingIcon?.invoke()
    }
}

@Composable
public fun PaperTextAction(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentPadding: androidx.compose.foundation.layout.PaddingValues = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 4.dp),
    content: @Composable RowScope.() -> Unit,
) = PaperAction(onClick = onClick, modifier = modifier, enabled = enabled, contentPadding = contentPadding, content = content)

@Composable
public fun PaperVerticalDivider(modifier: Modifier = Modifier, color: androidx.compose.ui.graphics.Color = LocalPaperColors.current.border, thickness: Dp = 1.dp) =
    VerticalDivider(modifier = modifier, color = color, thickness = thickness)

/** A slot-based tooltip keeps renderer ownership in DS without exposing Material tooltip types. */
@Composable
public fun PaperTooltipHost(
    tooltip: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) = androidx.compose.material3.TooltipBox(
    positionProvider = androidx.compose.material3.TooltipDefaults.rememberPlainTooltipPositionProvider(),
    tooltip = { PaperTooltipSurface(tooltip) },
    state = androidx.compose.material3.rememberTooltipState(), modifier = modifier, content = content,
)

/** Slot-based modal used by feature flows that need domain-specific validation or actions. */
@Composable
public fun PaperModal(
    onDismissRequest: () -> Unit,
    title: @Composable (() -> Unit)? = null,
    text: @Composable (() -> Unit)? = null,
    confirmButton: @Composable () -> Unit,
    dismissButton: @Composable (() -> Unit)? = null,
) = AlertDialog(
    onDismissRequest = onDismissRequest,
    title = title,
    text = text,
    confirmButton = confirmButton,
    dismissButton = dismissButton,
)

/** Wide desktop dialog shell; features retain their domain content and state. */
@Composable
public fun PaperWideDialog(onDismissRequest: () -> Unit, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        PaperPanel(modifier, PaperSurfaceKind.PANEL) { Column(Modifier.padding(20.dp), content = content) }
    }
}

@Composable
public fun PaperAttachmentChip(label: String, onRemove: (() -> Unit)?, modifier: Modifier = Modifier, content: (@Composable () -> Unit)? = null) {
    Row(
        modifier = modifier.background(LocalPaperColors.current.raisedSurface, RoundedCornerShape(8.dp)).padding(6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        content?.invoke()
        PaperText(label, role = PaperTextRole.LABEL, modifier = Modifier.weight(1f))
        onRemove?.let { PaperIconButton("Удалить $label", it) { PaperText("×", role = PaperTextRole.LABEL) } }
    }
}

@Composable
public fun PaperAttachmentRow(modifier: Modifier = Modifier, content: @Composable () -> Unit) = Box(modifier, content = { content() })

/** Visual state supplied by a feature-owned thumbnail loader. */
public enum class PaperAttachmentThumbnailState { LOADING, READY, ERROR }

/**
 * Compact attachment preview used by composers. Loading and byte ownership stay
 * in the feature; this Paper API owns the common image, loading and error states.
 */
@Composable
public fun PaperAttachmentThumbnail(
    label: String,
    description: String,
    state: PaperAttachmentThumbnailState,
    bitmap: ImageBitmap? = null,
    onRemove: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val previewStatus = when (state) {
        PaperAttachmentThumbnailState.LOADING -> "Загрузка миниатюры"
        PaperAttachmentThumbnailState.READY -> "Миниатюра готова"
        PaperAttachmentThumbnailState.ERROR -> "Миниатюра недоступна"
    }
    PaperAttachmentChip(
        label = label,
        onRemove = onRemove,
        modifier = modifier.semantics { contentDescription = "$description: $previewStatus" },
    ) {
        when (state) {
            PaperAttachmentThumbnailState.READY -> bitmap?.let {
                PaperImage(it, description, Modifier.size(34.dp).clip(RoundedCornerShape(7.dp)))
            } ?: PaperText("!", role = PaperTextRole.LABEL)
            PaperAttachmentThumbnailState.LOADING -> PaperText("…", role = PaperTextRole.BODY)
            PaperAttachmentThumbnailState.ERROR -> PaperText("!", role = PaperTextRole.LABEL)
        }
    }
}

@Composable
public fun PaperImage(bitmap: ImageBitmap, description: String?, modifier: Modifier = Modifier, size: Dp? = null) {
    Image(bitmap = bitmap, contentDescription = description, modifier = if (size == null) modifier else modifier.size(size), contentScale = ContentScale.Crop)
}

@Composable
public fun PaperApprovalDock(modifier: Modifier = Modifier, content: @Composable () -> Unit) =
    PaperPanel(modifier, PaperSurfaceKind.RAISED, content)

@Composable
public fun PaperQuestionnaire(modifier: Modifier = Modifier, content: @Composable () -> Unit) =
    PaperWorkspaceComposer(modifier) { content() }

/** Reusable host for interactive canvas/tree views; feature modules own graph data and gestures. */
@Composable
public fun PaperGraph(modifier: Modifier = Modifier, toolbar: @Composable (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) =
    PaperPanel(modifier, PaperSurfaceKind.RAISED) { Column { toolbar?.invoke(); content() } }

/** Interactive graph node with shared selection, inactive and keyboard behavior. */
@Composable
public fun PaperGraphNode(
    selected: Boolean,
    related: Boolean,
    inactive: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = LocalPaperColors.current
    PaperAction(onClick, modifier, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
        PaperSurface(
            Modifier.fillMaxSize().graphicsLayer { alpha = if (inactive) .72f else 1f },
            kind = if (selected || related) PaperSurfaceKind.SELECTED else PaperSurfaceKind.PANEL,
        ) {
            Column(Modifier.padding(8.dp), content = content)
        }
    }
}

/** Start and finish terminals are styled by the graph renderer, not feature code. */
@Composable
public fun PaperGraphTerminal(label: String, terminal: Boolean, modifier: Modifier = Modifier) {
    val colors = LocalPaperColors.current
    Box(
        modifier.size(96.dp).background(colors.surface, CircleShape)
            .border(if (terminal) 1.dp else 2.dp, colors.action, CircleShape),
        contentAlignment = Alignment.Center,
    ) { PaperText(label, role = PaperTextRole.LABEL, textAlign = androidx.compose.ui.text.style.TextAlign.Center) }
}

/** Reusable disclosure/status surface for orchestration and other background work. */
@Composable
public fun PaperStatusPanel(
    modifier: Modifier = Modifier,
    scrolled: Boolean = false,
    content: @Composable () -> Unit,
) {
    val shadowElevation by animateDpAsState(if (scrolled) 6.dp else 0.dp)
    PaperPanel(modifier, PaperSurfaceKind.RAISED, shadowElevation = shadowElevation, content = content)
}

/** Reusable wizard container. Domain validation and transitions stay in the caller. */
@Composable
public fun PaperWizard(modifier: Modifier = Modifier, content: @Composable () -> Unit) =
    PaperPanel(modifier, PaperSurfaceKind.PANEL, content)

@Composable
public fun PaperScheduleEditor(modifier: Modifier = Modifier, content: @Composable () -> Unit) =
    PaperPanel(modifier, PaperSurfaceKind.PANEL, content)

@Composable
public fun PaperLink(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) =
    PaperAction(onClick, modifier) { PaperText(label, role = PaperTextRole.BODY, color = LocalPaperColors.current.action) }

@Composable
public fun PaperIndeterminateProgress(modifier: Modifier = Modifier, label: String? = null) {
    LinearProgressIndicator(modifier.fillMaxWidth().semantics { if (label != null) contentDescription = label })
}

/** Composer editing shares compact field geometry and interaction states. */
@Composable
public fun PaperComposerField(
    value: String,
    onValueChange: (String) -> Unit,
    label: @Composable (() -> Unit)? = null,
    placeholder: @Composable (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    singleLine: Boolean = false,
    minLines: Int = 1,
    maxLines: Int = Int.MAX_VALUE,
    textStyle: TextStyle = LocalPaperTypography.current.body,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailingIcon: @Composable (() -> Unit)? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
) {
    PaperInput(
        value = value, onValueChange = onValueChange, modifier = modifier,
        label = label, placeholder = placeholder, enabled = enabled, singleLine = singleLine, minLines = minLines, maxLines = maxLines,
        textStyle = textStyle, visualTransformation = visualTransformation, trailingIcon = trailingIcon,
        keyboardOptions = keyboardOptions, keyboardActions = keyboardActions,
    )
}
