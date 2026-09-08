package io.aequicor.magicpaper.plugins.builtin

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.aequicor.magicpaper.ui.components.planningGraphProjection
import io.aequicor.magicpaper.data.planning.PlanningStore
import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.plugins.MagicPlugin
import io.aequicor.magicpaper.plugins.CodingSessionPanel
import io.aequicor.magicpaper.ui.components.ChatMarkdown
import io.aequicor.magicpaper.ui.screens.CodingStepRow
import io.aequicor.magicpaper.ui.components.DecisionGraph
import io.aequicor.magicpaper.ui.components.FavoriteModelPicker
import io.aequicor.magicpaper.ui.components.EffortControl
import io.aequicor.magicpaper.util.Id
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class CodingPlanningPlugin(
    private val store: PlanningStore, private val composer: PlanComposer,
    private val researcher: DossierResearcher, private val execution: PlanningExecutionService,
    private val runtime: CodingRuntime, private val projectsRepo: CodingProjectRepository?,
    private val profileRepo: LlmProfileRepository, private val settingsRepo: SettingsRepository,
) : MagicPlugin, CodingSessionPanel {
    override val id = "coding-planning"
    override val title = "Планирование"
    override val description = "Дерево решений: варианты, зависимости, модели и реализация с автоматическим продолжением."
    override val icon = "⚑"
    @Composable override fun Content() = Panel(null, Modifier)
    @Composable override fun SessionPanel(project: CodingProject, modifier: Modifier) = Panel(project, modifier)

    @OptIn(ExperimentalLayoutApi::class)
    @Composable private fun Panel(locked: CodingProject?, modifier: Modifier) {
        val scope = rememberCoroutineScope()
        val plans by store.plans.collectAsState()
        val live by execution.live.collectAsState()
        val dossiers by store.dossiers.collectAsState()
        val serviceError by execution.error.collectAsState()
        var projects by remember { mutableStateOf<List<CodingProject>>(emptyList()) }
        var profiles by remember { mutableStateOf<List<LlmProfile>>(emptyList()) }
        var settings by remember { mutableStateOf(AppSettings()) }
        var loaded by remember { mutableStateOf(false) }
        var projectId by rememberSaveable { mutableStateOf("") }
        val project = locked ?: projects.firstOrNull { it.id == projectId } ?: projects.firstOrNull()
        val saved = plans.firstOrNull { it.projectId == project?.id }
        fun preview(attempt: StageAttempt): StageAttempt {
            val current = live[attempt.id]
            return if (current != null && current.updatedAt > attempt.updatedAt)
                attempt.copy(report = current.report, activity = current.activity, mergeReport = current.mergeReport, steps = current.steps) else attempt
        }
        val plan = saved?.copy(milestones = saved.milestones.map { stage -> stage.copy(attempts = stage.attempts.map(::preview)) }, finalAttempt = saved.finalAttempt?.let(::preview))
        var goal by rememberSaveable(project?.id, plan?.id) { mutableStateOf(plan?.goal.orEmpty()) }
        var input by rememberSaveable(project?.id, plan?.id) { mutableStateOf("") }
        var selected by rememberSaveable(project?.id, plan?.id) { mutableStateOf<String?>(null) }
        var viewedStep by rememberSaveable(project?.id, plan?.id) { mutableStateOf<PlanningStep?>(null) }
        val step = (viewedStep ?: plan?.currentPlanningStep ?: PlanningStep.GOAL).let { if (it == PlanningStep.STATUS) PlanningStep.REVIEW else it }
        val running = plan?.currentPlanningStep == PlanningStep.STATUS
        var notice by remember { mutableStateOf<String?>(null) }
        var busy by remember { mutableStateOf(false) }
        var submitting by remember { mutableStateOf(false) }
        var activity by remember { mutableStateOf<List<CodingStep>>(emptyList()) }
        var draftPlanner by remember { mutableStateOf<ModelSelection?>(null) }
        var draftSearch by remember { mutableStateOf<SearchProvider?>(null) }
        var pickPlanner by remember { mutableStateOf(false) }
        var advanced by remember { mutableStateOf(false) }
        val choice = if (plan == null) draftPlanner else plan.plannerSelection
        val planner = if (choice != null) ProfileResolver.selection(choice, profiles) else ProfileResolver.resolve(null as ChatSession?, settings, profiles)
        fun action(block: suspend () -> Unit) {
            if (submitting) return
            submitting = true
            scope.launch {
                try { block(); notice = null }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { notice = e.message ?: "Не удалось выполнить действие" }
                finally { submitting = false }
            }
        }
        LaunchedEffect(Unit) {
            try { store.plans(); store.dossiers(); projects = projectsRepo?.all().orEmpty(); profiles = profileRepo.load(); settings = settingsRepo.load(); loaded = true }
            catch (e: Exception) { notice = e.message }
        }
        fun edit(change: (Plan) -> Plan) { val current = plan ?: return; action { execution.edit(current.projectId, current.revision, change) } }
        fun navigate(target: PlanningStep) {
            viewedStep = target
            if (plan != null && !running) action { store.update(plan.projectId) { it.copy(wizardStep = target) } }
        }
        fun record(event: CodingStep) {
            activity = if (event.kind == CodingStepKind.THINKING && activity.lastOrNull()?.let { it.kind == event.kind && it.callId == event.callId } == true)
                activity.dropLast(1) + event else activity + event
        }
        fun doRefine(message: String, nodeId: String? = null, initial: Plan? = null) {
            val current = initial ?: plan ?: return
            if (busy || submitting || message.isBlank()) return
            busy = true; notice = null; viewedStep = PlanningStep.CLARIFY
            activity = listOf(CodingStep(CodingStepKind.INFO, "Подготовка запроса…"))
            scope.launch {
                try {
                    if (initial != null) store.save(initial)
                    val pending = store.update(current.projectId) { p -> p.copy(wizardStep = PlanningStep.CLARIFY,
                        dialogue = p.dialogue + PlanningMessage(Id.new(), "user", message)) }
                    profiles = profileRepo.load(); settings = settingsRepo.load()
                    val judge = if (pending.plannerSelection != null) ProfileResolver.selection(pending.plannerSelection, profiles)
                        else ProfileResolver.resolve(null as ChatSession?, settings, profiles)
                    val result = if (nodeId == null) composer.refine(pending, message, judge, profiles, dossiers, settings, ::record) { record(CodingStep(CodingStepKind.INFO, it)) }
                        else composer.recalculate(pending, nodeId, judge, profiles, dossiers, settings, ::record) { record(CodingStep(CodingStepKind.INFO, it)) }
                    record(CodingStep(CodingStepKind.INFO, "Ответ проверен. Сохранение плана…"))
                    val next = result.wizardStep ?: PlanningStep.CLARIFY
                    execution.applyProposal(pending, result.copy(wizardStep = next, dialogue = result.dialogue.mapIndexed { index, message ->
                        if (index == result.dialogue.lastIndex) message.copy(activity = activity) else message
                    }))
                    viewedStep = next; input = ""; activity = emptyList()
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    notice = e.message ?: "Не удалось получить ответ. Повторите запрос."
                    record(CodingStep(CodingStepKind.ERROR, notice!!))
                    try { store.update(current.projectId) { it.copy(dialogue = it.dialogue + PlanningMessage(Id.new(), "system", notice!!, activity)) }; activity = emptyList() }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { /* Keep the visible activity if persistence failed. */ }
                } finally { busy = false }
            }
        }
        Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Планирование", style = MaterialTheme.typography.headlineMedium)
            if (locked == null) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                projects.forEach { p -> FilterChip(project?.id == p.id, { projectId = p.id }, enabled = !busy && !submitting, label = { Text(p.name) }) }
            }
            if (!loaded) { LinearProgressIndicator(Modifier.fillMaxWidth()); notice?.let { Text(it, color = MaterialTheme.colorScheme.error) }; return@Column }
            if (project == null) { Text("Сначала добавьте проект в разделе «Проекты и код»."); return@Column }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PlanningStep.entries.filter { it != PlanningStep.STATUS }.forEachIndexed { index, target ->
                    FilterChip(step == target, { navigate(target) }, enabled = !busy && !submitting && when (target) {
                        PlanningStep.GOAL -> !running
                        PlanningStep.CLARIFY -> plan != null && !running
                        PlanningStep.REVIEW -> plan?.milestones?.isNotEmpty() == true
                        PlanningStep.STATUS -> running
                    }, label = { Text("${index + 1} · ${wizardLabel(target)}") })
                }
            }
            Text("Шаг ${step.ordinal + 1} из 3 · ${wizardLabel(step)}", style = MaterialTheme.typography.titleLarge)
            (notice ?: serviceError)?.let { error ->
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Column(Modifier.padding(16.dp)) {
                        Text(error, color = MaterialTheme.colorScheme.onErrorContainer)
                        if (plan != null && !running) TextButton(enabled = !busy && !submitting,
                            onClick = { doRefine(plan.dialogue.lastOrNull { it.role == "user" }?.text ?: INITIAL_PLANNING_MESSAGE) }) { Text("Повторить запрос") }
                    }
                }
            }
            when (step) {
                PlanningStep.GOAL -> {
                    Text("Опишите результат и выберите, кто поможет составить план.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(goal, { goal = it }, label = { Text("Цель и ожидаемый результат") }, minLines = 4,
                        enabled = !busy, modifier = Modifier.fillMaxWidth(), placeholder = { Text("Что нужно сделать и как проверить результат?") })
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("Модель для планирования", style = MaterialTheme.typography.titleMedium)
                            OutlinedButton(onClick = { pickPlanner = true }, enabled = !busy && !submitting) {
                                Text("${planner?.shortLabel ?: "Выбрать модель"} · ${planner?.effort?.shortLabel ?: "default"} ▾")
                            }
                            Text("Search engine", style = MaterialTheme.typography.titleMedium)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                SearchProvider.entries.forEach { provider -> FilterChip((plan?.searchProvider ?: draftSearch ?: settings.searchProvider) == provider,
                                    { if (plan == null) draftSearch = provider else edit { it.copy(searchProvider = provider) } }, enabled = !busy && !submitting, label = { Text(searchLabel(provider)) }) }
                            }
                            Text("Ключи Google и Querit задаются в настройках приложения.", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    if (planner?.configured != true) Text("Выберите подключённую модель из избранного.", color = MaterialTheme.colorScheme.error)
                    Button(enabled = !busy && !submitting && goal.isNotBlank() && planner?.configured == true, onClick = {
                        if (plan == null) {
                            val fresh = Plan(Id.new(), project.id, goal.trim(), plannerSelection = draftPlanner, searchProvider = draftSearch ?: settings.searchProvider,
                                wizardStep = PlanningStep.CLARIFY, createdAt = Id.now(), updatedAt = Id.now())
                            doRefine(INITIAL_PLANNING_MESSAGE, initial = fresh.copy(tree = listOf(DecisionNode("${fresh.id}-root", fresh.goal, DecisionKind.GOAL))))
                        } else if (goal.trim() != plan.goal) {
                            action {
                                execution.edit(plan.projectId, plan.revision) { p -> p.copy(goal = goal.trim(), wizardStep = PlanningStep.CLARIFY,
                                    tree = p.tree.map { if (it.kind == DecisionKind.GOAL) it.copy(title = goal.trim()) else it }) }
                                viewedStep = PlanningStep.CLARIFY
                                input = "Цель изменена: ${goal.trim()}. Уточни детали и обнови план."
                            }
                        } else navigate(PlanningStep.CLARIFY)
                    }) { Text("Далее · Уточнения") }
                }
                PlanningStep.CLARIFY -> if (plan != null) {
                    Text(plan.goal, style = MaterialTheme.typography.titleMedium)
                    Text("Ответьте на вопросы, добавьте ограничения и детали. Когда всё готово — постройте план.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Dialogue(plan, input, { input = it }, busy || submitting, { doRefine(it) }, activity)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(enabled = !busy && !submitting, onClick = { navigate(PlanningStep.GOAL) }) { Text("Назад · Цель") }
                        Button(enabled = !busy && !submitting && plan.dialogue.any { it.role == "assistant" }, onClick = {
                            doRefine(buildString { if (input.isNotBlank()) appendLine(input.trim()); append("Построй и оцени варианты по имеющимся ответам. Сохрани ручные решения.") })
                        }) { Text("Построить план") }
                    }
                }
                PlanningStep.REVIEW -> if (plan != null) {
                    Text(plan.goal, style = MaterialTheme.typography.titleMedium)
                    Text(if (running) "Статус выполнения отображается на графике. Нажмите на этап, чтобы открыть задание и работу агента." else "Проверьте этапы и модели. Нажмите на этап, чтобы открыть его задание. Подтверждение запускает реализацию.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (running) {
                    LinearProgressIndicator(progress = { plan.progress }, modifier = Modifier.fillMaxWidth())
                    Text("${phaseLabel(plan)} · ${plan.doneCount}/${plan.selectedMilestones.size} этапов", style = MaterialTheme.typography.titleMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(enabled = !submitting && plan.intent == ExecutionIntent.RUN, onClick = { action { execution.pause(plan.projectId) } }) { Text("Пауза") }
                        Button(enabled = runtime.supported && !submitting && plan.intent != ExecutionIntent.RUN && plan.phase != ExecutionPhase.COMPLETE, onClick = { action { execution.start(plan.projectId) } }) { Text("Продолжить") }
                        TextButton(enabled = !submitting && plan.intent != ExecutionIntent.STOP, onClick = { action { execution.stop(plan.projectId) } }) { Text("Остановить") }
                        if (plan.issue != null) TextButton(enabled = !submitting, onClick = { action { execution.retry(plan.projectId) } }) { Text("Повторить после исправления") }
                    }
                    plan.issue?.let { Text(it.message, color = MaterialTheme.colorScheme.error) }
                    }
                    DecisionGraph(plan, selected, { selected = it }, Modifier.fillMaxWidth().height(480.dp), fitInitially = true,
                        onChooseOption = if (busy || submitting) null else { choiceId, optionId -> edit { selectPlanningOption(it, choiceId, optionId) } })
                    if (!running) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(enabled = !busy && !submitting && !running, onClick = { navigate(PlanningStep.CLARIFY) }) { Text("Уточнить") }
                        Button(enabled = !busy && !submitting && !running && runtime.supported && plan.selectedMilestones.isNotEmpty() && DecisionCompiler.compile(plan).valid,
                            onClick = { action { execution.start(plan.projectId); viewedStep = PlanningStep.REVIEW } }) { Text("Подтвердить") }

                    }
                    if (!runtime.supported) Text("Запуск реализации доступен в Desktop.")
                    val validation = DecisionCompiler.compile(plan)
                    if (!validation.valid) Text(validation.errors.joinToString("\n"), color = MaterialTheme.colorScheme.error)
                    if (!running) {
                        TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "▾ Скрыть параметры" else "▸ Приоритеты и параллельность") }
                        if (advanced) {
                            Row {
                                Text("Параллельно: ${plan.parallelism}", Modifier.weight(1f))
                                TextButton(enabled = !submitting, onClick = { edit { it.copy(parallelism = (it.parallelism - 1).coerceAtLeast(1)) } }) { Text("−") }
                                TextButton(enabled = !submitting, onClick = { edit { it.copy(parallelism = (it.parallelism + 1).coerceAtMost(8)) } }) { Text("+") }
                            }
                            FlowRow { listOf("Качество" to plan.priorities.quality, "Скорость" to plan.priorities.speed, "Экономичность" to plan.priorities.economy, "Надёжность" to plan.priorities.safety).forEachIndexed { index, (label, value) ->
                                TextButton(enabled = !submitting, onClick = { edit { old ->
                                    val v = (value + 1) % 4
                                    val priorities = when (index) { 0 -> old.priorities.copy(quality = v); 1 -> old.priorities.copy(speed = v); 2 -> old.priorities.copy(economy = v); else -> old.priorities.copy(safety = v) }
                                    composer.recommendChoices(old.copy(priorities = priorities))
                                } }) { Text("$label: $value") }
                            } }
                        }
                    }
                    PlanningHistory(plan)
                    if (running && (plan.intent == ExecutionIntent.STOP || plan.phase == ExecutionPhase.COMPLETE)) TextButton(enabled = !submitting,
                        onClick = { action { store.deletePlan(plan.projectId); viewedStep = null; selected = null; goal = "" } }) { Text("Новая цель") }
                }
                PlanningStep.STATUS -> Unit // Legacy persisted step is displayed on the graph.

            }
            if (plan != null && selected != null) {
                val projected = planningGraphProjection(plan)
                projected.tree.firstOrNull { it.id == selected }?.let { node ->
                    StageDetailsDialog(projected, node, { selected = null }) {
                        if (!running && plan.tree.any { it.id == node.id }) {
                            NodeEditor(plan, node, profiles, ::edit)
                            TextButton(enabled = !busy && !submitting, onClick = {
                                selected = null; doRefine("Пересчитай участок «${node.title}».", node.id)
                            }) { Text("Уточнить этап") }
                        }
                    }
                }
            }
            if (pickPlanner) FavoriteModelPicker(profiles, choice,
                { selection -> if (plan == null) draftPlanner = selection else edit { it.copy(plannerSelection = selection) } }, { pickPlanner = false }, "Модель оркестратора",
                footer = { TextButton(onClick = { if (plan == null) draftPlanner = null else edit { it.copy(plannerSelection = null) }; pickPlanner = false }) { Text("Модель по умолчанию") } })
        }
    }
}

