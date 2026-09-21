package io.aequicor.magicpaper.plugins.builtin

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.aequicor.magicpaper.ui.components.planningGraphProjection
import io.aequicor.magicpaper.data.planning.PlanningStore
import io.aequicor.magicpaper.data.planning.command
import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.plugins.MagicPlugin
import io.aequicor.magicpaper.plugins.CodingSessionPanel
import io.aequicor.magicpaper.ui.components.PaperChatMarkdown
import io.aequicor.magicpaper.ui.screens.CodingStepRow
import io.aequicor.magicpaper.ui.components.DecisionGraph
import io.aequicor.magicpaper.ui.components.FavoriteModelPicker
import io.aequicor.magicpaper.ui.components.EffortControl
import io.aequicor.magicpaper.ui.components.NativeLevelControl
import io.aequicor.magicpaper.designsystem.*
import io.aequicor.magicpaper.util.Id
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.Serializable
import io.aequicor.magicpaper.data.storage.DraftRepository
import io.aequicor.magicpaper.data.storage.PersistentDraftValue
import io.aequicor.magicpaper.plugins.PersistentPlugin

class CodingPlanningPlugin(
    private val store: PlanningStore, private val composer: PlanComposer,
    private val researcher: DossierResearcher, private val execution: PlanningExecutionService,
    private val runtime: CodingRuntime, private val projectsRepo: CodingProjectRepository?,
    private val profileRepo: LlmProfileRepository, private val settingsRepo: SettingsRepository,
    private val draftRepository: DraftRepository, private val applicationScope: CoroutineScope,
    private val modelDossiers: ModelDossierRepository,
    /** Каталоги моделей движков: плагин предлагает их в редакторе этапа, когда включён флаг. */
    private val models: CodingModelCatalog? = null,
) : MagicPlugin, CodingSessionPanel, PersistentPlugin {
    private val formDrafts = linkedMapOf<String, PersistentDraftValue<PlanningFormDraft>>()
    private val nodeDrafts = linkedMapOf<String, PersistentDraftValue<PlanningNodeDraft>>()
    private val panel = PlanningPanelController(store, composer, execution, projectsRepo, profileRepo, settingsRepo, applicationScope, modelDossiers)
    private fun formDraft(projectId: String?, plan: Plan?): PersistentDraftValue<PlanningFormDraft> {
        val key = "planning:${projectId ?: "unselected"}:${plan?.id ?: "new"}:form"
        return formDrafts.getOrPut(key) { PersistentDraftValue(draftRepository, key, PlanningFormDraft.serializer(), PlanningFormDraft(goal = plan?.goal.orEmpty()), applicationScope) }
    }
    private fun nodeDraft(plan: Plan, node: DecisionNode): PersistentDraftValue<PlanningNodeDraft> {
        val stage = plan.milestones.firstOrNull { it.id == (node.stageId ?: node.id) }
        val key = "planning:${plan.projectId}:${plan.id}:node:${node.id}"
        return nodeDrafts.getOrPut(key) { PersistentDraftValue(draftRepository, key, PlanningNodeDraft.serializer(),
            PlanningNodeDraft(node.title, stage?.description.orEmpty(), stage?.acceptance.orEmpty(), stage?.complexityPoints?.toString().orEmpty()), applicationScope) }
    }
    override suspend fun flushDrafts() { (formDrafts.values + nodeDrafts.values).forEach { it.flushDrafts() } }
    override suspend fun prepareForReset() {
        panel.prepareForReset()
        (formDrafts.values + nodeDrafts.values).forEach { it.prepareForReset() }
        formDrafts.clear(); nodeDrafts.clear()
    }
    override fun resumeAfterReset() { panel.resumeAfterReset() }
    override suspend fun removeProjectDrafts(projectId: String, planIds: Set<String>?) {
        panel.remove(projectId, planIds)
        val prefixes = planIds?.map { "planning:$projectId:$it:" } ?: listOf("planning:$projectId:")
        fun matches(key: String) = prefixes.any(key::startsWith)
        val owners = formDrafts.filterKeys(::matches).values + nodeDrafts.filterKeys(::matches).values
        owners.forEach { it.prepareForReset() }
        formDrafts.keys.filter(::matches).forEach(formDrafts::remove)
        nodeDrafts.keys.filter(::matches).forEach(nodeDrafts::remove)
        prefixes.forEach { prefix -> draftRepository.keys(prefix).forEach { draftRepository.remove(it) } }
    }

    override val id = "coding-planning"
    override val title = "Планирование"
    override val description = "Дерево решений: варианты, зависимости, модели и реализация с автоматическим продолжением."
    override val icon = "⚑"
    @Composable override fun Content() = Panel(null, Modifier)
    @Composable override fun SessionPanel(project: CodingProject, modifier: Modifier) = Panel(project, modifier)

    @OptIn(ExperimentalLayoutApi::class)
    @Composable private fun Panel(locked: CodingProject?, modifier: Modifier) {
        val display by panel.state.collectAsState()
        val projects = display.projects
        val profiles = display.profiles
        val settings = display.settings
        val serviceError = display.serviceError
        val loaded = display.loaded
        var projectId by rememberSaveable { mutableStateOf("") }
        val project = locked ?: projects.firstOrNull { it.id == projectId } ?: projects.firstOrNull()
        val plan = display.planFor(project?.id)
        val run = display.runs[plan?.id] ?: io.aequicor.magicpaper.ui.PlanningRunUi()
        val catalogs by (models?.snapshots ?: remember { kotlinx.coroutines.flow.MutableStateFlow<Map<CodingEngine, CodingModelSnapshot>>(emptyMap()) }).collectAsState()
        val nativeCatalog = plan?.engine?.let(catalogs::get)?.takeIf { settings.featureFlags.isEnabled(FeatureFlag.NATIVE_CODING_MODELS) }
        if (project?.id in display.removedProjects || display.removedPlans.any { it.first == project?.id && it.second == plan?.id }) {
            PaperText(if (project?.id in display.removedProjects) "Проект удалён" else "План удалён")
            return
        }
        val formOwner = formDraft(project?.id, plan)
        val formState by formOwner.draft.state.collectAsState()
        var goal by formOwner.field({ it.goal }) { value -> copy(goal = value) }
        var input by formOwner.field({ it.input }) { value -> copy(input = value) }
        var selected by rememberSaveable(project?.id, plan?.id) { mutableStateOf<String?>(null) }
        var viewedStepName by rememberSaveable(project?.id, plan?.id) { mutableStateOf<String?>(null) }
        var viewedStep by object : MutableState<PlanningStep?> {
            override var value: PlanningStep?
                get() = viewedStepName?.let { runCatching { PlanningStep.valueOf(it) }.getOrNull() }
                set(value) { viewedStepName = value?.name }
            override fun component1() = value
            override fun component2(): (PlanningStep?) -> Unit = { value = it }
        }
        val step = (viewedStep ?: plan?.currentPlanningStep ?: PlanningStep.GOAL).let { if (it == PlanningStep.STATUS) PlanningStep.REVIEW else it }
        val running = plan?.currentPlanningStep == PlanningStep.STATUS
        val operation = display.operations[project?.id] ?: PlanningPanelOperation()
        val notice = operation.notice ?: display.loadError
        val busy = PlanningPanelWork.REFINING in operation.active
        val submitting = PlanningPanelWork.SUBMITTING in operation.active
        val activity = operation.activity
        var draftEngine by formOwner.field({ it.engine }) { value -> copy(engine = value) }
        var draftPlanner by formOwner.field({ it.planner }) { value -> copy(planner = value) }
        var draftSearch by formOwner.field({ it.search }) { value -> copy(search = value) }
        var pickPlanner by remember { mutableStateOf(false) }
        var advanced by remember { mutableStateOf(false) }
        val choice = if (plan == null) draftPlanner else plan.plannerSelection
        val planner = if (choice != null) ProfileResolver.selection(choice, profiles) else ProfileResolver.resolve(null as ChatSession?, settings, profiles)
        fun action(block: suspend () -> Unit) { project?.id?.let { panel.action(it, block) } }
        LaunchedEffect(Unit) { panel.load() }
        fun edit(change: (Plan) -> Plan) { val current = plan ?: return; action { execution.edit(current.id, current.revision, change) } }
        fun navigate(target: PlanningStep) {
            viewedStep = target
            if (plan != null && !running) action { store.command(plan.id, PlanningMachine.Intent.Navigate(target, PlanningMachine.Stamp(Id.new(), Id.now()))) }
        }
        fun doRefine(message: String, nodeId: String? = null, initial: Plan? = null) {
            val current = initial ?: plan ?: return
            if (busy || submitting || message.isBlank()) return
            viewedStep = null
            panel.refine(current, message, nodeId, initial != null, formOwner)
        }
        PaperWizard(modifier.fillMaxWidth()) {
            PaperScrollColumn(Modifier, contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            PaperText("Планирование", role = PaperTextRole.HEADLINE)
            if (locked == null) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                projects.forEach { p -> PaperChoice(project?.id == p.id, { projectId = p.id }, enabled = !busy && !submitting, label = p.name) }
            }
            if (formState.error != null) PaperText("Не удалось сохранить черновик планирования.", color = LocalPaperColors.current.error)
            if (!loaded || !formState.loaded) {
                if (display.loadError == null) PaperProgress(Modifier.fillMaxWidth())
                else {
                    PaperText(requireNotNull(display.loadError), color = LocalPaperColors.current.error)
                    PaperButton("Повторить загрузку", onClick = panel::load)
                }
                return@PaperScrollColumn
            }
            if (project == null) { PaperText("Сначала добавьте проект в разделе «Проекты и код»."); return@PaperScrollColumn }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PlanningStep.entries.filter { it != PlanningStep.STATUS }.forEachIndexed { index, target ->
                    PaperChoice(step == target, { navigate(target) }, enabled = !busy && !submitting && when (target) {
                        PlanningStep.GOAL -> !running
                        PlanningStep.CLARIFY -> plan != null && !running
                        PlanningStep.REVIEW -> plan?.milestones?.isNotEmpty() == true
                        PlanningStep.STATUS -> running
                    }, label = "${index + 1} · ${wizardLabel(target)}")
                }
            }
            PaperText("Шаг ${step.ordinal + 1} из 3 · ${wizardLabel(step)}", role = PaperTextRole.TITLE)
            (notice ?: serviceError)?.let { error ->
                PaperPanel(Modifier.fillMaxWidth(), kind = PaperSurfaceKind.ERROR) {
                    Column(Modifier.padding(16.dp)) {
                        PaperText(error, color = LocalPaperColors.current.error)
                        if (display.loadError != null) PaperButton("Повторить загрузку", onClick = panel::load)
                        else if (plan != null && !running) PaperButton("Повторить запрос", enabled = !busy && !submitting, kind = PaperButtonKind.QUIET,
                            onClick = { doRefine(plan.dialogue.lastOrNull { it.role == "user" }?.text ?: INITIAL_PLANNING_MESSAGE) })
                    }
                }
            }
            when (step) {
                PlanningStep.GOAL -> {
                    PaperText("Опишите результат и выберите, кто поможет составить план.", color = LocalPaperColors.current.secondaryText)
                    PaperComposerField(goal, { goal = it }, label = { PaperText("Цель и ожидаемый результат", role = PaperTextRole.LABEL) }, minLines = 4,
                        enabled = !busy, modifier = Modifier.fillMaxWidth(), placeholder = { PaperText("Что нужно сделать и как проверить результат?", color = LocalPaperColors.current.secondaryText) })
                    PaperPanel(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            PaperText("Модель для планирования", role = PaperTextRole.TITLE)
                            PaperButton("${planner?.shortLabel ?: "Выбрать модель"} · ${planner?.effort?.shortLabel ?: "default"} ▾", onClick = { pickPlanner = true }, enabled = !busy && !submitting, kind = PaperButtonKind.SECONDARY)
                            PaperText("Движок сессий", role = PaperTextRole.TITLE)
                            if (plan == null) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                io.aequicor.magicpaper.data.coding.backendCatalog.descriptors.map { it.engine }.forEach { engine -> PaperChoice(selected = (draftEngine ?: settings.defaultCodingEngine) == engine,
                                    onSelect = { draftEngine = engine }, enabled = !busy && !submitting, label = engine.title) }
                            } else PaperText(plan.engine?.title ?: "Закреплён за сессиями", role = PaperTextRole.LABEL)
                            PaperText("Search engine", role = PaperTextRole.TITLE)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                SearchProvider.entries.forEach { provider -> PaperChoice((plan?.searchProvider ?: draftSearch ?: settings.searchProvider) == provider,
                                    { if (plan == null) draftSearch = provider else edit { it.copy(searchProvider = provider) } }, enabled = !busy && !submitting, label = searchLabel(provider)) }
                            }
                            PaperText("Ключи Google и Querit задаются в настройках приложения.", role = PaperTextRole.LABEL)
                        }
                    }
                    if (planner?.configured != true) PaperText("Выберите подключённую модель из избранного.", color = LocalPaperColors.current.error)
                    PaperButton("Далее · Уточнения", enabled = !busy && !submitting && goal.isNotBlank() && planner?.configured == true, onClick = {
                        if (plan == null) {
                            val fresh = Plan(Id.new(), project.id, goal.trim(), plannerSelection = draftPlanner, engine = draftEngine ?: settings.defaultCodingEngine, searchProvider = draftSearch ?: settings.searchProvider,
                                wizardStep = PlanningStep.CLARIFY, createdAt = Id.now(), updatedAt = Id.now())
                            doRefine(INITIAL_PLANNING_MESSAGE, initial = fresh.copy(tree = listOf(DecisionNode("${fresh.id}-root", fresh.goal, DecisionKind.GOAL))))
                        } else if (goal.trim() != plan.goal) {
                            val submittedGoal = goal.trim()
                            val submittedVersion = formOwner.draft.state.value.version
                            action {
                                execution.edit(plan.id, plan.revision) { p -> p.copy(goal = submittedGoal, wizardStep = PlanningStep.CLARIFY,
                                    tree = p.tree.map { if (it.kind == DecisionKind.GOAL) it.copy(title = submittedGoal) else it }) }
                                viewedStep = PlanningStep.CLARIFY
                                if (formOwner.draft.state.value.version == submittedVersion)
                                    input = "Цель изменена: $submittedGoal. Уточни детали и обнови план."
                            }
                        } else navigate(PlanningStep.CLARIFY)
                    })
                }
                PlanningStep.CLARIFY -> if (plan != null) {
                    PaperText(plan.goal, role = PaperTextRole.TITLE)
                    PaperText("Ответьте на вопросы, добавьте ограничения и детали. Когда всё готово — постройте план.", color = LocalPaperColors.current.secondaryText)
                    Dialogue(plan, input, { input = it }, busy || submitting, { doRefine(it) }, activity)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PaperButton("Назад · Цель", enabled = !busy && !submitting, kind = PaperButtonKind.SECONDARY, onClick = { navigate(PlanningStep.GOAL) })
                        PaperButton("Построить план", enabled = !busy && !submitting && plan.dialogue.any { it.role == "assistant" }, onClick = {
                            doRefine(buildString { if (input.isNotBlank()) appendLine(input.trim()); append("Построй и оцени варианты по имеющимся ответам. Сохрани ручные решения.") })
                        })
                    }
                }
                PlanningStep.REVIEW -> if (plan != null) {
                    PaperText(plan.goal, role = PaperTextRole.TITLE)
                    PaperText(if (running) "Статус выполнения отображается на графике. Нажмите на этап, чтобы открыть задание и работу агента." else "Проверьте этапы и модели. Нажмите на этап, чтобы открыть его задание. Подтверждение запускает реализацию.", color = LocalPaperColors.current.secondaryText)
                    if (running) {
                    PaperProgress(progress = plan.progress, modifier = Modifier.fillMaxWidth())
                    PaperText("${if (run.requiresRecovery) "Нужно внимание" else if (run.canContinue && plan.intent == ExecutionIntent.RUN) "Ожидает продолжения" else phaseLabel(plan)} · ${plan.doneCount}/${plan.selectedMilestones.size} этапов", role = PaperTextRole.TITLE)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PaperButton("Пауза", enabled = !submitting && run.active, kind = PaperButtonKind.SECONDARY, onClick = { action { execution.pause(plan.id) } })
                        PaperButton("Продолжить", enabled = runtime.supported && !submitting && run.canContinue, onClick = { action { execution.start(plan.id) } })
                        PaperButton("Остановить", enabled = !submitting && plan.intent != ExecutionIntent.STOP, kind = PaperButtonKind.QUIET, onClick = { action { execution.stop(plan.id) } })
                        if (run.requiresRecovery) PaperButton("Проверить предыдущую работу", enabled = !submitting,
                            kind = PaperButtonKind.QUIET, onClick = { panel.inspectRecovery(plan.projectId, plan.id) })
                        else if (plan.issue != null) PaperButton("Повторить после исправления", enabled = !submitting, kind = PaperButtonKind.QUIET, onClick = { action { execution.retry(plan.id) } })
                    }
                    operation.recovery?.takeIf { it.planId == plan.id }?.let { inspection ->
                        PaperPanel(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                PaperText("Предыдущая работа", role = PaperTextRole.TITLE)
                                inspection.operations.forEach { PaperText(it) }
                                PaperText("Проверьте результат операций и убедитесь, что связанные процессы завершены. Подтверждение не запускает работу.",
                                    role = PaperTextRole.LABEL)
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    PaperButton("Я проверил результат", enabled = !submitting,
                                        onClick = { panel.confirmRecovery(plan.projectId, inspection) })
                                    PaperButton("Отмена", enabled = !submitting, kind = PaperButtonKind.QUIET,
                                        onClick = { panel.dismissRecovery(plan.projectId) })
                                }
                            }
                        }
                    }
                    plan.issue?.let { PaperText(it.message, color = LocalPaperColors.current.error) }
                    }
                    DecisionGraph(plan, selected, { selected = it }, Modifier.fillMaxWidth().height(480.dp), fitInitially = true,
                        onChooseOption = if (busy || submitting) null else { choiceId, optionId -> edit { selectPlanningOption(it, choiceId, optionId) } })
                    if (!running) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PaperButton("Уточнить", enabled = !busy && !submitting && !running, kind = PaperButtonKind.SECONDARY, onClick = { navigate(PlanningStep.CLARIFY) })
                        PaperButton("Подтвердить", enabled = !busy && !submitting && !running && runtime.supported && plan.selectedMilestones.isNotEmpty() && DecisionCompiler.compile(plan).valid,
                            onClick = { action { execution.start(plan.id); viewedStep = PlanningStep.REVIEW } })

                    }
                    if (!runtime.supported) PaperText("Запуск реализации доступен в Desktop.")
                    val validation = DecisionCompiler.compile(plan)
                    if (!validation.valid) PaperText(validation.errors.joinToString("\n"), color = LocalPaperColors.current.error)
                    if (!running) {
                        PaperAction(onClick = { advanced = !advanced }) { PaperText(if (advanced) "▾ Скрыть параметры" else "▸ Приоритеты и параллельность", role = PaperTextRole.LABEL) }
                        if (advanced) {
                            Row {
                                PaperText("Параллельно: ${plan.parallelism}", Modifier.weight(1f))
                                PaperAction(enabled = !submitting, onClick = { edit { it.copy(parallelism = (it.parallelism - 1).coerceAtLeast(1)) } }) { PaperText("−", role = PaperTextRole.LABEL) }
                                PaperAction(enabled = !submitting, onClick = { edit { it.copy(parallelism = (it.parallelism + 1).coerceAtMost(8)) } }) { PaperText("+", role = PaperTextRole.LABEL) }
                            }
                            FlowRow { listOf("Качество" to plan.priorities.quality, "Скорость" to plan.priorities.speed, "Экономичность" to plan.priorities.economy, "Надёжность" to plan.priorities.safety).forEachIndexed { index, (label, value) ->
                                PaperAction(enabled = !submitting, onClick = { edit { old ->
                                    val v = (value + 1) % 4
                                    val priorities = when (index) { 0 -> old.priorities.copy(quality = v); 1 -> old.priorities.copy(speed = v); 2 -> old.priorities.copy(economy = v); else -> old.priorities.copy(safety = v) }
                                    composer.recommendChoices(old.copy(priorities = priorities))
                                } }) { PaperText("$label: $value", role = PaperTextRole.LABEL) }
                            } }
                        }
                    }
                    PlanningHistory(plan)
                    if (running && (plan.intent == ExecutionIntent.STOP || plan.phase == ExecutionPhase.COMPLETE)) PaperButton("Новая цель", enabled = !submitting,
                        kind = PaperButtonKind.QUIET, onClick = { action { store.deletePlan(plan.id); viewedStep = null; selected = null; goal = "" } })
                }
                PlanningStep.STATUS -> Unit // Legacy persisted step is displayed on the graph.

            }
            if (plan != null && selected != null) {
                val projected = planningGraphProjection(plan)
                projected.tree.firstOrNull { it.id == selected }?.let { node ->
                    StageDetailsDialog(projected, node, { selected = null }) {
                        if (!running && plan.tree.any { it.id == node.id }) {
                            NodeEditor(plan, node, profiles, nativeCatalog, nodeDraft(plan, node), ::edit)
                            PaperButton("Уточнить этап", enabled = !busy && !submitting, kind = PaperButtonKind.QUIET, onClick = {
                                selected = null; doRefine("Пересчитай участок «${node.title}».", node.id)
                            })
                        }
                    }
                }
            }
            if (pickPlanner) FavoriteModelPicker(profiles, choice,
                { selection -> if (plan == null) draftPlanner = selection else edit { it.copy(plannerSelection = selection) } }, { pickPlanner = false }, "Модель оркестратора",
                footer = { PaperButton("Модель по умолчанию", kind = PaperButtonKind.QUIET, onClick = { if (plan == null) draftPlanner = null else edit { it.copy(plannerSelection = null) }; pickPlanner = false }) })
        }
    }
}
}

