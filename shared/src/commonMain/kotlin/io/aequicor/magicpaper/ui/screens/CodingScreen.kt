package io.aequicor.magicpaper.ui.screens

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.border
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.material3.FilterChip
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.aequicor.magicpaper.domain.Attachment
import io.aequicor.magicpaper.domain.CodingDraft
import io.aequicor.magicpaper.domain.CodingApproval
import io.aequicor.magicpaper.domain.CodingApprovalDecision
import io.aequicor.magicpaper.ui.components.CodingApprovalDock
import io.aequicor.magicpaper.domain.CodingMessage
import io.aequicor.magicpaper.domain.CodingProject
import io.aequicor.magicpaper.domain.CodingRole
import io.aequicor.magicpaper.domain.CodingSessionStatus
import io.aequicor.magicpaper.domain.CodingStep
import io.aequicor.magicpaper.domain.PlanningChatService
import io.aequicor.magicpaper.domain.SearchProvider
import io.aequicor.magicpaper.domain.ExecutionIntent
import io.aequicor.magicpaper.domain.MilestoneStatus
import io.aequicor.magicpaper.ui.components.PlanningQuestionsDock
import io.aequicor.magicpaper.ui.components.PlanningBlockerDock
import io.aequicor.magicpaper.ui.components.PlanningChatMessage
import io.aequicor.magicpaper.ui.components.codingChatRows
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import io.aequicor.magicpaper.domain.isVisibleActivity
import io.aequicor.magicpaper.domain.CodingStepKind
import io.aequicor.magicpaper.domain.LlmProfile
import io.aequicor.magicpaper.domain.ProviderType
import io.aequicor.magicpaper.domain.RuntimePhase
import io.aequicor.magicpaper.domain.RuntimeStatus
import io.aequicor.magicpaper.plugins.CodingSessionPanel
import io.aequicor.magicpaper.ui.CodingSessionMode
import io.aequicor.magicpaper.ui.CodingSessionUi
import io.aequicor.magicpaper.ui.CodingUi
import io.aequicor.magicpaper.ui.MagicPaperViewModel
import io.aequicor.magicpaper.ui.withStageChat
import io.aequicor.magicpaper.ui.components.ChatMarkdown
import io.aequicor.magicpaper.ui.components.ChatScrollItem
import io.aequicor.magicpaper.ui.components.chatDisclosure
import io.aequicor.magicpaper.ui.components.CodingAttachments
import io.aequicor.magicpaper.ui.components.CodingModelChip
import io.aequicor.magicpaper.ui.components.CodingModelSwitcherDialog
import io.aequicor.magicpaper.ui.components.PendingAttachmentsRow
import io.aequicor.magicpaper.ui.components.stickToBottom
import io.aequicor.magicpaper.ui.theme.MagicFonts

/**
 * Экран «Проекты и код»: в проекте несколько кодинг-сессий, у каждой — кружок
 * активности. Сессии живут в левом меню и крепятся к своему проекту: список
 * раскрыт под выбранным проектом, справа — журнал активной сессии.
 * [profiles]/[activeProfileId] — источники ИИ для переключателя модели сессии.
 */
@Composable
fun CodingScreen(
    vm: MagicPaperViewModel,
    ui: CodingUi,
    panelPlugin: CodingSessionPanel? = null,
    profiles: List<LlmProfile> = emptyList(),
    activeProfileId: String = "",
) {
    Column(modifier = Modifier.fillMaxSize()) {
        RuntimeBar(ui.runtime, ui.installing, vm::prepareCodingRuntime, vm::uninstallCodingRuntime)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        ResizableProjectPanels(modifier = Modifier.weight(1f), sidebar = { panelModifier ->
            ProjectsPanel(
                ui = ui,
                onAddProject = vm::addCodingProject,
                onSelectProject = vm::selectCodingProject,
                onDeleteProject = vm::deleteCodingProject,
                onDeleteAllSessions = vm::deleteAllCodingSessions,
                onSelectSession = vm::selectCodingSession,
                onAddSession = vm::addCodingSession,
                onDeleteSession = vm::deleteCodingSession,
                onAbortSession = vm::abortCodingSession,
                modifier = panelModifier,
            )
        }) {
                val project = ui.current
                val activeId = project?.let { ui.activeSessionIdOf(it.id) }
                val active = project?.let { ui.sessionsOf(it.id) }?.firstOrNull { it.session.id == activeId }
                if (project == null || active == null) {
                    ProjectsEmptyHint(hasProject = project != null)
                } else {
                    SessionArea(
                        vm = vm,
                        ui = ui,
                        project = project,
                        active = active,
                        panelPlugin = panelPlugin,
                        profiles = profiles,
                        activeProfileId = activeProfileId,
                    )
                }
        }
    }
}

