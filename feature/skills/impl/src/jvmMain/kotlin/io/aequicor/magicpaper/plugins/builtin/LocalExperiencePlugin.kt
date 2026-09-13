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
import io.aequicor.magicpaper.plugins.PersistentPlugin
import io.aequicor.magicpaper.data.storage.DraftRepository
import kotlinx.coroutines.*

/** Only enum-valued observations; there is no editor for training text or model prompts. */
class LocalExperiencePlugin(
    private val experience: LocalSkillExperience,
    private val profiles: LlmProfileRepository,
    draftRepository: DraftRepository,
    applicationScope: CoroutineScope,
) : MagicPlugin, PersistentPlugin {
    internal val forms = ExperienceFormOwner(experience, profiles, draftRepository, applicationScope)
    override suspend fun flushDrafts() = forms.flushDrafts()
    override suspend fun prepareForReset() = forms.prepareForReset()
    override fun resumeAfterReset() = forms.resumeAfterReset()
    override val id = "self-education"
    override val title = "Локальный опыт"
    override val description = "Результаты задач, проверяемые кандидаты и независимое удаление опыта."
    override val icon = "✦"

    @Composable
    override fun Content() {
        val view by forms.view.collectAsState()
        val actionState by forms.actions.state.collectAsState()
        val deletionState by forms.deletion.state.collectAsState()
        val maintenanceFailure by forms.maintenanceFailure.collectAsState()
        val resultState by forms.result.draft.state.collectAsState()
        val retentionState by forms.retention.draft.state.collectAsState()
        val selectionState by forms.selection.draft.state.collectAsState()
        val searchState by forms.searchDraft.draft.state.collectAsState()
        var scenario by forms.result.field(resultState, { it.scenario }, { copy(scenario = it) })
        var features by forms.result.field(resultState, { it.features }, { copy(features = it) })
        var success by forms.result.field(resultState, { it.success }, { copy(success = it) })
        var days by forms.retention.field(retentionState, { it.days.orEmpty() }, { copy(days = it) })
        var query by forms.searchDraft.field(searchState, { it.query }, { copy(query = it) })
        val selected = selectionState.value.selected
        val available = view.profiles
        val selectedProfile = available.singleOrNull { it.id == selectionState.value.profileId }
        val rows = view.rows
        val candidates = view.candidates
        val suggestions = view.suggestions
        val preview = selectionState.value.preview
        val legacy = view.legacy
        val busy = actionState.busy || actionState.cleanupPending || deletionState.busy
        val deleteConfirm = selectionState.value.deleteConfirmed
        LaunchedEffect(Unit) { forms.start() }
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            PaperText(title, style = LocalPaperTypography.current.title)
            maintenanceFailure?.let {
                PaperText(it, color = LocalPaperColors.current.error)
                PaperAction(enabled = !busy, onClick = forms::search) { PaperText("Повторить проверку") }
            }
            if (view.error != null) {
                PaperText(view.error.orEmpty(), color = LocalPaperColors.current.error)
                PaperAction(onClick = forms::start) { PaperText("Повторить загрузку") }
            }
            for ((state, retry) in listOf(resultState to { forms.result.draft.retry() }, retentionState to { forms.retention.draft.retry() }, selectionState to { forms.selection.draft.retry() }, searchState to { forms.searchDraft.draft.retry() })) {
                if (state.error != null) {
                    PaperText("Черновик не сохранён.", color = LocalPaperColors.current.error)
                    PaperAction(onClick = retry) { PaperText("Повторить сохранение") }
                }
            }
            if (actionState.cleanupPending) PaperAction(onClick = forms.actions::retryCleanup) { PaperText("Повторить очистку черновика") }
            if (deletionState.cleanupPending) PaperAction(onClick = forms.deletion::retryCleanup) { PaperText("Повторить очистку черновика") }
            if (!view.loaded || !listOf(resultState, retentionState, selectionState, searchState).all { it.loaded }) {
                PaperProgress(Modifier.fillMaxWidth()); return@Column
            }
            if (busy) PaperProgress(Modifier.fillMaxWidth())
            PaperText("Строгое обучение: сохраняются только тип задачи, выбранные признаки и отметка успеха. Исходные тексты, файлы и чаты не используются. Веса модели не меняются.")
            if (legacy) PaperText("Обнаружен прежний текстовый журнал. Обучение заблокировано. Кнопка «Удалить весь опыт» удалит его и связанные версии; тексты не переносятся.")
            ExperienceScenario.entries.forEach { item ->
                PaperAction(enabled = !busy && !legacy, onClick = { scenario = item }) {
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
            PaperAction(enabled = !busy && !legacy, onClick = forms::record) { PaperText("Сохранить результат") }
            Row {
                PaperInput(days, { days = it }, label = { PaperText("Хранить дней (1–365)") }, modifier = Modifier.weight(1f))
                PaperAction(enabled = !busy, onClick = forms::applyRetention) { PaperText("Применить") }
            }
            PaperInput(query, { query = it }, label = { PaperText("Локальный поиск") })
            PaperAction(enabled = !busy, onClick = forms::search) { PaperText("Найти") }
            PaperText("Записей одного типа (все исходы): " + rows.mapNotNull { it.scenario }.groupingBy { it }.eachCount().filterValues { it >= 2 }.toString())
            rows.forEach { row ->
                Row {
                    PaperCheck(row.id in selected, { checked -> forms.updateSelection { it.copy(selected = if (checked) it.selected + row.id else it.selected - row.id) } })
                    Column(Modifier.weight(1f)) {
                        PaperText("${row.scenario?.label ?: "Сценарий не определён"} · ${row.result.label}")
                        PaperText(row.features.joinToString("\n") { it.label }, maxLines = 5)
                    }
                }
            }
            PaperText("Выбрано результатов: ${selected.size}; связанных версий: " +
                candidates.filter { it.sources.any(selected::contains) }.joinToString { it.key }.ifEmpty { "нет" } +
                ". Всего в журнале кандидатов: ${candidates.size}.")
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) { PaperCheck(deleteConfirm, { checked -> forms.updateSelection { it.copy(deleteConfirmed = checked) } }); PaperText("Удалить опыт и связанные версии, проверки; отменить задачи. Исходные чаты сохраняются.") }
            // Deletion/cancellation deliberately remain available while a provider request is running.
            PaperAction(enabled = deleteConfirm && !deletionState.busy && !deletionState.cleanupPending, onClick = {
                forms.delete(all = false)
            }) { PaperText("Удалить выбранный опыт") }
            PaperAction(enabled = deleteConfirm && !deletionState.busy && !deletionState.cleanupPending, onClick = {
                forms.delete(all = true)
            }) { PaperText("Удалить весь опыт") }
            PaperText("Для кандидата выберите от 2 до 6 результатов одного типа. Программа назначит ID, версию и семь синтетических проверок: 4 фиксированные, 3 отложенные. Контекст ограничен 24 000 символами.")
            available.forEach { profile -> PaperAction(enabled = !busy, onClick = { forms.updateSelection { it.copy(profileId = profile.id) } }) { PaperText("${if (selectedProfile?.id == profile.id) "✓ " else ""}${profile.provider} · ${profile.modelId}") } }
            PaperText("Автоматические предложения: минимум 3 подтверждённых успеха с одинаковым сценарием и признаками. Ошибки, отмены и непроверенные исходы не учитываются. Использованный опыт повторно не предлагается.")
            suggestions.forEach { suggestion ->
                PaperText("${suggestion.scenario.label}: " + suggestion.features.joinToString { it.label }.ifEmpty { "без дополнительных признаков" })
                PaperText(suggestion.explanation)
                PaperAction(enabled = !busy && !legacy && selectedProfile != null, onClick = { forms.makePreview(suggestion.sources) }) { PaperText("Предпросмотр предложенного SKILL.md") }
            }
            PaperAction(enabled = !busy && !legacy && selectedProfile != null, onClick = { forms.makePreview() }) { PaperText("Предпросмотр отправки") }
            preview?.let { p ->
                PaperText("Провайдер: ${p.provider}; модель: ${p.model}")
                PaperText(p.context)
                PaperText(p.evaluation)
                PaperText("Разрешение действует только на этот цикл: 1 генерация и 14 проверок через выбранный текстовый профиль. Кандидат поступит в карантин; активация согласуется отдельно.")
                if (!view.previewReady) PaperText("Обновите предпросмотр перед отправкой.")
                PaperAction(enabled = !busy && view.previewReady, onClick = forms::generate) { PaperText("Разрешить отправку, создать и проверить") }
            }
            PaperAction(enabled = !deletionState.busy, onClick = forms::cancel) { PaperText("Отменить фоновые задачи") }
            candidates.forEach { c ->
                PaperText("${c.key}: ${if (c.passed) "метрики пройдены, требуется review" else "продвижение заблокировано"}")
                c.scores.forEach { PaperText("${if (it.heldOut) "Отложенный" else "Фиксированный"} ${it.caseIndex + 1}: активная ${it.baseline} → кандидат ${it.candidate}") }
            }
            if (actionState.notice.isNotBlank()) PaperText(actionState.notice)
            if (deletionState.notice.isNotBlank()) PaperText(deletionState.notice)
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
