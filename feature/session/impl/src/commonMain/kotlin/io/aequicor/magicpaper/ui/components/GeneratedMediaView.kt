package io.aequicor.magicpaper.ui.components

import androidx.compose.runtime.*
import androidx.compose.ui.graphics.ImageBitmap
import io.aequicor.magicpaper.data.storage.MediaStore
import io.aequicor.magicpaper.data.storage.UnavailableMediaStore
import io.aequicor.magicpaper.designsystem.*
import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.logging.AppLog
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

private val LocalGeneratedMediaStore = staticCompositionLocalOf<MediaStore> { UnavailableMediaStore }
private val LocalGeneratedMediaService = staticCompositionLocalOf<MediaGenerationService?> { null }
private val LocalGeneratedMediaAnimation = staticCompositionLocalOf { true }

/** The application supplies its existing storage/service owners; composing a page creates no runtime. */
@Composable
fun GeneratedMediaProvider(store: MediaStore, service: MediaGenerationService? = null, animate: Boolean = true,
    content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalGeneratedMediaStore provides store, LocalGeneratedMediaService provides service,
        LocalGeneratedMediaAnimation provides animate, content = content)
}

private val mediaDecoders = Semaphore(2)

@Composable
fun GeneratedMediaView(media: GeneratedMedia, live: Boolean = false) {
    val store = LocalGeneratedMediaStore.current
    val service = LocalGeneratedMediaService.current
    val operations = service?.operations?.collectAsState()?.value.orEmpty()
    val current = operations[media.id] ?: if (live) media else media.interrupted()
    var bitmap by remember(current.asset?.id) { mutableStateOf<ImageBitmap?>(null) }
    var videoPath by remember(current.asset?.id) { mutableStateOf<String?>(null) }
    var loadError by remember(current.asset?.id) { mutableStateOf<String?>(null) }
    var attempt by remember(current.id) { mutableIntStateOf(0) }
    var recovering by remember(current.id) { mutableStateOf(false) }
    var recoveryError by remember(current.id) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(store, current.asset, attempt) {
        val asset = current.asset ?: return@LaunchedEffect
        loadError = null
        try {
            if (current.kind == MediaKind.IMAGE) {
                bitmap = withContext(Dispatchers.Default) {
                    mediaDecoders.withPermit { decodeGeneratedMediaBitmap(store.read(asset), asset.mimeType) }
                }
            } else {
                videoPath = withContext(Dispatchers.Default) { store.localPath(asset) }
                if (videoPath == null) loadError = "Видео недоступно на этом устройстве."
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            AppLog.error("media", "asset.load.failed", failure, mapOf("assetId" to asset.id, "mediaId" to current.id))
            loadError = "Не удалось загрузить сохранённый файл."
        }
    }
    val state = when {
        loadError != null -> PaperMediaState.FAILED
        recovering -> PaperMediaState.LOADING
        current.phase == MediaPhase.READY && current.asset == null -> PaperMediaState.FAILED
        current.phase == MediaPhase.READY && (current.kind == MediaKind.IMAGE && bitmap == null ||
            current.kind == MediaKind.VIDEO && videoPath == null) -> PaperMediaState.LOADING
        current.phase == MediaPhase.READY -> PaperMediaState.READY
        current.phase == MediaPhase.FAILED -> PaperMediaState.FAILED
        current.phase == MediaPhase.UNKNOWN -> PaperMediaState.UNKNOWN
        current.phase == MediaPhase.CANCELLED -> PaperMediaState.CANCELLED
        current.phase == MediaPhase.DOWNLOADING -> PaperMediaState.LOADING
        else -> PaperMediaState.GENERATING
    }
    val retry: (() -> Unit)? = when {
        loadError != null -> ({ attempt++ })
        current.phase == MediaPhase.UNKNOWN && service != null -> ({
            if (!recovering) {
                recovering = true
                recoveryError = null
                scope.launch {
                    try {
                        if (service.recoverMedia(current.id) == null) recoveryError = "Сведения о создании недоступны."
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) {
                        AppLog.error("media", "recovery.request.failed", failure, mapOf("mediaId" to current.id))
                        recoveryError = "Не удалось проверить результат. Повторите проверку."
                    } finally { recovering = false }
                }
            }
        })
        else -> null
    }
    PaperGeneratedMedia(kind = if (current.kind == MediaKind.IMAGE) PaperMediaKind.IMAGE else PaperMediaKind.VIDEO,
        state = state, caption = current.caption, aspectRatio = current.width.toFloat() / current.height.coerceAtLeast(1),
        bitmap = bitmap, videoPath = videoPath, message = loadError ?: recoveryError ?:
            if (current.phase == MediaPhase.READY && current.asset == null) "Сведения о файле недоступны." else current.message,
        onRetry = retry, animate = LocalGeneratedMediaAnimation.current,
        onPlaybackError = { failure ->
            val fields = mapOf("mediaId" to current.id, "assetId" to current.asset?.id.orEmpty())
            if (failure != null) AppLog.error("media", "playback.failed", failure, fields)
            else AppLog.error("media", "playback.failed", fields)
        })
}
