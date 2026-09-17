package io.aequicor.magicpaper.designsystem

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

@Preview(name = "Scroll areas", group = "Scrolling", widthDp = 520, heightDp = 280)
@Preview(name = "Scroll areas large text", group = "Scrolling", widthDp = 520, heightDp = 360, fontScale = 2f)
@Composable
internal fun PaperScrollPreview() = PaperTheme {
    PaperSurface(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxSize().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PaperScrollColumn(Modifier.weight(1f).fillMaxHeight(), contentPadding = PaddingValues(8.dp)) {
                repeat(40) { PaperText("Источник ${it + 1}", Modifier.padding(vertical = 6.dp), role = PaperTextRole.CHROME) }
            }
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                PaperLazyColumn(Modifier.weight(1f)) {
                    items(200, key = { it }) { PaperText("Вопрос ${it + 1}", Modifier.padding(8.dp)) }
                }
                PaperScrollRow(Modifier.fillMaxWidth().height(40.dp)) {
                    PaperText("val resources = sources.filter { source -> source.isReadable && source.isSelected }", role = PaperTextRole.CODE)
                }
            }
        }
    }
}
