package io.aequicor.magicpaper.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import io.aequicor.magicpaper.designsystem.*
import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.ui.components.*

/** Research keeps a single editor instance while expanding and across streamed response updates. */
@Composable
internal fun ResearchComposer(state: CodingComposerDraft, enabled: Boolean, busy: Boolean, paused: Boolean,
    profile: LlmProfile?, contextUsage: ContextUsageSnapshot?, contextCompacting: Boolean,
    placeholder: String, onOpenSwitcher: () -> Unit,
    engine: CodingEngine, onEngineChange: ((CodingEngine) -> Unit)?,
    onSend: (String, List<Attachment>) -> Unit, onPause: () -> Unit,
    onResume: (String, List<Attachment>) -> Unit, onClarify: (String, List<Attachment>) -> Unit,
    onPickAttachments: (Int, (List<Attachment>) -> Unit) -> Unit,
    onPasteAttachments: (Int, (List<Attachment>) -> Unit) -> Boolean) {
    var text by state.text
    var attachments by state.attachments
    var editor by remember(state) { mutableStateOf(TextFieldValue(text, TextRange(text.length))) }
    var expanded by remember(state) { mutableStateOf(false) }
    var menu by remember(state) { mutableStateOf(false) }
    val optionsFocus = remember { FocusRequester() }
    val focus = remember { FocusRequester() }
    LaunchedEffect(text) { if (editor.text != text) editor = TextFieldValue(text, TextRange(text.length)) }
    val hasInput = text.isNotBlank() || attachments.isNotEmpty()
    val action = when {
        busy && hasInput -> ResearchComposerAction.CLARIFY
        busy -> ResearchComposerAction.PAUSE
        paused -> ResearchComposerAction.RESUME
        else -> ResearchComposerAction.SEND
    }
    val canSubmit = if (action == ResearchComposerAction.PAUSE) true else enabled && (hasInput || paused)
    fun submit() {
        if (!canSubmit) return
        when (action) {
            ResearchComposerAction.SEND -> onSend(text, attachments)
            ResearchComposerAction.CLARIFY -> onClarify(text, attachments)
            ResearchComposerAction.PAUSE -> onPause()
            ResearchComposerAction.RESUME -> onResume(text, attachments)
        }
    }
    fun picked(files: List<Attachment>) { attachments = (attachments + files).take(MAX_ATTACHMENTS_PER_MESSAGE) }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val compact = maxWidth < 440.dp
        val expandedHeight = (maxHeight - 96.dp).coerceIn(80.dp, 206.dp)
        PaperWorkspaceComposer(document = true, options = {
            PaperComposerOptionsPanel(menu, { menu = false; optionsFocus.requestFocus() },
                maxHeight = (maxHeight * .4f).coerceIn(80.dp, 240.dp)) {
                PaperRichMenuAction(text = { PaperText("Прикрепить файлы", role = PaperTextRole.CHROME) },
                    onClick = { onPickAttachments(attachments.size, ::picked) },
                    enabled = attachments.size < MAX_ATTACHMENTS_PER_MESSAGE,
                    leadingIcon = { PaperNoteAddIcon(tint = LocalPaperColors.current.action) })
                PaperDivider()
                PaperText("Движок", Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    role = PaperTextRole.CHROME, color = LocalPaperColors.current.secondaryText)
                if (onEngineChange != null) {
                    CodingEngine.entries.forEach { option ->
                        PaperRichMenuAction(text = { PaperText(option.title, role = PaperTextRole.CHROME) },
                            trailingIcon = if (option == engine) ({ PaperText("✓") }) else null,
                            onClick = { onEngineChange(option) }, enabled = !busy)
                    }
                } else PaperText(engine.title, Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    role = PaperTextRole.CHROME)
            }
        }) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                PaperPromptField(editor, { editor = it; text = it.text }, placeholder,
                    Modifier.weight(1f).heightIn(min = if (expanded) expandedHeight else 32.dp,
                        max = if (expanded) expandedHeight else 92.dp).focusRequester(focus)
                        .onPreviewKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) false
                            else if (event.key == Key.V && (event.isCtrlPressed || event.isMetaPressed))
                                onPasteAttachments(attachments.size, ::picked)
                            else if (event.key == Key.Enter && (event.isCtrlPressed || event.isMetaPressed)) {
                                if (action != ResearchComposerAction.PAUSE) submit()
                                true
                            } else false
                        }, maxLines = if (expanded) 10 else 3, enabled = enabled,
                    style = LocalPaperTypography.current.chrome)
                // Align the affordance with the frame's corner, outside its inner padding.
                PaperComposerExpandButton(expanded, Modifier.offset(x = 8.dp, y = (-4).dp)) {
                    expanded = !expanded
                    focus.requestFocus()
                }
            }
            PendingAttachmentsRow(attachments, { index -> attachments = attachments.filterIndexed { i, _ -> i != index } })
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                PaperComposerOptionsToggle(menu, { menu = !menu }, Modifier.focusRequester(optionsFocus))
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                    CodingModelChip(profile, false, onOpenSwitcher)
                }
                ContextUsageIndicator(contextUsage, model = profile?.modelId.orEmpty(), compacting = contextCompacting)
                // Balance the model chip's 8 dp text inset so the visible gaps match.
                PaperButton(if (compact) action.glyph else action.label, ::submit, Modifier.padding(start = 8.dp),
                    kind = if (action == ResearchComposerAction.PAUSE) PaperButtonKind.SECONDARY else PaperButtonKind.PRIMARY,
                    enabled = canSubmit, accessibilityLabel = action.label)
            }
        }
    }
}

private enum class ResearchComposerAction(val label: String, val glyph: String) {
    SEND("Отправить", "↑"), PAUSE("Пауза", "Ⅱ"), CLARIFY("Уточнить", "↑"), RESUME("Продолжить", "▶")
}