@Composable private fun Dialogue(plan: Plan, input: String, onInput: (String) -> Unit, busy: Boolean, onSend: (String) -> Unit, activity: List<CodingStep>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PaperText("Уточнение цели", role = PaperTextRole.TITLE)
        val dialogueScroll = rememberScrollState()
        LaunchedEffect(plan.dialogue.size, dialogueScroll.maxValue) { dialogueScroll.animateScrollTo(dialogueScroll.maxValue) }
        PaperScrollColumn(Modifier.heightIn(max = 360.dp), state = dialogueScroll) {
            if (plan.dialogue.isEmpty() && !busy) PaperText("Начните уточнение: модель задаст вопросы о результате и ограничениях.", color = LocalPaperColors.current.secondaryText)
            plan.dialogue.forEach { message ->
                PaperPanel(Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    if (message.role == "user") PaperSurfaceKind.RAISED else PaperSurfaceKind.SELECTED) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        PaperText(if (message.role == "user") "Вы" else "Оркестратор", role = PaperTextRole.LABEL)
                        message.activity.forEach { CodingStepRow(it, false) }
                        PaperChatMarkdown(message.text)
                    }
                }
            }
        }
        if (busy || activity.isNotEmpty()) PaperPanel(Modifier.fillMaxWidth()) {
            val activityScroll = rememberScrollState()
            LaunchedEffect(activity, activityScroll.maxValue) { activityScroll.scrollTo(activityScroll.maxValue) }
            PaperScrollColumn(Modifier.heightIn(max = 320.dp), state = activityScroll, contentPadding = PaddingValues(12.dp)) {
                if (busy) PaperProgress(Modifier.fillMaxWidth())
                activity.forEach { CodingStepRow(it, busy) }
            }
        }
        PaperComposerField(input, onInput, label = { PaperText("Ответ, ограничение или изменение", role = PaperTextRole.LABEL) }, modifier = Modifier.fillMaxWidth(), enabled = !busy)
        PaperButton(if (busy) "Уточняю…" else "Отправить", onClick = { onSend(input) }, enabled = !busy && input.isNotBlank())

    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun NodeEditor(plan: Plan, node: DecisionNode, profiles: List<LlmProfile>, draftOwner: PersistentDraftValue<PlanningNodeDraft>, edit: ((Plan) -> Plan) -> Unit) =
    NodeEditor(plan, node, profiles, null, draftOwner, edit)

