package io.aequicor.magicpaper.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import io.aequicor.magicpaper.domain.EffortLevel
import io.aequicor.magicpaper.domain.LlmProfile
import io.aequicor.magicpaper.domain.ProviderCatalog
import io.aequicor.magicpaper.domain.glyph
import io.aequicor.magicpaper.domain.title
import io.aequicor.magicpaper.ui.MagicPaperViewModel

/**
 * Переключатель модели: отдельная кнопка в чате открывает этот список.
 * Выбор профиля — переопределение для текущего свитка; «сделать основным» —
 * глобальный выбор; усилие меняется прямо здесь.
 */
@Composable
fun ModelSwitcherDialog(
    vm: MagicPaperViewModel,
    profiles: List<LlmProfile>,
    activeProfileId: String,
    sessionProfileId: String?,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.widthIn(max = 400.dp).heightIn(max = 520.dp),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 4.dp,
        ) {
            ModelSwitcherContent(vm, profiles, activeProfileId, sessionProfileId, onDismiss)
        }
    }
}

/** Общее содержимое переключателя (используется в диалоге; пригодится и для Popup). */
@Composable
fun ModelSwitcherContent(
    vm: MagicPaperViewModel,
    profiles: List<LlmProfile>,
    activeProfileId: String,
    sessionProfileId: String?,
    onDismiss: () -> Unit,
) {
    // Разрешённый профиль: переопределение свитка важнее глобального.
    val resolvedId = sessionProfileId ?: activeProfileId
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text("Магический источник", style = MaterialTheme.typography.titleMedium)
        Text(
            "Выбор действует для этого свитка; «основной» — для всех свитков.",
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
            val selected = profile.id == resolvedId
            val isMain = profile.id == activeProfileId
            val overriddenHere = sessionProfileId == profile.id
            ProfileRow(
                profile = profile,
                selected = selected,
                isMain = isMain,
                onClick = { vm.selectChatProfile(profile.id) },
                onMakeMain = { vm.setActiveProfile(profile.id) },
            )
            if (overriddenHere) {
                TextButton(onClick = { vm.selectChatProfile(null) }) {
                    Text("◌ Снять переопределение свитка")
                }
            }
        }

        // Усилие выбранного профиля — быстрая крутилка.
        val resolved = profiles.firstOrNull { it.id == resolvedId }
        if (resolved != null) {
            Spacer(Modifier.height(6.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(8.dp))
            val effortSupported = ProviderCatalog.supportsEffort(resolved)
            Text(
                if (effortSupported) "Усилие модели" else "Усилие (температурный режим — модель без нативного усилия)",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                EffortLevel.entries.forEach { level ->
                    TextButton(onClick = { vm.setProfileEffort(resolved.id, level) }) {
                        Text(
                            level.title,
                            color = if (resolved.effort == level) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
            }
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

/** Строка профиля: маркер выбора, имя·модель, усилие, кнопка «основной». */
@Composable
private fun ProfileRow(
    profile: LlmProfile,
    selected: Boolean,
    isMain: Boolean,
    onClick: () -> Unit,
    onMakeMain: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (selected) "◉" else "○",
            style = MaterialTheme.typography.titleMedium,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(profile.shortLabel, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
            Text(
                buildString {
                    if (isMain) append("основной")
                    if (!profile.configured) append(if (isNotEmpty()) " · не настроен" else "не настроен")
                }.ifBlank { " " },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            profile.effort.glyph,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(6.dp))
        TextButton(onClick = onMakeMain, enabled = !isMain) { Text("основной") }
    }
}
