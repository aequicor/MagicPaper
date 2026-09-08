package io.aequicor.magicpaper.plugins.builtin

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
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
            if (legacy) { rows = emptyList(); candidates = emptyList(); return }
            rows = withContext(Dispatchers.IO) { experience.search(query) }
            candidates = withContext(Dispatchers.IO) { experience.candidates() }
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
            } catch (_: Exception) { notice = "Локальный опыт недоступен; существующие данные сохранены." }
        }
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text("Строгое обучение: сохраняются только тип задачи, выбранные признаки и отметка успеха. Исходные тексты, файлы и чаты не используются. Веса модели не меняются.")
            if (legacy) Text("Обнаружен прежний текстовый журнал. Обучение заблокировано. Кнопка «Удалить весь опыт» удалит его и связанные версии; тексты не переносятся.")
            ExperienceScenario.entries.forEach { item ->
                TextButton(enabled = !busy && !legacy, onClick = { scenario = item; preview = null }) {
                    Text("${if (scenario == item) "✓ " else ""}${item.label}")
                }
            }
            ExperienceFeature.entries.forEach { feature ->
                Row {
                    Checkbox(feature in features, { checked -> features = if (checked) features + feature else features - feature }, enabled = !legacy && !busy)
                    Text(feature.label)
                }
            }
            Row { Checkbox(success, { success = it }); Text("Задача выполнена успешно") }
            TextButton(enabled = !busy && !legacy, onClick = { action {
                withContext(Dispatchers.IO) { experience.record(scenario, success, features) }
                features = emptySet(); notice = "Результат сохранён только локально."
            } }) { Text("Сохранить результат") }
            Row {
                OutlinedTextField(days, { days = it }, label = { Text("Хранить дней (1–365)") }, modifier = Modifier.weight(1f))
                TextButton(enabled = !busy, onClick = { action { withContext(Dispatchers.IO) { experience.retention(days.toInt()) }; preview = null } }) { Text("Применить") }
            }
            OutlinedTextField(query, { query = it }, label = { Text("Локальный поиск") })
            TextButton(enabled = !busy, onClick = { action { } }) { Text("Найти") }
            Text("Повторы: " + rows.groupingBy { it.scenario }.eachCount().filterValues { it >= 2 }.toString())
            rows.forEach { row ->
                Row {
                    Checkbox(row.id in selected, { checked -> selected = if (checked) selected + row.id else selected - row.id; preview = null })
                    Column(Modifier.weight(1f)) {
                        Text("${row.scenario.label} · ${if (row.success) "успех" else "неуспех"}")
                        Text(row.features.joinToString("\n") { it.label }, maxLines = 5)
                    }
                }
            }
            Text("Выбрано результатов: ${selected.size}; связанных версий: " +
                candidates.filter { it.sources.any(selected::contains) }.joinToString { it.key }.ifEmpty { "нет" } +
                ". Всего в журнале кандидатов: ${candidates.size}.")
            Row { Checkbox(deleteConfirm, { deleteConfirm = it }); Text("Удалить опыт и связанные версии, проверки; отменить задачи. Исходные чаты сохраняются.") }
            // Deletion/cancellation deliberately remain available while a provider request is running.
            TextButton(enabled = deleteConfirm, onClick = {
                scope.launch {
                    try { withContext(Dispatchers.IO) { experience.delete(selected) }; selected = emptySet(); preview = null; refresh() }
                    catch (_: Exception) { notice = "Удаление не завершено; повторите операцию." }
                    deleteConfirm = false
                }
            }) { Text("Удалить выбранный опыт") }
            TextButton(enabled = deleteConfirm, onClick = {
                scope.launch {
                    try { withContext(Dispatchers.IO) { experience.deleteAll() }; selected = emptySet(); preview = null; refresh() }
                    catch (_: Exception) { notice = "Удаление не завершено; повторите операцию." }
                    deleteConfirm = false
                }
            }) { Text("Удалить весь опыт") }
            Text("Для кандидата выберите от 2 до 6 результатов одного типа. Программа назначит ID, версию и семь синтетических проверок: 4 фиксированные, 3 отложенные. Контекст ограничен 24 000 символами.")
            available.forEach { profile -> TextButton(enabled = !busy, onClick = { selectedProfile = profile; preview = null }) { Text("${if (selectedProfile?.id == profile.id) "✓ " else ""}${profile.provider} · ${profile.modelId}") } }
            TextButton(enabled = !busy && !legacy && selectedProfile != null, onClick = { action {
                preview = withContext(Dispatchers.IO) { experience.preview(selected, requireNotNull(selectedProfile)) }
            } }) { Text("Предпросмотр отправки") }
            preview?.let { p ->
                Text("Провайдер: ${p.provider}; модель: ${p.model}")
                Text(p.context)
                Text(p.evaluation)
                Text("Разрешение действует только на этот цикл: 1 генерация и 14 проверок через выбранный текстовый профиль. Кандидат поступит в карантин; активация согласуется отдельно.")
                TextButton(enabled = !busy, onClick = { action {
                    preview = null
                    val result = withContext(Dispatchers.IO) { experience.generate(p.token, confirmed = true) }
                    notice = if (result.passed) "${result.key}: проверки пройдены. Откройте «Пакеты навыков»: diff, review, подтверждение активации и отдельных новых разрешений."
                        else "${result.key}: есть ухудшение или непройденная проверка. Продвижение заблокировано."
                } }) { Text("Разрешить отправку, создать и проверить") }
            }
            TextButton(onClick = { scope.launch { experience.cancel(); preview = null } }) { Text("Отменить фоновые задачи") }
            candidates.forEach { c ->
                Text("${c.key}: ${if (c.passed) "метрики пройдены, требуется review" else "продвижение заблокировано"}")
                c.scores.forEach { Text("${if (it.heldOut) "Отложенный" else "Фиксированный"} ${it.caseIndex + 1}: активная ${it.baseline} → кандидат ${it.candidate}") }
            }
            Text(notice)
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
        Text("Не удалось открыть локальный опыт. Данные сохранены; генерация и применение пакетов заблокированы до восстановления хранилища.")
    }
}
