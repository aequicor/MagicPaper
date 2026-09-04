package io.aequicor.magicpaper.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import io.aequicor.magicpaper.domain.AdvancedSettings
import io.aequicor.magicpaper.domain.AppSettings
import io.aequicor.magicpaper.domain.EffortLevel
import io.aequicor.magicpaper.domain.LlmProfile
import io.aequicor.magicpaper.domain.ProviderCatalog
import io.aequicor.magicpaper.domain.ProviderSpec
import io.aequicor.magicpaper.domain.SearchProvider
import io.aequicor.magicpaper.domain.title
import io.aequicor.magicpaper.ui.MagicPaperViewModel
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
        ProfileEditor(vm, profile)
        return
    }

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

        Section("Магические источники (модели)")
        Text(
            "Профили подключения ИИ-провайдеров. Переключать источник можно прямо в чате — " +
                "кнопкой над полем заклинаний. «Основной» действует для всех свитков.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        if (state.llmProfiles.isEmpty()) {
            Text(
                "Источники не подключены. Добавьте провайдера — локальный сервер или API.",
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        state.llmProfiles.forEach { profile ->
            val isActive = profile.id == settings.activeLlmProfileId
            ProfileRowEntry(
                profile = profile,
                isActive = isActive,
                onClick = { vm.setActiveProfile(profile.id) },
                onEdit = { vm.editLlmProfile(profile.id) },
                onDelete = { vm.deleteLlmProfile(profile.id) },
            )
        }
        TextButton(
            onClick = { vm.editLlmProfile(Id.new()) },
            modifier = Modifier.heightIn(min = 48.dp),
        ) { Text("+ Подключить провайдера") }

        Spacer(Modifier.height(12.dp))
        Section("Поисковый движок")
        SearchProviderPicker(draft.searchProvider) { draft = draft.copy(searchProvider = it) }
        Field("Querit API-ключ", settings.queritApiKey) { draft = draft.copy(queritApiKey = it) }
        Field("Google API-ключ", settings.googleApiKey) { draft = draft.copy(googleApiKey = it) }
        Field("Google Search Engine ID", settings.googleSearchEngineId) {
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

/** Строка профиля в списке источников: имя·модель, бейдж усилия, активный, правка, удаление. */
@Composable
private fun ProfileRowEntry(
    profile: LlmProfile,
    isActive: Boolean,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                buildString {
                    append("✦ ")
                    append(profile.shortLabel)
                    if (isActive) append("  · основной")
                    if (!profile.configured) append("  · не настроен")
                },
                style = MaterialTheme.typography.bodyLarge,
                color = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            Text(
                "${profile.provider.name.lowercase()} · усилие: ${profile.effort.name.lowercase()}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onEdit) { Text("изм.") }
        TextButton(onClick = onDelete) { Text("✕") }
    }
}

/**
 * Редактор профиля подключения: провайдер из каталога → ключ → модель → усилие → тонкие настройки.
 * Черновик живёт локально; сохранение — одним действием через [MagicPaperViewModel.saveLlmProfile].
 */
@Composable
fun ProfileEditor(vm: MagicPaperViewModel, profile: LlmProfile) {
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
    // Тонкие настройки редактируются строками: пустое = «по умолчанию провайдера».
    val adv = draft.advanced
    var temperature by remember(profile.id) { mutableStateOf(adv.temperature?.toString().orEmpty()) }
    var maxTokens by remember(profile.id) { mutableStateOf(adv.maxTokens?.toString().orEmpty()) }
    var topP by remember(profile.id) { mutableStateOf(adv.topP?.toString().orEmpty()) }
    var timeout by remember(profile.id) { mutableStateOf(adv.timeoutSeconds.toString()) }
    var contextMessages by remember(profile.id) { mutableStateOf(adv.contextMessages.toString()) }

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
        ProviderCatalog.all.forEach { candidate ->
            ProviderRow(
                spec = candidate,
                selected = candidate.displayName == specName,
                onClick = {
                    specName = candidate.displayName
                    draft = draft.copy(
                        provider = candidate.type,
                        name = candidate.displayName,
                        baseUrl = candidate.defaultBaseUrl.ifBlank { draft.baseUrl },
                        modelId = candidate.models.firstOrNull()?.id.orEmpty().ifBlank { draft.modelId },
                    )
                },
            )
        }
        Spacer(Modifier.height(10.dp))

        Field("Название источника", draft.name) { draft = draft.copy(name = it) }
        Field("Base URL", draft.baseUrl) { draft = draft.copy(baseUrl = it) }
        Field("API-ключ (${spec?.keyHint ?: "пусто для локальных серверов"})", draft.apiKey) {
            draft = draft.copy(apiKey = it)
        }

        Spacer(Modifier.height(10.dp))
        Section("Модель")
        val specModels = spec?.models.orEmpty()
        if (specModels.isNotEmpty()) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                specModels.forEach { model ->
                    TextButton(onClick = { draft = draft.copy(modelId = model.id) }) {
                        Text(
                            model.id,
                            color = if (draft.modelId == model.id) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
            }
        }
        Field("Имя модели (или своё)", draft.modelId) { draft = draft.copy(modelId = it) }
        val effortNative = ProviderCatalog.supportsEffort(draft)
        Text(
            if (effortNative) "Модель поддерживает нативное усилие."
            else "У модели нет нативного усилия — уровень применится температурным режимом.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(10.dp))
        Section("Усилие")
        EffortPicker(draft.effort) { draft = draft.copy(effort = it) }

        Spacer(Modifier.height(10.dp))
        Section("Тонкие настройки (пусто = по умолчанию провайдера)")
        Field("Температура (0–2)", temperature) { temperature = it }
        Field("Макс. токенов ответа", maxTokens) { maxTokens = it }
        Field("Top-p (0–1)", topP) { topP = it }
        Field("Таймаут, секунд", timeout) { timeout = it }
        Field("Глубина истории (сообщений)", contextMessages) { contextMessages = it }
        Field("Свой системный промпт (пусто = штатный)", adv.systemPromptOverride) {
            draft = draft.copy(advanced = draft.advanced.copy(systemPromptOverride = it))
        }

        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(
                onClick = {
                    vm.saveLlmProfile(
                        draft.copy(
                            advanced = AdvancedSettings(
                                temperature = temperature.toDoubleOrNull(),
                                maxTokens = maxTokens.toIntOrNull(),
                                topP = topP.toDoubleOrNull(),
                                timeoutSeconds = timeout.toIntOrNull() ?: 60,
                                systemPromptOverride = draft.advanced.systemPromptOverride,
                                contextMessages = contextMessages.toIntOrNull() ?: 8,
                            ),
                        ),
                    )
                },
                enabled = draft.baseUrl.isNotBlank() && draft.modelId.isNotBlank(),
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text("Сохранить источник") }
            TextButton(
                onClick = { vm.closeLlmProfileEditor() },
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text("Отмена") }
        }
    }
}

/** Строка выбора провайдера из каталога. */
@Composable
private fun ProviderRow(spec: ProviderSpec, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .clickable(onClick = onClick)
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
        Text(spec.displayName, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
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

/** Сегментированный выбор уровня усилия. */
@Composable
internal fun EffortPicker(selected: EffortLevel, onSelect: (EffortLevel) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
        EffortLevel.entries.forEach { level ->
            TextButton(onClick = { onSelect(level) }) {
                Text(
                    level.title,
                    color = if (level == selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
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
