package io.aequicor.magicpaper.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import io.aequicor.magicpaper.designsystem.*
import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.logging.AppLog
import io.aequicor.magicpaper.ui.DefaultSettingsComponent
import io.aequicor.magicpaper.ui.SettingsState
import io.aequicor.magicpaper.ui.DraftSaveError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

@Composable
internal fun MediaSettings(vm: DefaultSettingsComponent, state: SettingsState) {
    PaperText("Изображения и видео", role = PaperTextRole.TITLE)
    if (!state.mediaSupported) PaperText("Генерация доступна в приложении для macOS и Windows.")
    MediaKind.entries.forEach { kind ->
        val current = state.settings.media.selection(kind)
        val draft = vm.drafts.media(kind, current)
        val draftState by draft.state.collectAsState()
        val fields = draftState.value.fields
        val selectedProfile = fields["profile"].orEmpty()
        val protocol = MediaProtocol.entries.firstOrNull { it.name == fields["protocol"] }
            ?: if (kind == MediaKind.IMAGE) MediaProtocol.OPENAI_IMAGES else MediaProtocol.DASHSCOPE_VIDEO
        val selection = MediaModelSelection(selectedProfile, fields["model"].orEmpty(), protocol, fields["url"].orEmpty())
        MediaSettingsForm(
            kind = kind, selection = selection, savedSelection = current,
            profiles = state.llmProfiles.filter { it.provider == ProviderType.OPENAI_COMPATIBLE || it.provider == ProviderType.OPENROUTER },
            status = state.mediaConnections[kind], supported = state.mediaSupported, saving = state.settingsSaving,
            onSelectionChange = { next -> draft.update { it.copy(fields = it.fields + mapOf(
                "profile" to next.profileId, "model" to next.modelId, "protocol" to next.protocol.name, "url" to next.baseUrl,
            )) } },
            onCheck = { vm.saveMediaSelection(kind, selection.copy(modelId = selection.modelId.trim(), baseUrl = selection.baseUrl.trim()), verify = true) },
            onDisable = { vm.saveMediaSelection(kind, null) },
            draftError = { DraftSaveError(draft) },
            preview = { MediaConnectionPreview(it, state.settings.paperAnimationEnabled, vm::readMediaAsset, vm::mediaAssetPath, vm::recoverMediaResult) },
        )
    }
}

@Composable
internal fun MediaConnectionPreview(
    media: GeneratedMedia,
    animate: Boolean,
    readAsset: suspend (MediaAsset) -> ByteArray,
    assetPath: suspend (MediaAsset) -> String?,
    recover: suspend (String) -> GeneratedMedia?,
) {
    var displayed by remember(media) { mutableStateOf(media) }
    var loadAttempt by remember(media.id) { mutableIntStateOf(0) }
    var recovering by remember(media.id) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var bitmap by remember(displayed.asset, loadAttempt) { mutableStateOf<ImageBitmap?>(null) }
    var path by remember(displayed.asset, loadAttempt) { mutableStateOf<String?>(null) }
    var errorMessage by remember(media.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(displayed.asset, loadAttempt) {
        val asset = displayed.asset ?: return@LaunchedEffect
        errorMessage = null
        try {
            if (displayed.kind == MediaKind.IMAGE) {
                val bytes = readAsset(asset)
                bitmap = withContext(Dispatchers.Default) { decodePaperMediaImage(bytes, asset.mimeType) }
            } else path = assetPath(asset) ?: error("Media has no local playback path")
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            AppLog.error("MediaSettings", "preview_failed", failure, mapOf("assetId" to asset.id))
            errorMessage = "Не удалось открыть пробный результат. Попробуйте загрузить его снова."
        }
    }
    val retry: (() -> Unit)? = when {
        recovering -> null
        errorMessage != null && displayed.asset != null -> ({ loadAttempt++ })
        displayed.phase == MediaPhase.UNKNOWN -> ({
            if (!recovering) {
                recovering = true
                errorMessage = null
                scope.launch {
                    try {
                        displayed = recover(displayed.id) ?: error("Existing probe was not found")
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) {
                        AppLog.error("MediaSettings", "preview_recovery_failed", failure, mapOf("mediaId" to displayed.id))
                        errorMessage = "Не удалось проверить результат. Попробуйте ещё раз позже."
                    } finally { recovering = false }
                }
            }
        })
        else -> null
    }
    key(loadAttempt) {
        PaperGeneratedMedia(
            kind = if (displayed.kind == MediaKind.IMAGE) PaperMediaKind.IMAGE else PaperMediaKind.VIDEO,
            state = when {
                recovering -> PaperMediaState.LOADING
                errorMessage != null && displayed.asset != null -> PaperMediaState.FAILED
                else -> when (displayed.phase) {
                    MediaPhase.READY -> if (bitmap != null || path != null) PaperMediaState.READY else PaperMediaState.LOADING
                    MediaPhase.FAILED -> PaperMediaState.FAILED
                    MediaPhase.UNKNOWN -> PaperMediaState.UNKNOWN
                    MediaPhase.CANCELLED -> PaperMediaState.CANCELLED
                    else -> PaperMediaState.GENERATING
                }
            }, caption = displayed.caption, bitmap = bitmap, videoPath = path,
            aspectRatio = displayed.width.toFloat() / displayed.height.coerceAtLeast(1),
            message = errorMessage ?: displayed.message, animate = animate, onRetry = retry,
            onPlaybackError = { failure ->
                AppLog.error("MediaSettings", "preview_playback_failed", failure ?: IllegalStateException("Native playback failed"),
                    mapOf("assetId" to displayed.asset?.id.orEmpty()))
                errorMessage = "Не удалось открыть пробный результат. Попробуйте загрузить его снова."
            },
        )
    }
}