/**
 * [nativeCatalog] — каталог движка плана, когда включён флаг: тогда этапу можно назначить модель из
 * него. Нативное назначение показывается и правится в словаре движка при любом значении флага.
 */
@Composable internal fun NodeEditor(plan: Plan, node: DecisionNode, profiles: List<LlmProfile>, nativeCatalog: CodingModelSnapshot?, draftOwner: PersistentDraftValue<PlanningNodeDraft>, edit: ((Plan) -> Plan) -> Unit) {
    val stage = plan.milestones.firstOrNull { it.id == (node.stageId ?: node.id) }
    val frozen = stage != null && (stage.attempts.isNotEmpty() || stage.status != MilestoneStatus.PENDING)
    val draftState by draftOwner.draft.state.collectAsState()
    var title by draftOwner.field({ it.title }) { value -> copy(title = value) }
    var description by draftOwner.field({ it.description }) { value -> copy(description = value) }
    var acceptance by draftOwner.field({ it.acceptance }) { value -> copy(acceptance = value) }
    var complexity by draftOwner.field({ it.complexity }) { value -> copy(complexity = value) }
    val committed = PlanningNodeDraft(node.title, stage?.description.orEmpty(), stage?.acceptance.orEmpty(), stage?.complexityPoints?.toString().orEmpty())
    LaunchedEffect(committed, draftState.loaded) {
        val current = draftOwner.draft.state.value
        if (current.loaded && current.value == committed && current.version > 0)
            draftOwner.draft.clearIfUnchanged(current.version, committed)
    }
    val parsedComplexity = complexity.replace(',', '.').toDoubleOrNull()
    val complexityValid = complexity.isBlank() || (parsedComplexity != null && parsedComplexity.isFinite() && parsedComplexity > 0)
    fun updateStage(change: (Milestone) -> Milestone) = edit { old -> old.copy(milestones = old.milestones.map { if (it.id == stage?.id) change(it) else it }) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PaperDivider(); PaperText("Выбрано: ${node.title}", role = PaperTextRole.TITLE)
        if (draftState.error != null) PaperText("Не удалось сохранить черновик этапа.", color = LocalPaperColors.current.error)
        if (!draftState.loaded) { PaperProgress(Modifier.fillMaxWidth()); return@Column }
        val a = stage?.assessment ?: node.assessment
        PaperText("Качество: ${grade(a.quality)} · Скорость: ${grade(a.speed)} · Экономичность: ${grade(a.economy)} · Надёжность: ${grade(a.safety)}")
        if (a.explanation.isNotBlank()) PaperText(a.explanation)
        if (!frozen) FlowRow {
            listOf("Качество" to a.quality, "Скорость" to a.speed, "Экономичность" to a.economy, "Надёжность" to a.safety).forEachIndexed { i, (label, value) ->
                PaperAction(onClick = { edit { old ->
                    val updated = a.withGrade(i, (value + 1) % 4)
                    old.copy(tree = old.tree.map { if (it.id == node.id) it.copy(assessment = updated) else it },
                        milestones = old.milestones.map { if (it.id == stage?.id) it.copy(assessment = updated) else it })
                } }) { PaperText("$label: ${grade(value)}", role = PaperTextRole.LABEL) }
            }
        }
        if (node.kind == DecisionKind.CHOICE) plan.tree.filter { it.id in node.children }.forEach { option ->
            PaperChoice(option.id == node.selectedOptionId, { edit { old -> old.copy(tree = old.tree.map { if (it.id == node.id) it.copy(selectedOptionId = option.id, manualSelection = true) else it }) } }, label = option.title)
        }
        PaperField(title, { title = it }, enabled = !frozen, label = "Название", singleLine = false, modifier = Modifier.fillMaxWidth())
        if (stage != null) {
            PaperField(complexity, { complexity = it }, enabled = !frozen, label = "Сложность, усл. ед.", errorMessage = if (complexityValid) null else "Введите положительное число", supportingText = "Относительная оценка: например, 1, 2, 3, 5, 8, 13", modifier = Modifier.fillMaxWidth())
            PaperField(description, { description = it }, enabled = !frozen, label = "Что сделать", singleLine = false, modifier = Modifier.fillMaxWidth())
            PaperField(acceptance, { acceptance = it }, enabled = !frozen, label = "Критерии проверки", singleLine = false, modifier = Modifier.fillMaxWidth())
        }
        PaperAction(enabled = !frozen && title.isNotBlank() && complexityValid, onClick = { edit { old -> old.copy(goal = if (node.kind == DecisionKind.GOAL) title else old.goal, tree = old.tree.map { if (it.id == node.id) it.copy(title = title) else it }, milestones = old.milestones.map { if (it.id == stage?.id) it.copy(title = title, description = description, acceptance = acceptance, complexityPoints = parsedComplexity) else it }) } }) { PaperText("Сохранить изменения", role = PaperTextRole.LABEL) }
        if (stage != null) {
            val assignment = stage.assignment
            val nativeChoice = assignment?.native
            val nativeModel = nativeChoice?.let { choice -> nativeCatalog?.find(choice.provider, choice.modelId) }
            PaperText(assignment?.let {
                if (nativeChoice != null) "Модель: ${nativeModel?.name ?: it.displayName.ifBlank { it.modelId }} · уровень: ${nativeModel?.levelLabel(nativeChoice.level) ?: nativeChoice.level ?: "по умолчанию"}"
                else "Модель: ${profiles.firstOrNull { p -> p.id == it.profileId }?.modelName(it.modelId) ?: it.displayName.ifBlank { it.modelId }} · effort: ${it.effort.shortLabel}"
            } ?: "Исполнитель не назначен")
            if (!frozen) {
                var menu by remember { mutableStateOf(false) }
                Box {
                    PaperAction(onClick = { menu = true }) { PaperText("Выбрать модель", role = PaperTextRole.LABEL) }
                    PaperMenuHost(menu, { menu = false }) {
                    nativeCatalog?.let { snapshot -> profiles.firstOrNull { it.isNativeConnectionFor(snapshot.engine) }?.let { connection ->
                        snapshot.models.forEach { model ->
                            PaperMenuAction(label = "${connection.name} · ${model.name} · каталог движка", onClick = {
                                updateStage { it.copy(agentProfileId = connection.id, agentModelId = model.id,
                                    assignment = nativeStageAssignment(connection, snapshot.engine, model, null, manual = true)) }; menu = false
                            })
                        }
                    } }
                    profiles.filter { it.connectionConfigured && it.supportsCoding }.forEach { p -> p.displayModels.forEach { model ->
                        PaperMenuAction(label = "${p.name} · ${p.modelName(model)}${if (p.enabled) "" else " · отключён"}", enabled = p.enabled, onClick = {
                            val effective = EffortSelection.ofOrNull(ModelDefaults.capability(p.copy(modelId = model)).resolveEffort(p.effortSelectionFor(model)).level)
                            updateStage { it.copy(agentProfileId = p.id, agentModelId = model, assignment = StageAssignment(p.id, model, effective, effective, manual = true, displayName = p.modelName(model))) }; menu = false
                        })
                    } } }
                }
                if (assignment != null && nativeChoice != null && nativeModel != null && nativeModel.supportsLevels) {
                    // The engine's own levels: the app ladder would fold ultra into max and hide the real choice.
                    NativeLevelControl(nativeModel, nativeChoice.level) { level ->
                        val next = nativeChoice.copy(level = level)
                        updateStage { it.copy(assignment = assignment.copy(native = next, effort = next.displayEffort(), effectiveEffort = next.displayEffort(), manual = true)) }
                    }
                }
                val profile = profiles.firstOrNull { it.id == assignment?.profileId }
                if (profile?.enabled == true && assignment != null && nativeChoice == null) {
                    val capability = ModelDefaults.capability(profile.copy(modelId = assignment.modelId))
                    EffortControl(capability, assignment.effort, { effort -> updateStage { it.copy(assignment = assignment.copy(effort = effort, effectiveEffort = EffortSelection.ofOrNull(capability.resolveEffort(effort).level), manual = true)) } })
                }
            }
            assignment?.explanation?.takeIf { it.isNotBlank() }?.let { PaperText(it) }
            if (frozen) PaperText("Этап начат. Конфигурация и история закреплены.")
            PaperText("Зависит от:")
            FlowRow { plan.milestones.filter { it.id != stage.id }.forEach { dep ->
                PaperChoice(dep.id in stage.dependsOn, enabled = !frozen, onSelect = { updateStage { it.copy(dependsOn = if (dep.id in it.dependsOn) it.dependsOn - dep.id else it.dependsOn + dep.id) } }, label = dep.title)
            } }
            stage.attempts.forEach { attempt ->
                PaperText("Попытка ${attempt.id.take(8)} · ${attemptLabel(attempt.phase)} · ${attempt.assignment.displayName.ifBlank { attempt.assignment.modelId }} · ${attempt.assignment.effort.shortLabel}")
                if (attempt.activity.isNotBlank()) PaperText(attempt.activity)
                attempt.error?.let { PaperText(it.message, color = LocalPaperColors.current.error) }
                if (attempt.report.isNotBlank()) PaperText(attempt.report)
            }
            if (stage.checkNote.isNotBlank()) PaperText("Проверка: ${stage.checkNote}")
        }
        if (node.kind != DecisionKind.STAGE) Row {
            if (node.kind != DecisionKind.CHOICE) PaperAction(onClick = { edit { old ->
                val id = Id.uuid()
                old.copy(milestones = old.milestones + Milestone(id, "Новый этап"), tree = old.tree.map { if (it.id == node.id) it.copy(children = it.children + id) else it } + DecisionNode(id, "Новый этап", DecisionKind.STAGE, stageId = id))
            } }) { PaperText("+ Этап", role = PaperTextRole.LABEL) }
            PaperAction(onClick = { edit { old ->
                val choice = if (node.kind == DecisionKind.CHOICE) node.id else Id.new(); val option = Id.new()
                if (node.kind == DecisionKind.CHOICE) old.copy(tree = old.tree.map { if (it.id == node.id) it.copy(children = it.children + option) else it } + DecisionNode(option, "Новый вариант", DecisionKind.OPTION))
                else old.copy(tree = old.tree.map { if (it.id == node.id) it.copy(children = it.children + choice) else it } + listOf(DecisionNode(choice, "Выбор подхода", DecisionKind.CHOICE, listOf(option), option), DecisionNode(option, "Новый вариант", DecisionKind.OPTION)))
            } }) { PaperText(if (node.kind == DecisionKind.CHOICE) "+ Вариант" else "+ Выбор подхода", role = PaperTextRole.LABEL) }
        }
        if (node.kind != DecisionKind.GOAL && !frozen) PaperAction(onClick = { edit { old -> DecisionCompiler.removeNode(old, node.id) } }) { PaperText("Удалить узел и его ветвь", role = PaperTextRole.LABEL, color = LocalPaperColors.current.error) }
    }
}
private fun grade(value: Int) = when (value) { 1 -> "низкая"; 2 -> "средняя"; 3 -> "высокая"; else -> "неизвестно" }
private fun StageAssessment.withGrade(index: Int, value: Int) = when (index) {
    0 -> copy(quality = value); 1 -> copy(speed = value); 2 -> copy(economy = value); else -> copy(safety = value)
}
private fun phaseLabel(p: Plan): String = when {
    p.phase == ExecutionPhase.COMPLETE -> "✔ Выполнено"
    p.intent == ExecutionIntent.PAUSE -> "Ⅱ Пауза"
    p.phase == ExecutionPhase.WAITING -> "⚠ Ожидание"
    p.intent == ExecutionIntent.RUN -> "◷ " + when (p.phase) {
        ExecutionPhase.RECOVERING -> "Восстановление"
        ExecutionPhase.VERIFYING -> "Проверка"
        ExecutionPhase.INTEGRATING -> "Объединение"
        ExecutionPhase.APPLYING -> "Перенос результата"
        else -> "Выполняется"
    }
    p.status == PlanStatus.STOPPED -> "■ Остановлено"
    else -> "○ Подготовка"
}
private fun attemptLabel(phase: AttemptPhase) = when (phase) {
    AttemptPhase.PREPARED -> "Подготовлен"
    AttemptPhase.EXECUTING -> "Выполняется"
    AttemptPhase.VERIFYING -> "Проверка"
    AttemptPhase.INTEGRATING -> "Объединение"
    AttemptPhase.COMPLETE -> "Готово"
    AttemptPhase.FAILED -> "Ошибка"
}

