package io.aequicor.magicpaper.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import io.aequicor.magicpaper.domain.LlmProfile
import io.aequicor.magicpaper.domain.ModelDefaults
import io.aequicor.magicpaper.domain.ProviderType
import io.aequicor.magicpaper.ui.MagicPaperViewModel

/**
 * Чип текущей модели кодинг-сессии в композиции: тап открывает переключатель.
 * Показывает кодинг-контур профиля: имя источника, его coding-модель и усилие.
 */
@Composable
fun CodingModelChip(
    profile: LlmProfile?,
    overridden: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TextButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 40.dp),
    ) {
        if (profile == null) {
            Text(
                "✦ Источник не подключён",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        } else {
            val model = profile.codingModel
            val capability = ModelDefaults.capability(profile, model)
            val effort = profile.effortLabel(capability, model)
            Text(
                "${if (overridden) "◌ " else ""}✦ ${profile.name} · $model · $effort ▾",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
            )
        }
    }
}

/**
 * Переключатель источника кодинг-агента: выбор действует для конкретной
 * кодинг-сессии; «основной» — глобально; усилие меняется прямо здесь.
 */
@Composable
fun CodingModelSwitcherDialog(
    vm: MagicPaperViewModel,
    sessionId: String,
    profiles: List<LlmProfile>,
    activeProfileId: String,
    sessionProfileId: String?,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.widthIn(max = 420.dp).heightIn(max = 560.dp),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 4.dp,
        ) {
            // Разрешённый профиль: переопределение сессии важнее глобального.
            val resolvedId = sessionProfileId ?: activeProfileId
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            ) {
                Text("Источник агента", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Выбор действует для этой кодинг-сессии; «основной» — для всех. " +
                        "На desktop кодинг-агент работает с OpenAI-совместимыми серверами и подпиской ChatGPT.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))

                if (profiles.isEmpty()) {
                    Text(
                        "Источники не подключены. Добавьте провайдера в настройках.",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = { vm.open(io.aequicor.magicpaper.ui.Screen.SETTINGS); onDismiss() }) {
                        Text("Открыть настройки")
                    }
                }

                profiles.forEach { profile ->
                    val incompatible = profile.provider != ProviderType.OPENAI_COMPATIBLE &&
                        profile.provider != ProviderType.OPENAI_SUBSCRIPTION
                    ProfileRow(
                        profile = profile,
                        selected = profile.id == resolvedId,
                        isMain = profile.id == activeProfileId,
                        onClick = { vm.selectCodingProfile(sessionId, profile.id) },
                        onMakeMain = { vm.setActiveProfile(profile.id) },
                        note = if (incompatible) "⚠ не подходит для desktop coding-агента" else null,
                    )
                    if (sessionProfileId == profile.id) {
                        TextButton(onClick = { vm.selectCodingProfile(sessionId, null) }) {
                            Text("◌ Снять переопределение сессии")
                        }
                    }
                }

                // Избранные модели и усилие выбранного профиля (кодинг-контур).
                val resolved = profiles.firstOrNull { it.id == resolvedId }
                if (resolved != null) {
                    Spacer(Modifier.height(6.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Spacer(Modifier.height(8.dp))
                    val codingModel = resolved.codingModel
                    Text(
                        "Модель агента",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "Выбор действует только на кодинг-сессии; чат сохраняет свою модель.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FavoriteModelsSection(
                        profile = resolved,
                        currentModel = codingModel,
                        onPick = { modelId ->
                            if (resolved.id != resolvedId) vm.selectCodingProfile(sessionId, resolved.id)
                            vm.setProfileCodingModel(resolved.id, modelId)
                        },
                        onEditSource = { vm.editLlmProfile(resolved.id) },
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Усилие модели",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    EffortControl(
                        capability = ModelDefaults.capability(resolved, codingModel),
                        selection = resolved.effortSelectionFor(codingModel),
                        onSelect = { vm.setProfileEffort(resolved.id, it, codingModel) },
                    )
                    TextButton(onClick = { vm.editLlmProfile(resolved.id) }) {
                        Text("⚙ Тонкие настройки источника…")
                    }
                }

                Spacer(Modifier.height(4.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Готово") }
                }
            }
        }
    }
}
