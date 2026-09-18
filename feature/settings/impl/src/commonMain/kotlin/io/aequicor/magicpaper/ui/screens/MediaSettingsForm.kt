package io.aequicor.magicpaper.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.aequicor.magicpaper.designsystem.*
import io.aequicor.magicpaper.domain.*

/** Rendering is independent of the saved draft and of the service that performs paid probes. */
@Composable
internal fun MediaSettingsForm(
    kind: MediaKind,
    selection: MediaModelSelection,
    savedSelection: MediaModelSelection?,
    profiles: List<LlmProfile>,
    status: MediaConnectionStatus?,
    supported: Boolean,
    saving: Boolean,
    onSelectionChange: (MediaModelSelection) -> Unit,
    onCheck: () -> Unit,
    onDisable: () -> Unit,
    draftError: @Composable () -> Unit = {},
    preview: @Composable (GeneratedMedia) -> Unit = {},
) {
    val spacing = LocalPaperSpacing.current
    val current = selection.copy(modelId = selection.modelId.trim(), baseUrl = selection.baseUrl.trim()) == savedSelection
    val checking = current && status?.availability == MediaAvailability.CHECKING
    PaperPanel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(spacing.md), verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
            PaperText(if (kind == MediaKind.IMAGE) "Создание изображений" else "Создание видео", role = PaperTextRole.TITLE)
            draftError()
            PaperText("Подключение", role = PaperTextRole.LABEL)
            if (profiles.isEmpty()) PaperText("Добавьте поставщика в разделе ниже.")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(spacing.xs), verticalArrangement = Arrangement.spacedBy(spacing.xxs)) {
                profiles.forEach { profile ->
                    PaperChoice(selection.profileId == profile.id, {
                        val protocol = if (kind == MediaKind.VIDEO) MediaProtocol.DASHSCOPE_VIDEO
                        else if (profile.baseUrl.contains(".aliyuncs.com")) MediaProtocol.DASHSCOPE_IMAGE else MediaProtocol.OPENAI_IMAGES
                        onSelectionChange(MediaModelSelection(profile.id, "", protocol, suggestedMediaBaseUrl(profile, protocol)))
                    }, profile.name, enabled = profile.enabled && supported)
                }
            }
            if (kind == MediaKind.IMAGE) FlowRow(
                horizontalArrangement = Arrangement.spacedBy(spacing.xs), verticalArrangement = Arrangement.spacedBy(spacing.xxs),
            ) {
                listOf(MediaProtocol.OPENAI_IMAGES to "OpenAI-совместимый", MediaProtocol.DASHSCOPE_IMAGE to "Alibaba Cloud").forEach { (protocol, title) ->
                    PaperChoice(selection.protocol == protocol, {
                        val url = profiles.firstOrNull { it.id == selection.profileId }?.let { suggestedMediaBaseUrl(it, protocol) }
                        onSelectionChange(selection.copy(protocol = protocol, baseUrl = url ?: selection.baseUrl))
                    }, title, enabled = supported)
                }
            }
            PaperInput(selection.modelId, { onSelectionChange(selection.copy(modelId = it)) }, Modifier.fillMaxWidth(),
                label = { PaperText("Модель") }, singleLine = true, enabled = supported)
            val suggestions = profiles.firstOrNull { it.id == selection.profileId }?.modelCatalog.orEmpty().map { it.id }.filter {
                if (kind == MediaKind.IMAGE) it.contains("image", true) || it.contains("t2i", true) else it.contains("t2v", true)
            }.take(8)
            if (suggestions.isNotEmpty()) FlowRow(
                horizontalArrangement = Arrangement.spacedBy(spacing.xs), verticalArrangement = Arrangement.spacedBy(spacing.xxs),
            ) {
                suggestions.forEach { id ->
                    PaperChoice(selection.modelId == id, { onSelectionChange(selection.copy(modelId = id)) }, id, enabled = supported)
                }
            }
            PaperInput(selection.baseUrl, { onSelectionChange(selection.copy(baseUrl = it)) }, Modifier.fillMaxWidth(),
                label = { PaperText("Адрес API генерации") }, singleLine = true, enabled = supported)
            PaperText(if (kind == MediaKind.IMAGE) "Проверка создаст одно изображение. Оплачивается по тарифу API."
                else "Проверка создаст видео длительностью ${selection.copy(modelId = selection.modelId.trim()).minimumProbeVideoDurationSeconds()} с. Оплачивается по тарифу API.", role = PaperTextRole.LABEL)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(spacing.xs), verticalArrangement = Arrangement.spacedBy(spacing.xxs)) {
                PaperButton(if (checking) "Проверяем…" else "Сохранить и проверить", onCheck,
                    enabled = supported && !saving && !checking && selection.profileId.isNotBlank() && selection.modelId.isNotBlank() && selection.baseUrl.isNotBlank(),
                    busy = checking, maxLines = 2)
                if (savedSelection != null) PaperButton("Отключить", onDisable, kind = PaperButtonKind.QUIET, enabled = !saving)
            }
            if (current) {
                val label = when (status?.availability) {
                    MediaAvailability.AVAILABLE -> "Подключение работает"
                    MediaAvailability.CHECKING -> "Создаётся пробный результат…"
                    MediaAvailability.UNAVAILABLE -> status.message.ifBlank { "Подключение недоступно" }
                    else -> "Подключение не проверено"
                }
                PaperText(label, color = if (status?.availability == MediaAvailability.UNAVAILABLE) LocalPaperColors.current.error else LocalPaperColors.current.secondaryText)
                status?.preview?.let { preview(it) }
            }
        }
    }
}
