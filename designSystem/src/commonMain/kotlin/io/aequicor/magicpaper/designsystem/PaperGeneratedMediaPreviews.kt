package io.aequicor.magicpaper.designsystem

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

@Preview(name = "Generating illustration", group = "Generated media", widthDp = 560, heightDp = 440)
@Composable
internal fun PaperMediaGeneratingPreview() = PaperMediaPreview(PaperMediaState.GENERATING)

@Preview(name = "Book illustration", group = "Generated media", widthDp = 560, heightDp = 440)
@Preview(name = "Narrow illustration", group = "Generated media", widthDp = 360, heightDp = 460)
@Preview(name = "Large text illustration", group = "Generated media", widthDp = 360, heightDp = 600, fontScale = 2f)
@Composable
internal fun PaperMediaReadyPreview() = PaperMediaPreview(PaperMediaState.READY)

@Preview(name = "Generation failed", group = "Generated media", widthDp = 360, heightDp = 440)
@Composable
internal fun PaperMediaFailedPreview() = PaperMediaPreview(PaperMediaState.FAILED)

@Preview(name = "Interrupted generation", group = "Generated media", widthDp = 360, heightDp = 460)
@Composable
internal fun PaperMediaUnknownPreview() = PaperMediaPreview(PaperMediaState.UNKNOWN)

@Preview(name = "Creating video", group = "Generated media", widthDp = 560, heightDp = 700)
@Composable
internal fun PaperMediaVideoPreview() = PaperMediaPreview(PaperMediaState.GENERATING, PaperMediaKind.VIDEO)

@Composable
internal fun PaperMediaPreview(state: PaperMediaState, kind: PaperMediaKind = PaperMediaKind.IMAGE) = PaperTheme {
    val colors = LocalPaperColors.current
    val bitmap = remember {
        ImageBitmap(640, 360).also { image ->
            val canvas = Canvas(image)
            canvas.drawRect(Rect(0f, 0f, 640f, 360f), Paint().apply { color = colors.successSurface })
            canvas.drawCircle(Offset(320f, 180f), 100f, Paint().apply { color = colors.action.copy(alpha = .3f) })
            canvas.drawCircle(Offset(440f, 220f), 60f, Paint().apply { color = colors.action })
        }
    }
    PaperSurface(Modifier.fillMaxSize()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PaperText("Наглядный пример", role = PaperTextRole.TITLE)
            PaperText("Иллюстрация остаётся рядом с пояснением.")
            PaperGeneratedMedia(kind, state, caption = "Как соотносятся части целого", aspectRatio = 16f / 9f,
                bitmap = bitmap.takeIf { state == PaperMediaState.READY }, animate = false,
                message = when (state) {
                    PaperMediaState.FAILED -> "Сервис временно недоступен."
                    PaperMediaState.UNKNOWN -> "Проверьте результат перед повторным созданием."
                    else -> ""
                }, onRetry = if (state == PaperMediaState.UNKNOWN) ({}) else null)
            PaperText("Следующий абзац продолжает мысль под изображением.")
        }
    }
}
