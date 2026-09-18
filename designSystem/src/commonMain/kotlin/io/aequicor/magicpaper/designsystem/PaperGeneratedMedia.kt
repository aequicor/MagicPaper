package io.aequicor.magicpaper.designsystem

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp

public enum class PaperMediaKind { IMAGE, VIDEO }
public enum class PaperMediaState { GENERATING, LOADING, READY, FAILED, UNKNOWN, CANCELLED }

/** A book illustration retains its layout slot while an operation progresses. Loading stays with its owner. */
@Composable
public fun PaperGeneratedMedia(
    kind: PaperMediaKind,
    state: PaperMediaState,
    caption: String = "",
    aspectRatio: Float = 1f,
    bitmap: ImageBitmap? = null,
    videoPath: String? = null,
    message: String = "",
    onRetry: (() -> Unit)? = null,
    animate: Boolean = true,
    onPlaybackError: (Throwable?) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val colors = LocalPaperColors.current
    val title = if (kind == PaperMediaKind.IMAGE) "Изображение" else "Видео"
    val label = when (state) {
        PaperMediaState.GENERATING -> if (kind == PaperMediaKind.IMAGE) "Создаю изображение…" else "Создаю видео…"
        PaperMediaState.LOADING -> "Загружаю результат…"
        PaperMediaState.READY -> "$title готово"
        PaperMediaState.FAILED -> "Не удалось создать ${if (kind == PaperMediaKind.IMAGE) "изображение" else "видео"}"
        PaperMediaState.UNKNOWN -> "Нужно проверить результат"
        PaperMediaState.CANCELLED -> "Создание остановлено"
    }
    val motionScale = rememberCoroutineScope().coroutineContext[MotionDurationScale]?.scaleFactor ?: 1f
    val busy = state == PaperMediaState.GENERATING || state == PaperMediaState.LOADING
    val opacity = if (busy && animate && motionScale > 0f) {
        val transition = rememberInfiniteTransition(label = "Media generation")
        val pulse by transition.animateFloat(.35f, .75f, infiniteRepeatable(tween(1100), RepeatMode.Reverse), label = "Paper media pulse")
        pulse
    } else .5f
    Column(modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state == PaperMediaState.READY && kind == PaperMediaKind.VIDEO && videoPath != null) {
            PaperVideo(videoPath, caption.ifBlank { title }, aspectRatio, onPlaybackError, Modifier.fillMaxWidth())
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Box(Modifier.fillMaxWidth().aspectRatio(aspectRatio.takeIf { it.isFinite() && it > 0f }?.coerceIn(.3f, 3f) ?: 1f)
                .clip(RoundedCornerShape(12.dp)).background(colors.raisedSurface)
                .semantics { contentDescription = caption.ifBlank { title }; stateDescription = label },
                contentAlignment = Alignment.Center) {
                if (state == PaperMediaState.READY && bitmap != null) {
                    PaperImage(bitmap, caption.ifBlank { title }, Modifier.fillMaxSize(), scale = PaperImageScale.FIT)
                } else {
                    if (busy) Box(Modifier.fillMaxSize().background(colors.action.copy(alpha = opacity * .16f)))
                    Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        PaperText(label, role = PaperTextRole.CHROME)
                        if (message.isNotBlank()) PaperText(message, role = PaperTextRole.LABEL,
                            color = if (state == PaperMediaState.FAILED) colors.error else colors.secondaryText)
                        onRetry?.let { PaperButton(if (state == PaperMediaState.UNKNOWN) "Проверить результат" else "Загрузить снова", it,
                            kind = PaperButtonKind.SECONDARY, enabled = !busy) }
                    }
                }
            }
            if (kind == PaperMediaKind.VIDEO) PaperVideoControls(false, true, 0f, "00:00", "00:00", 1f, {}, {}, {}, {}, enabled = false)
            }
        }
        if (caption.isNotBlank()) PaperText(caption, role = PaperTextRole.LABEL, color = colors.secondaryText)
    }
}

/** Playback never auto-starts when a saved page is opened. Desktop owns the native player lifecycle. */
@Composable
public expect fun PaperVideo(localPath: String, description: String, aspectRatio: Float = 16f / 9f,
    onError: (Throwable?) -> Unit = {}, modifier: Modifier = Modifier)

/** Shared geometry also reserves the playback lane while video generation is pending. */
@Composable
internal fun PaperVideoControls(playing: Boolean, loading: Boolean, position: Float, positionText: String, durationText: String,
    volume: Float, onPlay: () -> Unit, onSeek: (Float) -> Unit, onSeekFinished: () -> Unit, onVolume: (Float) -> Unit,
    enabled: Boolean = true) {
    Column {
        Slider(position.coerceIn(0f, 1000f), onSeek, valueRange = 0f..1000f, onValueChangeFinished = onSeekFinished,
            enabled = enabled, modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Позиция видео" })
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PaperButton(if (playing) "Пауза" else "Смотреть", onPlay, enabled = enabled && !loading, kind = PaperButtonKind.SECONDARY)
            PaperText("$positionText / $durationText", modifier = Modifier.weight(1f), role = PaperTextRole.LABEL)
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PaperIconButton(if (volume == 0f) "Включить звук" else "Выключить звук", { onVolume(if (volume == 0f) 1f else 0f) }, enabled = enabled) {
                PaperText(if (volume == 0f) "♪×" else "♪", role = PaperTextRole.CHROME)
            }
            Slider(volume.coerceIn(0f, 1f), onVolume, enabled = enabled,
                modifier = Modifier.weight(1f).semantics { contentDescription = "Громкость видео" })
        }
    }
}
