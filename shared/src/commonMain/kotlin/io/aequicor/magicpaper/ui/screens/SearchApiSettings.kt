package io.aequicor.magicpaper.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import io.aequicor.magicpaper.domain.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun SearchProviderPicker(selected: SearchProvider, onSelect: (SearchProvider) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SearchProvider.entries.forEach { provider ->
            FilterChip(selected = selected == provider, onClick = { onSelect(provider) }, label = {
                Text(when (provider) {
                    SearchProvider.AUTO -> "Авто"
                    SearchProvider.WIKIPEDIA -> "Wikipedia"
                    SearchProvider.QUERIT -> "Querit"
                    SearchProvider.GOOGLE -> "Google"
                })
            })
        }
    }
}

/** One compact editor shared by settings and onboarding. */
@Composable
internal fun SearchApiSettings(
    draft: AppSettings,
    check: suspend (SearchConnection, AppSettings) -> SearchConnectionResult,
    onDraft: (AppSettings) -> Unit,
) {
    Column(Modifier.widthIn(max = 720.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SearchProviderPicker(draft.searchProvider) { onDraft(draft.copy(searchProvider = it)) }
        Text(when (draft.searchProvider) {
            SearchProvider.AUTO -> "Настроенные Google → Querit → Wikipedia. При ошибке или пустом ответе поиск продолжится в следующем движке."
            SearchProvider.WIKIPEDIA -> "Поиск по Wikipedia. Работает без ключа и платных запросов."
            SearchProvider.QUERIT -> "Поиск в интернете через Querit. Можно дополнить результаты текстом страниц."
            SearchProvider.GOOGLE -> "Поиск Google через Programmable Search. Нужны API-ключ и ID поискового движка."
        }, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

        if (draft.searchProvider == SearchProvider.AUTO || draft.searchProvider == SearchProvider.QUERIT) {
            ConnectionCard("Querit Search", if (draft.queritApiKey.isBlank()) "Ключ не задан" else "Ключ задан",
                collapsible = draft.searchProvider == SearchProvider.AUTO) {
                ApiKeyField(draft.queritApiKey) { onDraft(draft.copy(queritApiKey = it)) }
                ServerAddress(draft.queritBaseUrl) { onDraft(draft.copy(queritBaseUrl = it)) }
                SearchOption("Webpage Text", "Добавлять текст в выдачу Querit. Использует тот же ключ.", draft.queritWebpageTextEnabled) {
                    onDraft(draft.copy(queritWebpageTextEnabled = it))
                }
                ConnectionCheck(SearchConnection.QUERIT, draft, check)
            }
        }
        if (draft.searchProvider == SearchProvider.AUTO || draft.searchProvider == SearchProvider.GOOGLE) {
            ConnectionCard("Google Search", if (draft.googleApiKey.isNotBlank() && draft.googleSearchEngineId.isNotBlank()) "Параметры заданы" else "Нужны ключ и ID",
                collapsible = draft.searchProvider == SearchProvider.AUTO) {
                ApiKeyField(draft.googleApiKey) { onDraft(draft.copy(googleApiKey = it)) }
                Field("Search Engine ID", draft.googleSearchEngineId) { onDraft(draft.copy(googleSearchEngineId = it)) }
                ServerAddress(draft.googleSearchUrl) { onDraft(draft.copy(googleSearchUrl = it)) }
                ConnectionCheck(SearchConnection.GOOGLE, draft, check)
            }
        }
        if (draft.searchProvider == SearchProvider.WIKIPEDIA || draft.searchProvider == SearchProvider.AUTO) {
            ConnectionCard("Wikipedia", "Без ключа", collapsible = draft.searchProvider == SearchProvider.AUTO) {
                ConnectionCheck(SearchConnection.WIKIPEDIA, draft, check)
            }
        }
        OutlinedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SearchOption("Чтение страниц", "Content API · дополняет результаты любого движка.", draft.queritContentEnabled) {
                    onDraft(draft.copy(queritContentEnabled = it))
                }
                if (draft.queritContentEnabled) {
                    HorizontalDivider()
                    Text("Отдельное подключение Querit. Чтение страниц расходует запросы API.", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    ApiKeyField(draft.queritContentApiKey) { onDraft(draft.copy(queritContentApiKey = it)) }
                    ServerAddress(draft.queritContentBaseUrl) { onDraft(draft.copy(queritContentBaseUrl = it)) }
                    ConnectionCheck(SearchConnection.CONTENT, draft, check)
                }
            }
        }
    }
}

@Composable
private fun ConnectionCard(title: String, summary: String, collapsible: Boolean, content: @Composable ColumnScope.() -> Unit) {
    var expanded by remember(collapsible) { mutableStateOf(!collapsible) }
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (collapsible) {
                TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(0.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
                        Text(title, style = MaterialTheme.typography.titleMedium)
                        Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(if (expanded) "Свернуть ↑" else "Настроить ↓")
                }
            } else Text(title, style = MaterialTheme.typography.titleMedium)
            if (expanded) content()
        }
    }
}

@Composable
private fun ApiKeyField(value: String, onChange: (String) -> Unit) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(value, onChange, label = { Text("API-ключ") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = { TextButton(onClick = { visible = !visible }) { Text(if (visible) "Скрыть" else "Показать") } })
}

@Composable
private fun ServerAddress(value: String, onChange: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(0.dp)) {
        Text(if (expanded) "Скрыть адрес сервера ↑" else "Адрес сервера ↓")
    }
    if (expanded) Field("Хост / URL API", value) { onChange(it) }
}

@Composable
private fun SearchOption(title: String, description: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().toggleable(checked, role = Role.Switch, onValueChange = onChange).padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, onCheckedChange = null)
    }
}

@Composable
private fun ConnectionCheck(connection: SearchConnection, draft: AppSettings, check: suspend (SearchConnection, AppSettings) -> SearchConnectionResult) {
    // Edits dispose the old scope: a result for previous credentials can never label a new draft.
    key(connection, draft) {
        val scope = rememberCoroutineScope()
        var running by remember { mutableStateOf(false) }
        var result by remember { mutableStateOf<SearchConnectionResult?>(null) }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = !running, onClick = {
                running = true
                result = null
                scope.launch {
                    try { result = check(connection, draft) }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { result = SearchConnectionResult(false, "Не удалось проверить подключение. Повторите попытку.") }
                    finally { running = false }
                }
            }) {
                if (running) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(if (running) "Проверяем…" else "Проверить подключение")
            }
            result?.let {
                Text((if (it.success) "✓ " else "! ") + it.message, style = MaterialTheme.typography.bodyMedium,
                    color = if (it.success) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
            }
            Text(if (connection == SearchConnection.CONTENT) "Проверка читает example.com · 1 запрос · без сохранения настроек"
                else "Тестовый поиск · 1 запрос · без сохранения настроек", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
