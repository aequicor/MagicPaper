package io.aequicor.magicpaper.plugins.builtin

import io.aequicor.magicpaper.data.planning.PlanningStore
import io.aequicor.magicpaper.data.planning.command
import io.aequicor.magicpaper.data.storage.PersistentDraftValue
import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.domain.planning.*
import io.aequicor.magicpaper.logging.AppLog
import io.aequicor.magicpaper.util.Id
import io.aequicor.magicpaper.ui.PlanningRunUi
import io.aequicor.magicpaper.ui.runUi
import io.aequicor.magicpaper.ui.runUiSnapshot
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

internal enum class PlanningPanelWork { REFINING, SUBMITTING }

internal data class PlanningPanelOperation(
    val active: Set<PlanningPanelWork> = emptySet(),
    val notice: String? = null,
    val activity: List<CodingStep> = emptyList(),
    val recovery: PlanningRecoveryInspection? = null,
)

internal data class PlanningPanelState(
    val plans: List<Plan> = emptyList(),
    val runs: Map<String, PlanningRunUi> = emptyMap(),
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
    private val modelDossiers: ModelDossierRepository,
) {
    private val local = MutableStateFlow(PlanningPanelState())
    val state = combine(local, store.plans, execution.live, execution.error, store.runUi()) { own, plans, live, error, runs ->
        own.copy(plans = plans, live = live, serviceError = error, runs = runs)
    }.stateIn(scope, SharingStarted.Eagerly, local.value.copy(plans = store.plans.value,
        live = execution.live.value, serviceError = execution.error.value, runs = store.runUiSnapshot()))
    private var loading: Job? = null
    private val jobs = mutableMapOf<Pair<String, PlanningPanelWork>, Job>()
    private var resetting = false

    fun load() {
        if (resetting || loading?.isActive == true) return
        loading = scope.launch {
            local.update { it.copy(loadError = null) }
            try {
                store.plans()
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

    fun inspectRecovery(projectId: String, planId: String) = action(projectId) {
        showRecovery(projectId, null)
        showRecovery(projectId, execution.inspectPlanRecovery(planId))
    }

    fun confirmRecovery(projectId: String, inspection: PlanningRecoveryInspection) = action(projectId) {
        require(local.value.operations[projectId]?.recovery == inspection) { "Повторите сверку перед подтверждением" }
        showRecovery(projectId, null)
        execution.confirmPlanRecovery(inspection)
    }

    fun dismissRecovery(projectId: String) { showRecovery(projectId, null) }

    private fun showRecovery(projectId: String, inspection: PlanningRecoveryInspection?) {
        local.update { state ->
            val operation = state.operations[projectId] ?: PlanningPanelOperation()
            state.copy(operations = state.operations + (projectId to operation.copy(recovery = inspection)))
        }
    }

    fun refine(current: Plan, message: String, nodeId: String?, initial: Boolean,
               form: PersistentDraftValue<PlanningFormDraft>) {
        if (message.isBlank() || current.projectId to current.id in local.value.removedPlans) return
        val sentDraft = form.draft
        val sentVersion = sentDraft.state.value.version
        launchOperation(current.projectId, PlanningPanelWork.REFINING) {
            record(current.projectId, CodingStep(CodingStepKind.INFO, "Подготовка запроса…"))
            if (initial) {
                store.command(current.id, PlanningMachine.Intent.Create(current, PlanningMachine.Stamp(Id.new(), Id.now())))
                sentDraft.clearIfUnchanged(sentVersion)
            }
            val accepted = store.dispatch(current.id, PlanningMachine.Intent.BeginRefinement(
                PlanningMessage(Id.new(), "user", message), nodeId, false, current.plannerSelection,
                current.searchProvider, PlanningMachine.Stamp(Id.new(), Id.now())))
            accepted.rejection?.let { error(it.reason) }
            val pending = checkNotNull(accepted.state.plan)
            val refinement = checkNotNull(accepted.state.refinement)
            val savedProfiles = profiles.load()
            val savedSettings = settings.load()
            local.update { it.copy(profiles = savedProfiles, settings = savedSettings) }
            val judge = if (pending.plannerSelection != null) ProfileResolver.selection(checkNotNull(pending.plannerSelection), savedProfiles)
                else ProfileResolver.resolve(null as ChatSession?, savedSettings, savedProfiles)
            val onStep: (CodingStep) -> Unit = { record(current.projectId, it) }
            val onActivity: (String) -> Unit = { onStep(CodingStep(CodingStepKind.INFO, it)) }
            val result = if (nodeId == null) composer.refine(pending, message, judge, savedProfiles,
                modelDossiers.dossiers(), savedSettings, onStep, onActivity)
            else composer.recalculate(pending, nodeId, judge, savedProfiles,
                modelDossiers.dossiers(), savedSettings, onStep, onActivity)
            currentCoroutineContext().ensureActive()
            record(current.projectId, CodingStep(CodingStepKind.INFO, "Ответ проверен. Сохранение плана…"))
            val activity = local.value.operations[current.projectId]?.activity.orEmpty()
            store.command(current.id, PlanningMachine.Fact.RefinementCompleted(refinement.ref,
                RefinementResult(PlanSpecification.from(result), result.dialogue.last().copy(activity = activity),
                    result.wizardStep ?: PlanningStep.CLARIFY), PlanningMachine.Stamp(Id.new(), Id.now())))
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