@Composable
internal fun ResizableProjectPanels(
    modifier: Modifier = Modifier,
    sidebar: @Composable (Modifier) -> Unit,
    content: @Composable () -> Unit,
) {
    var preferredWidth by rememberSaveable { mutableStateOf(272f) }
    val density = LocalDensity.current
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val maximum = (maxWidth.value - 328f).coerceIn(160f, 600f)
        val minimum = minOf(200f, maximum)
        val panelWidth = preferredWidth.coerceIn(minimum, maximum)
        Row(Modifier.fillMaxSize()) {
            sidebar(Modifier.width(panelWidth.dp))
            Box(
                Modifier.width(8.dp).fillMaxHeight()
                    .semantics { contentDescription = "Изменить ширину списка сессий" }
                    .draggable(rememberDraggableState { delta ->
                        preferredWidth = (preferredWidth.coerceIn(minimum, maximum) + with(density) { delta.toDp().value }).coerceIn(minimum, maximum)
                    }, Orientation.Horizontal),
                contentAlignment = Alignment.Center,
            ) {
                VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Box(Modifier.width(3.dp).height(28.dp).background(MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.small))
            }
            Box(Modifier.weight(1f)) { content() }
        }
    }
}

/** Содержимое правой части: вкладки режима сессии и журнал (или панель плагина). */
@Composable
private fun SessionArea(
    vm: MagicPaperViewModel,
    ui: CodingUi,
    project: CodingProject,
    active: CodingSessionUi,
    panelPlugin: CodingSessionPanel?,
    profiles: List<LlmProfile>,
    activeProfileId: String,
) {
    // Переключатель источника/модели/усилия активной сессии.
    var switcherOpen by rememberSaveable(active.session.id) { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxSize()) {
        val service = vm.planningChat
        val scope = rememberCoroutineScope()
        val serviceError = service?.error?.collectAsState()?.value
        serviceError?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(12.dp)) }
        val serviceDrafts = service?.drafts?.collectAsState()?.value.orEmpty()
        val plans = service?.store?.plans?.collectAsState()?.value.orEmpty()
        val live = service?.execution?.live?.collectAsState()?.value.orEmpty()
        val workerPlan = plans.firstOrNull { it.id == active.session.planId }
        val parentMessages = ui.sessions.firstOrNull { it.session.id == active.session.parentSessionId }?.messages.orEmpty()
        val stageChat = active.withStageChat(workerPlan, live, parentMessages)
        val draft = serviceDrafts[active.session.id] ?: stageChat.draft
        val effective = stageChat.copy(draft = draft.copy(awaitingApproval = active.draft.awaitingApproval),
            running = stageChat.running || draft.active)
            CodingChat(
                project = project,
                session = effective,
                approvals = ui.approvals.filter { it.projectId == project.id },
                onApproval = vm::respondCodingApproval,
                onStopApproval = vm::abortCodingSession,
                busy = effective.running,
                allowQueue = active.session.stageId != null,
                planningService = service,
                planningQuestionsSession = ui.sessions.firstOrNull { it.session.id == active.session.parentSessionId } ?: effective,
                onOpenSession = vm::selectCodingSession,
                engineReady = active.session.planningMode || active.session.stageId != null || ui.runtime.ready || (
                    vm.codingProfileOf(active.session)?.provider == ProviderType.OPENAI_SUBSCRIPTION &&
                        vm.openAiSubscriptionSignedIn()
                    ),
                onSend = { text, attachments -> vm.sendCodingPromptTo(active.session.id, text, attachments) },
                onAbort = {
                    when {
                        active.session.stageId != null && active.session.planId != null -> service?.control(active.session.planId, "stop")
                        serviceDrafts[active.session.id]?.active == true -> service?.cancelRequest(active.session.id)
                        else -> vm.abortCodingSession(active.session.id)
                    }
                },
                onPickAttachments = { already, onPicked -> vm.pickAttachments(already, onPicked) },
                onPlanning = if (service != null && active.session.stageId == null) {
                    { scope.launch { service.configure(active.session, planning = true) } }
                } else null,
                modelChip = {
                    if (service != null && active.session.planningMode && active.session.stageId == null) {
                        var searchMenu by remember { mutableStateOf(false) }
                        Box {
                            TextButton(onClick = { searchMenu = true }, modifier = Modifier.height(32.dp), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) { Text("${active.session.searchProvider.name} ▾", style = MaterialTheme.typography.labelMedium) }
                            DropdownMenu(searchMenu, { searchMenu = false }) {
                                SearchProvider.entries.forEach { provider -> DropdownMenuItem(text = { Text(provider.name) }, onClick = { searchMenu = false; scope.launch { service.configure(active.session, search = provider) } }) }
                            }
                        }
                    }
                    CodingModelChip(
                        profile = vm.codingProfileOf(active.session),
                        overridden = active.session.llmProfileId != null,
                        onClick = { switcherOpen = true },
                    )
                },
            )
    }
    if (switcherOpen) {
        CodingModelSwitcherDialog(
            vm = vm,
            sessionId = active.session.id,
            profiles = profiles,
            activeProfileId = activeProfileId,
            sessionProfileId = active.session.llmProfileId,
            onDismiss = { switcherOpen = false },
        )
    }
}

@Composable
private fun SessionTab(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(MaterialTheme.shapes.small)
            .background(
                if (selected) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surface
                }
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 5.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

@Composable
private fun RuntimeBar(
    runtime: RuntimeStatus,
    installing: Boolean,
    onPrepare: () -> Unit,
    onUninstall: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            runtimeGlyph(runtime),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                buildString {
                    append("Движок: ")
                    append(runtimeLabel(runtime))
                    if (runtime.version.isNotBlank()) append(" · v${runtime.version}")
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                runtime.detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
            )
        }
        if (installing) {
            CircularProgressIndicator(
                modifier = Modifier.padding(end = 8.dp).height(16.dp).width(16.dp),
                strokeWidth = 2.dp,
            )
        } else if (!runtime.ready) {
            TextButton(onClick = onPrepare, modifier = Modifier.heightIn(min = 40.dp)) {
                Text("Подготовить движок")
            }
        }
        TextButton(onClick = onUninstall, modifier = Modifier.heightIn(min = 40.dp)) {
            Text("Удалить зависимости")
        }
    }
}

