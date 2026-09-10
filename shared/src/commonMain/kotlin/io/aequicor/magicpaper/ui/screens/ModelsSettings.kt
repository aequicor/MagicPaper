package io.aequicor.magicpaper.ui.screens

import io.aequicor.magicpaper.designsystem.*

import io.aequicor.magicpaper.designsystem.paperClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
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
        PaperAction(onClick = vm::closeModelsSettings) { PaperText("‹ Настройки") }
        PaperText("Модели", style = paperTextStyle(PaperTextRole.TITLE))
        Spacer(Modifier.height(4.dp))
        PaperText("По умолчанию", style = paperTextStyle(PaperTextRole.TITLE))
        PaperText("Операции приложения, настройка и планирование.", style = paperTextStyle(PaperTextRole.BODY), color = LocalPaperColors.current.secondaryText)
        ModelSettingsButton(
            profile = operational,
            selection = default,
            onChoose = { pickingDefault = true },
            onEffort = { effort -> default?.let { vm.setDefaultModel(it.copy(effort = effort)) } },
            onParameters = { default?.let { variantEditor = it.profileId to it.modelId } },
        )
        operational?.let {
            PaperText("Модель: ${it.shortLabel}", style = paperTextStyle(PaperTextRole.BODY))
            PaperText("Движок операций: ${it.completionEngineLabel}", style = paperTextStyle(PaperTextRole.BODY), color = LocalPaperColors.current.secondaryText)
        }
        PaperDivider(Modifier.padding(vertical = 8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            PaperText("Избранное · ${favorites.size}", Modifier.weight(1f), style = paperTextStyle(PaperTextRole.TITLE))
            PaperAction(onClick = vm::generateModelDescriptions, enabled = !state.descriptionsGenerating && favorites.isNotEmpty() && operational != null) {
                PaperText(if (state.descriptionsGenerating) "Создание…" else "Создать описания", style = paperTextStyle(PaperTextRole.LABEL))
            }
        }
        PaperText("Параметры поставщика. Свои параметры сохраняются отдельным вариантом. Effort выбирается в чате или проекте.", style = paperTextStyle(PaperTextRole.BODY), color = LocalPaperColors.current.secondaryText)
        PaperText("Описания: модель по умолчанию + интернет-поиск. Поиск: ${state.settings.descriptionSearchLabel()}.", style = paperTextStyle(PaperTextRole.BODY))
        PaperText("Движок новых сессий проектов: ${state.settings.defaultCodingEngine.title}. Для описаний используется движок операций, указанный выше.", style = paperTextStyle(PaperTextRole.BODY), color = LocalPaperColors.current.secondaryText)
        if (operational == null) PaperText("Выберите модель по умолчанию для создания описаний.", style = paperTextStyle(PaperTextRole.BODY), color = LocalPaperColors.current.error)
        if (state.descriptionsGenerating || state.descriptionsErrors.isNotEmpty()) state.descriptionsContext?.let {
            PaperText("${if (state.descriptionsGenerating) "Текущий запуск" else "Последний запуск"}: $it", style = paperTextStyle(PaperTextRole.BODY))
        }
        state.descriptionsProgress?.let { PaperText(it, style = paperTextStyle(PaperTextRole.BODY)) }
        state.descriptionsErrors.forEach { PaperText(it, style = paperTextStyle(PaperTextRole.BODY), color = LocalPaperColors.current.error) }
        if (favorites.isEmpty()) PaperText("Отметьте ★ у моделей в каталоге поставщика.", style = paperTextStyle(PaperTextRole.BODY))
        favorites.forEach { (p, key) -> key(p.id, key) {
            LibraryModelRow(p, key, state, vm, { variantEditor = p.id to key }, { descriptionEditor = p.id to key })
        } }
        PaperDivider(Modifier.padding(vertical = 8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            PaperText("Поставщики", Modifier.weight(1f), style = paperTextStyle(PaperTextRole.TITLE))
            PaperAction(onClick = { vm.editLlmProfile(Id.new()) }) { PaperText("+ Подключить") }
        }
        state.llmProfiles.forEach { p -> key(p.id) {
            var expanded by remember { mutableStateOf(false) }
            var query by remember { mutableStateOf("") }
            val all = (p.modelCatalog.map { it.id } + p.favoriteModels + listOf(p.sourceModelId(p.modelId)))
                .filter { it.isNotBlank() }.distinct()
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).paperClickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically) {
                PaperText("${if (expanded) "▾" else "▸"} ${p.name} · ${all.size}", Modifier.weight(1f).padding(vertical = 12.dp), style = paperTextStyle(PaperTextRole.BODY))
                PaperAction(onClick = { vm.editLlmProfile(p.id) }) { PaperText("Настроить", style = paperTextStyle(PaperTextRole.LABEL)) }
            }
            if (expanded) {
                Row {
                    PaperAction(onClick = { vm.refreshModelCatalog(p.id) }, enabled = p.id !in state.catalogRefreshing) { PaperText(if (p.id in state.catalogRefreshing) "Загрузка…" else "Обновить каталог") }
                    PaperAction(onClick = { vm.deleteLlmProfile(p.id) }) { PaperText("Удалить поставщика") }
                }
                PaperInput(query, { query = it }, label = { PaperText("Найти модель") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (all.isEmpty()) PaperText("Загрузите каталог или укажите модель в настройках подключения.", style = paperTextStyle(PaperTextRole.BODY))
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
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).paperClickable { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                PaperText(profile.modelName(model), style = paperTextStyle(PaperTextRole.BODY))
                PaperText(buildList {
                    add(profile.name)
                    if (variant != null) add("свои параметры")
                    if (isDefault) add("по умолчанию")
                    fact?.contextWindow?.let { add("контекст $it") }
                }.joinToString(" · "), style = paperTextStyle(PaperTextRole.LABEL), color = LocalPaperColors.current.secondaryText)
            }
            if (variant == null) PaperAction(onClick = { vm.updateModelLibrary(profile.withFavoriteModel(model)) }) {
                PaperText(if (profile.isFavoriteModel(model)) "★" else "☆")
            } else PaperText("★", Modifier.padding(16.dp), color = LocalPaperColors.current.action)
            PaperText(if (expanded) "▴" else "▾", style = paperTextStyle(PaperTextRole.LABEL))
        }
        if (expanded) {
            Column(Modifier.padding(start = 12.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (variant != null) PaperText("На основе ${variant.sourceModelId}", style = paperTextStyle(PaperTextRole.LABEL))
                PaperText(dossier?.strengths?.ifBlank { "Описание не заполнено" } ?: "Описание не заполнено", style = paperTextStyle(PaperTextRole.BODY))
                dossier?.limitations?.takeIf { it.isNotBlank() }?.let { PaperText("Ограничения: $it", style = paperTextStyle(PaperTextRole.BODY)) }
                if (dossier != null) {
                    if (dossier.source == DossierSource.WEB && dossier.updatedAt > 0)
                        PaperText("Поиск выполнен: ${kotlin.time.Instant.fromEpochMilliseconds(dossier.updatedAt).toString().take(10)}", style = paperTextStyle(PaperTextRole.LABEL))
                    if (dossier.source == DossierSource.WEB && dossier.references.isEmpty())
                        PaperText("Старое описание без интернет-источников. Создайте его заново для проверки актуальных данных.", style = paperTextStyle(PaperTextRole.BODY), color = LocalPaperColors.current.error)
                    PaperText(if (dossier.rating > 0) "Оценка: ${dossier.rating}/5" else "Оценка не задана", style = paperTextStyle(PaperTextRole.LABEL))
                    if (dossier.note.isNotBlank()) PaperText(dossier.note, style = paperTextStyle(PaperTextRole.LABEL))
                    dossier.references.filter { it.startsWith("https://") || it.startsWith("http://") }.forEachIndexed { index, url ->
                        PaperAction(onClick = { uriHandler.openUri(url) }) { PaperText("Источник ${index + 1}", style = paperTextStyle(PaperTextRole.LABEL)) }
                    }
                }
                val levels = ModelDefaults.capability(profile, model).selectableLevels
                if (levels.isNotEmpty()) PaperText("Effort: ${levels.joinToString(" · ") { it.shortLabel }}", style = paperTextStyle(PaperTextRole.LABEL))
                PaperText("Контекст: ${fact?.contextWindow ?: "не указан"} · Максимальный ответ: ${fact?.maxOutputTokens ?: "не указан"}", style = paperTextStyle(PaperTextRole.LABEL))
                PaperText("Настраиваемые параметры: ${fact?.supportedParameters?.joinToString()?.ifBlank { "не объявлены" } ?: "поставщик не указал"}", style = paperTextStyle(PaperTextRole.LABEL))
                PaperText("Параметры по умолчанию: ${fact?.defaultParameters?.entries?.joinToString { "${it.key} = ${it.value}" }?.ifBlank { "поставщик не указал" } ?: "поставщик не указал"}", style = paperTextStyle(PaperTextRole.LABEL))
                if (variant != null) PaperText("Температура: ${variant.options.temperature ?: "по умолчанию"} · Ответ: ${if (variant.options.sendMaxTokens) variant.options.maxTokens.toString() else "по умолчанию"}", style = paperTextStyle(PaperTextRole.LABEL))
                @OptIn(ExperimentalLayoutApi::class)
                FlowRow {
                    PaperAction(onClick = { vm.setDefaultModel(ModelSelection(profile.id, model)) }, enabled = !isDefault) { PaperText("По умолчанию", style = paperTextStyle(PaperTextRole.LABEL)) }
                    PaperAction(onClick = onVariant) { PaperText(if (variant == null) "Свои параметры" else "Изменить параметры", style = paperTextStyle(PaperTextRole.LABEL)) }
                    PaperAction(onClick = onDescription) { PaperText("Описание", style = paperTextStyle(PaperTextRole.LABEL)) }
                    if (variant != null) PaperAction(onClick = { vm.updateModelLibrary(profile.copy(variants = profile.variants.filterNot { it.id == model })) }, enabled = !isDefault) { PaperText("Удалить вариант", style = paperTextStyle(PaperTextRole.LABEL)) }
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
        parseModelTimeoutSeconds(timeout) != null && history.toIntOrNull()?.let { it in 1..1000 } == true &&
        extras.values.all { it.isBlank() || runCatching { Json.parseToJsonElement(it) }.isSuccess }
    EditorDialog("${if (existing == null) "Новый вариант" else "Параметры варианта"}", onDismiss) {
        PaperText("На основе $source. Пустое поле сохраняет поведение поставщика.", style = paperTextStyle(PaperTextRole.BODY))
        Field("Название", name) { name = it }
        if (supports("temperature")) Field("Температура · 0–2", temperature) { temperature = it }
        if (supports("top_p")) Field("Top-p · 0–1", topP) { topP = it }
        if (supports("max_tokens") || supports("max_output_tokens")) Field("Лимит ответа в токенах", maxTokens) { maxTokens = it }
        fact?.supportedParameters.orEmpty().filter { it in CUSTOM_MODEL_PARAMETERS }.forEach { parameter ->
            Field(parameter, extras[parameter].orEmpty()) { extras = extras + (parameter to it) }
        }
        Field("Таймаут, секунд · 0 без ограничения", timeout) { timeout = it }
        Field("Сообщений истории в чате", history) { history = it }
        PaperInput(prompt, { prompt = it }, label = { PaperText("Системная инструкция") }, modifier = Modifier.fillMaxWidth())
        if (!valid) PaperText("Проверьте название и диапазоны параметров.", style = paperTextStyle(PaperTextRole.BODY), color = LocalPaperColors.current.error)
        PaperAction(enabled = valid, onClick = {
            val variant = ModelVariant(existing?.id ?: "variant:${Id.new()}", name.trim(), source, options.copy(
                temperature = temperature.toDoubleOrNull(), topP = topP.toDoubleOrNull(),
                maxTokens = maxTokens.toIntOrNull() ?: options.maxTokens, sendMaxTokens = maxTokens.isNotBlank(),
                timeoutSeconds = timeout.toInt(), contextMessages = history.toInt(), systemPromptOverride = prompt,
                extraParameters = extras.filterValues { it.isNotBlank() }.mapValues { Json.parseToJsonElement(it.value) }))
            onSave(profile.copy(variants = profile.variants.filterNot { it.id == variant.id } + variant))
        }) { PaperText("Сохранить в избранное") }
    }
}

@Composable
private fun ModelDescriptionEditor(profile: LlmProfile, key: String, dossier: ModelDossier?, onSave: (ModelDossier) -> Unit, onDismiss: () -> Unit) {
    var strengths by remember { mutableStateOf(dossier?.strengths.orEmpty()) }
    var limitations by remember { mutableStateOf(dossier?.limitations.orEmpty()) }
    var rating by remember { mutableStateOf(dossier?.rating ?: 0) }
    EditorDialog(profile.modelName(key), onDismiss) {
        PaperInput(strengths, { strengths = it }, label = { PaperText("Сильные стороны") }, modifier = Modifier.fillMaxWidth())
        PaperInput(limitations, { limitations = it }, label = { PaperText("Ограничения") }, modifier = Modifier.fillMaxWidth())
        PaperAction(onClick = { rating = (rating + 1) % 6 }) { PaperText(if (rating == 0) "Оценка: не задана" else "Оценка: $rating/5") }
        PaperAction(onClick = { onSave((dossier ?: ModelDossier(Id.new(), profile.id)).copy(id = if (dossier?.modelId == key) dossier.id else Id.new(), modelId = key, strengths = strengths.trim(), limitations = limitations.trim(), rating = rating)) }) { PaperText("Сохранить описание") }
    }
}

@Composable
private fun EditorDialog(title: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    PaperDialog(title = title, onDismissRequest = onDismiss, dismissLabel = "Закрыть") {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    }
}

/** Zero waits until completion or cancellation; positive seconds preserve the user’s chosen deadline. */
internal fun parseModelTimeoutSeconds(value: String): Int? = value.toIntOrNull()?.takeIf { it >= 0 }
