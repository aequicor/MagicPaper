package io.aequicor.magicpaper.plugins.builtin

import io.aequicor.magicpaper.designsystem.*

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.aequicor.magicpaper.data.skills.*
import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.plugins.MagicPlugin
import kotlinx.coroutines.*

/** Only enum-valued observations; there is no editor for training text or model prompts. */
class LocalExperiencePlugin(
    private val experience: LocalSkillExperience,
    private val profiles: LlmProfileRepository,
) : MagicPlugin {
    override val id = "self-education"
    override val title = "Локальный опыт"
    override val description = "Результаты задач, проверяемые кандидаты и независимое удаление опыта."
    override val icon = "✦"

    @Composable
    override fun Content() {
        val scope = rememberCoroutineScope()
        var rows by remember { mutableStateOf<List<ExperienceOutcome>>(emptyList()) }
        var candidates by remember { mutableStateOf<List<ExperienceCandidate>>(emptyList()) }
        var suggestions by remember { mutableStateOf<List<ExperienceSuggestion>>(emptyList()) }
        var available by remember { mutableStateOf<List<LlmProfile>>(emptyList()) }
        var selectedProfile by remember { mutableStateOf<LlmProfile?>(null) }
        var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
        var scenario by remember { mutableStateOf(ExperienceScenario.SUMMARY) }
        var features by remember { mutableStateOf<Set<ExperienceFeature>>(emptySet()) }
        var legacy by remember { mutableStateOf(false) }
        var success by remember { mutableStateOf(true) }
        var query by remember { mutableStateOf("") }
        var days by remember { mutableStateOf("30") }
        var preview by remember { mutableStateOf<ExperiencePreview?>(null) }
        var notice by remember { mutableStateOf("") }
        var busy by remember { mutableStateOf(false) }
        var deleteConfirm by remember { mutableStateOf(false) }
        suspend fun refresh() {
            legacy = withContext(Dispatchers.IO) { experience.hasLegacyData() }
            if (legacy) { rows = emptyList(); candidates = emptyList(); suggestions = emptyList(); return }
            rows = withContext(Dispatchers.IO) { experience.search(query) }
            candidates = withContext(Dispatchers.IO) { experience.candidates() }
            suggestions = withContext(Dispatchers.IO) { experience.suggestions() }
        }
        fun action(block: suspend () -> Unit) {
            if (busy) return
            busy = true
            scope.launch {
                try { block(); refresh() }
                catch (e: CancellationException) { notice = "Задача отменена; активации не было."; throw e }
                catch (_: Exception) { notice = "Операция не завершена: проверьте лимиты, секреты, профиль и формат сценариев. Активации не было." }
                finally { busy = false }
            }
        }
        LaunchedEffect(Unit) {
            try {
                available = profiles.load().filter { it.configured && it.provider != ProviderType.OPENAI_SUBSCRIPTION }
                refresh()
                if (!legacy) days = withContext(Dispatchers.IO) { experience.retentionDays().toString() }
                experience.changes.collect { refresh() }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) { notice = "Локальный опыт недоступен; существующие данные сохранены." }
        }
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            PaperText(title, style = LocalPaperTypography.current.title)
            PaperText("Строгое обучение: сохраняются только тип задачи, выбранные признаки и отметка успеха. Исходные тексты, файлы и чаты не используются. Веса модели не меняются.")
            if (legacy) PaperText("Обнаружен прежний текстовый журнал. Обучение заблокировано. Кнопка «Удалить весь опыт» удалит его и связанные версии; тексты не переносятся.")
            ExperienceScenario.entries.forEach { item ->
                PaperAction(enabled = !busy && !legacy, onClick = { scenario = item; preview = null }) {
                    PaperText("${if (scenario == item) "✓ " else ""}${item.label}")
                }
            }
            ExperienceFeature.entries.forEach { feature ->
                Row {
                    PaperCheck(feature in features, { checked -> features = if (checked) features + feature else features - feature }, enabled = !legacy && !busy)
                    PaperText(feature.label)
                }
            }
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) { PaperCheck(success, { success = it }); PaperText("Задача выполнена успешно") }
            PaperAction(enabled = !busy && !legacy, onClick = { action {
                withContext(Dispatchers.IO) { experience.record(scenario, success, features) }
                features = emptySet(); notice = "Результат сохранён только локально."
            } }) { PaperText("Сохранить результат") }
            Row {
                PaperInput(days, { days = it }, label = { PaperText("Хранить дней (1–365)") }, modifier = Modifier.weight(1f))
                PaperAction(enabled = !busy, onClick = { action { withContext(Dispatchers.IO) { experience.retention(days.toInt()) }; preview = null } }) { PaperText("Применить") }
            }
            PaperInput(query, { query = it }, label = { PaperText("Локальный поиск") })
            PaperAction(enabled = !busy, onClick = { action { } }) { PaperText("Найти") }
            PaperText("Записей одного типа (все исходы): " + rows.mapNotNull { it.scenario }.groupingBy { it }.eachCount().filterValues { it >= 2 }.toString())
            rows.forEach { row ->
                Row {
                    PaperCheck(row.id in selected, { checked -> selected = if (checked) selected + row.id else selected - row.id; preview = null })
                    Column(Modifier.weight(1f)) {
                        PaperText("${row.scenario?.label ?: "Сценарий не определён"} · ${row.result.label}")
                        PaperText(row.features.joinToString("\n") { it.label }, maxLines = 5)
                    }
                }
            }
            PaperText("Выбрано результатов: ${selected.size}; связанных версий: " +
                candidates.filter { it.sources.any(selected::contains) }.joinToString { it.key }.ifEmpty { "нет" } +
                ". Всего в журнале кандидатов: ${candidates.size}.")
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) { PaperCheck(deleteConfirm, { deleteConfirm = it }); PaperText("Удалить опыт и связанные версии, проверки; отменить задачи. Исходные чаты сохраняются.") }
            // Deletion/cancellation deliberately remain available while a provider request is running.
            PaperAction(enabled = deleteConfirm, onClick = {
                scope.launch {
                    try { withContext(Dispatchers.IO) { experience.delete(selected) }; selected = emptySet(); preview = null; refresh() }
                    catch (_: Exception) { notice = "Удаление не завершено; повторите операцию." }
                    deleteConfirm = false
                }
            }) { PaperText("Удалить выбранный опыт") }
            PaperAction(enabled = deleteConfirm, onClick = {
                scope.launch {
                    try { withContext(Dispatchers.IO) { experience.deleteAll() }; selected = emptySet(); preview = null; refresh() }
                    catch (_: Exception) { notice = "Удаление не завершено; повторите операцию." }
                    deleteConfirm = false
                }
            }) { PaperText("Удалить весь опыт") }
            PaperText("Для кандидата выберите от 2 до 6 результатов одного типа. Программа назначит ID, версию и семь синтетических проверок: 4 фиксированные, 3 отложенные. Контекст ограничен 24 000 символами.")
            available.forEach { profile -> PaperAction(enabled = !busy, onClick = { selectedProfile = profile; preview = null }) { PaperText("${if (selectedProfile?.id == profile.id) "✓ " else ""}${profile.provider} · ${profile.modelId}") } }
            PaperText("Автоматические предложения: минимум 3 подтверждённых успеха с одинаковым сценарием и признаками. Ошибки, отмены и непроверенные исходы не учитываются. Использованный опыт повторно не предлагается.")
            suggestions.forEach { suggestion ->
                PaperText("${suggestion.scenario.label}: " + suggestion.features.joinToString { it.label }.ifEmpty { "без дополнительных признаков" })
                PaperText(suggestion.explanation)
                PaperAction(enabled = !busy && !legacy && selectedProfile != null, onClick = { action {
                    preview = withContext(Dispatchers.IO) { experience.previewSuggestion(suggestion.sources, requireNotNull(selectedProfile)) }
                } }) { PaperText("Предпросмотр предложенного SKILL.md") }
            }
            PaperAction(enabled = !busy && !legacy && selectedProfile != null, onClick = { action {
                preview = withContext(Dispatchers.IO) { experience.preview(selected, requireNotNull(selectedProfile)) }
            } }) { PaperText("Предпросмотр отправки") }
            preview?.let { p ->
                PaperText("Провайдер: ${p.provider}; модель: ${p.model}")
                PaperText(p.context)
                PaperText(p.evaluation)
                PaperText("Разрешение действует только на этот цикл: 1 генерация и 14 проверок через выбранный текстовый профиль. Кандидат поступит в карантин; активация согласуется отдельно.")
                PaperAction(enabled = !busy, onClick = { action {
                    preview = null
                    val result = withContext(Dispatchers.IO) { experience.generate(p.token, confirmed = true) }
                    notice = if (result.passed) "${result.key}: проверки пройдены. Откройте «Пакеты навыков»: diff, review, подтверждение активации и отдельных новых разрешений."
                        else "${result.key}: есть ухудшение или непройденная проверка. Продвижение заблокировано."
                } }) { PaperText("Разрешить отправку, создать и проверить") }
            }
            PaperAction(onClick = { scope.launch { experience.cancel(); preview = null } }) { PaperText("Отменить фоновые задачи") }
            candidates.forEach { c ->
                PaperText("${c.key}: ${if (c.passed) "метрики пройдены, требуется review" else "продвижение заблокировано"}")
                c.scores.forEach { PaperText("${if (it.heldOut) "Отложенный" else "Фиксированный"} ${it.caseIndex + 1}: активная ${it.baseline} → кандидат ${it.candidate}") }
            }
            PaperText(notice)
        }
    }
}

/** Keep the application usable when the journal is corrupt; do not fall back to legacy learning. */
internal object UnavailableExperiencePlugin : MagicPlugin {
    override val id = "self-education"
    override val title = "Локальный опыт"
    override val description = "Хранилище опыта недоступно."
    override val icon = "✦"
    @Composable override fun Content() {
        PaperText("Не удалось открыть локальный опыт. Данные сохранены; генерация и применение пакетов заблокированы до восстановления хранилища.")
    }
}
