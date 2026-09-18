package io.aequicor.magicpaper.designsystem

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.kdroidfilter.composemediaplayer.InitialPlayerState
import io.github.kdroidfilter.composemediaplayer.VideoPlayerState
import io.github.kdroidfilter.composemediaplayer.VideoPlayerSurface
import io.github.kdroidfilter.composemediaplayer.createVideoPlayerState
import java.io.File
import kotlinx.coroutines.CancellationException

@Composable
public actual fun PaperVideo(localPath: String, description: String, aspectRatio: Float,
    onError: (Throwable?) -> Unit, modifier: Modifier) {
    val preview = LocalInspectionMode.current
    val supported = remember { System.getProperty("os.name").let { it.startsWith("Mac") || it.startsWith("Windows") } }
    var attempt by remember(localPath) { mutableIntStateOf(0) }
    val result = remember(localPath, attempt, preview, supported) {
        if (preview || !supported) null else try { Result.success(createVideoPlayerState()) }
        catch (failure: Exception) { Result.failure<VideoPlayerState>(failure) }
        catch (failure: LinkageError) { Result.failure<VideoPlayerState>(failure) }
    }
    val player = result?.getOrNull()
    var openFailure by remember(localPath, attempt) { mutableStateOf<Throwable?>(null) }
    val latestError by rememberUpdatedState(onError)
    DisposableEffect(player) { onDispose {
        try { player?.dispose() } catch (failure: Exception) { latestError(failure) }
    } }
    LaunchedEffect(player, localPath) {
        if (player != null) try { player.openUri(File(localPath).toURI().toString(), InitialPlayerState.PAUSE) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { openFailure = failure }
    }
    val failed = result?.exceptionOrNull() ?: openFailure
    val nativeError = player?.error
    fun control(action: () -> Unit) {
        try { action() } catch (failure: Exception) { openFailure = failure }
    }
    LaunchedEffect(failed, nativeError) { if (failed != null || nativeError != null) latestError(failed) }
    Column(modifier.semantics { contentDescription = description }, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(aspectRatio.coerceIn(.3f, 3f)), contentAlignment = Alignment.Center) {
            when {
                !supported -> PaperText("Воспроизведение доступно в macOS и Windows", role = PaperTextRole.LABEL)
                failed != null || nativeError != null -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    PaperText("Не удалось воспроизвести видео", role = PaperTextRole.LABEL, color = LocalPaperColors.current.error)
                    PaperButton("Повторить", { attempt++ }, kind = PaperButtonKind.SECONDARY)
                }
                player != null -> VideoPlayerSurface(player, Modifier.fillMaxSize(), ContentScale.Fit)
                else -> PaperText("Видео", role = PaperTextRole.CHROME)
            }
        }
        if (player != null && failed == null && nativeError == null) {
            PaperVideoControls(player.isPlaying, player.isLoading, player.sliderPos, player.positionText, player.durationText,
                player.volume, onPlay = { control { if (player.isPlaying) player.pause() else player.play() } },
                onSeek = { control { player.seekStart(it) } }, onSeekFinished = { control { player.seekFinished() } },
                onVolume = { volume -> control { player.volume = volume } })
        } else PaperVideoControls(false, false, 0f, "00:00", "00:00", 1f, {}, {}, {}, {}, enabled = false)
    }
}