private const val INITIAL_PLANNING_MESSAGE = "Изучи проект и подготовь план достижения цели. Задавай уточняющие вопросы, когда считаешь необходимым; если данных достаточно, сразу предложи план."
private fun searchLabel(provider: SearchProvider) = when (provider) {
    SearchProvider.AUTO -> "Авто"
    SearchProvider.WIKIPEDIA -> "Wikipedia"
    SearchProvider.QUERIT -> "Querit"
    SearchProvider.GOOGLE -> "Google"
}

private fun wizardLabel(step: PlanningStep) = when (step) {
    PlanningStep.GOAL -> "Цель и модель"
    PlanningStep.CLARIFY -> "Уточнения"
    PlanningStep.REVIEW -> "План"
    PlanningStep.STATUS -> "Статус работы"
}

@Composable private fun PlanningHistory(plan: Plan) {
    var expanded by remember { mutableStateOf(false) }
    PaperAction(onClick = { expanded = !expanded }) {
        PaperText(if (expanded) "▾ Скрыть историю планирования" else "▸ История планирования и действия агента", role = PaperTextRole.LABEL)
    }
    if (expanded) plan.dialogue.forEach { message ->
        PaperText(if (message.role == "user") "Вы" else "Оркестратор", role = PaperTextRole.LABEL)
        message.activity.forEach { CodingStepRow(it, false) }
        PaperChatMarkdown(message.text)
    }
}

