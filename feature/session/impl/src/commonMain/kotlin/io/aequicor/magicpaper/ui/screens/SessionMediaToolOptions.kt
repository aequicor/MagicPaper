package io.aequicor.magicpaper.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import io.aequicor.magicpaper.designsystem.*
import io.aequicor.magicpaper.domain.*

/** The saved permission is independent of connection availability, so an unavailable tool can be disabled too. */
@Composable
internal fun SessionMediaToolOptions(
    tools: SessionMediaTools,
    connections: Map<MediaKind, MediaConnectionStatus>,
    onChange: (MediaKind, Boolean) -> Unit,
    onSettings: () -> Unit,
    enabled: Boolean = true,
) {
    MediaKind.entries.forEach { kind ->
        val label = if (kind == MediaKind.IMAGE) "Создание изображений" else "Создание видео"
        val connection = connections[kind]?.availability ?: MediaAvailability.UNCHECKED
        val status = when {
            !tools.enabled(kind) -> "Отключено в этой сессии"
            connection == MediaAvailability.AVAILABLE -> "Подключено"
            connection == MediaAvailability.CHECKING -> "Проверка подключения…"
            connection == MediaAvailability.UNAVAILABLE -> "Подключение недоступно"
            else -> "Подключение не проверено"
        }
        Column {
            PaperMenuToggleInfo(label, tools.enabled(kind), enabled,
                "Разрешить создание медиа в этой сессии. Подключение выбирается и проверяется в настройках моделей.",
                onCheckedChange = { onChange(kind, it) }, modifier = Modifier.semantics { stateDescription = status })
            PaperText(status, Modifier.padding(start = 10.dp, end = 10.dp, bottom = 4.dp),
                role = PaperTextRole.CHROME, color = LocalPaperColors.current.secondaryText)
        }
    }
    PaperRichMenuAction(text = { PaperText("Настроить модели", role = PaperTextRole.CHROME) }, onClick = onSettings)
}
