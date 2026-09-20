package io.aequicor.magicpaper.plugins.builtin

import io.aequicor.magicpaper.data.planning.PlanningStore
import io.aequicor.magicpaper.data.storage.PersistentDraftValue
import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.logging.AppLog
import io.aequicor.magicpaper.util.Id
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

internal enum class PlanningPanelWork { REFINING, SUBMITTING }

internal data class PlanningPanelOperation(
    val active: Set<PlanningPanelWork> = emptySet(),
    val notice: String? = null,
    val activity: List<CodingStep> = emptyList(),
)

internal data class PlanningPanelState(
    val plans: List<Plan> = emptyList(),
    val live: Map<String, StageAttempt> = emptyMap(),
    val serviceError: String? = null,
    val projects: List<CodingProject> = emptyList(),
    val profiles: List<LlmProfile> = emptyList(),
    val settings: AppSettings = AppSettings(),
    val loaded: Boolean = false,
    val loadError: String? = null,
    val operations: Map<String, PlanningPanelOperation> = emptyMap(),
    val removedProjects: Set<String> = emptySet(),
    val removedPlans: Set<Pair<String, String>> = emptySet(),
)

/** Presentation only: live output never replaces the durable identity or execution phase. */
internal fun PlanningPanelState.planFor(projectId: String?): Plan? {
    val saved = plans.firstOrNull { it.projectId == projectId } ?: return null
    fun preview(attempt: StageAttempt): StageAttempt {
        val current = live[attempt.id]?.takeIf { it.updatedAt > attempt.updatedAt } ?: return attempt
        return attempt.copy(report = current.report, activity = current.activity,
            mergeReport = current.mergeReport, steps = current.steps)
    }
    return saved.copy(milestones = saved.milestones.map { stage ->
        stage.copy(attempts = stage.attempts.map(::preview))
    }, finalAttempt = saved.finalAttempt?.let(::preview))
}

