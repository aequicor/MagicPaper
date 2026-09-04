package io.aequicor.magicpaper.plugins.builtin

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.aequicor.magicpaper.data.planning.PlanningStore
import io.aequicor.magicpaper.domain.AgentMatcher
import io.aequicor.magicpaper.domain.AppSettings
import io.aequicor.magicpaper.domain.CodingProject
import io.aequicor.magicpaper.domain.CodingProjectRepository
import io.aequicor.magicpaper.domain.CodingRuntime
import io.aequicor.magicpaper.domain.DossierResearcher
import io.aequicor.magicpaper.domain.DossierSource
import io.aequicor.magicpaper.domain.LlmProfile
import io.aequicor.magicpaper.domain.LlmProfileRepository
import io.aequicor.magicpaper.domain.Milestone
import io.aequicor.magicpaper.domain.MilestoneStatus
import io.aequicor.magicpaper.domain.ModelDossier
import io.aequicor.magicpaper.domain.Plan
import io.aequicor.magicpaper.domain.PlanComposer
import io.aequicor.magicpaper.domain.PlanDraft
import io.aequicor.magicpaper.domain.PlanRunner
import io.aequicor.magicpaper.domain.PlanStatus
import io.aequicor.magicpaper.domain.ProfileResolver
import io.aequicor.magicpaper.domain.SettingsRepository
import io.aequicor.magicpaper.plugins.MagicPlugin
import io.aequicor.magicpaper.util.Id
import kotlinx.coroutines.launch

/**
 * Плагин «Планирование» для кодинг-сессий:
 *  1. досье моделей — в какой области модель хороша и насколько (заполняет
 *     человек или модель один раз ищет о себе в публичных источниках);
 *  2. визуальный план задачи — график мэилстоунов, за каждым закреплён
 *     оптимальный агент (по досье: косинусная близость шага к сильным сторонам);
 *  3. автоматическое выполнение: каждый шаг выполняется своим агентом и
 *     проверяется на достижимость — план останавливается на первом провале.
 */
