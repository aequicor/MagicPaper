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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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

    if (state.enginesSettingsOpen) { EnginesSettings(vm, state); return }

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
                .clip(MaterialTheme.shapes.small)
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
                    "Живой фон всего окна, включая тулбар, на мощных ПК и телефонах. " +
                        "При заряде 20% и ниже анимация приостанавливается автоматически. " +
                        "На Android также учитывается энергосбережение. " +
                        "На неподдерживаемых устройствах и в браузере — статичная бумага.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = draft.paperAnimationEnabled, onCheckedChange = null)
        }
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
                .clip(MaterialTheme.shapes.small)
                .toggleable(
                    value = draft.hideSystemSteps,
                    role = Role.Switch,
                    onValueChange = { draft = draft.copy(hideSystemSteps = it) },
                ).padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text("Скрывать системные шаги", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Скрывает служебные статусы агента. Ответы, рассуждения, действия с инструментами и ошибки остаются видимыми.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = draft.hideSystemSteps, onCheckedChange = null)
        }
        Spacer(Modifier.height(12.dp))

        NavEntry("✦", "Модели", "По умолчанию, избранное и поставщики") { vm.openModelsSettings() }
        NavEntry("⚙", "Движки", "pi, Codex и движок новых сессий") { vm.openEnginesSettings() }

        Spacer(Modifier.height(12.dp))
        Section("Поисковый движок")
        SearchApiSettings(draft, vm::checkSearchConnection) { draft = it }

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
    var modelQuery by remember(profile.id) { mutableStateOf("") }
    val uriHandler = LocalUriHandler.current
    LaunchedEffect(state.openAiSubscription.login?.url) {
        state.openAiSubscription.login?.url?.let(uriHandler::openUri)
    }
    LaunchedEffect(subscription) {
        if (subscription && state.openAiSubscription.account == null && state.openAiSubscription.error == null) {
            vm.refreshOpenAiSubscription()
        }
    }
    LaunchedEffect(subscription, state.openAiSubscription.account?.signedIn) {
        if (subscription && state.openAiSubscription.account?.signedIn == true && draft.modelCatalog.isEmpty()) {
            vm.fetchModels(draft)
        }
    }
    LaunchedEffect(state.editorModels, state.editorModelsFor, draft.id, draft.provider, draft.baseUrl) {
        if (state.editorModels.isNotEmpty() && state.editorModelsFor == "${draft.id}:${draft.provider}:${draft.baseUrl}") {
            val loaded = draft.withCatalog(state.editorModels)
            // The provider's catalog is authoritative. Do not leave a preset
            // model selected when the account cannot actually use it.
            draft = loaded.copy(modelId = draft.modelId.takeIf { id -> loaded.modelCatalog.any { it.id == id } }
                ?: loaded.modelCatalog.first().id)
        }
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
    Column(
        modifier = Modifier
            .widthIn(max = 720.dp)
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
    ) {
        Text(if (profile.configured) "Настройки подключения" else "Подключить модели", style = MaterialTheme.typography.titleLarge)
        Text(
            "Подключение → избранное → готово",
            style = MaterialTheme.typography.labelSmall,
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
                        modelCatalog = emptyList(), favoriteModels = emptyList(),
                        modelReasoning = emptyMap(), variants = emptyList(), codingModelId = "",
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

        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Избранные модели", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            TextButton(onClick = { vm.fetchModels(draft) }, enabled = !state.editorModelsLoading && draft.connectionConfigured && (!subscription || state.openAiSubscription.account?.signedIn == true)) {
                Text(if (state.editorModelsLoading) "Загрузка…" else if (draft.modelCatalog.isEmpty()) "Загрузить" else "Обновить", style = MaterialTheme.typography.labelSmall)
            }
        }
        state.editorModelsError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Text("Выбрано ${draft.favoriteModels.size} · для быстрого выбора в чате и проектах", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (draft.modelCatalog.isEmpty()) {
            Text("Загрузите доступные модели, чтобы добавить их в избранное.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            OutlinedTextField(modelQuery, { modelQuery = it }, label = { Text("Найти модель") },
                singleLine = true, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
            val filtered = draft.modelCatalog.filter { it.id.contains(modelQuery, true) || it.name.contains(modelQuery, true) }
            if (filtered.isEmpty()) Text("Модели не найдены", style = MaterialTheme.typography.bodySmall)
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 280.dp)) {
                items(filtered, key = { it.id }) { model ->
                    val checked = model.id in draft.favoriteModels
                    Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).toggleable(value = checked, role = Role.Checkbox,
                        onValueChange = { draft = draft.withFavoriteModel(model.id) }).heightIn(min = 48.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = checked, onCheckedChange = null)
                        Text(model.name, Modifier.weight(1f).padding(start = 8.dp), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
        if (spec?.allowsManualModelId == true) {
            Field("Модель вручную", draft.modelId) { draft = draft.copy(modelId = it) }
            TextButton(onClick = { draft = draft.withFavoriteModel(draft.modelId) }, enabled = draft.modelId.isNotBlank()) {
                Text(if (draft.modelId in draft.favoriteModels) "Убрать из избранного" else "Добавить в избранное")
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Button(onClick = {
            val first = draft.modelId.takeIf { it in draft.favoriteModels } ?: draft.favoriteModels.firstOrNull()
                ?: draft.modelId.ifBlank { draft.modelCatalog.firstOrNull()?.id.orEmpty() }
            vm.saveLlmProfile(draft.copy(modelId = first, modelLibraryVersion = 1))
        }, enabled = draft.connectionConfigured && (!subscription || state.openAiSubscription.account?.signedIn == true)) { Text("Сохранить") }
        TextButton(onClick = vm::closeLlmProfileEditor) { Text("Отмена") }
        }
    }
    }

}

/** Direct providers expose a catalog, so a model id should not have to be typed. */
@Composable
private fun CatalogModelPicker(
    models: List<ProviderModel>,
    selectedId: String,
    onSelected: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = models.firstOrNull { it.id == selectedId }
    Text("Модель", style = MaterialTheme.typography.labelMedium)
    TextButton(onClick = { expanded = true }, enabled = models.isNotEmpty()) {
        Text(selected?.name ?: selectedId.ifBlank { "Каталог моделей пока пуст" })
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        models.forEach { model ->
            DropdownMenuItem(
                text = { Text(if (model.name == model.id) model.id else "${model.name} · ${model.id}") },
                onClick = {
                    onSelected(model.id)
                    expanded = false
                },
            )
        }
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
internal fun SubscriptionAccount(vm: MagicPaperViewModel, state: UiState) {
    val auth = state.openAiSubscription
    var showDetails by remember { mutableStateOf(false) }
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
        style = MaterialTheme.typography.bodySmall,
        color = if (account?.signedIn == true) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (account?.signedIn == true) TextButton(onClick = { showDetails = !showDetails }) {
        Text(if (showDetails) "Скрыть лимиты ▴" else "Лимиты подписки ▾", style = MaterialTheme.typography.labelSmall)
    }
    if (showDetails) account?.rateLimits?.forEach { limit ->
        val reset = limit.resetsAtEpochSeconds?.let {
            val minutes = ((it - Id.now() / 1000).coerceAtLeast(0) + 59) / 60
            when {
                minutes == 0L -> "обновление ожидается"
                minutes < 60 -> "обновление через $minutes мин"
                minutes < 1440 -> "обновление через ${minutes / 60} ч ${minutes % 60} мин"
                else -> "обновление через ${minutes / 1440} д ${minutes % 1440 / 60} ч"
            }
        }
        Text(
            "${limit.name} · ${if (limit.window == "primary") "основной лимит" else if (limit.window == "secondary") "дополнительный лимит" else limit.window}: осталось ${(100 - limit.usedPercent).coerceIn(0, 100)}%" +
                (reset?.let { " · $it" } ?: ""),
            style = MaterialTheme.typography.labelSmall,
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
internal fun Field(label: String, value: String, secret: Boolean = false, onChange: (String) -> Unit) {
    var text by remember(value) { mutableStateOf(value) }
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            onChange(it)
        },
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        label = { Text(label) },
        visualTransformation = if (secret) androidx.compose.ui.text.input.PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        singleLine = true,
    )
}