@Composable private fun Dialogue(plan: Plan, input: String, onInput: (String) -> Unit, busy: Boolean, onSend: (String) -> Unit, activity: List<CodingStep>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Уточнение цели", style = MaterialTheme.typography.titleMedium)
        val dialogueScroll = rememberScrollState()
        LaunchedEffect(plan.dialogue.size, dialogueScroll.maxValue) { dialogueScroll.animateScrollTo(dialogueScroll.maxValue) }
        Column(Modifier.heightIn(max = 360.dp).verticalScroll(dialogueScroll)) {
            if (plan.dialogue.isEmpty() && !busy) Text("Начните уточнение: модель задаст вопросы о результате и ограничениях.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            plan.dialogue.forEach { message ->
                Surface(Modifier.fillMaxWidth().padding(vertical = 4.dp), shape = MaterialTheme.shapes.medium,
                    color = if (message.role == "user") MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.secondaryContainer) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(if (message.role == "user") "Вы" else "Оркестратор", style = MaterialTheme.typography.labelMedium)
                        message.activity.forEach { CodingStepRow(it, false) }
                        ChatMarkdown(message.text)
                    }
                }
            }
        }
        if (busy || activity.isNotEmpty()) Card(Modifier.fillMaxWidth()) {
            val activityScroll = rememberScrollState()
            LaunchedEffect(activity, activityScroll.maxValue) { activityScroll.scrollTo(activityScroll.maxValue) }
            Column(Modifier.heightIn(max = 320.dp).verticalScroll(activityScroll).padding(12.dp)) {
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                activity.forEach { CodingStepRow(it, busy) }
            }
        }
        OutlinedTextField(input, onInput, label = { Text("Ответ, ограничение или изменение") }, modifier = Modifier.fillMaxWidth(), enabled = !busy)
        Button(onClick = { onSend(input) }, enabled = !busy && input.isNotBlank()) { Text(if (busy) "Уточняю…" else "Отправить") }

    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable private fun NodeEditor(plan: Plan, node: DecisionNode, profiles: List<LlmProfile>, edit: ((Plan) -> Plan) -> Unit) {
    val stage = plan.milestones.firstOrNull { it.id == (node.stageId ?: node.id) }
    val frozen = stage != null && (stage.attempts.isNotEmpty() || stage.status != MilestoneStatus.PENDING)
    var title by remember(node.id, node.title) { mutableStateOf(node.title) }
    var description by remember(stage?.id, stage?.description) { mutableStateOf(stage?.description.orEmpty()) }
    var acceptance by remember(stage?.id, stage?.acceptance) { mutableStateOf(stage?.acceptance.orEmpty()) }
    var complexity by remember(stage?.id, stage?.complexityPoints) { mutableStateOf(stage?.complexityPoints?.toString().orEmpty()) }
    val parsedComplexity = complexity.replace(',', '.').toDoubleOrNull()
    val complexityValid = complexity.isBlank() || (parsedComplexity != null && parsedComplexity.isFinite() && parsedComplexity > 0)
    fun updateStage(change: (Milestone) -> Milestone) = edit { old -> old.copy(milestones = old.milestones.map { if (it.id == stage?.id) change(it) else it }) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HorizontalDivider(); Text("Выбрано: ${node.title}", style = MaterialTheme.typography.titleMedium)
        val a = stage?.assessment ?: node.assessment
        Text("Качество: ${grade(a.quality)} · Скорость: ${grade(a.speed)} · Экономичность: ${grade(a.economy)} · Надёжность: ${grade(a.safety)}")
        if (a.explanation.isNotBlank()) Text(a.explanation)
        if (!frozen) FlowRow {
            listOf("Качество" to a.quality, "Скорость" to a.speed, "Экономичность" to a.economy, "Надёжность" to a.safety).forEachIndexed { i, (label, value) ->
                TextButton(onClick = { edit { old ->
                    val updated = a.withGrade(i, (value + 1) % 4)
                    old.copy(tree = old.tree.map { if (it.id == node.id) it.copy(assessment = updated) else it },
                        milestones = old.milestones.map { if (it.id == stage?.id) it.copy(assessment = updated) else it })
                } }) { Text("$label: ${grade(value)}") }
            }
        }
        if (node.kind == DecisionKind.CHOICE) plan.tree.filter { it.id in node.children }.forEach { option ->
            FilterChip(option.id == node.selectedOptionId, { edit { old -> old.copy(tree = old.tree.map { if (it.id == node.id) it.copy(selectedOptionId = option.id, manualSelection = true) else it }) } }, label = { Text(option.title) })
        }
        OutlinedTextField(title, { title = it }, enabled = !frozen, label = { Text("Название") }, modifier = Modifier.fillMaxWidth())
        if (stage != null) {
            OutlinedTextField(complexity, { complexity = it }, enabled = !frozen, label = { Text("Сложность, усл. ед.") },
                supportingText = { Text("Относительная оценка: например, 1, 2, 3, 5, 8, 13") },
                isError = !complexityValid, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(description, { description = it }, enabled = !frozen, label = { Text("Что сделать") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(acceptance, { acceptance = it }, enabled = !frozen, label = { Text("Критерии проверки") }, modifier = Modifier.fillMaxWidth())
        }
        TextButton(enabled = !frozen && title.isNotBlank() && complexityValid, onClick = { edit { old -> old.copy(goal = if (node.kind == DecisionKind.GOAL) title else old.goal, tree = old.tree.map { if (it.id == node.id) it.copy(title = title) else it }, milestones = old.milestones.map { if (it.id == stage?.id) it.copy(title = title, description = description, acceptance = acceptance, complexityPoints = parsedComplexity) else it }) } }) { Text("Сохранить изменения") }
        if (stage != null) {
            val assignment = stage.assignment
            Text(assignment?.let { "Модель: ${profiles.firstOrNull { p -> p.id == it.profileId }?.modelName(it.modelId) ?: it.displayName.ifBlank { it.modelId }} · effort: ${it.effort.shortLabel}" } ?: "Исполнитель не назначен")
            if (!frozen) {
                var menu by remember { mutableStateOf(false) }
                Box {
                    TextButton(onClick = { menu = true }) { Text("Выбрать модель") }
                    DropdownMenu(menu, { menu = false }) { profiles.filter { it.connectionConfigured && it.supportsCoding }.forEach { p -> p.displayModels.forEach { model ->
                        DropdownMenuItem(text = { Text("${p.name} · ${p.modelName(model)}") }, onClick = {
                            val effective = EffortSelection.ofOrNull(ModelDefaults.capability(p.copy(modelId = model)).resolveEffort(p.effortSelectionFor(model)).level)
                            updateStage { it.copy(agentProfileId = p.id, agentModelId = model, assignment = StageAssignment(p.id, model, effective, effective, manual = true, displayName = p.modelName(model))) }; menu = false
                        })
                    } } }
                }
                val profile = profiles.firstOrNull { it.id == assignment?.profileId }
                if (profile != null && assignment != null) {
                    val capability = ModelDefaults.capability(profile.copy(modelId = assignment.modelId))
                    EffortControl(capability, assignment.effort, { effort -> updateStage { it.copy(assignment = assignment.copy(effort = effort, effectiveEffort = EffortSelection.ofOrNull(capability.resolveEffort(effort).level), manual = true)) } })
                }
            }
            assignment?.explanation?.takeIf { it.isNotBlank() }?.let { Text(it) }
            if (frozen) Text("Этап начат. Конфигурация и история закреплены.")
            Text("Зависит от:")
            FlowRow { plan.milestones.filter { it.id != stage.id }.forEach { dep ->
                FilterChip(dep.id in stage.dependsOn, enabled = !frozen, onClick = { updateStage { it.copy(dependsOn = if (dep.id in it.dependsOn) it.dependsOn - dep.id else it.dependsOn + dep.id) } }, label = { Text(dep.title) })
            } }
            stage.attempts.forEach { attempt ->
                Text("Попытка ${attempt.id.take(8)} · ${attemptLabel(attempt.phase)} · ${attempt.assignment.displayName.ifBlank { attempt.assignment.modelId }} · ${attempt.assignment.effort.shortLabel}")
                if (attempt.activity.isNotBlank()) Text(attempt.activity)
                attempt.error?.let { Text(it.message, color = MaterialTheme.colorScheme.error) }
                if (attempt.report.isNotBlank()) Text(attempt.report)
            }
            if (stage.checkNote.isNotBlank()) Text("Проверка: ${stage.checkNote}")
        }
        if (node.kind != DecisionKind.STAGE) Row {
            if (node.kind != DecisionKind.CHOICE) TextButton(onClick = { edit { old ->
                val id = Id.new()
                old.copy(milestones = old.milestones + Milestone(id, "Новый этап"), tree = old.tree.map { if (it.id == node.id) it.copy(children = it.children + id) else it } + DecisionNode(id, "Новый этап", DecisionKind.STAGE, stageId = id))
            } }) { Text("+ Этап") }
            TextButton(onClick = { edit { old ->
                val choice = if (node.kind == DecisionKind.CHOICE) node.id else Id.new(); val option = Id.new()
                if (node.kind == DecisionKind.CHOICE) old.copy(tree = old.tree.map { if (it.id == node.id) it.copy(children = it.children + option) else it } + DecisionNode(option, "Новый вариант", DecisionKind.OPTION))
                else old.copy(tree = old.tree.map { if (it.id == node.id) it.copy(children = it.children + choice) else it } + listOf(DecisionNode(choice, "Выбор подхода", DecisionKind.CHOICE, listOf(option), option), DecisionNode(option, "Новый вариант", DecisionKind.OPTION)))
            } }) { Text(if (node.kind == DecisionKind.CHOICE) "+ Вариант" else "+ Выбор подхода") }
        }
        if (node.kind != DecisionKind.GOAL && !frozen) TextButton(onClick = { edit { old -> DecisionCompiler.removeNode(old, node.id) } }) { Text("Удалить узел и его ветвь") }
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

private const val INITIAL_PLANNING_MESSAGE = "Сначала задай до трёх уточняющих вопросов о критериях успеха и существенных ограничениях цели. Дождись моих ответов, прежде чем строить варианты."
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
    TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "▾ Скрыть историю планирования" else "▸ История планирования и действия агента") }
    if (expanded) plan.dialogue.forEach { message ->
        Text(if (message.role == "user") "Вы" else "Оркестратор", style = MaterialTheme.typography.labelLarge)
        message.activity.forEach { CodingStepRow(it, false) }
        ChatMarkdown(message.text)
    }
}

@Composable private fun AttemptActivity(attempt: StageAttempt) {
    Text("Попытка ${attempt.id.take(8)} · ${attemptLabel(attempt.phase)}", style = MaterialTheme.typography.labelLarge)
    val visibleSteps = readableStageActivity(attempt.steps.filter { it.isVisibleActivity })
    if (visibleSteps.isNotEmpty()) visibleSteps.forEach { CodingStepRow(it, attempt.phase == AttemptPhase.EXECUTING) }
    else if (attempt.activity.isNotBlank()) CodingStepRow(CodingStep(CodingStepKind.INFO, attempt.activity), attempt.phase == AttemptPhase.EXECUTING)
    if (visibleSteps.isEmpty() && attempt.report.isNotBlank()) ChatMarkdown(attempt.report)
    if (attempt.phase == AttemptPhase.EXECUTING && visibleSteps.isEmpty() && attempt.activity.isBlank() && attempt.report.isBlank()) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Text("Ожидание первых событий агента…", style = MaterialTheme.typography.bodyMedium)
        }
    }
    if (attempt.mergeReport.isNotBlank()) { Text("Объединение результата"); ChatMarkdown(attempt.mergeReport) }
    attempt.error?.let { Text(it.message, color = MaterialTheme.colorScheme.error) }
}