private fun runtimeGlyph(runtime: RuntimeStatus): String = when {
    runtime.ready -> "✦"
    runtime.phase == RuntimePhase.ERROR -> "✕"
    runtime.phase == RuntimePhase.UNSUPPORTED -> "✕"
    else -> "◷"
}

private fun runtimeLabel(runtime: RuntimeStatus): String = when (runtime.phase) {
    RuntimePhase.READY -> "готов"
    RuntimePhase.CHECKING -> "проверка"
    RuntimePhase.INSTALLING -> "установка"
    RuntimePhase.ERROR -> "ошибка"
    RuntimePhase.UNSUPPORTED -> "недоступен на этой платформе"
    RuntimePhase.UNKNOWN -> "неизвестно"
}

// ---- Кружок активности ----------------------------------------------------

/**
 * Цвета активности кодинг-сессии: вне пастельной схемы — это сигнальные
 * цвета состояния, они должны читаться мгновенно.
 */
private val StatusWorking = Color(0xFFCE5B5B)   // красный: агент работает
private val StatusWaiting = Color(0xFFE0A63C)   // жёлтый: ждёт ответа или подтверждения
private val StatusIdle = Color(0xFF79A97C)      // зелёный: ждёт запроса
private val StatusQueued = Color(0xFF97959B)    // серый: ждёт передачи работы

private val CodingSessionStatus.label: String
    get() = when (this) {
        CodingSessionStatus.WORKING -> "работает"
        CodingSessionStatus.WAITING -> "ждёт ответа или подтверждения"
        CodingSessionStatus.BLOCKED -> "выполнение остановлено"
        CodingSessionStatus.QUEUED -> "ждёт планировщика"
        CodingSessionStatus.IDLE -> "ждёт запроса"
    }

/** Индикатор-кружок: пульсирует при работе, иначе залипка цветом статуса. */
@Composable
fun ActivityDot(
    status: CodingSessionStatus,
    modifier: Modifier = Modifier,
    size: Int = 10,
) {
    val color = when (status) {
        CodingSessionStatus.WORKING -> StatusWorking
        CodingSessionStatus.WAITING -> StatusWaiting
        CodingSessionStatus.BLOCKED -> Color(0xFFC77843)
        CodingSessionStatus.QUEUED -> StatusQueued
        CodingSessionStatus.IDLE -> StatusIdle
    }
    val pulse by animateFloatAsState(
        targetValue = if (status == CodingSessionStatus.WORKING) 1f else 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = 700,
                easing = FastOutSlowInEasing,
            ),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "activityDotPulse",
    )
    val scale = if (status == CodingSessionStatus.WORKING) 0.75f + 0.25f * pulse else 1f
    Box(
        modifier = modifier
            .size(size.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(androidx.compose.foundation.shape.CircleShape)
            .background(color),
    )
}

