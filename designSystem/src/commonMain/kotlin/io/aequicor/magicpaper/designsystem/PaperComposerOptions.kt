package io.aequicor.magicpaper.designsystem

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.aequicor.magicpaper.designsystem.resources.Res
import io.aequicor.magicpaper.designsystem.resources.top_panel_close
import io.aequicor.magicpaper.designsystem.resources.top_panel_open
import org.jetbrains.compose.resources.painterResource

/** Inline disclosure: the enclosing control owns its label and expanded state. */
@Composable
public fun PaperComposerOptionsToggle(expanded: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val label = if (expanded) "Скрыть параметры и ресурсы" else "Показать параметры и ресурсы"
    PaperTooltip(label) {
        PaperIconButton(label, onClick, modifier.semantics {
            stateDescription = if (expanded) "Развёрнуто" else "Свёрнуто"
        }) {
            Icon(painterResource(if (expanded) Res.drawable.top_panel_close else Res.drawable.top_panel_open),
                contentDescription = null, modifier = Modifier.size(16.dp),
                tint = if (expanded) LocalPaperColors.current.action else LocalPaperColors.current.secondaryText)
        }
    }
}

/** Reveals from the editor's lower edge. Finite transitions respect MotionDurationScale,
 * reverse immediately on another click, and leave no layout space or focus targets when closed. */
@Composable
public fun PaperComposerOptionsPanel(
    expanded: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    maxHeight: Dp = 240.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    AnimatedVisibility(expanded, modifier.fillMaxWidth().clipToBounds(),
        enter = expandVertically(tween(220), expandFrom = Alignment.Top) +
            slideInVertically(tween(220)) { -it / 4 } + fadeIn(tween(140)),
        exit = shrinkVertically(tween(180), shrinkTowards = Alignment.Top) +
            slideOutVertically(tween(180)) { -it / 4 } + fadeOut(tween(100))) {
        Column(Modifier.fillMaxWidth().onKeyEvent { event ->
            if (event.key == Key.Escape) {
                if (event.type == KeyEventType.KeyDown) onDismiss()
                true
            } else false
        }.padding(top = 8.dp)) {
            PaperDivider()
            PaperScrollColumn(Modifier.fillMaxWidth().heightIn(max = maxHeight),
                contentPadding = PaddingValues(vertical = 4.dp), content = content)
        }
    }
}

@Preview(name = "Options collapsed", group = "Composer options", widthDp = 640, heightDp = 320)
@Composable
internal fun PaperComposerOptionsClosedPreview() = PaperComposerOptionsPreview(false)

@Preview(name = "Options open", group = "Composer options", widthDp = 640, heightDp = 360)
@Preview(name = "Options narrow", group = "Composer options", widthDp = 360, heightDp = 400)
@Preview(name = "Options large text", group = "Composer options", widthDp = 480, heightDp = 480, fontScale = 2f)
@Composable
internal fun PaperComposerOptionsPreview(initiallyExpanded: Boolean = true) = PaperTheme {
    var expanded by remember { mutableStateOf(initiallyExpanded) }
    var text by remember { mutableStateOf("Помоги разобраться в вопросе") }
    val toggleFocus = remember { FocusRequester() }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        PaperWorkspaceComposer(Modifier.testTag("composer-frame"), document = true, options = {
            PaperComposerOptionsPanel(expanded, { expanded = false; toggleFocus.requestFocus() },
                Modifier.testTag("composer-options-panel")) {
                PaperRichMenuAction(text = { PaperText("Прикрепить файлы", role = PaperTextRole.CHROME) },
                    leadingIcon = { PaperNoteAddIcon(tint = LocalPaperColors.current.action) }, onClick = {})
                PaperRichMenuAction(text = { PaperText("Режим исследования", role = PaperTextRole.CHROME) },
                    trailingIcon = { PaperText("✓") }, onClick = {})
                PaperRichMenuAction(text = { PaperText("Режим планирования", role = PaperTextRole.CHROME) },
                    enabled = false, onClick = {})
            }
        }) {
            PaperPromptField(text, { text = it }, "Ваш вопрос…", maxLines = 3)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                PaperComposerOptionsToggle(expanded, { expanded = !expanded }, Modifier.focusRequester(toggleFocus))
                Spacer(Modifier.weight(1f))
                PaperButton("Отправить", {})
            }
        }
    }
}
