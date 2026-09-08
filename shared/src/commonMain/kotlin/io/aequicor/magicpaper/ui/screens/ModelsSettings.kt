package io.aequicor.magicpaper.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.platform.LocalUriHandler
import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.ui.MagicPaperViewModel
import io.aequicor.magicpaper.ui.UiState
import io.aequicor.magicpaper.ui.components.ModelSettingsButton
import io.aequicor.magicpaper.ui.components.FavoriteModelPicker
import io.aequicor.magicpaper.util.Id
import kotlinx.serialization.json.*

@Composable
fun ModelsSettings(vm: MagicPaperViewModel, state: UiState) {
    var pickingDefault by remember { mutableStateOf(false) }
    var variantEditor by remember { mutableStateOf<Pair<String, String>?>(null) }
    var descriptionEditor by remember { mutableStateOf<Pair<String, String>?>(null) }
    val operational = ProfileResolver.resolve(null as ChatSession?, state.settings, state.availableLlmProfiles)
    val default = state.settings.defaultModel ?: operational?.let { ModelSelection(it.id, it.selectionKey, it.effortSelectionFor()) }
    val favorites = state.llmProfiles.flatMap { p -> p.displayModels.map { p to it } }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = vm::closeModelsSettings) { Text("‹ Настройки") }
        Text("Модели", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(4.dp))
        Text("По умолчанию", style = MaterialTheme.typography.titleSmall)
        Text("Операции приложения, настройка и планирование.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ModelSettingsButton(
            profile = operational,
            selection = default,
            onChoose = { pickingDefault = true },
            onEffort = { effort -> default?.let { vm.setDefaultModel(it.copy(effort = effort)) } },
            onParameters = { default?.let { variantEditor = it.profileId to it.modelId } },
        )
        operational?.let {
            Text("Модель: ${it.shortLabel}", style = MaterialTheme.typography.bodySmall)
            Text("Движок операций: ${it.completionEngineLabel}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Избранное · ${favorites.size}", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            TextButton(onClick = vm::generateModelDescriptions, enabled = !state.descriptionsGenerating && favorites.isNotEmpty() && operational != null) {
                Text(if (state.descriptionsGenerating) "Создание…" else "Создать описания", style = MaterialTheme.typography.labelMedium)
            }
        }
        Text("Параметры поставщика. Свои параметры сохраняются отдельным вариантом. Effort выбирается в чате или проекте.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Описания: модель по умолчанию + интернет-поиск. Поиск: ${state.settings.descriptionSearchLabel()}.", style = MaterialTheme.typography.bodySmall)
        Text("Движок новых сессий проектов: ${state.settings.defaultCodingEngine.title}. Для описаний используется движок операций, указанный выше.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (operational == null) Text("Выберите модель по умолчанию для создания описаний.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        if (state.descriptionsGenerating || state.descriptionsErrors.isNotEmpty()) state.descriptionsContext?.let {
            Text("${if (state.descriptionsGenerating) "Текущий запуск" else "Последний запуск"}: $it", style = MaterialTheme.typography.bodySmall)
        }
        state.descriptionsProgress?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        state.descriptionsErrors.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        if (favorites.isEmpty()) Text("Отметьте ★ у моделей в каталоге поставщика.", style = MaterialTheme.typography.bodyMedium)
        favorites.forEach { (p, key) -> key(p.id, key) {
            LibraryModelRow(p, key, state, vm, { variantEditor = p.id to key }, { descriptionEditor = p.id to key })
        } }
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Поставщики", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            TextButton(onClick = { vm.editLlmProfile(Id.new()) }) { Text("+ Подключить") }
        }
        state.llmProfiles.forEach { p -> key(p.id) {
            var expanded by remember { mutableStateOf(false) }
            var query by remember { mutableStateOf("") }
            val all = (p.modelCatalog.map { it.id } + p.favoriteModels + listOf(p.sourceModelId(p.modelId)))
                .filter { it.isNotBlank() }.distinct()
            Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically) {
                Text("${if (expanded) "▾" else "▸"} ${p.name} · ${all.size}", Modifier.weight(1f).padding(vertical = 12.dp), style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { vm.editLlmProfile(p.id) }) { Text("Настроить", style = MaterialTheme.typography.labelSmall) }
            }
            if (expanded) {
                Row {
                    TextButton(onClick = { vm.refreshModelCatalog(p.id) }, enabled = p.id !in state.catalogRefreshing) { Text(if (p.id in state.catalogRefreshing) "Загрузка…" else "Обновить каталог") }
                    TextButton(onClick = { vm.deleteLlmProfile(p.id) }) { Text("Удалить поставщика") }
                }
                OutlinedTextField(query, { query = it }, label = { Text("Найти модель") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (all.isEmpty()) Text("Загрузите каталог или укажите модель в настройках подключения.", style = MaterialTheme.typography.bodySmall)
                all.filter { it.contains(query, true) || p.modelName(it).contains(query, true) }.forEach { model -> key(model) {
                    LibraryModelRow(p, model, state, vm, { variantEditor = p.id to model }, { descriptionEditor = p.id to model })
                } }
            }
        } }
    }
    if (pickingDefault) FavoriteModelPicker(state.availableLlmProfiles.map { p -> p.copy(favoriteModels = (p.favoriteModels + p.modelCatalog.map { it.id } + p.sourceModelId(p.modelId)).filter { it.isNotBlank() }.distinct()) },
        default, vm::setDefaultModel, { pickingDefault = false }, "Модель по умолчанию")
    variantEditor?.let { (id, model) -> state.llmProfiles.firstOrNull { it.id == id }?.let { p ->
        VariantEditor(p, model, { vm.updateModelLibrary(it); variantEditor = null }, { variantEditor = null })
    } }
    descriptionEditor?.let { (id, model) -> state.llmProfiles.firstOrNull { it.id == id }?.let { p ->
        ModelDescriptionEditor(p, model, state.modelDescriptions.forModel(p, model), { vm.saveModelDescription(it); descriptionEditor = null }, { descriptionEditor = null })
    } }
}

