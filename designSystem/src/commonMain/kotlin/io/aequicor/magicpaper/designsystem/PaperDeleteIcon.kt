package io.aequicor.magicpaper.designsystem

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.aequicor.magicpaper.designsystem.resources.Res
import io.aequicor.magicpaper.designsystem.resources.delete
import org.jetbrains.compose.resources.painterResource

/** Material Symbols Outlined delete, 400 / FILL 0 / opsz 24. The control owns its label. */
@Composable
public fun PaperDeleteIcon(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) {
    Icon(painterResource(Res.drawable.delete), contentDescription = null,
        modifier = modifier.size(24.dp), tint = tint)
}
