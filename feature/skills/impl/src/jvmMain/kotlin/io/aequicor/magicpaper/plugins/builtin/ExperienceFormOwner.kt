package io.aequicor.magicpaper.plugins.builtin

import io.aequicor.magicpaper.data.skills.*
import io.aequicor.magicpaper.data.storage.*
import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.logging.AppLog
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable

@Serializable internal data class ExperienceResultForm(
    val scenario: ExperienceScenario = ExperienceScenario.SUMMARY,
    val features: Set<ExperienceFeature> = emptySet(), val success: Boolean = true,
)
@Serializable internal data class ExperienceRetentionForm(val days: String? = null)
@Serializable internal data class ExperienceSearchForm(val query: String = "")
/** Descriptive preview only. The native journal's one-use authorization token is never persisted. */
@Serializable internal data class ExperiencePreviewForm(
    val provider: String, val model: String, val context: String, val evaluation: String,
)
@Serializable internal data class ExperienceSelectionForm(
    val selected: Set<String> = emptySet(), val profileId: String? = null,
    val preview: ExperiencePreviewForm? = null, val deleteConfirmed: Boolean = false,
)
internal data class ExperienceFormView(
    val loaded: Boolean = false, val legacy: Boolean = false,
    val rows: List<ExperienceOutcome> = emptyList(), val candidates: List<ExperienceCandidate> = emptyList(),
    val suggestions: List<ExperienceSuggestion> = emptyList(), val profiles: List<LlmProfile> = emptyList(),
    val previewReady: Boolean = false, val error: String? = null,
)

