package io.aequicor.magicpaper.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * An action that resolves a failure, set under its message. While the host carries it out, the
 * button gives way to [pendingLabel] and a cancel: such a flow usually waits on another
 * application, a browser page, and the reader must be able to abandon it there.
 */
@Composable
public fun PaperRecoveryAction(
    label: String,
    pendingLabel: String,
    pending: Boolean,
    onAction: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalPaperSpacing.current
    Row(modifier.padding(vertical = spacing.xxs), horizontalArrangement = Arrangement.spacedBy(spacing.xs),
        verticalAlignment = Alignment.CenterVertically) {
        if (pending) {
            PaperProgress(Modifier.size(18.dp), kind = PaperProgressKind.CIRCULAR, label = pendingLabel)
            PaperText(pendingLabel, Modifier.weight(1f, fill = false), role = PaperTextRole.LABEL,
                color = LocalPaperColors.current.secondaryText)
            PaperButton("Отменить", onCancel, kind = PaperButtonKind.QUIET)
        } else {
            PaperButton(label, onAction, kind = PaperButtonKind.SECONDARY)
        }
    }
}