/**
 * Левое меню раздела: список проектов, а под выбранным — его кодинг-сессии.
 * У каждой сессии свой кружок статуса и меню (прервать, удалить).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ProjectsPanel(
    ui: CodingUi,
    onAddProject: () -> Unit,
    onSelectProject: (String) -> Unit,
    onDeleteProject: (String) -> Unit,
    onSelectSession: (String) -> Unit,
    onAddSession: () -> Unit,
    onDeleteSession: (String) -> Unit,
    onAbortSession: (String) -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    onDeleteAllSessions: (String) -> Unit = {},
) {
    Column(modifier = modifier.fillMaxSize()) {
        Text(
            "Проекты",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        val collapsed = remember { mutableStateMapOf<String, Boolean>() }
        // Disclosure is local UI state: never reload a project or reset its active session.
        // Selecting another project opens its list; status updates preserve disclosure.
        var projectCollapsed by remember(ui.current?.id) { mutableStateOf(false) }
        LazyColumn(state = listState, modifier = Modifier.weight(1f)) {
            var position = 0
            ui.projects.forEach { project ->
                val selected = project.id == ui.current?.id
                val expanded = selected && !projectCollapsed
                val own = ui.sessionsOf(project.id)
                val projectHeader: @Composable (Boolean) -> Unit = { compact ->
                    ProjectRow(project, selected, expanded, ui.statusOf(project.id), own.count { it.running }, own.size,
                        {
                            if (selected) projectCollapsed = !projectCollapsed
                            else onSelectProject(project.id)
                        }, { onDeleteProject(project.id) }, compact, { onDeleteAllSessions(project.id) })
                }
                val projectPosition = position
                stickyHeader(key = "project-${project.id}") {
                    val pinned = listState.firstVisibleItemIndex > projectPosition ||
                        (listState.firstVisibleItemIndex == projectPosition && listState.firstVisibleItemScrollOffset > 0)
                    Column(Modifier.fillMaxWidth().background(if (pinned) MaterialTheme.colorScheme.surface else Color.Transparent)) { projectHeader(pinned) }
                }
                position++
                if (expanded) {
                    val activeId = ui.activeSessionIdOf(project.id)
                    val ids = own.map { it.session.id }.toSet()
                    own.filter { it.session.parentSessionId !in ids }.forEach { sessionUi ->
                        val children = own.filter { it.session.parentSessionId == sessionUi.session.id }
                        val showChildren = collapsed[sessionUi.session.id] != true
                        val headerPosition = position++
                        val sessionRow: @Composable () -> Unit = {
                            SessionRow(sessionUi, sessionUi.session.id == activeId,
                                { onSelectSession(sessionUi.session.id) }, { onDeleteSession(sessionUi.session.id) },
                                { onAbortSession(sessionUi.session.id) },
                                childCount = children.size, expanded = showChildren,
                                onToggleChildren = { collapsed[sessionUi.session.id] = showChildren })
                        }
                        if (sessionUi.session.planningMode || children.isNotEmpty()) {
                            stickyHeader(key = "session-${sessionUi.session.id}") {
                                val pinned = listState.firstVisibleItemIndex > headerPosition ||
                                    (listState.firstVisibleItemIndex == headerPosition && listState.firstVisibleItemScrollOffset > 0)
                                Column(Modifier.fillMaxWidth().background(if (pinned) MaterialTheme.colorScheme.surface else Color.Transparent)) {
                                    if (pinned) projectHeader(true)
                                    sessionRow()
                                }
                            }
                        } else item(key = "session-${sessionUi.session.id}") { sessionRow() }
                        if (showChildren) children.forEach { child ->
                            item(key = "session-${child.session.id}") {
                                SessionRow(child, child.session.id == activeId,
                                    { onSelectSession(child.session.id) }, { onDeleteSession(child.session.id) },
                                    { onAbortSession(child.session.id) }, nested = true)
                            }
                            position++
                        }
                    }
                    item(key = "add-${project.id}") { AddSessionRow(onAdd = onAddSession) }
                    position++
                }
            }
        }
        TextButton(onClick = onAddProject, modifier = Modifier.padding(8.dp)) {
            Text("✦ Новый проект")
        }
    }
}

@Composable
private fun ProjectRow(
    project: CodingProject,
    selected: Boolean,
    expanded: Boolean,
    status: CodingSessionStatus,
    runningSessions: Int,
    sessionCount: Int,
    onSelect: () -> Unit,
    onDelete: () -> Unit,
    compact: Boolean = false,
    onDeleteAllSessions: () -> Unit = {},
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .clip(MaterialTheme.shapes.small)
            .clickable(onClick = onSelect)
            .padding(start = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Стрелка-маркер: под выбранным проектом раскрыт список его сессий.
        if (!compact) Text(
            if (expanded) "▾" else "▸",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.width(14.dp),
        )
        StatusTooltip(status) { ActivityDot(status, size = 9) }
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                project.name,
                fontWeight = FontWeight.SemiBold,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyLarge,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            if (!compact) Text(
                buildString {
                    append("$sessionCount ${sessionCountWord(sessionCount)}")
                    if (sessionCount > 0) {
                        if (runningSessions > 0) append(", $runningSessions работают")
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        RowMenu(key = "project-${project.id}", entries = listOf("Удалить все сессии" to onDeleteAllSessions, "Удалить проект" to onDelete))
    }
}

/**
 * Строка кодинг-сессии в левом меню: отступ слева показывает, что сессия
 * принадлежит проекту выше.
 */
@Composable
private fun SessionRow(
    item: CodingSessionUi,
    selected: Boolean,
    onSelect: () -> Unit,
    onDelete: () -> Unit,
    onAbort: () -> Unit,
    nested: Boolean = false,
    childCount: Int = 0,
    expanded: Boolean = false,
    onToggleChildren: () -> Unit = {},
) {
    val status = item.status
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = if (nested) 42.dp else 20.dp, end = 8.dp, top = 2.dp, bottom = 2.dp)
            .clip(MaterialTheme.shapes.small)
            .background(
                if (selected) {
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                } else {
                    Color.Transparent
                }
            )
            .clickable(onClick = onSelect)
            .padding(horizontal = 6.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusTooltip(status) { ActivityDot(status, size = 8) }
        Spacer(Modifier.width(7.dp))
        Column(Modifier.weight(1f)) {
            Text(
                (if (item.session.planningMode && !item.session.name.startsWith("🔀")) "🔀 " else "") + item.session.name,
                style = if (nested) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (status != CodingSessionStatus.WAITING && (nested || item.running || status == CodingSessionStatus.BLOCKED)) Text(
                (if (nested) "Этап · " else "") +
                    if (status == CodingSessionStatus.IDLE && item.plan?.milestones?.firstOrNull { it.id == item.session.stageId }?.attempts?.lastOrNull()?.awaitingPlanner == true)
                        "передан планировщику" else status.label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
            )
        }
        if (childCount > 0) {
            Box(Modifier.size(24.dp).semantics { contentDescription = if (expanded) "Свернуть этапы" else "Раскрыть этапы" }
                .clickable(onClick = onToggleChildren), contentAlignment = Alignment.Center) {
                Text(if (expanded) "▾" else "▸", color = MaterialTheme.colorScheme.primary)
            }
            Text(childCount.toString(), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp))
        }
        RowMenu(
            key = "session-${item.session.id}",
            entries = buildList<Pair<String, () -> Unit>> {
                if (item.running) add("Прервать прогон" to onAbort)
                add("Удалить сессию" to onDelete)
            },
        )
    }
}

