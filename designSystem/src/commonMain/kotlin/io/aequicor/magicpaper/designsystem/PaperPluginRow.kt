package io.aequicor.magicpaper.designsystem

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

@Composable
fun PaperPluginRow(title: String, description: String, icon: String, enabled: Boolean, onToggle: () -> Unit, onOpen: (() -> Unit)? = null) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(PaperShapes.panel)
            .background(LocalPaperColors.current.surface).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PaperText(icon, style = LocalPaperTypography.current.title)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            if (onOpen == null) PaperText(title, style = LocalPaperTypography.current.body)
            else PaperButton(title, onOpen, kind = PaperButtonKind.QUIET)
            PaperText(description, style = LocalPaperTypography.current.body, color = LocalPaperColors.current.secondaryText)
        }
        PaperToggle(checked = enabled, onCheckedChange = { onToggle() })
    }
}
