package io.aequicor.magicpaper.designsystem

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.*
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

/** Answer footnotes: the count stays visible; hover/focus reveals the disclosure.
 * The whole header is one keyboard-accessible action, without shifting its content. */
@Composable
public fun PaperResearchSourcesDisclosure(
    count: Int,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalPaperColors.current
    val spacing = LocalPaperSpacing.current
    val policy = LocalPaperPlatformPolicy.current
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val focused by source.collectIsFocusedAsState()
    val showAction = hovered || (focused && LocalInputModeManager.current.inputMode == InputMode.Keyboard) ||
        policy.platform == PaperPlatform.ANDROID
    Row(modifier.fillMaxWidth().heightIn(min = policy.density.controlHeight)
        .semantics {
            contentDescription = if (expanded) "Свернуть источники ответа" else "Развернуть источники ответа"
            stateDescription = if (expanded) "Развёрнуто, источников: $count" else "Свёрнуто, источников: $count"
        }.paperClickable(role = Role.Button, interactionSource = source,
            onClick = { onExpandedChange(!expanded) }).padding(vertical = spacing.xxs),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
        PaperText("Источники ответа", Modifier.weight(1f), role = PaperTextRole.CHROME)
        PaperResearchCountBadge(count.toString(), selected = false)
        Canvas(Modifier.size(policy.density.controlHeight)) {
            if (showAction) {
                drawCircle(colors.hover, radius = 12.dp.toPx())
                val radius = 4.dp.toPx()
                val direction = if (expanded) -1 else 1
                val middle = center + Offset(0f, direction * radius / 2)
                drawLine(colors.action, center + Offset(-radius, -direction * radius / 2), middle,
                    1.5.dp.toPx(), StrokeCap.Round)
                drawLine(colors.action, middle, center + Offset(radius, -direction * radius / 2),
                    1.5.dp.toPx(), StrokeCap.Round)
            }
        }
    }
}

@Preview(name = "Answer sources collapsed", group = "Research sources", widthDp = 360, heightDp = 280)
@Preview(name = "Answer sources narrow large text", group = "Research sources", widthDp = 280, heightDp = 360, fontScale = 2f)
@Composable
internal fun PaperResearchSourcesDisclosurePreview(modifier: Modifier = Modifier) = PaperTheme {
    var expanded by remember { mutableStateOf(false) }
    PaperSurface(Modifier.fillMaxSize()) {
        Column(Modifier.padding(16.dp)) {
            PaperDivider()
            PaperResearchSourcesDisclosure(14, expanded, { expanded = it }, modifier)
            if (expanded) {
                PaperResearchSourceLink("Figma: Prompt to App", {}, number = 1)
                PaperResearchSourceLink("Introducing Figma Make", {}, number = 2)
            }
        }
    }
}
