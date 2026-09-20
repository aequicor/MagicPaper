package io.aequicor.magicpaper.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.designsystem.*


/** Shown by both an ordinary chat and a project session: it presents a profile, nothing more. */
@Composable
fun CodingModelChip(profile: LlmProfile?, overridden: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    PaperAction(onClick, modifier.heightIn(min = 32.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
        Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
            PaperText(profile?.let { it.modelName(it.selectionKey).ifBlank { it.name } } ?: "Выбрать модель",
                role = PaperTextRole.CHROME, maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            if (profile != null) {
                PaperText(profile.effortLabel(ModelDefaults.capability(profile)),
                    role = PaperTextRole.CHROME, color = LocalPaperColors.current.secondaryText,
                    maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
        }
    }
}

