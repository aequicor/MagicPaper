package io.aequicor.magicpaper.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.domain.AdvancedLlmOptions
import io.aequicor.magicpaper.domain.AppSettings
import io.aequicor.magicpaper.domain.EffortSelection
import io.aequicor.magicpaper.domain.LlmProfile
import io.aequicor.magicpaper.domain.ModelDefaults
import io.aequicor.magicpaper.domain.ModelDefaults.DiscoveredModel
import io.aequicor.magicpaper.domain.ProviderCatalog
import io.aequicor.magicpaper.domain.ProviderSpec
import io.aequicor.magicpaper.domain.ProviderType
import io.aequicor.magicpaper.domain.SearchProvider
import io.aequicor.magicpaper.ui.MagicPaperViewModel
import io.aequicor.magicpaper.ui.components.EffortControl
import io.aequicor.magicpaper.ui.Screen
import io.aequicor.magicpaper.ui.UiState
import io.aequicor.magicpaper.util.Id

/** Экран настроек: хаб разделов + источники, поиск, профиль. Черновик редактируется локально. */
@Composable
fun SettingsScreen(vm: MagicPaperViewModel, state: UiState) {
    val settings = state.settings
    var draft by remember(settings) { mutableStateOf(settings) }
    val editing = state.editingLlmProfileId

    // Открыт редактор профиля — показываем его вместо общего списка.
    if (editing != null) {
        val profile = state.llmProfiles.firstOrNull { it.id == editing }
            ?: LlmProfile(id = editing, name = "Новый источник", createdAt = Id.now())
        ProfileEditor(vm, profile, state)
        return
    }

    if (state.modelsSettingsOpen) { ModelsSettings(vm, state); return }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text("Настройки", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(12.dp))

        // ---- Разделы: сюда переехали кнопки навигации из шапки ----
        Section("Разделы")
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            NavEntry("✦", "Чат", "Лента свитка и поле заклинаний") { vm.open(Screen.CHAT) }
            NavEntry("⌘", "Проекты и код", "Кодинг-агент работает в папке проекта") { vm.open(Screen.CODING) }
            NavEntry("∑", "Плагины", "Панели и переключатели расширений") { vm.open(Screen.PLUGINS) }
            NavEntry("◷", "Справка", "Документация с живым поиском") { vm.open(Screen.DOCS) }
            NavEntry("✦", "Первый запуск", "Пройти ознакомительный тур заново") { vm.restartOnboarding() }
        }

        Spacer(Modifier.height(16.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(Modifier.height(16.dp))

        Section("Оформление")
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
                .toggleable(
                    value = draft.paperAnimationEnabled,
                    role = Role.Switch,
                    onValueChange = { draft = draft.copy(paperAnimationEnabled = it) },
                ).padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text("Анимация магической бумаги", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Живой фон чата и проектов на мощных ПК и телефонах. " +
                        "При заряде 20% и ниже анимация приостанавливается автоматически. " +
                        "На Android также учитывается энергосбережение. " +
                        "На неподдерживаемых устройствах и в браузере — статичная бумага.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = draft.paperAnimationEnabled, onCheckedChange = null)
        }
        Spacer(Modifier.height(12.dp))

        NavEntry("✦", "Модели", "По умолчанию, избранное и поставщики") { vm.openModelsSettings() }

        Spacer(Modifier.height(12.dp))
        Section("Поисковый движок")
        SearchProviderPicker(draft.searchProvider) { draft = draft.copy(searchProvider = it) }
        Field("Querit API-ключ", draft.queritApiKey) { draft = draft.copy(queritApiKey = it) }
        Field("Google API-ключ", draft.googleApiKey) { draft = draft.copy(googleApiKey = it) }
        Field("Google Search Engine ID", draft.googleSearchEngineId) {
            draft = draft.copy(googleSearchEngineId = it)
        }

        Spacer(Modifier.height(16.dp))
        TextButton(
            onClick = { vm.saveSettings(draft) },
            modifier = Modifier.heightIn(min = 48.dp),
        ) { Text("Сохранить настройки") }
        Spacer(Modifier.height(20.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(Modifier.height(12.dp))

        Section("Профиль и данные")
        Text(
            "Хранилище: ${state.storageInfo}. Приложение не требует прав администратора; " +
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

/**
 * Редактор профиля подключения: провайдер из каталога → ключ → модель → усилие → тонкие настройки.
 * Черновик живёт локально; сохранение — одним действием через [MagicPaperViewModel.saveLlmProfile].
 */
@Composable
fun ProfileEditor(vm: MagicPaperViewModel, profile: LlmProfile, state: UiState) {
    var draft by remember(profile.id) { mutableStateOf(profile) }
    // Конкретное каталожное описание: у одного типа может быть несколько записей
    // (например, несколько OpenAI-совместимых провайдеров), поэтому помним по имени.
    var specName by remember(profile.id) {
        val initial = ProviderCatalog.all.firstOrNull {
            it.type == draft.provider && (it.defaultBaseUrl.isBlank() || it.defaultBaseUrl == draft.baseUrl)
        } ?: ProviderCatalog.all.firstOrNull { it.type == draft.provider }
        mutableStateOf(initial?.displayName.orEmpty())
    }
    val spec = ProviderCatalog.all.firstOrNull { it.displayName == specName }
    var chooseProvider by remember { mutableStateOf(!profile.connectionConfigured) }
    val subscription = draft.provider == ProviderType.OPENAI_SUBSCRIPTION
    val subscriptionAvailable = state.openAiSubscription.available
    val uriHandler = LocalUriHandler.current
    LaunchedEffect(state.openAiSubscription.login?.url) {
        state.openAiSubscription.login?.url?.let(uriHandler::openUri)
    }
    LaunchedEffect(subscription) {
        if (subscription && state.openAiSubscription.account == null && state.openAiSubscription.error == null) {
            vm.refreshOpenAiSubscription()
        }
    }
    LaunchedEffect(state.editorModels) {
        if (state.editorModels.isNotEmpty() && state.editorModelsFor == "${draft.id}:${draft.provider}:${draft.baseUrl}") draft = draft.withCatalog(state.editorModels)
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text("Магический источник", style = MaterialTheme.typography.titleLarge)
        Text(
            if (profile.configured) "Правка подключения «${profile.name}»" else "Новое подключение",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))

        Section("Провайдер")
        TextButton(onClick = { chooseProvider = !chooseProvider }) { Text("${specName.ifBlank { "Выбрать поставщика" }} ▾") }
        if (chooseProvider) ProviderCatalog.all.forEach { candidate ->
            val enabled = (!candidate.desktopOnly || subscriptionAvailable) && !state.editorModelsLoading
            ProviderRow(
                spec = candidate,
                selected = candidate.displayName == specName,
                enabled = enabled,
                onClick = {
                    chooseProvider = false
                    specName = candidate.displayName
                    draft = draft.copy(
                        provider = candidate.type,
                        name = candidate.displayName,
                        baseUrl = if (candidate.usesSubscription) "" else candidate.defaultBaseUrl.ifBlank { draft.baseUrl },
                        apiKey = if (candidate.usesSubscription) "" else draft.apiKey,
                        modelId = candidate.models.firstOrNull()?.id.orEmpty().ifBlank { draft.modelId },
                    )
                },
            )
        }
        Spacer(Modifier.height(10.dp))

        Field("Название источника", draft.name) { draft = draft.copy(name = it) }
        if (subscription) {
            SubscriptionAccount(vm, state)
        } else {
            Field("Base URL", draft.baseUrl) { draft = draft.copy(baseUrl = it) }
            Field("API-ключ (${spec?.keyHint ?: "пусто для локальных серверов"})", draft.apiKey) {
                draft = draft.copy(apiKey = it)
            }
        }

        Spacer(Modifier.height(10.dp))
        TextButton(onClick = { vm.fetchModels(draft) }, enabled = !state.editorModelsLoading && draft.connectionConfigured) {
            Text(if (state.editorModelsLoading) "Загрузка…" else "Получить модели")
        }
        state.editorModelsError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Text("Моделей: ${draft.modelCatalog.size}", style = MaterialTheme.typography.bodySmall)
        if (draft.modelCatalog.isEmpty()) Field("Модель вручную (если каталога нет)", draft.modelId) { draft = draft.copy(modelId = it) }
        TextButton(onClick = {
            val first = draft.modelId.ifBlank { draft.modelCatalog.firstOrNull()?.id.orEmpty() }
            vm.saveLlmProfile(draft.copy(modelId = first, modelLibraryVersion = 1))
        }, enabled = draft.connectionConfigured && (!subscription || state.openAiSubscription.account?.signedIn == true)) { Text("Сохранить поставщика") }
        TextButton(onClick = vm::closeLlmProfileEditor) { Text("Отмена") }
    }

}

/** Строка выбора провайдера из каталога. */
@Composable
private fun ProviderRow(spec: ProviderSpec, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .clickable(enabled = enabled, onClick = onClick)
            .heightIn(min = 40.dp)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (selected) "◉" else "○",
            style = MaterialTheme.typography.bodyLarge,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(10.dp))
        Text(
            spec.displayName + if (!enabled) " · только desktop" else "",
            style = MaterialTheme.typography.bodyLarge,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
            modifier = Modifier.weight(1f),
        )
        if (spec.defaultBaseUrl.isNotBlank()) {
            Text(
                spec.defaultBaseUrl,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

/** Авторизация и квоты OpenAI-подписки. URL OAuth открывается вызывающим composable. */
@Composable
private fun SubscriptionAccount(vm: MagicPaperViewModel, state: UiState) {
    val auth = state.openAiSubscription
    val uriHandler = LocalUriHandler.current
    if (!auth.available) {
        Text(
            "OpenAI по подписке поддерживается только в desktop-приложении.",
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
        )
        return
    }
    val account = auth.account
    Text(
        when {
            auth.loading -> "Проверяю аккаунт…"
            account?.signedIn == true -> buildString {
                append("✓ ChatGPT")
                account.email?.let { append(" · ").append(it) }
                account.planType?.let { append(" · план ").append(it) }
            }
            else -> "Войдите в ChatGPT: запросы будут расходовать лимит вашей подписки, API-ключ не нужен."
        },
        style = MaterialTheme.typography.bodyMedium,
        color = if (account?.signedIn == true) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
    )
    account?.rateLimits?.forEach { limit ->
        Text(
            "${limit.name} · ${limit.window}: использовано ${limit.usedPercent}%" +
                (limit.resetsAtEpochSeconds?.let { " · сброс $it" } ?: ""),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    auth.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        when {
            auth.signingIn -> {
                TextButton(onClick = { auth.login?.url?.let(uriHandler::openUri) }) { Text("Открыть страницу входа") }
                TextButton(onClick = vm::cancelOpenAiSubscriptionLogin) { Text("Отмена") }
            }
            account?.signedIn == true -> {
                TextButton(onClick = { vm.refreshOpenAiSubscription(true) }) { Text("Обновить") }
                TextButton(onClick = vm::logoutOpenAiSubscription) { Text("Выйти") }
            }
            else -> TextButton(onClick = vm::startOpenAiSubscriptionLogin) { Text("Войти через ChatGPT") }
        }
    }
}

/** Строка-переход в раздел: иконка, заголовок, описание, «›» — как ListItem из M3. */
@Composable
private fun NavEntry(icon: String, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(icon, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text("›", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
internal fun Field(label: String, value: String, onChange: (String) -> Unit) {
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
internal fun SearchProviderPicker(selected: SearchProvider, onSelect: (SearchProvider) -> Unit) {
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