private fun milestoneLabel(status: MilestoneStatus) = when (status) {
    MilestoneStatus.PENDING -> "Ожидает запуска"
    MilestoneStatus.ACTIVE -> "Выполняется"
    MilestoneStatus.DONE -> "Готово"
    MilestoneStatus.FAILED -> "Ошибка"
    MilestoneStatus.SKIPPED -> "Пропущено"
}

@Composable internal fun StageDetailsDialog(plan: Plan, node: DecisionNode, onDismiss: () -> Unit, editor: @Composable () -> Unit = {}) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        StageDetailsContent(plan, node, onDismiss, Modifier.padding(16.dp).widthIn(max = 860.dp).fillMaxWidth().fillMaxHeight(.9f), editor)
    }
}

@Composable internal fun StageDetailsContent(plan: Plan, node: DecisionNode, onDismiss: () -> Unit, modifier: Modifier = Modifier, editor: @Composable () -> Unit = {}) {
    val stage = plan.milestones.firstOrNull { it.id == (node.stageId ?: node.id) }
        Surface(modifier, shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text(node.title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    TextButton(onClick = onDismiss) { Text("Закрыть") }
                }
                val scroll = rememberScrollState()
                Column(Modifier.weight(1f).verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (stage != null) {
                        Text(milestoneLabel(stage.status), style = MaterialTheme.typography.titleMedium)
                        stage.assignment?.let { Text("${it.displayName.ifBlank { it.modelId }} · ${it.effort.shortLabel}", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        Text("Задание этапа", style = MaterialTheme.typography.titleMedium)
                        ChatMarkdown(stage.description.ifBlank { stage.title })
                        if (stage.acceptance.isNotBlank()) { Text("Критерии готовности", style = MaterialTheme.typography.labelLarge); ChatMarkdown(stage.acceptance) }
                        if (stage.dependsOn.isNotEmpty()) Text("Зависимости: " + stage.dependsOn.joinToString { id -> plan.milestones.firstOrNull { it.id == id }?.title ?: id })
                        HorizontalDivider()
                        Text("Работа агента", style = MaterialTheme.typography.titleMedium)
                        if (stage.attempts.isEmpty()) Text("Этап ещё не запущен. Здесь появятся ответ и действия агента.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        stage.attempts.forEach { attempt ->
                            var showPrompt by remember(attempt.id) { mutableStateOf(false) }
                            if (attempt.prompt.isNotBlank()) {
                                TextButton(onClick = { showPrompt = !showPrompt }) { Text(if (showPrompt) "▾ Скрыть отправленный промпт" else "▸ Отправленный промпт") }
                                if (showPrompt) androidx.compose.foundation.text.selection.SelectionContainer { Text(attempt.prompt) }
                            }
                            AttemptActivity(attempt)
                        }
                        if (stage.checkNote.isNotBlank()) { Text("Проверка результата", style = MaterialTheme.typography.labelLarge); ChatMarkdown(stage.checkNote) }
                    } else {
                        ChatMarkdown(plan.goal)
                        Text(phaseLabel(plan), style = MaterialTheme.typography.titleMedium)
                        plan.issue?.let { Text(it.message, color = MaterialTheme.colorScheme.error) }
                        plan.journal.forEach { CodingStepRow(CodingStep(CodingStepKind.INFO, "${it.operation} · ${it.detail}"), false) }
                    }
                    editor()
                }
            }
        }
}
