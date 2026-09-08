package io.aequicor.magicpaper.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
internal fun ChatScrollToBottomButton(scroll: ChatScrollState, modifier: Modifier = Modifier) {
    key(scroll) {
        val scope = rememberCoroutineScope()
        val visible by remember { derivedStateOf { scroll.canScrollToEnd } }
        if (visible) {
            Surface(
                onClick = { scope.launch { scroll.navigateToEnd() } },
                modifier = modifier.size(48.dp).semantics {
                    contentDescription = "К концу чата"
                    role = Role.Button
                },
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                shadowElevation = 4.dp,
            ) {
                val color = MaterialTheme.colorScheme.onSurface
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