@Composable private fun AttemptActivity(attempt: StageAttempt) {
    PaperText("Попытка ${attempt.id.take(8)} · ${attemptLabel(attempt.phase)}", role = PaperTextRole.LABEL)
    val visibleSteps = readableStageActivity(attempt.steps.filter { it.isVisibleActivity })
    if (visibleSteps.isNotEmpty()) visibleSteps.forEach { CodingStepRow(it, attempt.phase == AttemptPhase.EXECUTING) }
    else if (attempt.activity.isNotBlank()) CodingStepRow(CodingStep(CodingStepKind.INFO, attempt.activity), attempt.phase == AttemptPhase.EXECUTING)
    if (visibleSteps.isEmpty() && attempt.report.isNotBlank()) PaperChatMarkdown(attempt.report)
    if (attempt.phase == AttemptPhase.EXECUTING && visibleSteps.isEmpty() && attempt.activity.isBlank() && attempt.report.isBlank()) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            PaperProgress(Modifier.size(16.dp), kind = PaperProgressKind.CIRCULAR, label = "Ожидание первых событий агента")
            PaperText("Ожидание первых событий агента…")
        }
    }
    if (attempt.mergeReport.isNotBlank()) { PaperText("Объединение результата"); PaperChatMarkdown(attempt.mergeReport) }
    attempt.error?.let { PaperText(it.message, color = LocalPaperColors.current.error) }
}

