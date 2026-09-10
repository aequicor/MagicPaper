package io.aequicor.magicpaper.ui.screens

import io.aequicor.magicpaper.designsystem.*

import io.aequicor.magicpaper.designsystem.paperClickable
import io.aequicor.magicpaper.designsystem.paperToggleable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
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
    var agentLimits by remember(settings.agentLimits) { mutableStateOf(AgentLimitsDraft.from(settings.agentLimits)) }
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
        PaperText("Настройки", style = paperTextStyle(PaperTextRole.TITLE))
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
        PaperDivider()
        Spacer(Modifier.height(16.dp))

        Section("Оформление")
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
                .clip(RoundedCornerShape(6.dp))
                .paperToggleable(
                    value = draft.paperAnimationEnabled,
                    role = Role.Switch,
                    onValueChange = { draft = draft.copy(paperAnimationEnabled = it) },
                ).padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                PaperText("Анимация магической бумаги", style = paperTextStyle(PaperTextRole.BODY))

            }
            PaperToggle(checked = draft.paperAnimationEnabled, onCheckedChange = null)
        }
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
                .clip(RoundedCornerShape(6.dp))
                .paperToggleable(
                    value = draft.hideSystemSteps,
                    role = Role.Switch,
                    onValueChange = { draft = draft.copy(hideSystemSteps = it) },
                ).padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                PaperText("Скрывать системные шаги", style = paperTextStyle(PaperTextRole.BODY))

            }
            PaperToggle(checked = draft.hideSystemSteps, onCheckedChange = null)
        }
        Spacer(Modifier.height(12.dp))

        NavEntry("✦", "Модели", "По умолчанию, избранное и поставщики") { vm.openModelsSettings() }
        NavEntry("⚙", "Движки", "pi, Codex и движок новых сессий") { vm.openEnginesSettings() }

        Spacer(Modifier.height(12.dp))
        PlanningRulesSettingsSection(draft.planningRules) { draft = draft.copy(planningRules = it) }

        Spacer(Modifier.height(12.dp))
        AgentLimitsSettingsSection(agentLimits) { agentLimits = it }

        if (state.coding.computerSupported) {
            Spacer(Modifier.height(12.dp))
            Section("Доступ к экрану")
            val active = state.coding.currentSession
            val session = active?.session
            if (session != null && !session.planningMode && !session.researchMode && session.stageId == null) {
                PaperText("Сессия: ${session.name}", style = paperTextStyle(PaperTextRole.LABEL))
                io.aequicor.magicpaper.ui.components.ComputerUsePanel(
                    state = state.coding.computer, sessionId = session.id, running = active.running,
                    onEnable = { vm.enableComputerUse(session.id, it) },
                    onDisable = { vm.disableComputerUse(session.id) },
                    onPreview = { vm.previewComputerUse(session.id) },
                    onSettings = vm::openComputerSystemSettings,
                )
            } else {
                PaperText("Выберите сессию кодинга без планирования или исследования.",
                    color = LocalPaperColors.current.secondaryText)
                PaperAction({ vm.open(Screen.CODING) }) { PaperText("Проекты и код") }
            }
        }

        Spacer(Modifier.height(12.dp))
        Section("Поисковый движок")
        SearchApiSettings(draft, vm::checkSearchConnection) { draft = it }

        Spacer(Modifier.height(16.dp))
        PaperAction(
            onClick = { agentLimits.limits()?.let { vm.saveSettings(draft.copy(agentLimits = it)) } },
            enabled = agentLimits.valid,
            modifier = Modifier.heightIn(min = 48.dp),
        ) { PaperText("Сохранить настройки") }
        Spacer(Modifier.height(20.dp))
        PaperDivider()
        Spacer(Modifier.height(12.dp))

        Section("Профиль и данные")
        PaperText(
            "Хранилище: ${state.storageInfo}. Приложение не требует прав администратора; " +
                "все данные лежат в папке пользователя и удаляются вместе с программой.",
            style = paperTextStyle(PaperTextRole.BODY),
            color = LocalPaperColors.current.secondaryText,
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PaperAction(onClick = { vm.exportProfile() }) { PaperText("Экспорт профиля") }
            PaperAction(onClick = { vm.importProfile() }) { PaperText("Импорт профиля") }
            PaperAction(onClick = { vm.wipeAll() }) { PaperText("Стереть всё") }
        }
    }
}

