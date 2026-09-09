package io.aequicor.magicpaper.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.designsystem.*

/** Compact model identity; secondary details and controls share one menu. */
@Composable
fun ModelSettingsButton(
    profile: LlmProfile?,
    selection: ModelSelection?,
    onChoose: () -> Unit,
    onEffort: (EffortSelection) -> Unit,
    onParameters: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    // sp preserves the user's font scaling; only secondary text gets smaller
    // on dense displays, without shrinking the button's touch target.
    val detailStyle = LocalPaperTypography.current.label.copy(
        fontSize = if (density.density >= 1.5f) 11.sp else 12.sp,
        lineHeight = 16.sp,
    )
    val muted = LocalPaperColors.current.secondaryText.copy(alpha = 0.65f)
    val model = selection?.modelId ?: profile?.selectionKey.orEmpty()
    val capability = profile?.let { ModelDefaults.capability(it, model) }
    val effort = selection?.effort ?: EffortSelection.Default
    val resolved = capability?.resolveEffort(effort)
    val effortLabel = if (effort == EffortSelection.Default) "Auto"
        else resolved?.level?.shortLabel ?: "Auto"
    Box(modifier) {
        PaperAction(
            onClick = { if (profile == null) onChoose() else expanded = true },
            modifier = Modifier.heightIn(min = 48.dp),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        ) {
            PaperText("✦", modifier = Modifier.padding(end = 8.dp))
            Column(Modifier.weight(1f, fill = false)) {
                PaperText(
                    profile?.sourceModelId(model) ?: "Выбрать модель",
                    style = LocalPaperTypography.current.body,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                profile?.let {
                    PaperText(it.name, style = detailStyle, color = muted,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (capability?.supportsEffort == true) {
                PaperText(effortLabel, Modifier.padding(start = 12.dp), style = detailStyle, color = muted)
            }
            PaperText("▾", Modifier.padding(start = 8.dp), color = muted)
        }
        PaperMenuHost(expanded = expanded, onDismissRequest = { expanded = false },
            modifier = Modifier.widthIn(max = 360.dp)) {
            profile?.let {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    PaperText(it.modelName(model), style = LocalPaperTypography.current.body)
                    PaperText(it.name, style = detailStyle, color = muted)
                }
            }
            if (capability?.supportsEffort == true) {
                EffortControl(capability, effort, onEffort,
                    Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
                PaperDivider()
            }
            PaperRichMenuAction(text = { PaperText("Сменить модель") }, onClick = {
                expanded = false
                onChoose()
            }, trailingIcon = { PaperText("›") })
            PaperRichMenuAction(text = { PaperText("Настроить параметры…") }, onClick = {
                expanded = false
                onParameters()
            }, enabled = profile != null, trailingIcon = { PaperText("›") })
        }
    }
}
