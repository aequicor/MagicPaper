package io.aequicor.magicpaper.ui.components

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.designsystem.*
import kotlin.math.roundToLong

private fun contextUsageNumber(value: Long?): String = value?.toString()?.reversed()?.chunked(3)?.joinToString(" ")?.reversed() ?: "Нет данных"

@Composable
internal fun ContextUsageIndicator(
    snapshot: ContextUsageSnapshot?,
    model: String = snapshot?.model.orEmpty(),
    compacting: Boolean = false,
) {
    var expanded by remember { mutableStateOf(false) }
    val fraction = snapshot?.fraction
    val label = fraction?.let { (if (snapshot.approximate) "≈" else "") + "${(it * 100).roundToLong()}%" } ?: "—"
    Box {
        PaperContextIndicator(fraction, label, { expanded = !expanded }, compacting = compacting)
        PaperMenuHost(expanded, { expanded = false }, Modifier.widthIn(max = 340.dp)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                PaperText("Контекстное окно", role = PaperTextRole.TITLE)
                PaperText("Занято: ${contextUsageNumber(snapshot?.used)} токенов")
                PaperText("Размер окна: ${contextUsageNumber(snapshot?.limit)} токенов")
                if (model.isNotBlank()) PaperText(model)
                if (compacting) PaperText("Сжатие контекста…", role = PaperTextRole.LABEL)
                PaperText(if (snapshot?.approximate == true) "Приблизительная оценка движка" else if (fraction == null) "Ожидание данных движка" else "Последние данные движка", role = PaperTextRole.LABEL)
                PaperText("Неотправленный текст не учитывается", role = PaperTextRole.LABEL)
            }
        }
    }
}