/** One native plugin owns commands and drafts; a composed panel only observes this owner. */
internal class ExperienceFormOwner(
    private val experience: LocalSkillExperience,
    private val profiles: LlmProfileRepository,
    repository: DraftRepository,
    private val scope: CoroutineScope,
) {
    val result = PersistentDraftValue(repository, "experience:result", ExperienceResultForm.serializer(), ExperienceResultForm(), scope)
    val retention = PersistentDraftValue(repository, "experience:retention", ExperienceRetentionForm.serializer(), ExperienceRetentionForm(), scope)
    val selection = PersistentDraftValue(repository, "experience:selection", ExperienceSelectionForm.serializer(), ExperienceSelectionForm(), scope)
    val searchDraft = PersistentDraftValue(repository, "experience:search", ExperienceSearchForm.serializer(), ExperienceSearchForm(), scope)
    private val owners = listOf(result, retention, selection, searchDraft)
    private var paused = false
    val actions = SkillFormAction(scope, { !paused }, "LocalExperiencePlugin", null)
    val deletion = SkillFormAction(scope, { !paused }, "LocalExperienceDeletion", null)
    private val mutableView = MutableStateFlow(ExperienceFormView())
    val view = mutableView.asStateFlow()
    private val mutableMaintenanceFailure = MutableStateFlow<String?>(null)
    val maintenanceFailure = mutableMaintenanceFailure.asStateFlow()
    private var observer: Job? = null
    private var preview: Pair<Long, ExperiencePreview>? = null

    fun start() {
        if (paused || observer != null) return
        observer = scope.launch(start = CoroutineStart.LAZY) {
            try {
                owners.forEach { it.draft.awaitSaved() }
                refresh()
                if (!view.value.legacy && retention.draft.state.value.value.days == null) {
                    val days = withContext(Dispatchers.IO) { experience.retentionDays().toString() }
                    retention.update { if (it.days == null) it.copy(days = days) else it }
                }
                experience.changes.collect { refresh() }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                AppLog.error("LocalExperiencePlugin", "load_failed", failure)
                mutableView.value = mutableView.value.copy(error = "Локальный опыт недоступен. Данные сохранены.")
            } finally { observer = null }
        }
        observer?.start()
    }

    private suspend fun refresh() {
        val currentProfiles = profiles.load().filter { it.configured && !it.provider.subscription }
        val query = searchDraft.draft.state.value.value.query
        val fresh = withContext(Dispatchers.IO) {
            if (experience.hasLegacyData()) ExperienceFormView(loaded = true, legacy = true, profiles = currentProfiles)
            else ExperienceFormView(true, rows = experience.search(query), candidates = experience.candidates(),
                suggestions = experience.suggestions(), profiles = currentProfiles)
        }
        mutableView.value = fresh.copy(previewReady = preview?.first == selection.draft.state.value.version)
    }

    fun reportMaintenanceFailure(failure: Exception) {
        AppLog.error("LocalExperiencePlugin", "maintenance_failed", failure)
        mutableMaintenanceFailure.value = "Не удалось выполнить фоновую очистку опыта. Повторите проверку."
    }
    fun clearMaintenanceFailure() { mutableMaintenanceFailure.value = null }
    fun search() = actions.launch {
        refresh()
        if (!view.value.legacy) clearMaintenanceFailure()
        ""
    }
    fun updateSelection(transform: (ExperienceSelectionForm) -> ExperienceSelectionForm) {
        if (paused) return
        preview = null
        selection.update { old ->
            val next = transform(old)
            next.copy(preview = null, deleteConfirmed = next.deleteConfirmed && next.selected == old.selected)
        }
        mutableView.value = mutableView.value.copy(previewReady = false)
    }

    fun record() {
        val draft = result.draft
        val captured = draft.state.value
        if (!captured.loaded) return
        actions.launch {
            draft.awaitSaved()
            withContext(Dispatchers.IO) { experience.record(captured.value.scenario, captured.value.success, captured.value.features) }
            actions.accepted { draft.awaitSaved(); draft.clearIfUnchanged(captured.version, captured.value.copy(features = emptySet())) }
            refresh()
            "Результат сохранён только локально."
        }
    }

    fun applyRetention() {
        val draft = retention.draft
        val captured = draft.state.value
        if (!captured.loaded) return
        actions.launch {
            val days = captured.value.days?.toIntOrNull()
            if (days == null || days !in 1..365) return@launch "Укажите целое число дней от 1 до 365."
            draft.awaitSaved()
            withContext(Dispatchers.IO) { experience.retention(days) }
            actions.accepted { draft.awaitSaved(); draft.clearIfUnchanged(captured.version, ExperienceRetentionForm(days.toString())) }
            invalidatePreview()
            refresh()
            "Срок хранения сохранён."
        }
    }

    fun makePreview(suggested: Set<String>? = null) {
        val draft = selection.draft
        val captured = draft.state.value
        if (!captured.loaded) return
        actions.launch {
            draft.awaitSaved()
            val profile = profiles.load().singleOrNull { it.id == captured.value.profileId && it.configured && !it.provider.subscription }
                ?: return@launch "Выберите доступный текстовый профиль."
            val prepared = withContext(Dispatchers.IO) {
                if (suggested != null) experience.previewSuggestion(suggested, profile)
                else experience.preview(captured.value.selected, profile)
            }
            if (draft.state.value.version != captured.version) return@launch "Выбор изменился. Откройте новый предпросмотр."
            draft.update(captured.value.copy(selected = suggested ?: captured.value.selected, deleteConfirmed = false,
                preview = ExperiencePreviewForm(prepared.provider, prepared.model, prepared.context, prepared.evaluation)))
            preview = draft.state.value.version to prepared
            mutableView.value = mutableView.value.copy(previewReady = true)
            "Предпросмотр готов. Отправка ещё не выполнена."
        }
    }

    fun generate() {
        val draft = selection.draft
        val captured = draft.state.value
        val prepared = preview?.takeIf { it.first == captured.version }?.second ?: return
        actions.launch {
            draft.awaitSaved()
            // Consume the in-memory capability before the first external attempt; retries require a new preview.
            preview = null
            mutableView.value = mutableView.value.copy(previewReady = false)
            val candidate = withContext(Dispatchers.IO) { experience.generate(prepared.token, confirmed = true) }
            actions.accepted { draft.awaitSaved(); draft.clearIfUnchanged(captured.version,
                captured.value.copy(selected = emptySet(), preview = null)) }
            refresh()
            if (candidate.passed) "${candidate.key}: проверки пройдены. Откройте «Пакеты навыков» для проверки и подтверждения."
            else "${candidate.key}: есть ухудшение или непройденная проверка. Продвижение заблокировано."
        }
    }

    fun delete(all: Boolean) {
        val draft = selection.draft
        val captured = draft.state.value
        if (!captured.loaded || !captured.value.deleteConfirmed) return
        deletion.launch {
            withContext(Dispatchers.IO) { if (all) experience.deleteAll() else experience.delete(captured.value.selected) }
            deletion.accepted { draft.awaitSaved(); draft.clearIfUnchanged(captured.version, captured.value.copy(selected = emptySet(), preview = null, deleteConfirmed = false)) }
            preview = null
            refresh()
            "Опыт удалён."
        }
    }

    fun cancel() = deletion.launch {
        withContext(Dispatchers.IO) { experience.cancel() }
        invalidatePreview()
        "Фоновые задачи отменены."
    }

    private fun invalidatePreview() {
        preview = null
        selection.update { it.copy(preview = null) }
        mutableView.value = mutableView.value.copy(previewReady = false)
    }

    suspend fun flushDrafts() {
        cleanupAll(listOf({ actions.awaitIdle() }, { deletion.awaitIdle() }, { forEachOwner { it.flushDrafts() } }))
    }
    suspend fun prepareForReset() {
        paused = true
        cleanupAll(listOf(
            { withContext(Dispatchers.IO) { experience.cancel() } },
            { actions.awaitIdle() }, { deletion.awaitIdle() },
            { observer?.cancelAndJoin(); observer = null; preview = null },
            { forEachOwner { it.prepareForReset() } },
            { mutableView.value = ExperienceFormView() },
        ))
    }
    fun resumeAfterReset() {
        owners.forEach { it.resumeAfterReset() }
        paused = false
    }
    private suspend fun forEachOwner(block: suspend (PersistentDraftValue<*>) -> Unit) {
        cleanupAll(owners.map { owner -> { block(owner) } })
    }
    private suspend fun cleanupAll(steps: List<suspend () -> Unit>) {
        var failure: Exception? = null
        steps.forEach {
            try { it() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (next: Exception) { if (failure == null) failure = next else failure?.addSuppressed(next) }
        }
        failure?.let { throw it }
    }
}
