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
                        "Кодинг-агент работает с OpenAI-совместимыми серверами.",
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
                    val incompatible = profile.provider != ProviderType.OPENAI_COMPATIBLE
                    ProfileRow(
                        profile = profile,
                        selected = profile.id == resolvedId,
                        isMain = profile.id == activeProfileId,
                        onClick = { vm.selectCodingProfile(sessionId, profile.id) },
                        onMakeMain = { vm.setActiveProfile(profile.id) },
                        note = if (incompatible) "⚠ не подходит: агент принимает только OpenAI-совместимые" else null,
                    )
                    if (sessionProfileId == profile.id) {
                        TextButton(onClick = { vm.selectCodingProfile(sessionId, null) }) {
                            Text("◌ Снять переопределение сессии")
                        }
                    }
                }

                // Избранные модели и усилие выбранного профиля.
                val resolved = profiles.firstOrNull { it.id == resolvedId }
                if (resolved != null) {
                    Spacer(Modifier.height(6.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Модель",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FavoriteModelsSection(
                        profile = resolved,
                        onPick = { modelId ->
                            if (resolved.id != resolvedId) vm.selectCodingProfile(sessionId, resolved.id)
                            vm.setProfileModel(resolved.id, modelId)
                        },
                        onEditSource = { vm.editLlmProfile(resolved.id) },
                    )
                    Spacer(Modifier.height(8.dp))
                    val effortSupported = ModelDefaults.supportsEffort(resolved)
                    Text(
                        if (effortSupported) "Усилие модели" else "Усилие (температурный режим — модель без нативного усилия)",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    EffortControl(effort = resolved.effort, onEffort = { vm.setProfileEffort(resolved.id, it) })
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