/** The plugin owns this controller for its application lifetime, independently of composition. */
internal class PlanningPanelController(
    private val store: PlanningStore,
    private val composer: PlanComposer,
    private val execution: PlanningExecutionService,
    private val projects: CodingProjectRepository?,
    private val profiles: LlmProfileRepository,
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
) {
    private val local = MutableStateFlow(PlanningPanelState())
    val state = combine(local, store.plans, execution.live, execution.error) { own, plans, live, error ->
        own.copy(plans = plans, live = live, serviceError = error)
    }.stateIn(scope, SharingStarted.Eagerly, local.value.copy(plans = store.plans.value,
        live = execution.live.value, serviceError = execution.error.value))
    private var loading: Job? = null
    private val jobs = mutableMapOf<Pair<String, PlanningPanelWork>, Job>()
    private var resetting = false

    fun load() {
        if (resetting || loading?.isActive == true) return
        loading = scope.launch {
            local.update { it.copy(loadError = null) }
            try {
                store.plans(); store.dossiers()
                val savedProjects = projects?.all().orEmpty()
                val savedProfiles = profiles.load()
                val savedSettings = settings.load()
                currentCoroutineContext().ensureActive()
                local.update { it.copy(projects = savedProjects, profiles = savedProfiles, settings = savedSettings, loaded = true) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                reportFailure("load", null, failure)
                local.update { it.copy(loadError = "Не удалось загрузить планирование. Повторите загрузку.") }
            }
        }
    }

    fun action(projectId: String, block: suspend () -> Unit) = launchOperation(projectId, PlanningPanelWork.SUBMITTING, block)

    fun refine(current: Plan, message: String, nodeId: String?, initial: Boolean,
               form: PersistentDraftValue<PlanningFormDraft>) {
        if (message.isBlank() || current.projectId to current.id in local.value.removedPlans) return
        val sentDraft = form.draft
        val sentVersion = sentDraft.state.value.version
        launchOperation(current.projectId, PlanningPanelWork.REFINING) {
            record(current.projectId, CodingStep(CodingStepKind.INFO, "Подготовка запроса…"))
            if (initial) {
                store.save(current)
                sentDraft.clearIfUnchanged(sentVersion)
            }
            val pending = store.update(current.id) { it.copy(wizardStep = PlanningStep.CLARIFY,
                dialogue = it.dialogue + PlanningMessage(Id.new(), "user", message)) }
            val savedProfiles = profiles.load()
            val savedSettings = settings.load()
            local.update { it.copy(profiles = savedProfiles, settings = savedSettings) }
            val judge = if (pending.plannerSelection != null) ProfileResolver.selection(checkNotNull(pending.plannerSelection), savedProfiles)
                else ProfileResolver.resolve(null as ChatSession?, savedSettings, savedProfiles)
            val onStep: (CodingStep) -> Unit = { record(current.projectId, it) }
            val onActivity: (String) -> Unit = { onStep(CodingStep(CodingStepKind.INFO, it)) }
            val result = if (nodeId == null) composer.refine(pending, message, judge, savedProfiles,
                store.dossiers.value, savedSettings, onStep, onActivity)
            else composer.recalculate(pending, nodeId, judge, savedProfiles,
                store.dossiers.value, savedSettings, onStep, onActivity)
            currentCoroutineContext().ensureActive()
            record(current.projectId, CodingStep(CodingStepKind.INFO, "Ответ проверен. Сохранение плана…"))
            val activity = local.value.operations[current.projectId]?.activity.orEmpty()
            execution.applyProposal(pending, result.copy(wizardStep = result.wizardStep ?: PlanningStep.CLARIFY,
                dialogue = result.dialogue.mapIndexed { index, entry ->
                    if (index == result.dialogue.lastIndex) entry.copy(activity = activity) else entry
                }))
            currentCoroutineContext().ensureActive()
            if (sentDraft.state.value.version == sentVersion) sentDraft.update { it.copy(input = "") }
        }
    }

    private fun launchOperation(projectId: String, work: PlanningPanelWork, block: suspend () -> Unit) {
        val key = projectId to work
        if (resetting || projectId in local.value.removedProjects || jobs[key]?.isActive == true ||
            (work == PlanningPanelWork.REFINING && jobs[projectId to PlanningPanelWork.SUBMITTING]?.isActive == true)) return
        local.update { state ->
            val previous = state.operations[projectId] ?: PlanningPanelOperation()
            state.copy(operations = state.operations + (projectId to previous.copy(active = previous.active + work,
                notice = null, activity = if (work == PlanningPanelWork.REFINING) emptyList() else previous.activity)))
        }
        val operationId = Id.new()
        val job = scope.launch(start = CoroutineStart.LAZY) {
            AppLog.info("planning-panel", "operation.started", mapOf("projectId" to projectId, "operationId" to operationId, "operation" to work.name))
            try {
                block()
                currentCoroutineContext().ensureActive()
                local.update { state ->
                    val previous = requireNotNull(state.operations[projectId])
                    val next = previous.copy(active = previous.active - work,
                        activity = if (work == PlanningPanelWork.REFINING) emptyList() else previous.activity)
                    state.copy(operations = if (next == PlanningPanelOperation()) state.operations - projectId
                        else state.operations + (projectId to next))
                }
                AppLog.info("planning-panel", "operation.completed", mapOf("projectId" to projectId, "operationId" to operationId))
            } catch (cancelled: CancellationException) {
                local.update { state -> state.copy(operations = state.operations + (projectId to
                    (state.operations[projectId] ?: PlanningPanelOperation()).let { previous ->
                        previous.copy(active = previous.active - work, notice = "Действие прервано.")
                    })) }
                AppLog.info("planning-panel", "operation.cancelled", mapOf("projectId" to projectId, "operationId" to operationId))
                throw cancelled
            } catch (failure: Exception) {
                reportFailure(work.name, projectId, failure, operationId)
                val notice = if (work == PlanningPanelWork.REFINING) "Не удалось получить ответ. Повторите запрос."
                    else "Не удалось выполнить действие. Повторите попытку."
                record(projectId, CodingStep(CodingStepKind.ERROR, notice))
                local.update { state -> state.copy(operations = state.operations + (projectId to
                    requireNotNull(state.operations[projectId]).let { previous -> previous.copy(active = previous.active - work, notice = notice) })) }
            }
        }
        jobs[key] = job
        job.start()
    }

    private fun record(projectId: String, event: CodingStep) {
        local.update { state ->
            val operation = state.operations[projectId] ?: return@update state
            val activity = operation.activity
            val index = if (event.callId.isNotBlank()) activity.indexOfLast { it.kind == event.kind && it.callId == event.callId }
                else activity.lastIndex.takeIf { event.kind in listOf(CodingStepKind.THINKING, CodingStepKind.SUMMARY) && activity.lastOrNull()?.kind == event.kind } ?: -1
            state.copy(operations = state.operations + (projectId to operation.copy(activity =
                if (index < 0) activity + event else activity.mapIndexed { i, old -> if (i == index) event else old })))
        }
    }

    suspend fun remove(projectId: String, planIds: Set<String>?) {
        local.update { it.copy(removedProjects = if (planIds == null) it.removedProjects + projectId else it.removedProjects,
            removedPlans = it.removedPlans + planIds.orEmpty().map { id -> projectId to id }) }
        val removed = jobs.filterKeys { it.first == projectId }
        removed.values.forEach { it.cancel() }
        removed.values.toList().joinAll()
        removed.keys.forEach(jobs::remove)
        local.update { it.copy(operations = it.operations - projectId) }
    }

    suspend fun prepareForReset() {
        resetting = true
        loading?.cancelAndJoin()
        jobs.values.toList().forEach { it.cancel() }
        jobs.values.toList().joinAll()
        jobs.clear()
        local.value = PlanningPanelState()
    }

    fun resumeAfterReset() { resetting = false }

    private fun reportFailure(operation: String, projectId: String?, failure: Exception, operationId: String? = null) {
        AppLog.error("planning-panel", "$operation.failed", mapOf("projectId" to projectId.orEmpty(),
            "operationId" to operationId.orEmpty(), "cause" to failure::class.simpleName.orEmpty()))
    }
}
