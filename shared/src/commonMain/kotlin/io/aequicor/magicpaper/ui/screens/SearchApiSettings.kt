package io.aequicor.magicpaper.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import io.aequicor.magicpaper.designsystem.LocalPaperColors
import io.aequicor.magicpaper.designsystem.PaperButton
import io.aequicor.magicpaper.designsystem.PaperButtonKind
import io.aequicor.magicpaper.designsystem.PaperChoice
import io.aequicor.magicpaper.designsystem.PaperDivider
import io.aequicor.magicpaper.designsystem.PaperInput
import io.aequicor.magicpaper.designsystem.PaperPanel
import io.aequicor.magicpaper.designsystem.PaperProgress
import io.aequicor.magicpaper.designsystem.PaperProgressKind
import io.aequicor.magicpaper.designsystem.PaperSwitch
import io.aequicor.magicpaper.designsystem.PaperText
import io.aequicor.magicpaper.designsystem.PaperTextRole
import io.aequicor.magicpaper.domain.AppSettings
import io.aequicor.magicpaper.domain.SearchConnection
import io.aequicor.magicpaper.domain.SearchConnectionResult
import io.aequicor.magicpaper.domain.SearchProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun SearchProviderPicker(selected: SearchProvider, onSelect: (SearchProvider) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SearchProvider.entries.forEach { provider ->
            PaperChoice(selected == provider, { onSelect(provider) }, when (provider) {
                SearchProvider.AUTO -> "Авто"; SearchProvider.WIKIPEDIA -> "Wikipedia"; SearchProvider.QUERIT -> "Querit"; SearchProvider.GOOGLE -> "Google"
            })
        }
    }
}

/** One compact editor shared by settings and onboarding. */
@Composable
internal fun SearchApiSettings(draft: AppSettings, check: suspend (SearchConnection, AppSettings) -> SearchConnectionResult, onDraft: (AppSettings) -> Unit) {
    Column(Modifier.widthIn(max = 720.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SearchProviderPicker(draft.searchProvider) { onDraft(draft.copy(searchProvider = it)) }
        PaperText(when (draft.searchProvider) {
            SearchProvider.AUTO -> "Настроенные Google → Querit → Wikipedia. При ошибке или пустом ответе поиск продолжится в следующем движке."
            SearchProvider.WIKIPEDIA -> "Поиск по Wikipedia. Работает без ключа и платных запросов."
            SearchProvider.QUERIT -> "Поиск в интернете через Querit. Можно дополнить результаты текстом страниц."
            SearchProvider.GOOGLE -> "Поиск Google через Programmable Search. Нужны API-ключ и ID поискового движка."
        }, color = LocalPaperColors.current.secondaryText)
        if (draft.searchProvider == SearchProvider.AUTO || draft.searchProvider == SearchProvider.QUERIT) ConnectionCard("Querit Search", if (draft.queritApiKey.isBlank()) "Ключ не задан" else "Ключ задан", draft.searchProvider == SearchProvider.AUTO) {
            ApiKeyField(draft.queritApiKey) { onDraft(draft.copy(queritApiKey = it)) }; ServerAddress(draft.queritBaseUrl) { onDraft(draft.copy(queritBaseUrl = it)) }
            SearchOption("Webpage Text", "Добавлять текст в выдачу Querit. Использует тот же ключ.", draft.queritWebpageTextEnabled) { onDraft(draft.copy(queritWebpageTextEnabled = it)) }; ConnectionCheck(SearchConnection.QUERIT, draft, check)
        }
        if (draft.searchProvider == SearchProvider.AUTO || draft.searchProvider == SearchProvider.GOOGLE) ConnectionCard("Google Search", if (draft.googleApiKey.isNotBlank() && draft.googleSearchEngineId.isNotBlank()) "Параметры заданы" else "Нужны ключ и ID", draft.searchProvider == SearchProvider.AUTO) {
            ApiKeyField(draft.googleApiKey) { onDraft(draft.copy(googleApiKey = it)) }; Field("Search Engine ID", draft.googleSearchEngineId) { onDraft(draft.copy(googleSearchEngineId = it)) }; ServerAddress(draft.googleSearchUrl) { onDraft(draft.copy(googleSearchUrl = it)) }; ConnectionCheck(SearchConnection.GOOGLE, draft, check)
        }
        if (draft.searchProvider == SearchProvider.WIKIPEDIA || draft.searchProvider == SearchProvider.AUTO) ConnectionCard("Wikipedia", "Без ключа", draft.searchProvider == SearchProvider.AUTO) { ConnectionCheck(SearchConnection.WIKIPEDIA, draft, check) }
        PaperPanel(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SearchOption("Чтение страниц", "Content API · дополняет результаты любого движка.", draft.queritContentEnabled) { onDraft(draft.copy(queritContentEnabled = it)) }
            if (draft.queritContentEnabled) { PaperDivider(); PaperText("Отдельное подключение Querit. Чтение страниц расходует запросы API.", role = PaperTextRole.LABEL, color = LocalPaperColors.current.secondaryText); ApiKeyField(draft.queritContentApiKey) { onDraft(draft.copy(queritContentApiKey = it)) }; ServerAddress(draft.queritContentBaseUrl) { onDraft(draft.copy(queritContentBaseUrl = it)) }; ConnectionCheck(SearchConnection.CONTENT, draft, check) }
        } }
    }
}

