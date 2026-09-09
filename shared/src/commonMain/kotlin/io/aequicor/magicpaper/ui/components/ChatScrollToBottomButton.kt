package io.aequicor.magicpaper.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import io.aequicor.magicpaper.designsystem.LocalPaperColors
import io.aequicor.magicpaper.designsystem.PaperIconButton
import kotlinx.coroutines.launch

@Composable
internal fun ChatScrollToBottomButton(scroll: ChatScrollState, modifier: Modifier = Modifier) {
    key(scroll) {
        val scope = rememberCoroutineScope()
        val visible by remember { derivedStateOf { scroll.canScrollToEnd } }
        if (visible) {
            PaperIconButton(
                label = "К концу чата",
                onClick = { scope.launch { scroll.navigateToEnd() } },
                modifier = modifier.size(48.dp),
            ) {
                val color = LocalPaperColors.current.text
                Box(contentAlignment = Alignment.Center) {
                    Canvas(Modifier.size(24.dp)) {
                        val tip = Offset(size.width / 2, size.height * 0.8f)
                        val stroke = 2.dp.toPx()
                        drawLine(color, Offset(tip.x, size.height * 0.2f), tip, stroke, StrokeCap.Round)
                        drawLine(color, Offset(size.width * 0.25f, size.height * 0.55f), tip, stroke, StrokeCap.Round)
                        drawLine(color, Offset(size.width * 0.75f, size.height * 0.55f), tip, stroke, StrokeCap.Round)
                    }
                }
            }
        }
    }
}
