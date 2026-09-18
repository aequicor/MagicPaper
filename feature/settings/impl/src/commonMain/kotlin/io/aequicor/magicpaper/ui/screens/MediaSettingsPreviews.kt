package io.aequicor.magicpaper.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.tooling.preview.Preview
import io.aequicor.magicpaper.designsystem.*
import io.aequicor.magicpaper.domain.*

internal enum class MediaSettingsPreviewState { DEFAULT, CHECKED, CHECKING, ERROR, UNSUPPORTED, EMPTY }

internal val mediaPreviewProfiles = listOf(
    LlmProfile("openai", "OpenAI", "https://api.openai.com/v1", modelCatalog = listOf(ProviderModel("gpt-image-1.5"))),
    LlmProfile("alibaba", "Alibaba Cloud", "https://dashscope-intl.aliyuncs.com/compatible-mode/v1",
        modelCatalog = listOf(ProviderModel("qwen-image-plus"), ProviderModel("wan2.7-t2v"))),
)

internal fun mediaPreviewSelection(kind: MediaKind) = if (kind == MediaKind.IMAGE)
    MediaModelSelection("openai", "gpt-image-1.5", MediaProtocol.OPENAI_IMAGES, "https://api.openai.com/v1")
else MediaModelSelection("alibaba", "wan2.7-t2v", MediaProtocol.DASHSCOPE_VIDEO, "https://dashscope-intl.aliyuncs.com/api/v1")

internal fun mediaPreviewStatus(kind: MediaKind, state: MediaSettingsPreviewState): MediaConnectionStatus = MediaConnectionStatus(
    kind = kind,
    availability = when (state) {
        MediaSettingsPreviewState.CHECKED -> MediaAvailability.AVAILABLE
        MediaSettingsPreviewState.CHECKING -> MediaAvailability.CHECKING
        MediaSettingsPreviewState.ERROR -> MediaAvailability.UNAVAILABLE
        else -> MediaAvailability.UNCHECKED
    },
    message = if (state == MediaSettingsPreviewState.ERROR) "Подключение недоступно. Проверьте адрес сервера и ключ поставщика." else "",
    preview = when {
        state == MediaSettingsPreviewState.CHECKING -> GeneratedMedia("probe-${kind.name}", kind, MediaPhase.GENERATING,
            "Пробное изображение".takeIf { kind == MediaKind.IMAGE } ?: "Пробное видео", width = 1024, height = 576)
        state == MediaSettingsPreviewState.CHECKED && kind == MediaKind.IMAGE -> GeneratedMedia("probe-image", kind, MediaPhase.READY,
            "Пробное изображение", width = 1024, height = 576)
        else -> null
    },
)

/** No services, native player, file reads or billable generation are started by this fixture. */
@Composable
internal fun MediaSettingsPreviewContent(
    state: MediaSettingsPreviewState,
    onCheck: (MediaKind) -> Unit = {},
    onDisable: (MediaKind) -> Unit = {},
    onSelectionChange: (MediaKind, MediaModelSelection) -> Unit = { _, _ -> },
) {
    val spacing = LocalPaperSpacing.current
    PaperSurface(Modifier.fillMaxSize(), kind = PaperSurfaceKind.CANVAS) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.md)) {
            PaperText("Изображения и видео", role = PaperTextRole.TITLE)
            if (state == MediaSettingsPreviewState.UNSUPPORTED) PaperText("Генерация доступна в приложении для macOS и Windows.")
            MediaKind.entries.forEach { kind ->
                val selection = mediaPreviewSelection(kind).let {
                    if (state == MediaSettingsPreviewState.EMPTY) it.copy(profileId = "", modelId = "", baseUrl = "") else it
                }
                MediaSettingsForm(kind, selection, selection.takeUnless { state in listOf(MediaSettingsPreviewState.DEFAULT, MediaSettingsPreviewState.EMPTY) },
                    mediaPreviewProfiles.takeUnless { state == MediaSettingsPreviewState.EMPTY }.orEmpty(), mediaPreviewStatus(kind, state),
                    supported = state != MediaSettingsPreviewState.UNSUPPORTED, saving = false,
                    onSelectionChange = { onSelectionChange(kind, it) }, onCheck = { onCheck(kind) }, onDisable = { onDisable(kind) },
                    preview = { media -> MediaSettingsPreviewResult(media) })
            }
        }
    }
}

@Composable
private fun MediaSettingsPreviewResult(media: GeneratedMedia) {
    val bitmap = remember {
        ImageBitmap(320, 180).also { image ->
            Canvas(image).apply {
                drawRect(Rect(0f, 0f, 320f, 180f), Paint().apply { color = Color.White })
                drawCircle(Offset(160f, 90f), 48f, Paint().apply { color = Color(0xFF387BC7) })
            }
        }
    }
    PaperGeneratedMedia(if (media.kind == MediaKind.IMAGE) PaperMediaKind.IMAGE else PaperMediaKind.VIDEO,
        if (media.phase == MediaPhase.READY) PaperMediaState.READY else PaperMediaState.GENERATING,
        caption = media.caption, aspectRatio = 16f / 9f, bitmap = bitmap.takeIf { media.phase == MediaPhase.READY }, animate = false)
}

@Preview(name = "Default", group = "Media settings", widthDp = 900, heightDp = 1400)
@Preview(name = "Narrow", group = "Media settings", widthDp = 390, heightDp = 1600)
@Preview(name = "200% text", group = "Media settings", widthDp = 390, heightDp = 1800, fontScale = 2f)
@Composable internal fun MediaSettingsDefaultPreview() { PaperTheme { MediaSettingsPreviewContent(MediaSettingsPreviewState.DEFAULT) } }

@Preview(name = "Checked connections", group = "Media settings", widthDp = 900, heightDp = 1600)
@Composable internal fun MediaSettingsCheckedPreview() { PaperTheme { MediaSettingsPreviewContent(MediaSettingsPreviewState.CHECKED) } }

@Preview(name = "Generating probes", group = "Media settings", widthDp = 390, heightDp = 1800)
@Composable internal fun MediaSettingsCheckingPreview() { PaperTheme { MediaSettingsPreviewContent(MediaSettingsPreviewState.CHECKING) } }

@Preview(name = "Connection errors", group = "Media settings", widthDp = 390, heightDp = 1600)
@Composable internal fun MediaSettingsErrorPreview() { PaperTheme { MediaSettingsPreviewContent(MediaSettingsPreviewState.ERROR) } }

@Preview(name = "Unsupported platform", group = "Media settings", widthDp = 390, heightDp = 1600)
@Composable internal fun MediaSettingsUnsupportedPreview() { PaperTheme { MediaSettingsPreviewContent(MediaSettingsPreviewState.UNSUPPORTED) } }

@Preview(name = "No providers", group = "Media settings", widthDp = 390, heightDp = 1400)
@Composable internal fun MediaSettingsEmptyPreview() { PaperTheme { MediaSettingsPreviewContent(MediaSettingsPreviewState.EMPTY) } }