private fun milestoneLabel(status: MilestoneStatus) = when (status) {
    MilestoneStatus.PENDING -> "Ожидает запуска"
    MilestoneStatus.ACTIVE -> "Выполняется"
    MilestoneStatus.DONE -> "Готово"
    MilestoneStatus.FAILED -> "Ошибка"
    MilestoneStatus.SKIPPED -> "Пропущено"
}

@Composable internal fun StageDetailsDialog(plan: Plan, node: DecisionNode, onDismiss: () -> Unit, editor: @Composable () -> Unit = {}) {
    PaperWideDialog(onDismissRequest = onDismiss) {
        StageDetailsContent(plan, node, onDismiss, Modifier.padding(16.dp).widthIn(max = 860.dp).fillMaxWidth().fillMaxHeight(.9f), editor)
    }
}

@Composable internal fun StageDetailsContent(plan: Plan, node: DecisionNode, onDismiss: () -> Unit, modifier: Modifier = Modifier, editor: @Composable () -> Unit = {}) {
    val stage = plan.milestones.firstOrNull { it.id == (node.stageId ?: node.id) }
        PaperPanel(modifier) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    PaperText(node.title, Modifier.weight(1f), role = PaperTextRole.TITLE)
                    PaperAction(onClick = onDismiss) { PaperText("Закрыть", role = PaperTextRole.LABEL) }
                }
                val scroll = rememberScrollState()
                PaperScrollColumn(Modifier.weight(1f), state = scroll, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (stage != null) {
                        PaperText(milestoneLabel(stage.status), role = PaperTextRole.TITLE)
                        stage.assignment?.let { PaperText("${it.displayName.ifBlank { it.modelId }} · ${it.effort.shortLabel}", color = LocalPaperColors.current.secondaryText) }
                        PaperText("Задание этапа", role = PaperTextRole.TITLE)
                        PaperChatMarkdown(stage.description.ifBlank { stage.title })
                        if (stage.acceptance.isNotBlank()) { PaperText("Критерии готовности", role = PaperTextRole.LABEL); PaperChatMarkdown(stage.acceptance) }
                        if (stage.dependsOn.isNotEmpty()) PaperText("Зависимости: " + stage.dependsOn.joinToString { id -> plan.milestones.firstOrNull { it.id == id }?.title ?: id })
                        PaperDivider()
                        PaperText("Работа агента", role = PaperTextRole.TITLE)
                        if (stage.attempts.isEmpty()) PaperText("Этап ещё не запущен. Здесь появятся ответ и действия агента.", color = LocalPaperColors.current.secondaryText)
                        stage.attempts.forEach { attempt ->
                            var showPrompt by remember(attempt.id) { mutableStateOf(false) }
                            if (attempt.prompt.isNotBlank()) {
                                PaperAction(onClick = { showPrompt = !showPrompt }) { PaperText(if (showPrompt) "▾ Скрыть отправленный промпт" else "▸ Отправленный промпт", role = PaperTextRole.LABEL) }
                                if (showPrompt) androidx.compose.foundation.text.selection.SelectionContainer { PaperText(attempt.prompt) }
                            }
                            AttemptActivity(attempt)
                        }
                        if (stage.checkNote.isNotBlank()) { PaperText("Проверка результата", role = PaperTextRole.LABEL); PaperChatMarkdown(stage.checkNote) }
                    } else {
                        PaperChatMarkdown(plan.goal)
                        PaperText(phaseLabel(plan), role = PaperTextRole.TITLE)
                        plan.issue?.let { PaperText(it.message, color = LocalPaperColors.current.error) }
                        plan.journal.forEach { CodingStepRow(CodingStep(CodingStepKind.INFO, "${it.operation} · ${it.detail}"), false) }
                    }
                    editor()
                }
            }
        }
}

@Serializable
internal data class PlanningFormDraft(
    val goal: String = "", val input: String = "", val engine: CodingEngine? = null,
    val planner: ModelSelection? = null, val search: SearchProvider? = null,
)
@Serializable
internal data class PlanningNodeDraft(val title: String = "", val description: String = "", val acceptance: String = "", val complexity: String = "")

private fun <T, V> PersistentDraftValue<T>.field(read: (T) -> V, write: T.(V) -> T): MutableState<V> = object : MutableState<V> {
    override var value: V
        get() = read(draft.state.value.value)
        set(value) { update { it.write(value) } }
    override fun component1() = value
    override fun component2(): (V) -> Unit = { value = it }
}