class CodingPlanningPlugin(
    private val store: PlanningStore,
    private val composer: PlanComposer,
    private val researcher: DossierResearcher,
    private val runner: PlanRunner,
    private val runtime: CodingRuntime,
    private val projectsRepo: CodingProjectRepository?,
    private val profileRepo: LlmProfileRepository,
    private val settingsRepo: SettingsRepository,
) : MagicPlugin {
    override val id = "coding-planning"
    override val title = "Планирование"
    override val description = "Досье моделей, график мэилстоунов и авто-выполнение с проверкой достижимости."
    override val icon = "⚑"

    @Composable
    override fun Content() {
        val scope = rememberCoroutineScope()
        val plans by store.plans.collectAsState()
        val dossiers by store.dossiers.collectAsState()
        var projects by remember { mutableStateOf<List<CodingProject>>(emptyList()) }
        var profiles by remember { mutableStateOf<List<LlmProfile>>(emptyList()) }
        var settings by remember { mutableStateOf(AppSettings()) }
        var selectedProjectId by rememberSaveable { mutableStateOf("") }
        var goal by rememberSaveable { mutableStateOf("") }
        var draft by remember { mutableStateOf<PlanDraft?>(null) }
        var composing by remember { mutableStateOf(false) }
        var researchingId by remember { mutableStateOf<String?>(null) }
        var running by remember { mutableStateOf(false) }
        var notice by remember { mutableStateOf<String?>(null) }
        val abortFlag = remember { mutableStateOf(false) }

        LaunchedEffect(Unit) {
            projects = projectsRepo?.all().orEmpty()
            profiles = profileRepo.all()
            settings = settingsRepo.load()
        }

        val project = projects.firstOrNull { it.id == selectedProjectId } ?: projects.firstOrNull()
        val plan = project?.let { p -> plans.firstOrNull { it.projectId == p.id } }

        // Актуальный профиль разрешения (модель-планировщик и модель-судья).
        fun judge(): LlmProfile? = ProfileResolver.resolve(null, settings, profiles)

        fun composePlan() {
            if (composing || project == null) return
            composing = true
            notice = null
            scope.launch {
                // Источники могли появиться, пока панель открыта.
                profiles = profileRepo.all()
                draft = composer.compose(goal.trim(), judge(), dossiers, profiles)
                composing = false
            }
        }

        fun approveDraft() {
            val current = draft ?: return
            val proj = project ?: return
            val now = Id.now()
            // Закрепление оптимального агента: имя из плана → реальный профиль,
            // иначе подбор по досье (близость шага к сильным сторонам + оценка).
            val milestones = current.milestones.map { step ->
                val named = profiles.firstOrNull { it.name == step.agent && it.configured }
                val bound = named ?: AgentMatcher.best("${step.title} ${step.description}", dossiers, profiles)
                Milestone(
                    id = Id.new(),
                    title = step.title,
                    description = step.description,
                    agentProfileId = bound?.id.orEmpty(),
                )
            }
            scope.launch {
                store.save(
                    Plan(
                        id = Id.new(),
                        projectId = proj.id,
                        goal = goal.trim(),
                        milestones = milestones,
                        createdAt = now,
                        updatedAt = now,
                    )
                )
                draft = null
                notice = "План сохранён: ${milestones.size} мэилстоунов. Можно выполнять."
            }
        }

        fun runPlan() {
            val current = plan ?: return
            val proj = project
            if (running) return
            if (!runtime.supported) {
                notice = "Кодинг-агент недоступен на этой платформе."
                return
            }
            running = true
            abortFlag.value = false
            notice = null
            scope.launch {
                profiles = profileRepo.all()
                val finalPlan = runner.run(
                    plan = current,
                    project = proj,
                    profiles = profiles,
                    judge = judge(),
                    onUpdate = { updated -> store.save(updated.copy(updatedAt = Id.now())) },
                    isAborted = { abortFlag.value },
                )
                running = false
                notice = when (finalPlan.status) {
                    PlanStatus.DONE -> "План выполнен: все мэилстоуны прошли проверку."
                    PlanStatus.FAILED -> "План остановлен: шаг не прошёл проверку достижимости."
                    PlanStatus.STOPPED -> "Выполнение остановлено."
                    else -> null
                }
            }
        }

        fun abortPlan() {
            abortFlag.value = true
            runtime.abort()
        }

        fun research(target: LlmProfile) {
            if (researchingId != null) return
            researchingId = target.id
            notice = null
            scope.launch {
                val dossier = researcher.research(target, judge(), settings)
                val existing = dossiers.firstOrNull { it.profileId == target.id }
                store.saveDossier(
                    dossier.copy(
                        id = existing?.id ?: Id.new(),
                        profileId = target.id,
                        updatedAt = Id.now(),
                    )
                )
                researchingId = null
                notice = "Досье «${target.shortLabel}» обновлено."
            }
        }

        fun saveMilestoneEdit(updated: Plan, milestone: Milestone) {
            scope.launch {
                store.save(updated.replace(milestone).copy(updatedAt = Id.now()))
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(vertical = 8.dp),
        ) {
            Text(icon + " " + title, style = MaterialTheme.typography.titleMedium)
            Text(
                "Модели получают досье (в чём сильны и насколько), задача разбивается на " +
                    "график мэилстоунов с оптимальным агентом на каждом шаге, выполнение идёт " +
                    "автоматически с проверкой достижимости каждого шага.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))

            DossierSection(
                profiles = profiles,
                dossiers = dossiers,
                researchingId = researchingId,
                onResearch = ::research,
                onSaveManual = { dossier ->
                    scope.launch {
                        store.saveDossier(dossier)
                        notice = "Досье сохранено."
                    }
                },
            )
            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(12.dp))

            PlanSection(
                projects = projects,
                project = project,
                selectedProjectId = selectedProjectId,
                onSelectProject = { selectedProjectId = it },
                goal = goal,
                onGoalChange = { goal = it },
                profiles = profiles,
                plan = plan,
                draft = draft,
                composing = composing,
                running = running,
                onCompose = ::composePlan,
                onApprove = ::approveDraft,
                onDismissDraft = { draft = null },
                onRun = ::runPlan,
                onAbort = ::abortPlan,
                onDeletePlan = {
                    val proj = project
                    if (proj != null) scope.launch { store.deletePlan(proj.id) }
                },
                onMilestoneUpdate = ::saveMilestoneEdit,
            )

            notice?.let {
                Spacer(Modifier.height(8.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }

    private fun Plan.replace(milestone: Milestone): Plan =
        copy(milestones = milestones.map { if (it.id == milestone.id) milestone else it })
}

// ---- Досье моделей ---------------------------------------------------------

@Composable
private fun DossierSection(
    profiles: List<LlmProfile>,
    dossiers: List<ModelDossier>,
    researchingId: String?,
    onResearch: (LlmProfile) -> Unit,
    onSaveManual: (ModelDossier) -> Unit,
) {
    Text("Досье агентов", style = MaterialTheme.typography.titleSmall)
    if (profiles.isEmpty()) {
        Text(
            "Нет настроенных источников. Добавьте провайдера в настройках — " +
                "досье нужно, чтобы выбирать оптимального агента для каждого шага.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    profiles.filter { it.configured }.forEach { profile ->
        val dossier = dossiers.firstOrNull { it.profileId == profile.id }
        DossierRow(
            profile = profile,
            dossier = dossier,
            researching = researchingId == profile.id,
            onResearch = { onResearch(profile) },
            onSaveManual = onSaveManual,
        )
    }
}

@Composable
private fun DossierRow(
    profile: LlmProfile,
    dossier: ModelDossier?,
    researching: Boolean,
    onResearch: () -> Unit,
    onSaveManual: (ModelDossier) -> Unit,
) {
    var editing by remember { mutableStateOf(false) }
    var strengths by remember(dossier) { mutableStateOf(dossier?.strengths.orEmpty()) }
    var rating by remember(dossier) { mutableStateOf(dossier?.rating ?: 0) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(profile.shortLabel, style = MaterialTheme.typography.bodyLarge)
            if (dossier == null || dossier.strengths.isBlank()) {
                Text(
                    "Досье не заполнено — опишите вручную или найдите в сети.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    dossier.strengths,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = if (editing) Int.MAX_VALUE else 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    sourceLabel(dossier),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        RatingStars(rating = dossier?.rating ?: 0)
        Spacer(Modifier.width(6.dp))
        TextButton(onClick = { editing = !editing }) {
            Text(if (editing) "Закрыть" else "Изменить")
        }
        TextButton(onClick = onResearch, enabled = !researching) {
            Text(
                when {
                    researching -> "Ищу…"
                    dossier?.source == DossierSource.WEB -> "Обновить из сети"
                    else -> "Найти в сети"
                }
            )
        }
    }
    if (editing) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 10.dp, end = 10.dp, bottom = 6.dp)
                .clip(MaterialTheme.shapes.medium)
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.6f))
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                "В какой области модель хороша и насколько сильна (для сравнения):",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = strengths,
                onValueChange = { strengths = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Сильные стороны") },
                minLines = 2,
                maxLines = 6,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Оценка силы:", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.width(8.dp))
                RatingPicker(rating = rating, onSelect = { rating = it })
            }
            Row {
                TextButton(
                    onClick = {
                        val existing = dossier
                        onSaveManual(
                            ModelDossier(
                                id = existing?.id ?: Id.new(),
                                profileId = profile.id,
                                strengths = strengths.trim(),
                                rating = rating,
                                source = DossierSource.USER,
                                updatedAt = Id.now(),
                            )
                        )
                        editing = false
                    },
                    enabled = strengths.isNotBlank(),
                ) {
                    Text("Сохранить досье")
                }
                TextButton(onClick = { editing = false }) { Text("Отменить") }
            }
        }
    }
}

private fun sourceLabel(dossier: ModelDossier): String = when (dossier.source) {
    DossierSource.USER -> "описано человеком"
    DossierSource.WEB -> "найдено в сети"
    DossierSource.HEURISTIC -> "черновик по имени модели"
} + if (dossier.note.isNotBlank()) " · ${dossier.note}" else ""

/** Пять звёзд оценки силы (только показ). */
@Composable
private fun RatingStars(rating: Int) {
    Row {
        repeat(5) { index ->
            Text(
                if (index < rating) "✦" else "·",
                style = MaterialTheme.typography.labelSmall,
                color = if (index < rating) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.outlineVariant
                },
            )
        }
    }
}

@Composable
private fun RatingPicker(rating: Int, onSelect: (Int) -> Unit) {
    Row {
        repeat(5) { index ->
            Text(
                if (index < rating) "✦" else "✧",
                style = MaterialTheme.typography.titleSmall,
                color = if (index < rating) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.outline
                },
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable { onSelect(if (rating == index + 1) 0 else index + 1) }
                    .padding(horizontal = 3.dp),
            )
        }
    }
}

// ---- План задачи ------------------------------------------------------------

@Composable
private fun PlanSection(
    projects: List<CodingProject>,
    project: CodingProject?,
    selectedProjectId: String,
    onSelectProject: (String) -> Unit,
    goal: String,
    onGoalChange: (String) -> Unit,
    profiles: List<LlmProfile>,
    plan: Plan?,
    draft: PlanDraft?,
    composing: Boolean,
    running: Boolean,
    onCompose: () -> Unit,
    onApprove: () -> Unit,
    onDismissDraft: () -> Unit,
    onRun: () -> Unit,
    onAbort: () -> Unit,
    onDeletePlan: () -> Unit,
    onMilestoneUpdate: (Plan, Milestone) -> Unit,
) {
    Text("План задачи", style = MaterialTheme.typography.titleSmall)
    if (projects.isEmpty()) {
        Text(
            "Сначала добавьте проект в разделе «Проекты и код» — план выполняется в его папке.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    var projectMenuOpen by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Проект:", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.width(6.dp))
        Box {
            TextButton(onClick = { projectMenuOpen = true }) {
                Text(project?.name ?: "выберите проект")
            }
            DropdownMenu(expanded = projectMenuOpen, onDismissRequest = { projectMenuOpen = false }) {
                projects.forEach { p ->
                    DropdownMenuItem(
                        text = { Text(p.name) },
                        onClick = {
                            onSelectProject(p.id)
                            projectMenuOpen = false
                        },
                    )
                }
            }
        }
    }
    OutlinedTextField(
        value = goal,
        onValueChange = onGoalChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text("Цель задачи") },
        placeholder = { Text("Например: добавить авторизацию с тестами") },
        minLines = 1,
        maxLines = 4,
    )
    Spacer(Modifier.height(6.dp))
    if (plan == null) {
        TextButton(onClick = onCompose, enabled = !composing && goal.isNotBlank()) {
            Text(if (composing) "Составляю план…" else "Составить план")
        }
    }

    // Черновик от планировщика: утвердить или отклонить.
    if (plan == null && draft != null) {
        Spacer(Modifier.height(8.dp))
        DraftView(draft = draft, onApprove = onApprove, onDismiss = onDismissDraft)
    }

    // Утверждённый план: график, статусы, выполнение.
    if (plan != null) {
        Spacer(Modifier.height(10.dp))
        PlanHeader(plan = plan, running = running)
        Spacer(Modifier.height(6.dp))
        PlanTimeline(plan = plan)
        Spacer(Modifier.height(10.dp))
        plan.milestones.forEach { milestone ->
            MilestoneRow(
                plan = plan,
                milestone = milestone,
                profiles = profiles,
                running = running,
                onMilestoneUpdate = onMilestoneUpdate,
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (running) {
                TextButton(onClick = onAbort) { Text("Прервать выполнение") }
            } else {
                TextButton(onClick = onRun, enabled = plan.nextPending != null) {
                    Text(
                        when {
                            plan.status == PlanStatus.DONE -> "Все шаги выполнены"
                            plan.status == PlanStatus.FAILED -> "Продолжить после сбоя"
                            else -> "Выполнить план"
                        }
                    )
                }
            }
            TextButton(onClick = onDeletePlan, enabled = !running) { Text("Удалить план") }
        }
    }
}

@Composable
private fun PlanHeader(plan: Plan, running: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            statusGlyph(plan.status),
            style = MaterialTheme.typography.titleMedium,
            color = planStatusColor(plan.status, MaterialTheme.colorScheme),
        )
        Spacer(Modifier.width(6.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                plan.goal,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${plan.doneCount}/${plan.milestones.size} · ${planStatusLabel(plan.status, running)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    Spacer(Modifier.height(4.dp))
    LinearProgressIndicator(
        progress = { plan.progress },
        modifier = Modifier.fillMaxWidth().height(6.dp).clip(MaterialTheme.shapes.small),
        color = MaterialTheme.colorScheme.primary,
        trackColor = MaterialTheme.colorScheme.outlineVariant,
    )
}

/** Визуальный график: цепочка вех, соединённых линиями, цвет — статус. */
@Composable
private fun PlanTimeline(plan: Plan) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.Top,
    ) {
        plan.milestones.forEachIndexed { index, milestone ->
            if (index > 0) {
                // Коннектор: линия на уровне центра кружка вехи (20/2 - 1).
                Box(
                    modifier = Modifier.width(16.dp).height(20.dp),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    Box(
                        modifier = Modifier
                            .padding(top = 9.dp)
                            .width(16.dp)
                            .height(2.dp)
                            .background(scheme.outlineVariant)
                    )
                }
            }
            Column(
                modifier = Modifier.width(84.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(milestoneColor(milestone.status, scheme)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "${index + 1}",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (milestone.status == MilestoneStatus.PENDING) {
                            scheme.onSurfaceVariant
                        } else {
                            scheme.surface
                        },
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    milestone.title,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun DraftView(draft: PlanDraft, onApprove: () -> Unit, onDismiss: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surface)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("Черновик плана", style = MaterialTheme.typography.titleSmall)
        draft.note.takeIf { it.isNotBlank() }?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        draft.milestones.forEachIndexed { index, step ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.small)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                Text(
                    "${index + 1}. ${step.title}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (step.description.isNotBlank()) {
                    Text(
                        step.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (step.agent.isNotBlank()) {
                    Text(
                        "оптимальный агент: ${step.agent}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
        Row {
            TextButton(onClick = onApprove, enabled = draft.milestones.isNotEmpty()) {
                Text("Утвердить план")
            }
            TextButton(onClick = onDismiss) { Text("Отменить") }
        }
    }
}

@Composable
private fun MilestoneRow(
    plan: Plan,
    milestone: Milestone,
    profiles: List<LlmProfile>,
    running: Boolean,
    onMilestoneUpdate: (Plan, Milestone) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val agent = profiles.firstOrNull { it.id == milestone.agentProfileId }
    var agentMenuOpen by remember { mutableStateOf(false) }
    var showReport by remember { mutableStateOf(false) }
    val hasReport = milestone.report.isNotBlank() || milestone.checkNote.isNotBlank()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                milestoneGlyph(milestone.status),
                style = MaterialTheme.typography.bodyMedium,
                color = milestoneColor(milestone.status, scheme),
            )
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    milestone.title,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (milestone.description.isNotBlank()) {
                    Text(
                        milestone.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box {
                TextButton(onClick = { agentMenuOpen = true }, enabled = !running) {
                    Text(
                        agent?.shortLabel ?: "агент не назначен",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (agent != null) scheme.primary else scheme.error,
                    )
                }
                DropdownMenu(expanded = agentMenuOpen, onDismissRequest = { agentMenuOpen = false }) {
                    profiles.filter { it.configured }.forEach { p ->
                        DropdownMenuItem(
                            text = { Text(p.shortLabel) },
                            onClick = {
                                onMilestoneUpdate(plan, milestone.copy(agentProfileId = p.id))
                                agentMenuOpen = false
                            },
                        )
                    }
                }
            }
            Spacer(Modifier.width(4.dp))
            if (hasReport) {
                TextButton(onClick = { showReport = !showReport }) {
                    Text(if (showReport) "Скрыть отчёт" else "Отчёт и проверка")
                }
            }
            if (!running && (milestone.status == MilestoneStatus.PENDING || milestone.status == MilestoneStatus.FAILED)) {
                TextButton(onClick = {
                    onMilestoneUpdate(plan, milestone.copy(status = MilestoneStatus.SKIPPED, updatedAt = Id.now()))
                }) {
                    Text("Пропустить")
                }
            }
        }
        if (showReport && hasReport) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.small)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    .padding(8.dp),
            ) {
                if (milestone.report.isNotBlank()) {
                    Text(
                        milestone.report,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (milestone.checkNote.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Проверка: ${milestone.checkNote}",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (milestone.status == MilestoneStatus.FAILED) scheme.error else scheme.primary,
                    )
                }
            }
        }
    }
}

// ---- Подписи и цвета ---------------------------------------------------------

private fun milestoneGlyph(status: MilestoneStatus): String = when (status) {
    MilestoneStatus.PENDING -> "○"
    MilestoneStatus.ACTIVE -> "◷"
    MilestoneStatus.DONE -> "✔"
    MilestoneStatus.FAILED -> "✕"
    MilestoneStatus.SKIPPED -> "↷"
}

private fun statusGlyph(status: PlanStatus): String = when (status) {
    PlanStatus.DRAFT -> "◷"
    PlanStatus.RUNNING -> "◷"
    PlanStatus.DONE -> "✦"
    PlanStatus.FAILED -> "✕"
    PlanStatus.STOPPED -> "▪"
}

private fun planStatusLabel(status: PlanStatus, running: Boolean): String = when {
    running -> "выполняется…"
    else -> when (status) {
        PlanStatus.DRAFT -> "составлен"
        PlanStatus.RUNNING -> "выполняется"
        PlanStatus.DONE -> "завершён"
        PlanStatus.FAILED -> "остановлен на проверке"
        PlanStatus.STOPPED -> "остановлен"
    }
}

private fun milestoneColor(status: MilestoneStatus, scheme: androidx.compose.material3.ColorScheme): Color =
    when (status) {
        MilestoneStatus.PENDING -> scheme.outline
        MilestoneStatus.ACTIVE -> scheme.primary
        MilestoneStatus.DONE -> scheme.secondary
        MilestoneStatus.FAILED -> scheme.error
        MilestoneStatus.SKIPPED -> scheme.outlineVariant
    }

private fun planStatusColor(status: PlanStatus, scheme: androidx.compose.material3.ColorScheme): Color =
    when (status) {
        PlanStatus.DRAFT -> scheme.outline
        PlanStatus.RUNNING -> scheme.primary
        PlanStatus.DONE -> scheme.secondary
        PlanStatus.FAILED -> scheme.error
        PlanStatus.STOPPED -> scheme.outlineVariant
    }