@Composable private fun ConnectionCard(title: String, summary: String, collapsible: Boolean, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    var expanded by remember(collapsible) { mutableStateOf(!collapsible) }
    PaperPanel(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (collapsible) PaperButton("$title · $summary ${if (expanded) "↑" else "↓"}", { expanded = !expanded }, kind = PaperButtonKind.QUIET) else PaperText(title, role = PaperTextRole.TITLE)
        if (expanded) content()
    } }
}

@Composable private fun ApiKeyField(value: String, onChange: (String) -> Unit) {
    var visible by remember { mutableStateOf(false) }
    PaperInput(value, onChange, Modifier.fillMaxWidth(), label = { PaperText("API-ключ") }, singleLine = true, visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(), trailingIcon = { PaperButton(if (visible) "Скрыть" else "Показать", { visible = !visible }, kind = PaperButtonKind.QUIET) })
}

@Composable private fun ServerAddress(value: String, onChange: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    PaperButton(if (expanded) "Скрыть адрес сервера ↑" else "Адрес сервера ↓", { expanded = !expanded }, kind = PaperButtonKind.QUIET)
    if (expanded) Field("Хост / URL API", value, onChange = onChange)
}

@Composable private fun SearchOption(title: String, description: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { PaperText(title); PaperText(description, role = PaperTextRole.LABEL, color = LocalPaperColors.current.secondaryText) }
        PaperSwitch(checked, onChange, title)
    }
}

@Composable private fun ConnectionCheck(connection: SearchConnection, draft: AppSettings, check: suspend (SearchConnection, AppSettings) -> SearchConnectionResult) {
    key(connection, draft) {
        val scope = rememberCoroutineScope(); var running by remember { mutableStateOf(false) }; var result by remember { mutableStateOf<SearchConnectionResult?>(null) }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PaperButton(if (running) "Проверяем…" else "Проверить подключение", { running = true; result = null; scope.launch { try { result = check(connection, draft) } catch (e: CancellationException) { throw e } catch (_: Exception) { result = SearchConnectionResult(false, "Не удалось проверить подключение. Повторите попытку.") } finally { running = false } } }, enabled = !running, busy = running, kind = PaperButtonKind.SECONDARY)
            result?.let { PaperText((if (it.success) "✓ " else "! ") + it.message, color = if (it.success) LocalPaperColors.current.action else LocalPaperColors.current.error) }
            PaperText(if (connection == SearchConnection.CONTENT) "Проверка читает example.com · 1 запрос · без сохранения настроек" else "Тестовый поиск · 1 запрос · без сохранения настроек", role = PaperTextRole.LABEL, color = LocalPaperColors.current.secondaryText)
        }
    }
}
