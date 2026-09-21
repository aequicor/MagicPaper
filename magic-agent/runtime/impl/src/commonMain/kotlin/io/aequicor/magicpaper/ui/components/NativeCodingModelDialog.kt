package io.aequicor.magicpaper.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.aequicor.magicpaper.designsystem.*
import io.aequicor.magicpaper.domain.CodingEngine
import io.aequicor.magicpaper.domain.CodingModel
import io.aequicor.magicpaper.domain.CodingModelResolution
import io.aequicor.magicpaper.domain.CodingModelSelection
import io.aequicor.magicpaper.domain.CodingModelSnapshot

/**
 * Выбор модели из каталога самого движка: имена, уровни и умолчание показываются как объявил
 * движок. Диалог не хранит состояния: выбор, снимок и признак опроса приходят снаружи.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NativeCodingModelDialog(
    engine: CodingEngine,
    snapshot: CodingModelSnapshot?,
    selection: CodingModelSelection?,
    refreshing: Boolean,
    onSelect: (CodingModelSelection) -> Unit,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
    footer: @Composable () -> Unit = {},
) {
    val models = snapshot?.models.orEmpty()
    val current = selection?.let { snapshot?.resolve(it) }
    val currentModel = when (current) {
        is CodingModelResolution.Available -> current.model
        is CodingModelResolution.LevelUnsupported -> current.model
        else -> null
    }
    PaperDialog(title = "Модель · ${engine.title}", onDismissRequest = onDismiss,
        modifier = Modifier.widthIn(max = 480.dp).heightIn(max = 600.dp)) {
        PaperScrollColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (models.isEmpty()) {
                PaperText(if (refreshing) "Загружаем список моделей…" else "Список моделей движка пока не загружен.")
            }
            models.forEach { model ->
                val chosen = selection?.provider == model.provider && selection.modelId == model.id
                PaperAction(onClick = { onSelect(selection.takeIf { chosen } ?: CodingModelSelection(engine, model.provider, model.id)) },
                    modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        PaperText("${if (chosen) "● " else ""}${model.name}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        PaperText(model.details(), role = PaperTextRole.LABEL, color = LocalPaperColors.current.secondaryText)
                    }
                }
            }
            when (current) {
                CodingModelResolution.ModelMissing -> PaperText(
                    "Сохранённой модели «${selection?.modelId}» нет в каталоге движка. Выберите другую или обновите список.",
                    role = PaperTextRole.LABEL, color = LocalPaperColors.current.secondaryText)
                is CodingModelResolution.LevelUnsupported -> PaperText(
                    "Модель ${current.model.name} не поддерживает уровень «${current.level}». Выберите один из доступных.",
                    role = PaperTextRole.LABEL, color = LocalPaperColors.current.secondaryText)
                else -> Unit
            }
            if (selection != null && currentModel != null && currentModel.supportsLevels) {
                PaperDivider()
                NativeLevelControl(currentModel, selection.level) { onSelect(selection.copy(level = it)) }
            }
            footer()
            PaperAction(onClick = onRefresh, enabled = !refreshing, modifier = Modifier.fillMaxWidth()) {
                PaperText(if (refreshing) "Обновляем список…" else "Обновить список моделей")
            }
            PaperAction(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { PaperText("Готово") }
        }
    }
}

/** Уровни ровно те, что объявил движок для модели; первый чип — его собственное умолчание. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun NativeLevelControl(model: CodingModel, level: String?, onSelect: (String?) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        PaperText("Уровень: ${model.levelLabel(level)}", role = PaperTextRole.LABEL, color = LocalPaperColors.current.secondaryText)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            PaperChoice(selected = level == null, onSelect = { onSelect(null) },
                label = model.levelLabel(null).orEmpty(), modifier = Modifier.padding(0.dp))
            model.levels.forEach { candidate ->
                PaperChoice(selected = level == candidate, onSelect = { onSelect(candidate) },
                    label = candidate, modifier = Modifier.padding(0.dp))
            }
        }
    }
}

private fun CodingModel.details(): String = listOfNotNull(
    provider,
    contextWindow?.let { "контекст ${it / 1000}K" },
    "изображения".takeIf { acceptsImages },
).joinToString(" · ")

/** Чип модели в композере: имя модели каталога и её уровень (реальное умолчание, если ничего не выбрано). */
@Composable
fun NativeCodingModelChip(selection: CodingModelSelection?, snapshot: CodingModelSnapshot?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val model = selection?.let { snapshot?.find(it.provider, it.modelId) }
    PaperAction(onClick, modifier.heightIn(min = 32.dp), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            PaperText(selection?.let { model?.name ?: it.modelId } ?: "Выбрать модель",
                role = PaperTextRole.CHROME, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val level = if (model != null) model.levelLabel(selection.level) else selection?.level
            if (level != null) {
                PaperText(level, role = PaperTextRole.CHROME, color = LocalPaperColors.current.secondaryText,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
