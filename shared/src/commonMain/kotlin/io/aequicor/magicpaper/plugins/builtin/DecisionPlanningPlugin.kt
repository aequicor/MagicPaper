package io.aequicor.magicpaper.plugins.builtin

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.aequicor.magicpaper.data.planning.PlanningStore
import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.plugins.MagicPlugin
import io.aequicor.magicpaper.plugins.CodingSessionPanel
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
        var projectId by rememberSaveable { mutableStateOf("") }
        var goal by rememberSaveable { mutableStateOf("") }
        var input by rememberSaveable { mutableStateOf("") }
        var selected by rememberSaveable { mutableStateOf<String?>(null) }
        var notice by remember { mutableStateOf<String?>(null) }
        var busy by remember { mutableStateOf(false) }
        var tab by rememberSaveable { mutableStateOf(0) }
        var showDossiers by remember { mutableStateOf(false) }
        var pickPlanner by remember { mutableStateOf(false) }
        var settings by remember { mutableStateOf(AppSettings()) }
        fun action(block: suspend () -> Unit) { scope.launch {
            try { block(); notice = null } catch (e: CancellationException) { throw e }
            catch (e: Exception) { notice = e.message ?: "Не удалось выполнить действие" }
        } }
        LaunchedEffect(Unit) {
            try { store.plans(); store.dossiers(); projects = projectsRepo?.all().orEmpty(); profiles = profileRepo.load(); settings = settingsRepo.load() }
            catch (e: Exception) { notice = e.message }
        }
        val project = locked ?: projects.firstOrNull { it.id == projectId } ?: projects.firstOrNull()
        fun preview(attempt: StageAttempt): StageAttempt {
            val current = live[attempt.id]
            return if (current != null && current.updatedAt > attempt.updatedAt)
                attempt.copy(report = current.report, activity = current.activity, mergeReport = current.mergeReport) else attempt
        }
        val plan = plans.firstOrNull { it.projectId == project?.id }?.let { saved -> saved.copy(
            milestones = saved.milestones.map { stage -> stage.copy(attempts = stage.attempts.map(::preview)) },
            finalAttempt = saved.finalAttempt?.let(::preview),
        ) }
        fun edit(change: (Plan) -> Plan) { val current = plan ?: return; action { execution.edit(current.projectId, current.revision, change) } }
        fun doRefine(message: String, nodeId: String?) {
            val current = plan ?: return
            if (busy || message.isBlank()) return
            busy = true
            scope.launch {
                try {
                    val pending = store.update(current.projectId) { p -> p.copy(dialogue = p.dialogue + PlanningMessage(Id.new(), "user", message)) }
                    profiles = profileRepo.load()
                    settings = settingsRepo.load()
                    val judge = pending.plannerSelection?.let { ProfileResolver.selection(it, profiles) }
                        ?: ProfileResolver.resolve(null as ChatSession?, settings, profiles)
                    val result = if (nodeId == null) composer.refine(pending, message, judge, profiles, dossiers)
                        else composer.recalculate(pending, nodeId, judge, profiles, dossiers)
                    execution.applyProposal(pending, result)
                    input = ""; notice = null
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { notice = e.message }
                finally { busy = false }
            }
        }
        fun refine(message: String) = doRefine(message, null)
        Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("⚑ Дерево решений проекта", style = MaterialTheme.typography.titleLarge)
            Text("Уточните цель, сравните варианты и запустите выбранный путь. Работа продолжится после перезапуска приложения.")
            if (locked == null) FlowRow { projects.forEach { p -> FilterChip(project?.id == p.id, { projectId = p.id; selected = null }, label = { Text(p.name) }) } }
            if (project == null) { Text("Сначала добавьте проект в разделе «Проекты и код»."); return@Column }
            if (plan == null) {
                OutlinedTextField(goal, { goal = it }, label = { Text("Цель и ожидаемый результат") }, modifier = Modifier.fillMaxWidth())
                Button(enabled = goal.isNotBlank(), onClick = { action {
                    val fresh = Plan(Id.new(), project.id, goal.trim(), createdAt = Id.now(), updatedAt = Id.now())
                    store.save(fresh.copy(tree = listOf(DecisionNode("${fresh.id}-root", fresh.goal, DecisionKind.GOAL))))
                } }) { Text("Начать планирование") }
            } else {
                Text(plan.goal, style = MaterialTheme.typography.titleMedium)
                val planner = plan.plannerSelection?.let { ProfileResolver.selection(it, profiles) }
                    ?: ProfileResolver.resolve(null as ChatSession?, settings, profiles)
                TextButton(onClick = { pickPlanner = true }, enabled = !busy) {
                    Text("Планировщик: ${planner?.shortLabel ?: "выбрать модель"} · ${planner?.effort?.shortLabel ?: "default"}${if (plan.plannerSelection == null) " · по умолчанию" else ""} ▾", style = MaterialTheme.typography.labelMedium)
                }
                LinearProgressIndicator(progress = { plan.progress }, modifier = Modifier.fillMaxWidth())
                Text("${phaseLabel(plan)} · ${plan.doneCount}/${plan.selectedMilestones.size} этапов")
                FlowRow {
                    Button(onClick = { action { execution.start(plan.projectId) } }, enabled = runtime.supported && plan.intent != ExecutionIntent.RUN && plan.phase != ExecutionPhase.COMPLETE) { Text("Утвердить и запустить") }
                    TextButton(onClick = { action { execution.pause(plan.projectId) } }, enabled = plan.intent == ExecutionIntent.RUN) { Text("Пауза") }
                    TextButton(onClick = { action { execution.stop(plan.projectId) } }, enabled = plan.intent != ExecutionIntent.STOP) { Text("Остановить") }
                    if (plan.issue != null) TextButton(onClick = { action { execution.retry(plan.projectId) } }) { Text("Повторить после исправления") }

                    if (plan.intent == ExecutionIntent.STOP || plan.phase == ExecutionPhase.COMPLETE)
                        TextButton(onClick = { action { store.deletePlan(plan.projectId); selected = null; goal = "" } }) { Text("Новая цель") }
                }
                if (!runtime.supported) Text("На этой платформе доступны планирование и редактирование. Реализация — в Desktop.")
                plan.issue?.let { issue ->
                    Text(issue.message, color = MaterialTheme.colorScheme.error)
                    Text(if (issue.requiresUser) "Нужно ваше решение. Прогресс сохранён." else if (issue.retryAt > 0) "Автоматический повтор ${issue.retries}/3" else "Продолжение автоматически после устранения причины.")
                }
                plan.finalAttempt?.let { final ->
                    Text("Итоговая проверка: ${attemptLabel(final.phase)}", style = MaterialTheme.typography.labelLarge)
                    if (final.activity.isNotBlank()) Text(final.activity.takeLast(1000))
                }
                Row {
                    Text("Параллельно: ${plan.parallelism}", Modifier.weight(1f))
                    TextButton(onClick = { edit { it.copy(parallelism = (it.parallelism - 1).coerceAtLeast(1)) } }) { Text("−") }
                    TextButton(onClick = { edit { it.copy(parallelism = (it.parallelism + 1).coerceAtMost(8)) } }) { Text("+") }
                }
                Text("Приоритеты: 0 — не учитывать, 3 — высокий")
                FlowRow { listOf("Качество" to plan.priorities.quality, "Скорость" to plan.priorities.speed, "Экономичность" to plan.priorities.economy, "Надёжность" to plan.priorities.safety).forEachIndexed { index, (label, value) ->
                    TextButton(onClick = { edit { old ->
                        val v = (value + 1) % 4
                        val p = when (index) { 0 -> old.priorities.copy(quality = v); 1 -> old.priorities.copy(speed = v); 2 -> old.priorities.copy(economy = v); else -> old.priorities.copy(safety = v) }
                        composer.recommendChoices(old.copy(priorities = p))
                    } }) { Text("$label: $value") }
                } }
                BoxWithConstraints {
                    if (maxWidth >= 800.dp) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.width(280.dp)) { Dialogue(plan, input, { input = it }, busy, ::refine) }
                        DecisionGraph(plan, selected, { selected = it }, Modifier.weight(1f).height(480.dp))
                    } else Column {
                        Row { TextButton(onClick = { tab = 0 }) { Text("Дерево") }; TextButton(onClick = { tab = 1 }) { Text("Уточнения") } }
                        if (tab == 0) DecisionGraph(plan, selected, { selected = it }, Modifier.fillMaxWidth().height(420.dp))
                        else Dialogue(plan, input, { input = it }, busy, ::refine)
                    }
                }
                val projected = io.aequicor.magicpaper.ui.components.planningGraphProjection(plan)
                val editorPlan = if (plan.tree.none { it.id == selected } && projected.tree.any { it.id == selected }) projected else plan
                (editorPlan.tree.firstOrNull { it.id == selected } ?: plan.tree.firstOrNull { it.kind == DecisionKind.GOAL })?.let { node ->
                    NodeEditor(editorPlan, node, profiles, ::edit)
                    if (!busy && plan.tree.any { it.id == node.id } && plan.finalAttempt == null)
                        TextButton(onClick = { doRefine("Пересчитай участок «${node.title}», сохрани остальные решения.", node.id) }) { Text("Пересчитать этот участок") }
                }
                val validation = DecisionCompiler.compile(plan)
                if (!validation.valid) Text(validation.errors.joinToString("\n"), color = MaterialTheme.colorScheme.error)
            }
            TextButton(onClick = { showDossiers = !showDossiers }) { Text("${if (showDossiers) "▾" else "▸"} Избранные модели · ${profiles.sumOf { it.displayModels.size }}") }
            if (showDossiers) profiles.forEach { p -> p.displayModels.forEach { model ->
                val dossier = dossiers.forModel(p, model)
                Text("${p.name} · ${p.modelName(model)}", style = MaterialTheme.typography.titleSmall)
                Text(dossier?.strengths?.ifBlank { "Описание не заполнено" } ?: "Описание не заполнено", style = MaterialTheme.typography.bodySmall)
                dossier?.limitations?.takeIf { it.isNotBlank() }?.let { Text("Ограничения: $it", style = MaterialTheme.typography.bodySmall) }
                Text("Оценка: ${dossier?.rating?.takeIf { it > 0 }?.let { "$it/5" } ?: "не задана"} · Effort: ${ModelDefaults.capability(p, model).selectableLevels.joinToString { it.shortLabel }.ifBlank { "по умолчанию" }}", style = MaterialTheme.typography.labelSmall)
                if (!p.supportsCoding) Text("Для чатов и разработки плана; исполнение этапов этим поставщиком пока недоступно.", style = MaterialTheme.typography.labelSmall)
            } }
            if (pickPlanner && plan != null) FavoriteModelPicker(profiles, plan.plannerSelection,
                { choice -> edit { it.copy(plannerSelection = choice) } }, { pickPlanner = false }, "Модель планировщика", footer = {
                    TextButton(onClick = { edit { it.copy(plannerSelection = null) }; pickPlanner = false }) { Text("Модель по умолчанию") }
                })
            (notice ?: serviceError)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable private fun Dialogue(plan: Plan, input: String, onInput: (String) -> Unit, busy: Boolean, onSend: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Уточнение цели", style = MaterialTheme.typography.titleMedium)
        Column(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState())) {
            plan.dialogue.forEach { Text("${if (it.role == "user") "Вы" else "Планировщик"}: ${it.text}") }
        }
        OutlinedTextField(input, onInput, label = { Text("Ответ, ограничение или изменение") }, modifier = Modifier.fillMaxWidth(), enabled = !busy)
        Button(onClick = { onSend(input) }, enabled = !busy && input.isNotBlank()) { Text(if (busy) "Уточняю…" else "Отправить") }
        TextButton(onClick = { onSend(if (plan.dialogue.isEmpty()) "Уточни цель и предложи существенные вопросы." else "Построй и оцени варианты по имеющимся ответам. Сохрани ручные решения.") }, enabled = !busy) { Text(if (plan.dialogue.isEmpty()) "Начать уточнение" else "Построить варианты") }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable private fun NodeEditor(plan: Plan, node: DecisionNode, profiles: List<LlmProfile>, edit: ((Plan) -> Plan) -> Unit) {
    val stage = plan.milestones.firstOrNull { it.id == (node.stageId ?: node.id) }
    val frozen = stage != null && (stage.attempts.isNotEmpty() || stage.status != MilestoneStatus.PENDING)
    var title by remember(node.id, node.title) { mutableStateOf(node.title) }
    var description by remember(stage?.id, stage?.description) { mutableStateOf(stage?.description.orEmpty()) }
    var acceptance by remember(stage?.id, stage?.acceptance) { mutableStateOf(stage?.acceptance.orEmpty()) }
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
            OutlinedTextField(description, { description = it }, enabled = !frozen, label = { Text("Что сделать") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(acceptance, { acceptance = it }, enabled = !frozen, label = { Text("Критерии проверки") }, modifier = Modifier.fillMaxWidth())
        }
        TextButton(enabled = !frozen && title.isNotBlank(), onClick = { edit { old -> old.copy(goal = if (node.kind == DecisionKind.GOAL) title else old.goal, tree = old.tree.map { if (it.id == node.id) it.copy(title = title) else it }, milestones = old.milestones.map { if (it.id == stage?.id) it.copy(title = title, description = description, acceptance = acceptance) else it }) } }) { Text("Сохранить изменения") }
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