@Composable
internal fun PlanningRulesSettingsSection(rules: PlanningRulesSettings, onChange: (PlanningRulesSettings) -> Unit) {
    var showEffective by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    Section("Правила планирования")
    PaperText("После сохранения — для новых запусков во всех проектах. Дочерние сессии наследуют редакцию запуска.",
        role = PaperTextRole.LABEL, color = LocalPaperColors.current.secondaryText)
    PaperText(if (rules.customPrompt == null) "По умолчанию · ${rules.snapshot().version}" else "Своя редакция · ${rules.snapshot().version}",
        role = PaperTextRole.LABEL)
    if (editing) PaperField(
        value = rules.customPrompt ?: DEFAULT_PLANNING_RULES,
        onValueChange = { onChange(rules.edited(it)) },
        label = "Методика", singleLine = false, modifier = Modifier.fillMaxWidth(),
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PaperAction(onClick = { editing = !editing }) { PaperText(if (editing) "Скрыть редактор" else "Редактировать") }
        PaperAction(onClick = { onChange(rules.reset()) }, enabled = rules.customPrompt != null) { PaperText("Вернуть стандартные") }
        PaperAction(onClick = { showEffective = !showEffective }) { PaperText(if (showEffective) "Скрыть эффективные правила" else "Эффективные правила") }
    }
    if (showEffective) PaperPanel(Modifier.fillMaxWidth()) {
        PaperText(rules.snapshot().effectivePrompt())
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
        PaperText(if (profile.configured) "Настройки подключения" else "Подключить модели", style = paperTextStyle(PaperTextRole.TITLE))
        PaperText(
            "Подключение → избранное → готово",
            style = paperTextStyle(PaperTextRole.LABEL),
            color = LocalPaperColors.current.secondaryText,
        )
        Spacer(Modifier.height(12.dp))

        Section("Провайдер")
        PaperAction(onClick = { chooseProvider = !chooseProvider }) { PaperText("${specName.ifBlank { "Выбрать поставщика" }} ▾") }
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

        PaperDivider(Modifier.padding(vertical = 12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            PaperText("Избранные модели", Modifier.weight(1f), style = paperTextStyle(PaperTextRole.TITLE))
            PaperAction(onClick = { vm.fetchModels(draft) }, enabled = !state.editorModelsLoading && draft.connectionConfigured && (!subscription || state.openAiSubscription.account?.signedIn == true)) {
                PaperText(if (state.editorModelsLoading) "Загрузка…" else if (draft.modelCatalog.isEmpty()) "Загрузить" else "Обновить", style = paperTextStyle(PaperTextRole.LABEL))
            }
        }
        state.editorModelsError?.let { PaperText(it, color = LocalPaperColors.current.error) }
        PaperText("Выбрано ${draft.favoriteModels.size} · для быстрого выбора в чате и проектах", style = paperTextStyle(PaperTextRole.LABEL), color = LocalPaperColors.current.secondaryText)
        if (draft.modelCatalog.isEmpty()) {
            PaperText("Загрузите доступные модели, чтобы добавить их в избранное.", style = paperTextStyle(PaperTextRole.BODY), color = LocalPaperColors.current.secondaryText)
        } else {
            PaperInput(modelQuery, { modelQuery = it }, label = { PaperText("Найти модель") },
                singleLine = true, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
            val filtered = draft.modelCatalog.filter { it.id.contains(modelQuery, true) || it.name.contains(modelQuery, true) }
            if (filtered.isEmpty()) PaperText("Модели не найдены", style = paperTextStyle(PaperTextRole.BODY))
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 280.dp)) {
                items(filtered, key = { it.id }) { model ->
                    val checked = model.id in draft.favoriteModels
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).paperToggleable(value = checked, role = Role.Checkbox,
                        onValueChange = { draft = draft.withFavoriteModel(model.id) }).heightIn(min = 48.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        PaperCheck(checked = checked, onCheckedChange = null)
                        PaperText(model.name, Modifier.weight(1f).padding(start = 8.dp), style = paperTextStyle(PaperTextRole.BODY))
                    }
                }
            }
        }
        if (spec?.allowsManualModelId == true) {
            Field("Модель вручную", draft.modelId) { draft = draft.copy(modelId = it) }
            PaperAction(onClick = { draft = draft.withFavoriteModel(draft.modelId) }, enabled = draft.modelId.isNotBlank()) {
                PaperText(if (draft.modelId in draft.favoriteModels) "Убрать из избранного" else "Добавить в избранное")
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        PaperAction(onClick = {
            val first = draft.modelId.takeIf { it in draft.favoriteModels } ?: draft.favoriteModels.firstOrNull()
                ?: draft.modelId.ifBlank { draft.modelCatalog.firstOrNull()?.id.orEmpty() }
            vm.saveLlmProfile(draft.copy(modelId = first, modelLibraryVersion = 1))
        }, enabled = draft.connectionConfigured && (!subscription || state.openAiSubscription.account?.signedIn == true)) { PaperText("Сохранить") }
        PaperAction(onClick = vm::closeLlmProfileEditor) { PaperText("Отмена") }
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
    PaperText("Модель", style = paperTextStyle(PaperTextRole.LABEL))
    PaperAction(onClick = { expanded = true }, enabled = models.isNotEmpty()) {
        PaperText(selected?.name ?: selectedId.ifBlank { "Каталог моделей пока пуст" })
    }
    PaperMenu(
        expanded = expanded,
        onDismissRequest = { expanded = false },
        items = models.map { model ->
            PaperMenuItem(if (model.name == model.id) model.id else "${model.name} · ${model.id}") {
                onSelected(model.id)
                expanded = false
            }
        },
    )
}

/** Строка выбора провайдера из каталога. */
@Composable
private fun ProviderRow(spec: ProviderSpec, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .paperClickable(enabled = enabled, onClick = onClick)
            .heightIn(min = 40.dp)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PaperText(
            if (selected) "◉" else "○",
            style = paperTextStyle(PaperTextRole.BODY),
            color = if (selected) LocalPaperColors.current.action else LocalPaperColors.current.secondaryText,
        )
        Spacer(Modifier.width(10.dp))
        PaperText(
            spec.displayName + if (!enabled) " · только desktop" else "",
            style = paperTextStyle(PaperTextRole.BODY),
            color = if (enabled) LocalPaperColors.current.text else LocalPaperColors.current.border,
            modifier = Modifier.weight(1f),
        )
        if (spec.defaultBaseUrl.isNotBlank()) {
            PaperText(
                spec.defaultBaseUrl,
                style = paperTextStyle(PaperTextRole.BODY),
                color = LocalPaperColors.current.secondaryText,
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
        PaperText(
            "OpenAI по подписке поддерживается только в desktop-приложении.",
            color = LocalPaperColors.current.error,
            style = paperTextStyle(PaperTextRole.BODY),
        )
        return
    }
    val account = auth.account
    PaperText(
        when {
            auth.loading -> "Проверяю аккаунт…"
            account?.signedIn == true -> buildString {
                append("✓ ChatGPT")
                account.email?.let { append(" · ").append(it) }
                account.planType?.let { append(" · план ").append(it) }
            }
            else -> "Войдите в ChatGPT: запросы будут расходовать лимит вашей подписки, API-ключ не нужен."
        },
        style = paperTextStyle(PaperTextRole.BODY),
        color = if (account?.signedIn == true) LocalPaperColors.current.action else LocalPaperColors.current.secondaryText,
    )
    if (account?.signedIn == true) PaperAction(onClick = { showDetails = !showDetails }) {
        PaperText(if (showDetails) "Скрыть лимиты ▴" else "Лимиты подписки ▾", style = paperTextStyle(PaperTextRole.LABEL))
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
        PaperText(
            "${limit.name} · ${if (limit.window == "primary") "основной лимит" else if (limit.window == "secondary") "дополнительный лимит" else limit.window}: осталось ${(100 - limit.usedPercent).coerceIn(0, 100)}%" +
                (reset?.let { " · $it" } ?: ""),
            style = paperTextStyle(PaperTextRole.LABEL),
            color = LocalPaperColors.current.secondaryText,
        )
    }
    auth.error?.let { PaperText(it, style = paperTextStyle(PaperTextRole.BODY), color = LocalPaperColors.current.error) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        when {
            auth.signingIn -> {
                PaperAction(onClick = { auth.login?.url?.let(uriHandler::openUri) }) { PaperText("Открыть страницу входа") }
                PaperAction(onClick = vm::cancelOpenAiSubscriptionLogin) { PaperText("Отмена") }
            }
            account?.signedIn == true -> {
                PaperAction(onClick = { vm.refreshOpenAiSubscription(true) }) { PaperText("Обновить") }
                PaperAction(onClick = vm::logoutOpenAiSubscription) { PaperText("Выйти") }
            }
            else -> PaperAction(onClick = vm::startOpenAiSubscriptionLogin) { PaperText("Войти через ChatGPT") }
        }
    }
}

/** Строка-переход в раздел: иконка, заголовок, описание, «›» — как ListItem из M3. */
@Composable
private fun NavEntry(icon: String, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .paperClickable(onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PaperText(icon, style = paperTextStyle(PaperTextRole.TITLE), color = LocalPaperColors.current.action)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            PaperText(title, style = paperTextStyle(PaperTextRole.BODY))
            PaperText(
                subtitle,
                style = paperTextStyle(PaperTextRole.BODY),
                color = LocalPaperColors.current.secondaryText,
            )
        }
        PaperText("›", style = paperTextStyle(PaperTextRole.TITLE), color = LocalPaperColors.current.secondaryText)
    }
}

@Composable
private fun Section(title: String) {
    PaperText(
        title,
        style = paperTextStyle(PaperTextRole.TITLE),
        color = LocalPaperColors.current.action,
    )
    Spacer(Modifier.height(6.dp))
}

@Composable
internal fun Field(label: String, value: String, secret: Boolean = false, onChange: (String) -> Unit) {
    var text by remember(value) { mutableStateOf(value) }
    PaperInput(
        value = text,
        onValueChange = {
            text = it
            onChange(it)
        },
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        label = { PaperText(label) },
        visualTransformation = if (secret) androidx.compose.ui.text.input.PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        singleLine = true,
    )
}
