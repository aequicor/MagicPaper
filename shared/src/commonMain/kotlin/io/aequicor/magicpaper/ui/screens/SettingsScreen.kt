package io.aequicor.magicpaper.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.aequicor.magicpaper.domain.AppSettings
import io.aequicor.magicpaper.domain.SearchProvider
import io.aequicor.magicpaper.ui.MagicPaperViewModel

/** Экран настроек: модель, поиск, профиль. Черновик редактируется локально. */
@Composable
fun SettingsScreen(vm: MagicPaperViewModel, settings: AppSettings, storageInfo: String) {
    var draft by remember(settings) { mutableStateOf(settings) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text("Настройки", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(12.dp))

        Section("Магический источник (модель)")
        Field("Base URL (OpenAI-совместимый)", settings.llmBaseUrl) {
            draft = draft.copy(llmBaseUrl = it)
        }
        Field("API-ключ (пусто для локальных серверов)", settings.llmApiKey) {
            draft = draft.copy(llmApiKey = it)
        }
        Field("Имя модели", settings.llmModel) { draft = draft.copy(llmModel = it) }

        Spacer(Modifier.height(12.dp))
        Section("Поисковый движок")
        SearchProviderPicker(draft.searchProvider) { draft = draft.copy(searchProvider = it) }
        Field("Querit API-ключ", settings.queritApiKey) { draft = draft.copy(queritApiKey = it) }
        Field("Google API-ключ", settings.googleApiKey) { draft = draft.copy(googleApiKey = it) }
        Field("Google Search Engine ID", settings.googleSearchEngineId) {
            draft = draft.copy(googleSearchEngineId = it)
        }

        Spacer(Modifier.height(16.dp))
        TextButton(onClick = { vm.saveSettings(draft) }) { Text("Сохранить настройки") }

        Spacer(Modifier.height(20.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(Modifier.height(12.dp))

        Section("Профиль и данные")
        Text(
            "Хранилище: $storageInfo. Приложение не требует прав администратора; " +
                "все данные лежат в папке пользователя и удаляются вместе с программой.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { vm.exportProfile() }) { Text("Экспорт профиля") }
            TextButton(onClick = { vm.importProfile() }) { Text("Импорт профиля") }
            TextButton(onClick = { vm.wipeAll() }) { Text("Стереть всё") }
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
    )
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun Field(label: String, value: String, onChange: (String) -> Unit) {
    var text by remember(value) { mutableStateOf(value) }
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            onChange(it)
        },
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        label = { Text(label) },
        singleLine = true,
    )
}

@Composable
private fun SearchProviderPicker(selected: SearchProvider, onSelect: (SearchProvider) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        SearchProvider.entries.forEach { provider ->
            val label = when (provider) {
                SearchProvider.AUTO -> "Авто"
                SearchProvider.WIKIPEDIA -> "Wikipedia"
                SearchProvider.QUERIT -> "Querit"
                SearchProvider.GOOGLE -> "Google"
            }
            TextButton(onClick = { onSelect(provider) }) {
                Text(
                    label,
                    color = if (provider == selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}