@Composable
private fun LibraryModelRow(profile: LlmProfile, model: String, state: UiState, vm: MagicPaperViewModel, onVariant: () -> Unit, onDescription: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val uriHandler = LocalUriHandler.current
    val variant = profile.variants.firstOrNull { it.id == model }
    val fact = profile.modelCatalog.firstOrNull { it.id == profile.sourceModelId(model) }
    val dossier = state.modelDescriptions.forModel(profile, model)
    val currentDefault = ProfileResolver.resolve(null as ChatSession?, state.settings, state.availableLlmProfiles)
    val isDefault = currentDefault?.id == profile.id && currentDefault.selectionKey == model
    Column {
        Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                Text(profile.modelName(model), style = MaterialTheme.typography.bodyMedium)
                Text(buildList {
                    add(profile.name)
                    if (variant != null) add("свои параметры")
                    if (isDefault) add("по умолчанию")
                    fact?.contextWindow?.let { add("контекст $it") }
                }.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (variant == null) TextButton(onClick = { vm.updateModelLibrary(profile.withFavoriteModel(model)) }) {
                Text(if (profile.isFavoriteModel(model)) "★" else "☆")
            } else Text("★", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.primary)
            Text(if (expanded) "▴" else "▾", style = MaterialTheme.typography.labelSmall)
        }
        if (expanded) {
            Column(Modifier.padding(start = 12.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (variant != null) Text("На основе ${variant.sourceModelId}", style = MaterialTheme.typography.labelSmall)
                Text(dossier?.strengths?.ifBlank { "Описание не заполнено" } ?: "Описание не заполнено", style = MaterialTheme.typography.bodySmall)
                dossier?.limitations?.takeIf { it.isNotBlank() }?.let { Text("Ограничения: $it", style = MaterialTheme.typography.bodySmall) }
                if (dossier != null) {
                    if (dossier.source == DossierSource.WEB && dossier.updatedAt > 0)
                        Text("Поиск выполнен: ${kotlin.time.Instant.fromEpochMilliseconds(dossier.updatedAt).toString().take(10)}", style = MaterialTheme.typography.labelSmall)
                    if (dossier.source == DossierSource.WEB && dossier.references.isEmpty())
                        Text("Старое описание без интернет-источников. Создайте его заново для проверки актуальных данных.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    Text(if (dossier.rating > 0) "Оценка: ${dossier.rating}/5" else "Оценка не задана", style = MaterialTheme.typography.labelSmall)
                    if (dossier.note.isNotBlank()) Text(dossier.note, style = MaterialTheme.typography.labelSmall)
                    dossier.references.filter { it.startsWith("https://") || it.startsWith("http://") }.forEachIndexed { index, url ->
                        TextButton(onClick = { uriHandler.openUri(url) }) { Text("Источник ${index + 1}", style = MaterialTheme.typography.labelSmall) }
                    }
                }
                val levels = ModelDefaults.capability(profile, model).selectableLevels
                if (levels.isNotEmpty()) Text("Effort: ${levels.joinToString(" · ") { it.shortLabel }}", style = MaterialTheme.typography.labelSmall)
                Text("Контекст: ${fact?.contextWindow ?: "не указан"} · Максимальный ответ: ${fact?.maxOutputTokens ?: "не указан"}", style = MaterialTheme.typography.labelSmall)
                Text("Настраиваемые параметры: ${fact?.supportedParameters?.joinToString()?.ifBlank { "не объявлены" } ?: "поставщик не указал"}", style = MaterialTheme.typography.labelSmall)
                Text("Параметры по умолчанию: ${fact?.defaultParameters?.entries?.joinToString { "${it.key} = ${it.value}" }?.ifBlank { "поставщик не указал" } ?: "поставщик не указал"}", style = MaterialTheme.typography.labelSmall)
                if (variant != null) Text("Температура: ${variant.options.temperature ?: "по умолчанию"} · Ответ: ${if (variant.options.sendMaxTokens) variant.options.maxTokens.toString() else "по умолчанию"}", style = MaterialTheme.typography.labelSmall)
                @OptIn(ExperimentalLayoutApi::class)
                FlowRow {
                    TextButton(onClick = { vm.setDefaultModel(ModelSelection(profile.id, model)) }, enabled = !isDefault) { Text("По умолчанию", style = MaterialTheme.typography.labelSmall) }
                    TextButton(onClick = onVariant) { Text(if (variant == null) "Свои параметры" else "Изменить параметры", style = MaterialTheme.typography.labelSmall) }
                    TextButton(onClick = onDescription) { Text("Описание", style = MaterialTheme.typography.labelSmall) }
                    if (variant != null) TextButton(onClick = { vm.updateModelLibrary(profile.copy(variants = profile.variants.filterNot { it.id == model })) }, enabled = !isDefault) { Text("Удалить вариант", style = MaterialTheme.typography.labelSmall) }
                }
            }
        }
    }
}

@Composable
private fun VariantEditor(profile: LlmProfile, model: String, onSave: (LlmProfile) -> Unit, onDismiss: () -> Unit) {
    val existing = profile.variants.firstOrNull { it.id == model }
    val source = profile.sourceModelId(model)
    val options = existing?.options ?: profile.providerOptions(model)
    val fact = profile.modelCatalog.firstOrNull { it.id == source }
    var name by remember { mutableStateOf(existing?.name ?: "${profile.modelName(model)} · свой вариант") }
    var temperature by remember { mutableStateOf(options.temperature?.toString().orEmpty()) }
    var topP by remember { mutableStateOf(options.topP?.toString().orEmpty()) }
    var maxTokens by remember { mutableStateOf(if (options.sendMaxTokens) options.maxTokens.toString() else "") }
    var timeout by remember { mutableStateOf(options.timeoutSeconds.toString()) }
    var history by remember { mutableStateOf(options.contextMessages.toString()) }
    var prompt by remember { mutableStateOf(options.systemPromptOverride) }
    var extras by remember { mutableStateOf(options.extraParameters.mapValues { it.value.toString() }) }
    val supported = fact?.supportedParameters
    val subscription = profile.provider == ProviderType.OPENAI_SUBSCRIPTION
    fun supports(key: String) = !subscription && (supported == null || key in supported)
    fun validNumber(text: String, min: Double, max: Double) = text.isBlank() || text.toDoubleOrNull()?.let { it.isFinite() && it in min..max } == true
    val valid = name.isNotBlank() && validNumber(temperature, 0.0, 2.0) && validNumber(topP, 0.0, 1.0) &&
        (maxTokens.isBlank() || maxTokens.toIntOrNull()?.let { it in 1..(fact?.maxOutputTokens ?: 10000000) } == true) &&
        timeout.toIntOrNull()?.let { it in 0..3600 } == true && history.toIntOrNull()?.let { it in 1..1000 } == true &&
        extras.values.all { it.isBlank() || runCatching { Json.parseToJsonElement(it) }.isSuccess }
    EditorDialog("${if (existing == null) "Новый вариант" else "Параметры варианта"}", onDismiss) {
        Text("На основе $source. Пустое поле сохраняет поведение поставщика.", style = MaterialTheme.typography.bodySmall)
        Field("Название", name) { name = it }
        if (supports("temperature")) Field("Температура · 0–2", temperature) { temperature = it }
        if (supports("top_p")) Field("Top-p · 0–1", topP) { topP = it }
        if (supports("max_tokens") || supports("max_output_tokens")) Field("Лимит ответа в токенах", maxTokens) { maxTokens = it }
        fact?.supportedParameters.orEmpty().filter { it in CUSTOM_MODEL_PARAMETERS }.forEach { parameter ->
            Field(parameter, extras[parameter].orEmpty()) { extras = extras + (parameter to it) }
        }
        Field("Таймаут, секунд · 0 без ограничения", timeout) { timeout = it }
        Field("Сообщений истории в чате", history) { history = it }
        OutlinedTextField(prompt, { prompt = it }, label = { Text("Системная инструкция") }, modifier = Modifier.fillMaxWidth())
        if (!valid) Text("Проверьте название и диапазоны параметров.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        TextButton(enabled = valid, onClick = {
            val variant = ModelVariant(existing?.id ?: "variant:${Id.new()}", name.trim(), source, options.copy(
                temperature = temperature.toDoubleOrNull(), topP = topP.toDoubleOrNull(),
                maxTokens = maxTokens.toIntOrNull() ?: options.maxTokens, sendMaxTokens = maxTokens.isNotBlank(),
                timeoutSeconds = timeout.toInt(), contextMessages = history.toInt(), systemPromptOverride = prompt,
                extraParameters = extras.filterValues { it.isNotBlank() }.mapValues { Json.parseToJsonElement(it.value) }))
            onSave(profile.copy(variants = profile.variants.filterNot { it.id == variant.id } + variant))
        }) { Text("Сохранить в избранное") }
    }
}

@Composable
private fun ModelDescriptionEditor(profile: LlmProfile, key: String, dossier: ModelDossier?, onSave: (ModelDossier) -> Unit, onDismiss: () -> Unit) {
    var strengths by remember { mutableStateOf(dossier?.strengths.orEmpty()) }
    var limitations by remember { mutableStateOf(dossier?.limitations.orEmpty()) }
    var rating by remember { mutableStateOf(dossier?.rating ?: 0) }
    EditorDialog(profile.modelName(key), onDismiss) {
        OutlinedTextField(strengths, { strengths = it }, label = { Text("Сильные стороны") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(limitations, { limitations = it }, label = { Text("Ограничения") }, modifier = Modifier.fillMaxWidth())
        TextButton(onClick = { rating = (rating + 1) % 6 }) { Text(if (rating == 0) "Оценка: не задана" else "Оценка: $rating/5") }
        TextButton(onClick = { onSave((dossier ?: ModelDossier(Id.new(), profile.id)).copy(id = if (dossier?.modelId == key) dossier.id else Id.new(), modelId = key, strengths = strengths.trim(), limitations = limitations.trim(), rating = rating)) }) { Text("Сохранить описание") }
    }
}

@Composable
private fun EditorDialog(title: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(Modifier.widthIn(max = 560.dp).heightIn(max = 720.dp), shape = MaterialTheme.shapes.large) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                content()
                TextButton(onClick = onDismiss) { Text("Закрыть") }
            }
        }
    }
}