@Composable
private fun AddSessionRow(onAdd: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 8.dp, top = 2.dp, bottom = 6.dp)
            .clip(MaterialTheme.shapes.small)
            .clickable(onClick = onAdd)
            .heightIn(min = 30.dp)
            .padding(horizontal = 12.dp, vertical = 5.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            "✦ новая сессия",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

/** Якорь «⋯» с выпадающим меню: общий для строк проекта и кодинг-сессии. */
@Composable
private fun RowMenu(key: String, entries: List<Pair<String, () -> Unit>>) {
    var open by rememberSaveable(key) { mutableStateOf(false) }
    Box {
        Text(
            "⋯",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .clip(MaterialTheme.shapes.small)
                .clickable { open = true }
                .padding(horizontal = 6.dp),
        )
        if (open) {
            DropdownMenu(expanded = true, onDismissRequest = { open = false }) {
                entries.forEach { (label, action) ->
                    DropdownMenuItem(
                        text = { Text(label) },
                        onClick = {
                            open = false
                            action()
                        },
                    )
                }
            }
        }
    }
}

/** Русское склонение счётчика: 1 сессия, 2 сессии, 5 сессий. */
private fun sessionCountWord(count: Int): String = when {
    count % 100 in 11..19 -> "сессий"
    count % 10 in 2..4 -> "сессии"
    count % 10 == 1 -> "сессия"
    else -> "сессий"
}

@Composable
private fun StatusTooltip(status: CodingSessionStatus, content: @Composable () -> Unit) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(
            androidx.compose.material3.TooltipAnchorPosition.Above,
        ),
        tooltip = {
            androidx.compose.material3.Surface(
                color = MaterialTheme.colorScheme.inverseSurface,
                shape = MaterialTheme.shapes.small,
            ) {
                Text(
                    status.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.inverseOnSurface,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        },
        state = rememberTooltipState(),
    ) { content() }
}

@Composable
private fun ProjectsEmptyHint(hasProject: Boolean) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("✦", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(8.dp))
        Text(
            if (hasProject) {
                "В проекте пока нет кодинг-сессий. Добавьте сессию в левом меню — у каждой свой контекст и журнал."
            } else {
                "Добавьте проект — папку, в которой агент будет читать файлы и выполнять запросы."
            },
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun CodingChat(
    project: CodingProject,
    session: CodingSessionUi,
    busy: Boolean,
    engineReady: Boolean,
    onSend: (String, List<Attachment>) -> Unit,
    onAbort: () -> Unit,
    onPickAttachments: (Int, (List<Attachment>) -> Unit) -> Unit,
    modelChip: (@Composable () -> Unit)? = null,
    planningService: PlanningChatService? = null,
    planningQuestionsSession: CodingSessionUi = session,
    onOpenSession: (String) -> Unit = {},
    allowQueue: Boolean = false,
    onPlanning: (() -> Unit)? = null,
    approvals: List<CodingApproval> = emptyList(),
    onApproval: (String, CodingApprovalDecision) -> Unit = { _, _ -> },
    onStopApproval: (String) -> Unit = {},
) {
    val listState = rememberLazyListState()
    val messages = session.messages
    val rows = remember(messages) { codingChatRows(messages) }
    val draft = session.draft
    var thinkingExpanded by rememberSaveable(session.session.id, busy) { mutableStateOf(false) }
    val hasDraft = draft.steps.isNotEmpty() || draft.thinking.isNotBlank() || draft.failedMessage != null
    val statusMessageId = messages.lastOrNull()?.takeIf { it.role == CodingRole.AGENT && !hasDraft }?.id
    val status: @Composable () -> Unit = {
        AgentMessageStatus(draft, thinkingExpanded, { thinkingExpanded = !thinkingExpanded })
    }
    // Живая лента держит конец: новый шаг прогона или доросший ответ видны сразу,
    // а не «с начала сообщения». Открутил журнал вверх — не мешаем читать.
    val scroll = stickToBottom(listState, session.session.id)
    val density = LocalDensity.current
    var footerHeight by remember { mutableStateOf(0.dp) }
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val questionHeight = maxHeight * 0.55f
        val blockerHeight = maxHeight * 0.4f
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize()
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    // Fade only the messages; keep the paper background continuous.
                    val edge = size.height - footerHeight.toPx()
                    drawRect(Brush.verticalGradient(
                        0f to Color.White, 0.35f to Color.White.copy(alpha = 0.8f),
                        0.7f to Color.White.copy(alpha = 0.25f), 1f to Color.Transparent,
                        startY = edge - 16.dp.toPx(), endY = edge + 16.dp.toPx(),
                    ), blendMode = BlendMode.DstIn)
                },
            contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = footerHeight + 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Text(
                    "Проект «${project.name}» · сессия «${if (session.session.planningMode && !session.session.name.startsWith("🔀")) "🔀 " else ""}${session.session.name}» · ${project.path}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(rows, key = { it.message.id }) { row ->
                val message = row.message
                ChatScrollItem(scroll, message.id) {
                    CodingMessageBubble(message) {
                        if (busy && statusMessageId != null &&
                            (message.id == statusMessageId || row.planCard?.id == statusMessageId)
                        ) status()
                        if (planningService != null && message.planning != null) {
                            Spacer(Modifier.height(6.dp))
                            PlanningChatMessage(message, session.session, messages, planningService, onOpenSession)
                        }
                        row.planCard?.let { card ->
                            Spacer(Modifier.height(12.dp))
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            Spacer(Modifier.height(12.dp))
                            ChatMarkdown(card.text)
                            if (planningService != null) {
                                Spacer(Modifier.height(6.dp))
                                PlanningChatMessage(card, session.session, messages, planningService, onOpenSession)
                            }
                        }
                        if (message.pendingDelivery) Text("Ожидает передачи после текущего хода", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            if (hasDraft || (busy && statusMessageId == null)) {
                item(key = "draft") {
                    ChatScrollItem(scroll, "draft") { DraftBubble(draft, if (busy) status else null) }
                }
            }
        }
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
            .onSizeChanged { footerHeight = with(density) { it.height.toDp() } }) {
            CodingApprovalDock(approvals, onApproval, onStopApproval,
                Modifier.fillMaxWidth().heightIn(max = questionHeight).padding(horizontal = 12.dp, vertical = 4.dp))
            if (planningService != null && approvals.isEmpty()) PlanningQuestionsDock(
                planningQuestionsSession.session, planningQuestionsSession.messages, planningService,
                planningService.drafts.collectAsState().value[planningQuestionsSession.session.id]?.active == true,
                Modifier.fillMaxWidth().heightIn(max = questionHeight).padding(bottom = 4.dp),
            )
            if (planningService != null && approvals.isEmpty()) PlanningBlockerDock(
                planningQuestionsSession.session, planningQuestionsSession.messages, planningService, busy,
                Modifier.fillMaxWidth().heightIn(max = blockerHeight).padding(horizontal = 12.dp, vertical = 4.dp),
            )
            CodingComposer(
                enabled = engineReady && (!busy || allowQueue),
                busy = busy && !allowQueue,
                controls = modelChip,
                planning = session.session.planningMode,
                onPlanning = onPlanning,
                onSend = onSend,
                onAbort = onAbort,
                onPickAttachments = onPickAttachments,
            )
        }
    }
}

/** Бабл записи журнала: лента прогона в хронологическом порядке либо просто текст. */
@Composable
private fun CodingMessageBubble(message: CodingMessage, footer: (@Composable () -> Unit)? = null) {
    val isUser = message.role == CodingRole.USER
    val bubbleColor = if (isUser) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 680.dp)
                .clip(
                    androidx.compose.foundation.shape.RoundedCornerShape(
                        topStart = if (isUser) 20.dp else 6.dp,
                        topEnd = if (isUser) 6.dp else 20.dp,
                        bottomStart = 20.dp,
                        bottomEnd = 20.dp,
                    )
                )
                .background(bubbleColor)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            if (isUser) {
                SelectionContainer { Text(message.text, style = MaterialTheme.typography.bodyLarge) }
                // Прикреплённые к запросу файлы (лежат в изолированной папке рантайма).
                CodingAttachments(message.attachments)
            } else if (message.steps.isNotEmpty()) {
                // Лента: текст и действия идут как приходили — в хронологическом порядке.
                message.steps.forEach { step -> CodingStepRow(step, live = false) }
            } else {
                // Совместимость со старыми журналами без ленты.
                SelectionContainer {
                    Column {
                        Text(
                            message.text,
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (message.failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                        )
                        if (message.activity.isNotEmpty()) {
                            Spacer(Modifier.height(6.dp))
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "Действия агента:",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            message.activity.forEach { line ->
                                Text(
                                    text = line,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
            footer?.invoke()
        }
    }
}

/**
 * Строка ленты прогона: текст ответа — как есть, действия — сворачиваемой строкой
 * с раскрывающимся выводом инструмента (что реально пришло в ответ).
 */
@Composable
internal fun CodingStepRow(step: CodingStep, live: Boolean) {
    if (!step.isVisibleActivity) return
    if (step.kind == CodingStepKind.INFO && io.aequicor.magicpaper.ui.components.LocalHideSystemSteps.current) return
    when (step.kind) {
        CodingStepKind.ANSWER -> {
            Spacer(Modifier.height(4.dp))
            ChatMarkdown(text = step.title)
            Spacer(Modifier.height(4.dp))
        }
        CodingStepKind.THINKING -> ThinkingStepRow(step)
        CodingStepKind.ERROR -> SelectionContainer {
            Text(
                "✕ ${step.title}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(vertical = 3.dp),
            )
        }
        CodingStepKind.INFO -> SelectionContainer {
            Text(
                "◷ ${step.title}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 2.dp),
            )
        }
        CodingStepKind.TOOL, CodingStepKind.EXEC -> ToolStepRow(step, live)
    }
}

/** Свёрнутая строка рассуждения в ленте: весь текст — по клику (нижняя панель и так его показывает). */
@Composable
private fun ThinkingStepRow(step: CodingStep) {
    var expanded by rememberSaveable("thinking-" + step.title.take(24).hashCode()) { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.5f))
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Row(Modifier.chatDisclosure { expanded = !expanded }, verticalAlignment = Alignment.CenterVertically) {
            Text(
                "💭",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                if (expanded) "Размышление агента" else "Размышление агента… (клик — раскрыть)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(
                if (expanded) "▴" else "▾",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (expanded) {
            Spacer(Modifier.height(4.dp))
            ChatMarkdown(step.title)
        }
    }
}

/** Команда или действие: часы до завершения, полный текст и вывод по тапу. */
@Composable
private fun ToolStepRow(step: CodingStep, live: Boolean) {
    var expanded by rememberSaveable(step.callId.ifBlank { step.title }) { mutableStateOf(false) }
    val statusColor = when {
        step.running -> MaterialTheme.colorScheme.primary
        !step.ok -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val hasDetail = step.result.isNotBlank()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.5f))
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().chatDisclosure { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Canvas(
                modifier = Modifier.size(14.dp).semantics {
                    contentDescription = when {
                        step.running -> "Выполняется"
                        !step.ok -> "Ошибка выполнения"
                        else -> "Выполнено"
                    }
                },
            ) {
                val stroke = 1.4.dp.toPx()
                // Draw every status inside the icon bounds, independent of text line height.
                when {
                    step.running -> {
                        drawCircle(statusColor, radius = size.minDimension / 2 - stroke / 2, style = Stroke(stroke))
                        drawLine(statusColor, center, Offset(center.x, size.height * 0.25f), stroke, StrokeCap.Round)
                        drawLine(statusColor, center, Offset(size.width * 0.72f, center.y), stroke, StrokeCap.Round)
                    }
                    step.ok -> {
                        val bend = Offset(size.width * 0.4f, size.height * 0.76f)
                        drawLine(statusColor, Offset(size.width * 0.16f, size.height * 0.52f), bend, stroke, StrokeCap.Round)
                        drawLine(statusColor, bend, Offset(size.width * 0.84f, size.height * 0.24f), stroke, StrokeCap.Round)
                    }
                    else -> {
                        drawLine(statusColor, Offset(size.width * 0.22f, size.height * 0.22f),
                            Offset(size.width * 0.78f, size.height * 0.78f), stroke, StrokeCap.Round)
                        drawLine(statusColor, Offset(size.width * 0.78f, size.height * 0.22f),
                            Offset(size.width * 0.22f, size.height * 0.78f), stroke, StrokeCap.Round)
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            Text(
                step.title,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = if (expanded) Int.MAX_VALUE else 2,
                modifier = Modifier.weight(1f),
            )
            Text(
                if (expanded) "▴" else "▾",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (step.running && live) {
            Text(
                if (step.kind == CodingStepKind.EXEC) "Выполняется команда…" else "Выполняется действие…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (expanded && hasDetail) {
            SelectionContainer {
                Text(
                    step.result,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = MagicFonts.code),
                    color = if (step.ok) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

/** Текущий ответ агента со статусом и раскрываемыми размышлениями внизу. */
@Composable
private fun DraftBubble(draft: CodingDraft, footer: (@Composable () -> Unit)? = null) {
    Column(
        Modifier.widthIn(max = 680.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.7f))
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        draft.steps.filter { footer == null || it.kind != CodingStepKind.THINKING }
            .forEach { CodingStepRow(it, live = true) }
        footer?.invoke()
    }
}

@Composable
internal fun AgentMessageStatus(draft: CodingDraft, expanded: Boolean, onToggle: () -> Unit) {
    val recorded = draft.steps.filter { it.kind == CodingStepKind.THINKING }.map { it.title }
    // The recorder normally includes the live fragment in steps; other runtimes may send it separately.
    val fragments = if (draft.thinking.isNotBlank() && recorded.lastOrNull() != draft.thinking)
        recorded + draft.thinking else recorded
    val thinking = fragments.joinToString("\n\n").trim()
    val tool = draft.steps.lastOrNull { it.running && it.kind in listOf(CodingStepKind.TOOL, CodingStepKind.EXEC) }
    val progress = draft.steps.lastOrNull()?.takeIf { it.kind == CodingStepKind.INFO && it.running }
    val currentThinking = draft.thinking.ifBlank {
        draft.steps.lastOrNull()?.takeIf { it.kind == CodingStepKind.THINKING }?.title.orEmpty()
    }
    val activity = when {
        draft.failedMessage != null -> "Работа остановлена из-за ошибки"
        !draft.active -> "Работа завершена"
        draft.awaitingApproval -> "Ожидает подтверждения…"
        progress != null -> progress.title
        tool?.kind == CodingStepKind.EXEC -> "Агент выполняет команду…"
        tool != null -> "Агент выполняет действие…"
        draft.awaitingModel -> "Ожидает ответа модели…"
        currentThinking.isNotBlank() -> currentThinkingSummary(currentThinking)
        draft.steps.lastOrNull()?.kind == CodingStepKind.ANSWER -> "Готовит ответ…"
        else -> "Агент работает…"
    }
    val hasThinking = thinking.isNotBlank()
    val isWorking = draft.active && !draft.awaitingApproval && draft.failedMessage == null &&
        (progress != null || tool != null || !draft.awaitingModel)
    var dots by remember { mutableStateOf(3) }
    LaunchedEffect(isWorking) {
        dots = 3
        if (isWorking) {
            while (true) {
                delay(500)
                dots = if (dots == 1) 3 else dots - 1
            }
        }
    }
    val label = buildAnnotatedString {
        if (isWorking) {
            append(activity.trimEnd('.', '…', ' '))
            append(".".repeat(dots))
            // Keep the status width stable throughout the animation.
            withStyle(SpanStyle(color = Color.Transparent)) {
                append(".".repeat(3 - dots))
            }
        } else {
            append(activity)
        }
    }
    Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ActivityDot(
                if (isWorking) CodingSessionStatus.WORKING
                else if (draft.active) CodingSessionStatus.WAITING else CodingSessionStatus.IDLE,
                size = 6,
            )
            Spacer(Modifier.width(6.dp))
            Text("Сейчас:", style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(4.dp))
            Text(label, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false))
        }
        if (hasThinking) {
            Column(Modifier.fillMaxWidth().padding(top = 6.dp)
                .clip(MaterialTheme.shapes.small)
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.5f))
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), MaterialTheme.shapes.small)) {
                Row(
                    Modifier.fillMaxWidth().chatDisclosure(onToggle).semantics {
                        contentDescription = if (expanded) "Свернуть размышления" else "Развернуть размышления"
                    }.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Размышления агента", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    Text(if (expanded) "▴" else "▾", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (expanded) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                        if (draft.active) {
                            Text("Текст обновляется по мере ответа агента", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(6.dp))
                        }
                        val scroll = rememberScrollState()
                        Box(Modifier.fillMaxWidth().heightIn(max = 190.dp).verticalScroll(scroll)) {
                            ChatMarkdown(thinking, compact = true)
                        }
                        LaunchedEffect(thinking) { scroll.scrollTo(scroll.maxValue) }
                    }
                }
            }
        }
    }
}

/** Use the latest heading supplied by the agent as the short activity label. */
private fun currentThinkingSummary(thinking: String): String {
    val heading = Regex("(?m)^\\s*(?:#{1,6}\\s+([^\\n]+)|\\*\\*([^*\\n]+)\\*\\*)")
        .findAll(thinking).lastOrNull()
        ?.let { it.groupValues[1].ifBlank { it.groupValues[2] } }
    val summary = heading ?: thinking.trim().substringAfterLast("\n\n").lineSequence().firstOrNull().orEmpty()
    return summary.replace(Regex("[*_`#]"), "").replace(Regex("\\s+"), " ").trim().take(120)
        .ifBlank { "Обдумывает задачу" }
}

@Composable
private fun CodingComposer(
    enabled: Boolean,
    busy: Boolean,
    controls: (@Composable () -> Unit)? = null,
    planning: Boolean = false,
    onPlanning: (() -> Unit)? = null,
    onSend: (String, List<Attachment>) -> Unit,
    onAbort: () -> Unit,
    onPickAttachments: (Int, (List<Attachment>) -> Unit) -> Unit,
) {
    var text by rememberSaveable { mutableStateOf("") }
    var attachments by remember { mutableStateOf<List<Attachment>>(emptyList()) }
    fun submit() {
        if (text.isBlank() && attachments.isEmpty()) return
        onSend(text, attachments)
        text = ""
        attachments = emptyList()
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val trailingLimit = maxWidth * 0.45f
        Column(Modifier.fillMaxWidth()
            .clip(MaterialTheme.shapes.large.copy(
                bottomStart = CornerSize(0.dp),
                bottomEnd = CornerSize(0.dp),
            ))
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 4.dp, vertical = 2.dp)) {
            PendingAttachmentsRow(attachments, { target -> attachments = attachments.filterNot { it.id == target.id } })
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box {
                    var addMenuOpen by remember { mutableStateOf(false) }
                    TextButton(onClick = { addMenuOpen = true },
                        modifier = Modifier.size(32.dp).semantics { contentDescription = "Добавить" },
                        contentPadding = PaddingValues(0.dp)) {
                        Text("+", style = MaterialTheme.typography.titleLarge)
                    }
                    DropdownMenu(addMenuOpen, { addMenuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Прикрепить файлы") },
                            leadingIcon = { Text("📎") },
                            onClick = {
                                addMenuOpen = false
                                onPickAttachments(attachments.size) { attachments = attachments + it }
                            },
                        )
                        if (onPlanning != null) {
                            DropdownMenuItem(
                                text = { Text("Режим планирования") },
                                leadingIcon = { Text("🔀") },
                                trailingIcon = if (planning) { { Text("✓") } } else null,
                                onClick = {
                                    addMenuOpen = false
                                    if (!planning) onPlanning()
                                },
                            )
                        }
                    }
                }
                BasicTextField(
                    value = text, onValueChange = { text = it },
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 4.dp, vertical = 4.dp)
                        .onPreviewKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown && event.isMetaPressed && event.key == Key.Enter) {
                                submit()
                                true
                            } else {
                                false
                            }
                        },
                    maxLines = 6,
                    decorationBox = { inner ->
                        Box {
                            if (text.isEmpty()) Text("Поручение агенту в папке проекта…",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            inner()
                        }
                    },
                )
                Row(Modifier.widthIn(max = trailingLimit).horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(0.dp, Alignment.End)) {
                    controls?.invoke()
                }
                VerticalDivider(
                    modifier = Modifier.padding(horizontal = 6.dp).height(24.dp),
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
                if (busy) TextButton(onClick = onAbort, contentPadding = PaddingValues(horizontal = 4.dp)) { Text("Прервать") }
                else TextButton(enabled = enabled && (text.isNotBlank() || attachments.isNotEmpty()), onClick = ::submit,
                    contentPadding = PaddingValues(horizontal = 4.dp)) {
                    Text(if (enabled) "Отправить" else "Движок не готов", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}
