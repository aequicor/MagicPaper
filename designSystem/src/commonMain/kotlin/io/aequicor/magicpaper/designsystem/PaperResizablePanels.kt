package io.aequicor.magicpaper.designsystem

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp

@Composable
fun PaperResizablePanels(
    modifier: Modifier = Modifier,
    sidebarVisible: Boolean = true,
    preferredWidth: Float? = null,
    onPreferredWidthChange: (Float) -> Unit = {},
    sidebar: @Composable (Modifier) -> Unit,
    content: @Composable () -> Unit,
) {
    var localPreferredWidth by rememberSaveable { mutableStateOf(272f) }
    val requestedWidth = preferredWidth ?: localPreferredWidth
    val density = LocalDensity.current
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val maximum = (maxWidth.value - 328f).coerceIn(160f, 600f)
        val minimum = minOf(200f, maximum)
        val panelWidth = requestedWidth.coerceIn(minimum, maximum)
        // The strip is the drag target, the line is its trailing edge and the pill is the grip.
        val stripWidth = 8.dp
        val lineThickness = 1.dp
        val pillWidth = 3.dp
        Row(Modifier.fillMaxSize()) {
            if (sidebarVisible) {
                // The panel is measured under the drag strip: its full-width horizontal dividers
                // reach the vertical edge line instead of stopping one strip short of it.
                sidebar(Modifier.width(panelWidth.dp + stripWidth))
            }
            Box(Modifier.weight(1f)) { content() }
        }
        if (sidebarVisible) {
            // The strip overlays the trailing edge of the panel: the line keeps sitting on the
            // content boundary while the panel itself is painted underneath it.
            Box(
                Modifier.align(Alignment.TopStart).offset(x = panelWidth.dp).width(stripWidth).fillMaxHeight()
                    .semantics { contentDescription = "Изменить ширину списка сессий" }
                    .draggable(rememberDraggableState { delta ->
                        val changed = (requestedWidth.coerceIn(minimum, maximum) + with(density) { delta.toDp().value }).coerceIn(minimum, maximum)
                        if (preferredWidth == null) localPreferredWidth = changed
                        onPreferredWidthChange(changed)
                    }, Orientation.Horizontal),
            ) {
                // The line is the transcript edge where messages begin to fade, so it stays on the
                // boundary with the content; only the grip is moved to it.
                PaperVerticalDivider(
                    modifier = Modifier.align(Alignment.CenterEnd),
                    color = LocalPaperColors.current.border,
                    thickness = lineThickness,
                )
            }
            // Painted above both panels: the grip is centred on the line and must not be covered by
            // the content that starts on the same x. Dragging it still hits the strip underneath.
            val lineCenter = panelWidth.dp + stripWidth - lineThickness / 2f
            Box(
                Modifier.align(Alignment.CenterStart)
                    .offset(x = lineCenter - pillWidth / 2f)
                    .width(pillWidth).height(28.dp)
                    .background(LocalPaperColors.current.border, RoundedCornerShape(6.dp)),
            )
        }
    }
}
