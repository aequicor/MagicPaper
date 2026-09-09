package io.aequicor.magicpaper.ui.screens
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import io.aequicor.magicpaper.ui.components.InlineMessageParts
import io.aequicor.magicpaper.ui.components.MessageExpansion
import io.aequicor.magicpaper.ui.components.LocalMessageExpansion
import io.aequicor.magicpaper.ui.components.rememberInlineMessageParts
import io.aequicor.magicpaper.ui.components.PreserveInlineExpansion
import io.aequicor.magicpaper.ui.components.CollapseMessage


import io.aequicor.magicpaper.ui.components.ToolbarButton
import io.aequicor.magicpaper.ui.components.ToolbarIcon
import androidx.compose.runtime.CompositionLocalProvider
import io.aequicor.magicpaper.domain.CodingInteractionMode
import io.aequicor.magicpaper.domain.interactionMode
import io.aequicor.magicpaper.domain.UserInteractionRequest
import io.aequicor.magicpaper.domain.QuestionnaireDraft
import io.aequicor.magicpaper.domain.PlanningAnswer
import io.aequicor.magicpaper.domain.InteractionKind

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.border
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.indication
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.Layout
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.material3.FilterChip
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
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
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.aequicor.magicpaper.domain.effectiveRole
import io.aequicor.magicpaper.domain.CodingSessionRole
import io.aequicor.magicpaper.ui.components.OrchestrationStatus
import io.aequicor.magicpaper.ui.components.OrchestrationMessageRoute
import io.aequicor.magicpaper.ui.components.OrchestrationMessageInputStatus
import io.aequicor.magicpaper.ui.components.RequestPinsOverlay
import io.aequicor.magicpaper.ui.components.MessagePinColumn
import io.aequicor.magicpaper.ui.components.requestPinNumbers
import io.aequicor.magicpaper.ui.components.chatScrollInput
import io.aequicor.magicpaper.domain.PinConversation
import io.aequicor.magicpaper.domain.RequestPinGroup
import io.aequicor.magicpaper.domain.eventWaitLabel
import io.aequicor.magicpaper.domain.Attachment
import io.aequicor.magicpaper.domain.MAX_ATTACHMENTS_PER_MESSAGE
import io.aequicor.magicpaper.domain.CodingEngine
import io.aequicor.magicpaper.domain.CodingDraft
import io.aequicor.magicpaper.domain.CodingApproval
import io.aequicor.magicpaper.domain.CodingApprovalDecision
import io.aequicor.magicpaper.domain.CodingMessage
import io.aequicor.magicpaper.domain.CodingProject
import io.aequicor.magicpaper.domain.CodingRole
import io.aequicor.magicpaper.domain.CodingSession
import io.aequicor.magicpaper.domain.CodingSessionStatus
import io.aequicor.magicpaper.domain.CodingStep
import io.aequicor.magicpaper.domain.PlanningChatService
import io.aequicor.magicpaper.domain.SearchProvider
import io.aequicor.magicpaper.domain.ExecutionIntent
import io.aequicor.magicpaper.domain.MilestoneStatus
import io.aequicor.magicpaper.ui.components.UserInteractionDock
import io.aequicor.magicpaper.ui.components.LocalOpenQuestionnaire
import io.aequicor.magicpaper.ui.components.CodingComposerDraft
import io.aequicor.magicpaper.ui.components.PlanningChatMessage
import io.aequicor.magicpaper.ui.components.codingChatRows
import io.aequicor.magicpaper.ui.components.codingHistoryItems
import io.aequicor.magicpaper.ui.components.codingToolPreview
import io.aequicor.magicpaper.ui.components.isVisibleInChat
import io.aequicor.magicpaper.ui.components.codingDraftRow
import io.aequicor.magicpaper.ui.components.LocalHideSystemSteps
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
import io.aequicor.magicpaper.ui.components.ChatPlainText
import io.aequicor.magicpaper.ui.components.FadingSingleLineText
import io.aequicor.magicpaper.ui.components.ChatScrollItem
import io.aequicor.magicpaper.ui.components.ChatScrollToBottomButton
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
    if (ui.creatingSession) {
        val state by vm.state.collectAsState()
        NewCodingSessionDialog(state.settings.defaultCodingEngine, vm::cancelCodingSessionCreation, vm::addCodingSession)
    }
    var skillsProject by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(ui.current?.id) { skillsProject = null }
    skillsProject?.let { projectId ->
        androidx.compose.ui.window.Dialog(onDismissRequest = { skillsProject = null }) {
            androidx.compose.material3.Surface(shape = MaterialTheme.shapes.large) {
                Column(Modifier.padding(16.dp)) {
                    Text("SKILLS", style = MaterialTheme.typography.titleLarge)
                    vm.projectSkills?.Content(projectId)
                        ?: Text("Проектные навыки недоступны на этой платформе")
                    TextButton(onClick = { skillsProject = null }) { Text("Закрыть") }
                }
            }
        }
    }
    Column(modifier = Modifier.fillMaxSize()) {
        ResizableProjectPanels(modifier = Modifier.weight(1f), sidebar = { panelModifier ->
            ProjectsPanel(
                ui = ui,
                onAddProject = vm::addCodingProject,
                onSelectProject = vm::selectCodingProject,
                onDeleteProject = vm::deleteCodingProject,
                onDeleteAllSessions = vm::deleteAllCodingSessions,
                onSelectSession = vm::selectCodingSession,
                onAddSession = vm::requestCodingSession,
                onDeleteSession = vm::deleteCodingSession,
                onArchiveSession = vm::archiveCodingSession,
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
                        onSkills = { skillsProject = project.id },
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
    onSkills: () -> Unit,
    ui: CodingUi,
    project: CodingProject,
    active: CodingSessionUi,
    panelPlugin: CodingSessionPanel?,
    profiles: List<LlmProfile>,
    activeProfileId: String,
) {
    val sessionInfo = active.session
    // Переключатель источника/модели/усилия активной сессии.
    var switcherOpen by rememberSaveable(sessionInfo.id) { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxSize()) {
        val service = vm.planningChat
        val scope = rememberCoroutineScope()
        val serviceDrafts = service?.drafts?.collectAsState()?.value.orEmpty()
        val latestServiceDrafts = androidx.compose.runtime.rememberUpdatedState(serviceDrafts)
        val plans = service?.store?.plans?.collectAsState()?.value.orEmpty()
        val live = service?.execution?.live?.collectAsState()?.value.orEmpty()
        val workerPlan = plans.firstOrNull { it.id == sessionInfo.planId } ?: active.plan
        val parentMessages = ui.sessions.firstOrNull { it.session.id == sessionInfo.parentSessionId }?.messages.orEmpty()
        val stageChat = active.withStageChat(workerPlan, live, parentMessages)
        val draft = serviceDrafts[sessionInfo.id] ?: stageChat.draft
        val effective = stageChat.copy(draft = draft.copy(awaitingApproval = active.draft.awaitingApproval),
            running = stageChat.running || draft.active)
        val pins = vm.requestPins?.groups?.collectAsState()?.value.orEmpty()
        if (ui.computerSupported && !sessionInfo.planningMode && !sessionInfo.researchMode && sessionInfo.stageId == null) {
            io.aequicor.magicpaper.ui.components.ComputerUsePanel(
                state = ui.computer, sessionId = sessionInfo.id, running = effective.running,
                onEnable = { vm.enableComputerUse(sessionInfo.id, it) },
                onDisable = { vm.disableComputerUse(sessionInfo.id) },
                onPreview = { vm.previewComputerUse(sessionInfo.id) },
                onSettings = vm::openComputerSystemSettings,
            )
        }
            CodingChat(
                project = project,
                session = effective,
                pins = pins[PinConversation(sessionInfo.id, sessionInfo.projectId)].orEmpty(),
                approvals = ui.approvals.filter { it.projectId == project.id },
                onApproval = vm::respondCodingApproval,
                interactions = ui.interactions.filter { it.affects(active.session) },
                questionnaireDrafts = vm.questionnaireDrafts.collectAsState().value,
                onQuestionnaireDraft = vm::updateQuestionnaireDraft,
                onQuestionnaireSubmit = vm::submitQuestionnaire,
                onOpenQuestionnaire = vm::openQuestionnaire,
                composerDraft = vm.composerDrafts.getOrPut(active.session.id) { CodingComposerDraft() },
                onStopApproval = vm::abortCodingSession,
                busy = effective.running,
                allowQueue = sessionInfo.stageId != null || sessionInfo.planningMode,
                planningService = service,
                planningQuestionsSession = ui.sessions.firstOrNull { it.session.id == sessionInfo.parentSessionId } ?: effective,
                onOpenSession = vm::selectCodingSession,
                engineReady = true,
                onSend = { text, attachments -> vm.sendCodingPromptTo(sessionInfo.id, text, attachments) },
                onResume = { text, attachments -> vm.resumeCodingSession(sessionInfo.id, text, attachments) },
                onAbort = {
                    when {
                        sessionInfo.stageId != null && sessionInfo.planId != null -> service?.control(sessionInfo.planId, "stop")
                        latestServiceDrafts.value[sessionInfo.id]?.active == true -> service?.cancelRequest(sessionInfo.id)
                        else -> vm.abortCodingSession(sessionInfo.id)
                    }
                },
                onSkills = onSkills,
                onPickAttachments = { already, onPicked -> vm.pickAttachments(already, onPicked) },
                onPasteAttachments = { already, onPicked -> vm.pasteAttachments(already, onPicked) },
                onInteractionMode = if (sessionInfo.stageId == null && !sessionInfo.archived) {
                    { mode -> vm.changeCodingInteractionMode(sessionInfo.id, mode) }
                } else null,
                modeSwitchEnabled = !effective.running && effective.interactions.isEmpty() && !effective.awaitingUser,
                onSearchProvider = if (service != null && sessionInfo.planningMode && sessionInfo.stageId == null) {
                    { provider -> scope.launch { service.configure(sessionInfo, search = provider) } }
                } else null,
                modelChip = {
                    CodingModelChip(
                        profile = vm.codingProfileOf(sessionInfo, workerPlan),
                        overridden = sessionInfo.llmProfileId != null,
                        onClick = { switcherOpen = true },
                    )
                },
            )
    }
    if (switcherOpen) {
        CodingModelSwitcherDialog(
            vm = vm,
            sessionId = sessionInfo.id,
            profiles = profiles,
            activeProfileId = activeProfileId,
            sessionProfileId = sessionInfo.llmProfileId,
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
        CodingSessionStatus.WAITING -> "Ждём вашего ответа"
        CodingSessionStatus.CONFIRMATION -> "ждёт подтверждения доработки"
        CodingSessionStatus.BLOCKED -> "выполнение остановлено"
        CodingSessionStatus.QUEUED -> "ждёт оркестратора"
        CodingSessionStatus.SCHEDULED -> "ждёт события или времени"
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
        CodingSessionStatus.CONFIRMATION -> StatusWaiting
        CodingSessionStatus.BLOCKED -> Color(0xFFC77843)
        CodingSessionStatus.QUEUED -> StatusQueued
        CodingSessionStatus.SCHEDULED -> StatusQueued
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
    onArchiveSession: (String) -> Unit = {},
) {
    Column(modifier = modifier.fillMaxSize()) {
        val collapsed = remember { mutableStateMapOf<String, Boolean>() }
        // Disclosure is local UI state: never reload a project or reset its active session.
        // Selecting another project opens its list; status updates preserve disclosure.
        var projectCollapsed by remember(ui.current?.id) { mutableStateOf(false) }
        val projectIndex = ui.projects.indexOfFirst { it.id == ui.current?.id }
        val ownSessions = ui.current?.let { ui.sessionsOf(it.id) }.orEmpty().filterNot { it.session.archived }
        val sessionIds = ownSessions.map { it.session.id }.toSet()
        val groups = buildList {
            var index = projectIndex + 1
            if (!projectCollapsed) ownSessions.filter { it.session.parentSessionId !in sessionIds }.forEach { parent ->
                // Read the orchestrator's work in creation order, from top to bottom.
                val children = ownSessions.filter { it.session.parentSessionId == parent.session.id }
                    .sortedBy { it.session.createdAt }
                val expanded = collapsed[parent.session.id] != true
                val start = index++
                if (expanded) index += children.size
                add(ProjectSessionGroup(parent, children, expanded, start, index))
            }
        }
        val sessionHeader: @Composable (ProjectSessionGroup) -> Unit = { group ->
            val session = group.parent
            SessionRow(session, session.session.id == ui.activeSessionIdOf(session.session.projectId),
                { onSelectSession(session.session.id) }, { onDeleteSession(session.session.id) },
                { onAbortSession(session.session.id) },
                onArchive = { onArchiveSession(session.session.id) },
                childCount = group.children.size, expanded = group.expanded,
                onToggleChildren = {
                    if (group.expanded) {
                        val visible = listState.layoutInfo.visibleItemsInfo
                        val project = visible.firstOrNull { it.key == "project-${session.session.projectId}" }
                        val top = project?.let { (it.offset + it.size).coerceAtLeast(0) } ?: 0
                        val header = visible.firstOrNull { it.key == "session-${session.session.id}" }
                        // Keep a pinned card visible when its scrolled-away children disappear.
                        if (header == null || header.offset < top) listState.requestScrollToItem(group.index, -top)
                    }
                    collapsed[session.session.id] = group.expanded
                })
        }
        Box(Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                ui.projects.forEach { project ->
                    val selected = project.id == ui.current?.id
                    val expanded = selected && !projectCollapsed
                    val own = ui.sessionsOf(project.id).filterNot { it.session.archived }
                    // Only projects participate in the native sticky-header chain. Session
                    // headers occupy a separate level below it and cannot push a project away.
                    stickyHeader(key = "project-${project.id}") { index ->
                        val pinned = listState.firstVisibleItemIndex > index ||
                            (listState.firstVisibleItemIndex == index && listState.firstVisibleItemScrollOffset > 0)
                        ProjectHeaderSurface(pinned) {
                            ProjectRow(project, selected, expanded, ui.statusOf(project.id), own.count { it.running }, own.size,
                                {
                                    if (selected) projectCollapsed = !projectCollapsed
                                    else onSelectProject(project.id)
                                }, { onDeleteProject(project.id) }, onAddSession = onAddSession,
                                onDeleteAllSessions = { onDeleteAllSessions(project.id) })
                        }
                    }
                    if (expanded) {
                        val activeId = ui.activeSessionIdOf(project.id)
                        groups.forEach { group ->
                            item(key = "session-${group.parent.session.id}") { sessionHeader(group) }
                            if (group.expanded) group.children.forEach { child ->
                                item(key = "session-${child.session.id}") {
                                    SessionRow(child, child.session.id == activeId,
                                        { onSelectSession(child.session.id) }, { onDeleteSession(child.session.id) },
                                        { onAbortSession(child.session.id) },
                                        onArchive = { onArchiveSession(child.session.id) }, nested = true)
                                }
                            }
                        }
                    }
                }
            }
            ProjectPinnedSession(listState, "project-${ui.current?.id}", groups, sessionHeader)
        }
        TextButton(onClick = onAddProject, modifier = Modifier.padding(8.dp)) {
            Text("✦ Новый проект")
        }
    }
}

private data class ProjectSessionGroup(
    val parent: CodingSessionUi,
    val children: List<CodingSessionUi>,
    val expanded: Boolean,
    val index: Int,
    val endIndex: Int,
)

/** Animate the surface, never the lazy item's height: scroll anchors remain stable. */
@Composable
private fun ProjectHeaderSurface(pinned: Boolean, content: @Composable () -> Unit) {
    val progress by animateFloatAsState(
        if (pinned) 1f else 0f,
        tween(200, easing = FastOutSlowInEasing),
        label = "projectHeaderPin",
    )
    val surface = MaterialTheme.colorScheme.surface
    Column(Modifier.fillMaxWidth()
        .graphicsLayer { shadowElevation = 3.dp.toPx() * progress }
        .background(surface.copy(alpha = progress))) { content() }
}

/** The current session sticks below its project, only until its own children end. */
@Composable
private fun ProjectPinnedSession(
    listState: LazyListState,
    projectKey: String,
    groups: List<ProjectSessionGroup>,
    content: @Composable (ProjectSessionGroup) -> Unit,
) {
    val visible = listState.layoutInfo.visibleItemsInfo
    val project = visible.firstOrNull { it.key == projectKey } ?: return
    val top = (project.offset + project.size).coerceAtLeast(0)
    val firstBelowProject = visible.firstOrNull { it.index > project.index && it.offset + it.size > top } ?: return
    val group = groups.firstOrNull { firstBelowProject.index in it.index until it.endIndex } ?: return
    if (!group.parent.session.planningMode && group.children.isEmpty()) return
    val original = visible.firstOrNull { it.index == group.index }
    if (original != null && original.offset >= top) return
    // The following root session (or add-session row) pushes this header out. Clip
    // the movement below the project so neither level can obscure the other.
    val boundary = visible.firstOrNull { it.index == group.endIndex }?.offset
        ?: listState.layoutInfo.viewportEndOffset
    val available = (boundary - top).coerceAtLeast(0)
    val topPadding = with(LocalDensity.current) { top.toDp() }
    Box(Modifier.fillMaxSize().padding(top = topPadding).clipToBounds()) {
        Layout(modifier = Modifier.scrollable(listState, Orientation.Vertical, reverseDirection = true), content = {
            key(group.parent.session.id) {
                Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)) { content(group) }
            }
        }) { measurables, constraints ->
            val header = measurables.single().measure(constraints.copy(minHeight = 0))
            val height = minOf(header.height, available)
            layout(header.width, height) { header.placeRelative(0, height - header.height) }
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
    onAddSession: () -> Unit,
    onDeleteAllSessions: () -> Unit = {},
) {
    val hoverInteraction = remember { MutableInteractionSource() }
    val hovered by hoverInteraction.collectIsHoveredAsState()
    var menuOpen by rememberSaveable(project.id) { mutableStateOf(false) }
    val showActions = hovered || menuOpen
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .clip(MaterialTheme.shapes.small)
            .hoverable(hoverInteraction)
            .clickable(onClick = onSelect)
            .padding(start = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(14.dp)) {
            Text(
                if (expanded) "▾" else "▸",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        StatusTooltip(status) { ActivityDot(status, size = 9) }
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            FadingSingleLineText(
                project.name,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.bodyLarge,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
            FadingSingleLineText(
                buildString {
                    append("$sessionCount ${sessionCountWord(sessionCount)}")
                    if (sessionCount > 0) {
                        if (runningSessions > 0) append(", $runningSessions работают")
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        HoverActions(visible = showActions) {
            // The action is deliberately available only on the current project: the
            // creation dialog saves into the ViewModel's current project.
            if (selected) {
                TextButton(
                    onClick = onAddSession,
                    modifier = Modifier.semantics { contentDescription = "Новая сессия" },
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                ) {
                    Text("Новая сессия", style = MaterialTheme.typography.labelMedium)
                }
            }
            RowMenu(
                open = menuOpen,
                onOpenChange = { menuOpen = it },
                entries = listOf("Удалить все сессии" to onDeleteAllSessions, "Удалить проект" to onDelete),
            )
        }
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
    onArchive: () -> Unit,
    nested: Boolean = false,
    childCount: Int = 0,
    expanded: Boolean = false,
    onToggleChildren: () -> Unit = {},
) {
    val status = item.status
    val hoverInteraction = remember { MutableInteractionSource() }
    val hovered by hoverInteraction.collectIsHoveredAsState()
    var menuOpen by rememberSaveable(item.session.id) { mutableStateOf(false) }
    val showActions = hovered || menuOpen
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = if (nested) 42.dp else 20.dp, end = 8.dp, top = 2.dp, bottom = 2.dp)
            .clip(MaterialTheme.shapes.small)
            .hoverable(hoverInteraction)
            .background(
                if (selected) {
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                } else {
                    Color.Transparent
                }
            )
            .clickable(
                onClickLabel = if (selected && childCount > 0) {
                    if (expanded) "Свернуть этапы" else "Раскрыть этапы"
                } else null,
            ) {
                if (selected && childCount > 0) onToggleChildren() else onSelect()
            }
            .padding(horizontal = 6.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusTooltip(status) { ActivityDot(status, size = 8) }
        Spacer(Modifier.width(7.dp))
        FadingSingleLineText(
            item.session.name,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
        )
        HoverActions(visible = showActions) {
            if (childCount > 0) {
                Box(Modifier.size(24.dp)
                    .clip(MaterialTheme.shapes.small)
                    .semantics { contentDescription = if (expanded) "Свернуть этапы" else "Раскрыть этапы" }
                    .clickable(
                        onClick = onToggleChildren,
                    ),
                    contentAlignment = Alignment.Center) {
                    Text(if (expanded) "▾" else "▸", color = MaterialTheme.colorScheme.primary)
                }
            }
            TooltipBox(
                positionProvider = TooltipDefaults.rememberTooltipPositionProvider(
                    androidx.compose.material3.TooltipAnchorPosition.Above,
                ),
                tooltip = {
                    androidx.compose.material3.Surface(
                        color = MaterialTheme.colorScheme.inverseSurface,
                        shape = MaterialTheme.shapes.small,
                    ) {
                        Text("В архив", modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.inverseOnSurface)
                    }
                },
                state = rememberTooltipState(),
            ) {
                ToolbarButton(ToolbarIcon.Archive, label = "Архивировать сессию", size = 24.dp, onClick = onArchive)
            }
            RowMenu(
                open = menuOpen,
                onOpenChange = { menuOpen = it },
                entries = buildList<Pair<String, () -> Unit>> {
                    if (item.running) add("Прервать прогон" to onAbort)
                    add((if (item.session.stageId != null) "В архив" else "Удалить сессию") to onDelete)
                },
            )
        }
    }
}

/** Keep row height stable, but let titles use the width of hidden actions. */
@Composable
private fun HoverActions(visible: Boolean, content: @Composable () -> Unit) {
    Layout(
        modifier = if (visible) Modifier else Modifier.clearAndSetSemantics {},
        content = { Row(verticalAlignment = Alignment.CenterVertically) { content() } },
    ) { measurables, constraints ->
        val actions = measurables.single().measure(constraints.copy(minWidth = 0, minHeight = 0))
        layout(if (visible) actions.width else 0, actions.height) {
            // Unplaced actions are neither drawn nor available for pointer input.
            if (visible) actions.placeRelative(0, 0)
        }
    }
}

/** Якорь «⋯» с выпадающим меню: общий для строк проекта и кодинг-сессии. */
@Composable
private fun RowMenu(open: Boolean, onOpenChange: (Boolean) -> Unit, entries: List<Pair<String, () -> Unit>>) {
    Box {
        Text(
            "⋯",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .clip(MaterialTheme.shapes.small)
                .clickable { onOpenChange(true) }
                .semantics { contentDescription = "Действия" }
                .padding(horizontal = 6.dp),
        )
        if (open) {
            DropdownMenu(expanded = true, onDismissRequest = { onOpenChange(false) }) {
                entries.forEach { (label, action) ->
                    DropdownMenuItem(
                        text = { Text(label) },
                        onClick = {
                            onOpenChange(false)
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
    onPasteAttachments: (Int, (List<Attachment>) -> Unit) -> Boolean = { _, _ -> false },
    modelChip: (@Composable () -> Unit)? = null,
    planningService: PlanningChatService? = null,
    planningQuestionsSession: CodingSessionUi = session,
    onOpenSession: (String) -> Unit = {},
    allowQueue: Boolean = false,
    onPlanning: (() -> Unit)? = null,
    onInteractionMode: ((CodingInteractionMode) -> Unit)? = null,
    modeSwitchEnabled: Boolean = true,
    approvals: List<CodingApproval> = emptyList(),
    onApproval: (String, CodingApprovalDecision) -> Unit = { _, _ -> },
    onStopApproval: (String) -> Unit = {},
    onSkills: (() -> Unit)? = null,
    onSearchProvider: ((SearchProvider) -> Unit)? = null,
    onResume: ((String, List<Attachment>) -> Unit)? = null,
    interactions: List<UserInteractionRequest> = session.interactions,
    questionnaireDrafts: Map<String, QuestionnaireDraft> = emptyMap(),
    onQuestionnaireDraft: (String, QuestionnaireDraft) -> Unit = { _, _ -> },
    onQuestionnaireSubmit: (String, List<PlanningAnswer>) -> Unit = { _, _ -> },
    onOpenQuestionnaire: (InteractionKind, String) -> Unit = { _, _ -> },
    composerDraft: CodingComposerDraft = remember(session.session.id) { CodingComposerDraft() },
    pins: List<RequestPinGroup> = emptyList(),
    listState: LazyListState = key(session.session.id) {
        rememberLazyListState(initialFirstVisibleItemIndex = Int.MAX_VALUE)
    },
) {
    CompositionLocalProvider(LocalOpenQuestionnaire provides onOpenQuestionnaire) {
    val messages = session.messages
    val hideSystemSteps = LocalHideSystemSteps.current
    val rows = remember(messages, hideSystemSteps) { codingChatRows(messages, hideSystemSteps) }
    val history = remember(rows) { codingHistoryItems(rows) }
    val pinKeys = remember(history) {
        history.filter { it.first }.associate { it.row.message.id to it.key }
    }
    val draft = session.draft
    val draftRow = remember(draft, messages, hideSystemSteps, busy, session.session.id) {
        codingDraftRow(draft, messages, "draft:${session.session.id}", hideSystemSteps, busy)
    }
    val draftHistory = remember(draftRow) { codingHistoryItems(listOfNotNull(draftRow)) }
    val timeline = remember(history, draftHistory) { history + draftHistory }
    var expandedMessages by rememberSaveable(session.session.id) { mutableStateOf(emptyList<String>()) }
    val expandedParts = buildMap<String, InlineMessageParts> {
        timeline.filter { it.key in expandedMessages }.forEach { item ->
            key(item.key) {
                rememberInlineMessageParts(item.expandableText(), item.step?.kind == CodingStepKind.ANSWER)
                    ?.let { put(item.key, it) }
            }
        }
    }
    val fragments = remember(timeline, expandedParts) {
        timeline.flatMap { item ->
            val parts = expandedParts[item.key]
            if (parts == null || parts.size == 0) listOf(CodingMessageFragment(item))
            else (0 until parts.size).map { CodingMessageFragment(item, parts, it) }
        }
    }
    val pinIndices = remember(fragments) {
        buildMap { fragments.forEachIndexed { index, fragment ->
            if (fragment.item.first && fragment.index == 0) put(fragment.item.row.message.id, index + 1)
        } }
    }
    // Remember arrivals at the list level: lazy reuse and reopening saved history
    // must not replay the entrance animation or reset a command's disclosure.
    val seenTimelineKeys = remember(session.session.id) { timeline.map { it.key }.toMutableSet() }
    val arrivingKeys = remember(timeline, seenTimelineKeys) { timeline.map { it.key }.filterNot { it in seenTimelineKeys }.toSet() }
    SideEffect { seenTimelineKeys.addAll(arrivingKeys) }
    var thinkingExpanded by rememberSaveable(session.session.id, busy) { mutableStateOf(false) }
    val hasDraft = draftHistory.isNotEmpty()
    val statusMessageId = rows.lastOrNull()?.let { it.planCard ?: it.message }?.takeIf { it.role == CodingRole.AGENT && !hasDraft }?.id
    val status: @Composable () -> Unit = {
        key(session.session.id) {
            AgentMessageStatus(draft, thinkingExpanded, waitingForUser = interactions.isNotEmpty(), onToggle = { thinkingExpanded = !thinkingExpanded })
        }
    }
    // Живая лента держит конец: новый шаг прогона или доросший ответ видны сразу,
    // а не «с начала сообщения». Открутил журнал вверх — не мешаем читать.
    val scroll = stickToBottom(listState, session.session.id)
    PreserveInlineExpansion(expandedParts, scroll)
    val pinNumbers = remember(pins, pinIndices) { requestPinNumbers(pins, pinIndices.keys) }
    var browserMessageId by remember(scroll) { mutableStateOf<String?>(null) }
    val density = LocalDensity.current
    var footerHeight by remember { mutableStateOf(0.dp) }
    val showOrchestrationStatus = session.session.effectiveRole == CodingSessionRole.ORCHESTRATOR && planningService != null
    val scrolled by remember(listState) { derivedStateOf { listState.canScrollBackward } }
    Column(Modifier.fillMaxSize()) {
        if (showOrchestrationStatus)
            OrchestrationStatus(session, planningService, onOpenSession, Modifier.zIndex(1f), scrolled = scrolled)
        session.session.stageId?.let { id -> session.plan?.eventWaitLabel(id)?.takeIf { it.isNotBlank() }?.let { label ->
            Surface(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Text(label, Modifier.padding(10.dp), style = MaterialTheme.typography.bodySmall)
            }
        } }
        BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth()) {
            val questionHeight = maxHeight * 0.75f
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().chatScrollInput(scroll)
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
                contentPadding = PaddingValues(start = 16.dp, top = if (showOrchestrationStatus) 6.dp else 16.dp,
                    end = 16.dp, bottom = footerHeight + 16.dp),
                verticalArrangement = Arrangement.Top,
            ) {
                item(key = "project-header", contentType = "header") {
                    Text(
                        "Проект «${project.name}» · сессия «${if (session.session.planningMode && !session.session.name.startsWith("🔀")) "🔀 " else ""}${session.session.name}» · ${session.session.interactionMode.title} · ${project.path}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                items(fragments, key = { it.key }, contentType = { it.item.step?.kind ?: it.item.row.message.role }) { fragment ->
                    val item = fragment.item
                    val row = item.row
                    val message = row.message
                    val isDraft = message.id == draftRow?.message?.id
                    val rowStatus = status.takeIf { busy && statusMessageId != null &&
                        (message.id == statusMessageId || row.planCard?.id == statusMessageId) }
                    var hasAppeared by rememberSaveable(item.key) { mutableStateOf(false) }
                    val appearance = remember(item.key) {
                        val animate = fragment.parts == null && !hasAppeared && isDraft && draft.active && item.key in arrivingKeys &&
                            item.step?.kind in listOf(CodingStepKind.TOOL, CodingStepKind.EXEC)
                        MutableTransitionState(!animate).apply { targetState = true }
                    }
                    SideEffect { hasAppeared = true }
                    androidx.compose.animation.AnimatedVisibility(appearance,
                        enter = fadeIn(tween(180)) + expandVertically(tween(220), expandFrom = Alignment.Top),
                        exit = ExitTransition.None,
                    ) {
                        SavedCodingHistoryItem(item, scroll, session.session, messages, planningService, onOpenSession, rowStatus,
                            pinNumber = pinNumbers[message.id], onShowPins = { browserMessageId = message.id },
                            live = isDraft && draft.active && (item.last || item.step?.kind in listOf(CodingStepKind.TOOL, CodingStepKind.EXEC)),
                            continued = isDraft && busy, fragment = fragment,
                            onExpand = { expandedMessages = expandedMessages + item.key },
                            onCollapse = {
                                listState.requestScrollToItem(fragments.indexOfFirst { it.item.key == item.key } + 1)
                                expandedMessages = expandedMessages - item.key
                            })
                    }
                }
                if (busy && (hasDraft || statusMessageId == null)) {
                    val statusKey = "draft-status:${draft.timelineId ?: session.session.id}"
                    item(key = statusKey, contentType = "status") {
                        ChatScrollItem(scroll, statusKey) {
                            key(session.session.id) { DraftFragment(first = !hasDraft, last = true) { status() } }
                        }
                    }
                }
            }
            RequestPinsOverlay(pins, pinIndices, listState, scroll, Modifier.align(Alignment.TopEnd),
                browserMessageId = browserMessageId, onCloseBrowser = { browserMessageId = null }, itemKeys = pinKeys)
            ChatScrollToBottomButton(scroll,
                Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = footerHeight + 12.dp))
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .onSizeChanged { footerHeight = with(density) { it.height.toDp() } }) {
                val request = interactions.firstOrNull()
                if (request != null) key(request.id) {
                    CodingModeLabel(session.session.planningMode, session.session.researchMode)
                    UserInteractionDock(request, questionnaireDrafts[request.id] ?: QuestionnaireDraft(request.initialAnswers),
                        { onQuestionnaireDraft(request.id, it) }, { onQuestionnaireSubmit(request.id, it) },
                        Modifier.fillMaxWidth().heightIn(max = questionHeight).padding(horizontal = 8.dp, vertical = 4.dp),
                        queuedCount = interactions.size - 1)
                } else
                CodingComposer(
                    state = composerDraft,
                    enabled = engineReady && (!busy || allowQueue),
                    busy = busy && !allowQueue,
                    controls = modelChip,
                    planning = session.session.planningMode,
                    research = session.session.researchMode,
                    onInteractionMode = onInteractionMode,
                    modeSwitchEnabled = modeSwitchEnabled && !busy && !session.awaitingUser,
                    onPlanning = onPlanning,
                    engine = session.session.engine,
                    searchProvider = session.session.searchProvider,
                    onSearchProvider = onSearchProvider,
                    onSend = onSend,
                    onResume = onResume?.takeIf { session.canResume },
                    onAbort = onAbort,
                    onSkills = onSkills,
                    onPickAttachments = onPickAttachments,
                    onPasteAttachments = onPasteAttachments,
                )
            }
        }
    }
    }
}

private data class CodingMessageFragment(
    val item: io.aequicor.magicpaper.ui.components.CodingHistoryItem,
    val parts: InlineMessageParts? = null,
    val index: Int = 0,
) {
    val key: String get() = if (index == 0) item.key else "${item.key}:text:$index"
    val first: Boolean get() = index == 0
    val last: Boolean get() = parts == null || index == parts.size - 1
}

private fun io.aequicor.magicpaper.ui.components.CodingHistoryItem.expandableText(): String = when (step?.kind) {
    CodingStepKind.ANSWER -> step!!.title
    CodingStepKind.ERROR -> "✕ ${step!!.title}"
    CodingStepKind.INFO -> "◷ ${step!!.title}"
    CodingStepKind.TOOL, CodingStepKind.EXEC -> step!!.let { tool ->
        if (tool.title.length > 6000) tool.title + "\n\n" + tool.result else tool.result
    }
    else -> row.message.text
}

/** Saved and streaming steps share the same composition, including disclosure state. */
@Composable
private fun SavedCodingHistoryItem(
    item: io.aequicor.magicpaper.ui.components.CodingHistoryItem,
    scroll: io.aequicor.magicpaper.ui.components.ChatScrollState,
    session: CodingSession,
    messages: List<CodingMessage>,
    planningService: PlanningChatService?,
    onOpenSession: (String) -> Unit,
    status: (@Composable () -> Unit)?,
    pinNumber: Int?,
    onShowPins: () -> Unit,
    live: Boolean = false,
    continued: Boolean = false,
    fragment: CodingMessageFragment = CodingMessageFragment(item),
    onExpand: () -> Unit = {},
    onCollapse: () -> Unit = {},
) {
    val row = item.row
    val message = row.message
    ChatScrollItem(scroll, fragment.key) {
        CompositionLocalProvider(LocalMessageExpansion provides if (fragment.parts == null) MessageExpansion(item.expandableText(), onExpand) else null) {
            CodingMessageBubble(message, step = item.step, first = item.first && fragment.first,
                last = item.last && fragment.last && !continued, live = live,
                body = fragment.parts?.let { parts -> {
                    val step = item.step
                    if (step?.kind in listOf(CodingStepKind.TOOL, CodingStepKind.EXEC)) {
                        ToolStepContent(step!!.title, "", step.running, step.ok, live,
                            step.kind == CodingStepKind.EXEC, expanded = true, onToggle = onCollapse,
                            showHeader = fragment.first, body = {
                                parts.Content(fragment.index,
                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = MagicFonts.code),
                                    color = if (step.ok) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
                            })
                    } else parts.Content(fragment.index,
                        style = when (step?.kind) {
                            CodingStepKind.INFO -> MaterialTheme.typography.bodySmall
                            CodingStepKind.ERROR -> MaterialTheme.typography.bodyMedium
                            else -> MaterialTheme.typography.bodyLarge
                        },
                        color = when {
                            step?.kind == CodingStepKind.ERROR || message.failed -> MaterialTheme.colorScheme.error
                            step?.kind == CodingStepKind.INFO -> MaterialTheme.colorScheme.onSurfaceVariant
                            else -> MaterialTheme.colorScheme.onSurface
                        })
                    if (fragment.last) CollapseMessage(onCollapse)
                } },
                forceWidth = fragment.parts != null,
                showFooter = item.last && fragment.last,
                pinNumber = pinNumber, onShowPins = onShowPins,
                header = { OrchestrationMessageRoute(message, planningService, onOpenSession) }) {
                status?.invoke()
                if (planningService != null && message.planning != null) {
                    Spacer(Modifier.height(6.dp))
                    PlanningChatMessage(message, session, messages, planningService, onOpenSession)
                }
                row.planCard?.let { card ->
                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Spacer(Modifier.height(12.dp))
                    ChatMarkdown(card.text)
                    if (planningService != null) {
                        Spacer(Modifier.height(6.dp))
                        PlanningChatMessage(card, session, messages, planningService, onOpenSession)
                    }
                }
                OrchestrationMessageInputStatus(message, session.id, planningService)
                if (message.pendingDelivery) Text("Ожидает передачи после текущего хода", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

/** Бабл записи журнала: лента прогона в хронологическом порядке либо просто текст. */
@Composable
private fun CodingMessageBubble(
    message: CodingMessage,
    step: CodingStep? = null,
    first: Boolean = true,
    last: Boolean = true,
    live: Boolean = false,
    pinNumber: Int? = null,
    onShowPins: () -> Unit = {},
    header: (@Composable () -> Unit)? = null,
    body: (@Composable () -> Unit)? = null,
    forceWidth: Boolean = false,
    showFooter: Boolean = last,
    footer: (@Composable () -> Unit)? = null,
) {
    if (message.systemContext) {
        io.aequicor.magicpaper.ui.components.SessionContextMessage(message.id, message.text)
        return
    }
    val previewState = rememberSaveableStateHolder()
    val isUser = message.role == CodingRole.USER
    val bubbleColor = if (message.systemNotice) {
        MaterialTheme.colorScheme.surfaceContainerLow
    } else if (isUser) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = if (first) 10.dp else 0.dp),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        MessagePinColumn(
            number = pinNumber.takeIf { isUser && last },
            onClick = onShowPins,
            modifier = Modifier
                .widthIn(max = 680.dp)
                .then(if (step != null || forceWidth) Modifier.fillMaxWidth() else Modifier)
                .clip(
                    androidx.compose.foundation.shape.RoundedCornerShape(
                        topStart = if (!first) 0.dp else if (isUser) 20.dp else 6.dp,
                        topEnd = if (!first) 0.dp else if (isUser) 6.dp else 20.dp,
                        bottomStart = if (last) 20.dp else 0.dp,
                        bottomEnd = if (last) 20.dp else 0.dp,
                    )
                )
                .background(bubbleColor)
                .padding(start = 14.dp, end = 14.dp, top = if (first) 10.dp else 0.dp, bottom = if (last) 10.dp else 0.dp),
        ) {
            if (first && message.systemNotice) Text("Системное сообщение", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (first) header?.invoke()
            if (body != null) {
                body()
            } else previewState.SaveableStateProvider("preview") {
                if (isUser) {
                    ChatPlainText(message.text)
                } else if (step != null) {
                    CodingStepRow(step, live = live)
                } else {
                    // Совместимость со старыми журналами без ленты.
                    SelectionContainer {
                        Column {
                            ChatPlainText(
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
            }
            if (body != null && showFooter && step == null && !isUser && message.activity.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text("Действия агента:", style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                message.activity.forEach { line ->
                    Text(line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (showFooter) {
                CodingAttachments(message.attachments)
                footer?.invoke()
            }
        }
    }
}

/**
 * Строка ленты прогона: текст ответа — как есть, действия — сворачиваемой строкой
 * с раскрывающимся выводом инструмента (что реально пришло в ответ).
 */
@Composable
internal fun CodingStepRow(step: CodingStep, live: Boolean) {
    if (!step.isVisibleInChat(LocalHideSystemSteps.current)) return
    when (step.kind) {
        CodingStepKind.ANSWER -> {
            Spacer(Modifier.height(4.dp))
            ChatMarkdown(text = step.title, streaming = live)
            Spacer(Modifier.height(4.dp))
        }
        CodingStepKind.THINKING -> ThinkingStepRow(step, live)
        CodingStepKind.ERROR -> {
            ChatPlainText(
                "✕ ${step.title}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(vertical = 3.dp),
            )
        }
        CodingStepKind.INFO -> {
            ChatPlainText(
                "◷ ${step.title}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 2.dp),
            )
        }
        CodingStepKind.TOOL, CodingStepKind.EXEC -> ToolStepRow(step, live)
        CodingStepKind.SUMMARY -> Unit
    }
}

/** Свёрнутая строка рассуждения в ленте: весь текст — по клику (нижняя панель и так его показывает). */
@Composable
private fun ThinkingStepRow(step: CodingStep, live: Boolean) {
    var expanded by rememberSaveable(step.id) { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.5f))
            .indication(interaction, LocalIndication.current),
    ) {
        Row(Modifier.fillMaxWidth().chatDisclosure(interaction) { expanded = !expanded }
            .padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
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
            ChatMarkdown(step.title, Modifier.padding(start = 10.dp, end = 10.dp, top = 4.dp, bottom = 6.dp)
                .heightIn(max = 240.dp), streaming = live, scrollable = true)
        }
    }
}

/** Команда или действие: часы до завершения, полный текст и вывод по тапу. */
@Composable
private fun ToolStepRow(step: CodingStep, live: Boolean) {
    var expanded by rememberSaveable(step.id, step.callId) { mutableStateOf(false) }
    val preview = remember(step.title) { codingToolPreview(step.title) }
    // Output can grow on every event. A closed card has the same small set of
    // display inputs, so Compose can skip its content while that output streams.
    ToolStepContent(
        title = if (expanded) step.title else preview,
        result = if (expanded) step.result else "",
        running = step.running,
        ok = step.ok,
        live = live,
        isExec = step.kind == CodingStepKind.EXEC,
        expanded = expanded,
        onToggle = { expanded = !expanded },
    )
}

private enum class ToolStepStatus { RUNNING, SUCCEEDED, FAILED }

@Composable
private fun ToolStepContent(
    title: String,
    result: String,
    running: Boolean,
    ok: Boolean,
    live: Boolean,
    isExec: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
    showHeader: Boolean = true,
    body: (@Composable () -> Unit)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val status = when { running -> ToolStepStatus.RUNNING; ok -> ToolStepStatus.SUCCEEDED; else -> ToolStepStatus.FAILED }
    val statusColor by animateColorAsState(when (status) {
        ToolStepStatus.RUNNING -> MaterialTheme.colorScheme.primary
        ToolStepStatus.FAILED -> MaterialTheme.colorScheme.error
        ToolStepStatus.SUCCEEDED -> MaterialTheme.colorScheme.onSurfaceVariant
    }, animationSpec = tween(180), label = "Tool status color")
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.5f))
            .indication(interaction, LocalIndication.current),
    ) {
        if (showHeader) Row(
            Modifier.fillMaxWidth().chatDisclosure(interaction, onToggle)
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Crossfade(
                targetState = status,
                animationSpec = tween(180),
                label = "Tool status icon",
                modifier = Modifier.size(14.dp).semantics {
                    contentDescription = when (status) {
                        ToolStepStatus.RUNNING -> "Выполняется"
                        ToolStepStatus.FAILED -> "Ошибка выполнения"
                        ToolStepStatus.SUCCEEDED -> "Выполнено"
                    }
                },
            ) { iconStatus ->
                Canvas(Modifier.fillMaxSize()) {
                    val stroke = 1.4.dp.toPx()
                    // Draw every status inside the icon bounds, independent of text line height.
                    when (iconStatus) {
                        ToolStepStatus.RUNNING -> {
                            drawCircle(statusColor, radius = size.minDimension / 2 - stroke / 2, style = Stroke(stroke))
                            drawLine(statusColor, center, Offset(center.x, size.height * 0.25f), stroke, StrokeCap.Round)
                            drawLine(statusColor, center, Offset(size.width * 0.72f, center.y), stroke, StrokeCap.Round)
                        }
                        ToolStepStatus.SUCCEEDED -> {
                            val bend = Offset(size.width * 0.4f, size.height * 0.76f)
                            drawLine(statusColor, Offset(size.width * 0.16f, size.height * 0.52f), bend, stroke, StrokeCap.Round)
                            drawLine(statusColor, bend, Offset(size.width * 0.84f, size.height * 0.24f), stroke, StrokeCap.Round)
                        }
                        ToolStepStatus.FAILED -> {
                            drawLine(statusColor, Offset(size.width * 0.22f, size.height * 0.22f),
                                Offset(size.width * 0.78f, size.height * 0.78f), stroke, StrokeCap.Round)
                            drawLine(statusColor, Offset(size.width * 0.78f, size.height * 0.22f),
                                Offset(size.width * 0.22f, size.height * 0.78f), stroke, StrokeCap.Round)
                        }
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            if (expanded && body == null) ChatPlainText(if (title.length > 6000) title + "\n\n" + result else title,
                Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            else Text(title.take(6000), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text(
                if (expanded) "▴" else "▾",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        AnimatedVisibility(showHeader && running && live,
            enter = fadeIn(tween(160)) + expandVertically(tween(200), expandFrom = Alignment.Top),
            exit = fadeOut(tween(120)) + shrinkVertically(tween(200), shrinkTowards = Alignment.Top),
        ) {
            Text(
                if (isExec) "Выполняется команда…" else "Выполняется действие…",
                modifier = Modifier.padding(start = 10.dp, end = 10.dp, bottom = 6.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (body != null) Box(Modifier.padding(horizontal = 10.dp)) { body() }
        if (body == null && expanded && result.isNotBlank() && title.length <= 6000) {
            ChatPlainText(result, Modifier.padding(start = 10.dp, end = 10.dp, bottom = 6.dp),
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = MagicFonts.code),
                color = if (ok) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
        }
    }
}

/** Текущий ответ агента со статусом и раскрываемыми размышлениями внизу. */
@Composable
private fun DraftFragment(first: Boolean, last: Boolean, content: @Composable () -> Unit) {
    Column(
        Modifier.padding(top = if (first) 10.dp else 0.dp).widthIn(max = 680.dp).fillMaxWidth()
            .clip(MaterialTheme.shapes.medium.copy(
                topStart = if (first) MaterialTheme.shapes.medium.topStart else CornerSize(0.dp),
                topEnd = if (first) MaterialTheme.shapes.medium.topEnd else CornerSize(0.dp),
                bottomStart = if (last) MaterialTheme.shapes.medium.bottomStart else CornerSize(0.dp),
                bottomEnd = if (last) MaterialTheme.shapes.medium.bottomEnd else CornerSize(0.dp)))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(start = 14.dp, end = 14.dp, top = if (first) 10.dp else 0.dp, bottom = if (last) 10.dp else 0.dp),
    ) {
        content()
    }
}

@Composable
internal fun AgentMessageStatus(draft: CodingDraft, expanded: Boolean, waitingForUser: Boolean = false, onToggle: () -> Unit) {
    val fragments = remember(draft.steps, draft.thinking) {
        val recorded = draft.steps.filter { it.kind == CodingStepKind.THINKING }.map { it.title }
        // The recorder normally includes the live fragment in steps; other runtimes may send it separately.
        if (draft.thinking.isNotBlank() && recorded.lastOrNull() != draft.thinking) recorded + draft.thinking else recorded
    }
    val tool = draft.steps.lastOrNull { it.running && it.kind in listOf(CodingStepKind.TOOL, CodingStepKind.EXEC) }
    val progress = draft.steps.lastOrNull()?.takeIf { it.kind == CodingStepKind.INFO && it.running }
    val summary = remember(draft.reasoningSummary) { currentThinkingSummary(draft.reasoningSummary) }
    val activity = when {
        waitingForUser -> "Ждём вашего ответа"
        draft.failedMessage != null -> "Работа остановлена из-за ошибки"
        !draft.active -> "Работа завершена"
        progress != null -> progress.title
        tool?.kind == CodingStepKind.EXEC -> "Агент выполняет команду…"
        tool != null -> "Агент выполняет действие…"
        draft.awaitingModel -> "Ожидает ответа модели…"
        summary.isNotBlank() -> summary
        draft.steps.lastOrNull()?.kind == CodingStepKind.ANSWER -> "Готовит ответ…"
        else -> "Агент работает…"
    }
    val hasThinking = remember(fragments) { fragments.any { it.isNotBlank() } }
    val isWorking = draft.active && !waitingForUser && draft.failedMessage == null &&
        (progress != null || tool != null || !draft.awaitingModel)
    var dots by remember { mutableStateOf(1) }
    LaunchedEffect(isWorking) {
        dots = 1
        if (isWorking) {
            while (true) {
                delay(500)
                dots = dots % 3 + 1
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
                else if (draft.active) CodingSessionStatus.WORKING else CodingSessionStatus.IDLE,
                size = 6,
            )
            Spacer(Modifier.width(6.dp))
            Text(label, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false))
        }
        if (hasThinking) {
            val interaction = remember { MutableInteractionSource() }
            Column(Modifier.fillMaxWidth().padding(top = 6.dp)
                .clip(MaterialTheme.shapes.small)
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.5f))
                .indication(interaction, LocalIndication.current)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), MaterialTheme.shapes.small)) {
                Row(
                    Modifier.fillMaxWidth().chatDisclosure(interaction, onToggle).semantics {
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
                    val thinking = remember(fragments) { fragments.joinToString("\n\n").trim() }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                        if (draft.active) {
                            Text("Текст обновляется по мере ответа агента", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(6.dp))
                        }
                        ChatMarkdown(thinking, Modifier.fillMaxWidth().heightIn(max = 190.dp),
                            compact = true, streaming = draft.active, scrollable = true)
                    }
                }
            }
        }
    }
}

/** Use the latest heading supplied by the agent as the short activity label. */
internal fun currentThinkingSummary(thinking: String): String {
    if (thinking.isBlank()) return ""
    val tail = thinking.takeLast(4096)
    val heading = Regex("(?m)^\\s*(?:#{1,6}\\s+([^\\n]+)|\\*\\*([^*\\n]+)\\*\\*)")
        .findAll(tail).lastOrNull()
        ?.let { it.groupValues[1].ifBlank { it.groupValues[2] } }
    val summary = heading ?: tail.trim().substringAfterLast("\n\n").lineSequence().firstOrNull().orEmpty()
    return summary.replace(Regex("[*_`#]"), "").replace(Regex("\\s+"), " ").trim().take(120)
        .ifBlank { "Обдумывает задачу" }
}

@Composable
private fun CodingModeLabel(planning: Boolean, research: Boolean) {
    Text(if (research) "Исследование · код защищён" else if (planning) "Планирование" else "Обычный режим",
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
}

@Composable
internal fun CodingComposer(
    state: CodingComposerDraft = remember { CodingComposerDraft() },
    onSkills: (() -> Unit)? = null,
    enabled: Boolean,
    busy: Boolean,
    controls: (@Composable () -> Unit)? = null,
    planning: Boolean = false,
    research: Boolean = false,
    onPlanning: (() -> Unit)? = null,
    onInteractionMode: ((CodingInteractionMode) -> Unit)? = null,
    modeSwitchEnabled: Boolean = true,
    engine: CodingEngine? = null,
    searchProvider: SearchProvider = SearchProvider.AUTO,
    onSearchProvider: ((SearchProvider) -> Unit)? = null,
    onSend: (String, List<Attachment>) -> Unit,
    onAbort: () -> Unit,
    onPickAttachments: (Int, (List<Attachment>) -> Unit) -> Unit,
    onPasteAttachments: (Int, (List<Attachment>) -> Unit) -> Boolean = { _, _ -> false },
    onResume: ((String, List<Attachment>) -> Unit)? = null,
) {
    var text by state.text
    var attachments by state.attachments
    fun submit() {
        if (!enabled || busy || (onResume == null && text.isBlank() && attachments.isEmpty())) return
        (onResume ?: onSend)(text, attachments)
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
            if (onInteractionMode != null || planning || research) {
                CodingModeLabel(planning, research)
            }
            PendingAttachmentsRow(attachments, { target -> attachments = attachments.filterNot { it.id == target.id } })
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box {
                    var menuOpen by remember { mutableStateOf(false) }
                    var searchMenuOpen by remember { mutableStateOf(false) }
                    fun closeMenu() {
                        menuOpen = false
                        searchMenuOpen = false
                    }
                    TextButton(onClick = { searchMenuOpen = false; menuOpen = true },
                        modifier = Modifier.size(32.dp).semantics { contentDescription = "Инструменты и параметры сессии" },
                        contentPadding = PaddingValues(0.dp)) {
                        val iconColor = MaterialTheme.colorScheme.primary
                        Canvas(Modifier.size(18.dp)) {
                            drawCircle(iconColor, style = Stroke(width = 1.5.dp.toPx()))
                            drawCircle(iconColor, radius = 1.dp.toPx(), center = Offset(center.x, size.height * 0.3f))
                            drawLine(iconColor, Offset(center.x, size.height * 0.48f),
                                Offset(center.x, size.height * 0.73f), strokeWidth = 1.5.dp.toPx(), cap = StrokeCap.Round)
                        }
                    }
                    DropdownMenu(menuOpen, ::closeMenu) {
                        if (searchMenuOpen && onSearchProvider != null) {
                            DropdownMenuItem(
                                text = { Text("Поисковый движок") },
                                leadingIcon = { Text("‹") },
                                onClick = { searchMenuOpen = false },
                            )
                            HorizontalDivider()
                            SearchProvider.entries.forEach { provider ->
                                DropdownMenuItem(
                                    text = { Text(provider.menuLabel) },
                                    trailingIcon = if (searchProvider == provider) { { Text("✓") } } else null,
                                    onClick = { closeMenu(); onSearchProvider(provider) },
                                )
                            }
                        } else {
                            DropdownMenuItem(text = { Text("SKILLS") }, enabled = onSkills != null,
                                onClick = { closeMenu(); onSkills?.invoke() })
                            DropdownMenuItem(
                                text = { Text("Прикрепить файлы") },
                                leadingIcon = { Text("📎") },
                                onClick = {
                                    closeMenu()
                                    onPickAttachments(attachments.size) { attachments = attachments + it }
                                },
                            )
                            if (onInteractionMode != null) {
                                val currentMode = if (planning) CodingInteractionMode.PLANNING else if (research) CodingInteractionMode.RESEARCH else CodingInteractionMode.CODE
                                CodingInteractionMode.entries.forEach { mode ->
                                    DropdownMenuItem(
                                        text = { Text(when (mode) {
                                            CodingInteractionMode.CODE -> "Обычный режим"
                                            CodingInteractionMode.RESEARCH -> "Режим исследования"
                                            CodingInteractionMode.PLANNING -> "Режим планирования"
                                        }) },
                                        trailingIcon = if (currentMode == mode) { { Text("✓") } } else null,
                                        enabled = modeSwitchEnabled && !busy && (!planning || mode == CodingInteractionMode.PLANNING),
                                        onClick = { closeMenu(); if (currentMode != mode) onInteractionMode(mode) },
                                    )
                                }
                            } else if (onPlanning != null) {
                                DropdownMenuItem(
                                    text = { Text("Режим планирования") },
                                    leadingIcon = { Text("🔀") },
                                    trailingIcon = if (planning) { { Text("✓") } } else null,
                                    onClick = {
                                        closeMenu()
                                        if (!planning) onPlanning()
                                    },
                                )
                            }
                            if (onSearchProvider != null || engine != null) HorizontalDivider()
                            if (onSearchProvider != null) {
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text("Поисковый движок")
                                            Text(searchProvider.menuLabel, style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    },
                                    trailingIcon = { Text("›") },
                                    onClick = { searchMenuOpen = true },
                                )
                            }
                            if (engine != null) {
                                Column(Modifier.padding(horizontal = 12.dp, vertical = 12.dp)) {
                                    Text("Backend coding agent", style = MaterialTheme.typography.bodyLarge)
                                    Text(engine.title, style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
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
                            if (event.type == KeyEventType.KeyDown && event.key == Key.V &&
                                (event.isCtrlPressed || event.isMetaPressed)) {
                                onPasteAttachments(attachments.size) {
                                    attachments = (attachments + it).take(MAX_ATTACHMENTS_PER_MESSAGE)
                                }
                            } else if (event.type == KeyEventType.KeyDown && event.isMetaPressed && event.key == Key.Enter) {
                                submit()
                                true
                            } else {
                                false
                            }
                        },
                    maxLines = 6,
                    decorationBox = { inner ->
                        Box {
                            if (text.isEmpty()) Text(if (research) "Вопрос о проекте…" else "Поручение агенту в папке проекта…",
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
                else TextButton(enabled = enabled && (onResume != null || text.isNotBlank() || attachments.isNotEmpty()), onClick = ::submit,
                    contentPadding = PaddingValues(horizontal = 4.dp)) {
                    Text(if (!enabled) "Движок не готов" else if (onResume != null) "Продолжить" else "Отправить", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

private val SearchProvider.menuLabel: String
    get() = when (this) {
        SearchProvider.AUTO -> "Авто"
        SearchProvider.WIKIPEDIA -> "Wikipedia"
        SearchProvider.QUERIT -> "Querit"
        SearchProvider.GOOGLE -> "Google"
    }
