package io.aequicor.magicpaper.ui.screens
import io.aequicor.magicpaper.designsystem.PaperWorkspaceHeading
import io.aequicor.magicpaper.designsystem.PaperWorkspaceComposer
import io.aequicor.magicpaper.designsystem.PaperPromptField
import io.aequicor.magicpaper.designsystem.PaperWorkSurface
import io.aequicor.magicpaper.designsystem.PaperContentEntrance
import io.aequicor.magicpaper.designsystem.PaperTreeGroupHeader
import io.aequicor.magicpaper.designsystem.paperConversationMessage
import io.aequicor.magicpaper.domain.tools.ToolPhase

import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.SideEffect
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
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.FastOutSlowInEasing
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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.background
import io.aequicor.magicpaper.designsystem.paperClickable
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import io.aequicor.magicpaper.designsystem.paperChatTopShadow
import io.aequicor.magicpaper.designsystem.paperTranscriptFade
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.Layout
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import io.aequicor.magicpaper.designsystem.PaperFonts
import io.aequicor.magicpaper.designsystem.*
import androidx.compose.foundation.shape.RoundedCornerShape

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
            PaperPanel(shape = RoundedCornerShape(14.dp)) {
                Column(Modifier.padding(16.dp)) {
                    PaperText("SKILLS", style = LocalPaperTypography.current.title)
                    vm.projectSkills?.Content(projectId)
                        ?: PaperText("Проектные навыки недоступны на этой платформе")
                    PaperTextAction(onClick = { skillsProject = null }) { PaperText("Закрыть") }
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
                    ProjectsEmptyHint(hasProject = project != null, onCreate = {
                        if (project == null) vm.addCodingProject() else vm.requestCodingSession()
                    })
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
                PaperVerticalDivider(modifier = Modifier.align(Alignment.CenterEnd), color = LocalPaperColors.current.border)
                Box(Modifier.width(3.dp).height(28.dp).background(LocalPaperColors.current.border, RoundedCornerShape(6.dp)))
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
        ui.organisms[sessionInfo.organismId]?.takeIf { it.immunityId == sessionInfo.id }?.let { organism ->
            val actions by vm.immunityActions.collectAsState()
            io.aequicor.magicpaper.ui.components.ImmunityInterventions(organism,
                busyProposalIds = organism.interventions.filter { "${organism.id}:${it.id}" in actions }.map { it.id }.toSet(),
                onApprove = { proposal, action, confirmed -> vm.approveImmunityIntervention(organism.id, proposal.id, action, confirmed) },
                onDismiss = { vm.dismissImmunityIntervention(organism.id, it) })
        }
            CodingChat(
                project = project,
                session = effective,
                contextUsage = vm.usage.state.collectAsState().value.contexts["coding:${sessionInfo.id}"]?.takeIf { it.model == vm.codingProfileOf(sessionInfo, workerPlan)?.modelId }
                    ?: io.aequicor.magicpaper.domain.ContextUsageSnapshot("coding:${sessionInfo.id}", vm.codingProfileOf(sessionInfo, workerPlan)?.modelId.orEmpty()),
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
            .clip(RoundedCornerShape(6.dp))
            .background(
                if (selected) {
                    LocalPaperColors.current.selected
                } else {
                    LocalPaperColors.current.surface
                }
            )
            .paperClickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 5.dp),
    ) {
        PaperText(
            label,
            style = LocalPaperTypography.current.label,
            color = if (selected) {
                LocalPaperColors.current.action
            } else {
                LocalPaperColors.current.secondaryText
            },
        )
    }
}

// ---- Кружок активности ----------------------------------------------------

private val CodingSessionStatus.label: String
    get() = when (this) {
        CodingSessionStatus.WORKING -> "работает"
        CodingSessionStatus.WAITING -> "Ждём вашего ответа"
        CodingSessionStatus.CONFIRMATION -> "ждёт подтверждения доработки"
        CodingSessionStatus.BLOCKED -> "выполнение остановлено"
        CodingSessionStatus.QUEUED -> "ждёт родителя"
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
    io.aequicor.magicpaper.designsystem.PaperActivityIndicator(
        tone = when (status) {
            CodingSessionStatus.IDLE -> io.aequicor.magicpaper.designsystem.PaperActivityTone.READY
            CodingSessionStatus.WORKING -> io.aequicor.magicpaper.designsystem.PaperActivityTone.WORKING
            CodingSessionStatus.BLOCKED, CodingSessionStatus.WAITING,
            CodingSessionStatus.CONFIRMATION -> io.aequicor.magicpaper.designsystem.PaperActivityTone.ATTENTION
            CodingSessionStatus.QUEUED, CodingSessionStatus.SCHEDULED -> io.aequicor.magicpaper.designsystem.PaperActivityTone.QUEUED
        },
        label = status.label, running = status == CodingSessionStatus.WORKING,
        modifier = modifier, size = size.dp,
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
        val collapsedTasks = remember { mutableStateMapOf<String, Boolean>() }
        // Disclosure is local UI state: never reload a project or reset its active session.
        // Selecting another project opens its list; status updates preserve disclosure.
        var projectCollapsed by remember(ui.current?.id) { mutableStateOf(false) }
        val projectIndex = ui.projects.indexOfFirst { it.id == ui.current?.id }
        val tasks = ui.current?.let { ui.projectSessionTasks(it.id) }.orEmpty()
        val groups = buildList {
            var index = projectIndex + 1
            if (!projectCollapsed) tasks.forEach { task ->
                val rows = task.visibleRows(collapsed.filterValues { it }.keys)
                val expanded = if (task.organismId != null) collapsedTasks[task.key] != true
                    else collapsed[task.rootId] != true
                // An organism has a disclosure header of its own. Its zygote and immunity
                // remain peers in the view, just as they are in the runtime.
                val children = if (!expanded) emptyList() else if (task.organismId != null) rows else rows.drop(1)
                val start = index++
                index += children.size
                add(ProjectSessionGroup(task, rows.firstOrNull(), children, expanded, start, index))
            }
        }
        val sessionHeader: @Composable (ProjectSessionGroup) -> Unit = { group ->
            val task = group.task
            val toggle = {
                if (group.expanded) {
                    val visible = listState.layoutInfo.visibleItemsInfo
                    val project = visible.firstOrNull { it.key == "project-${ui.current?.id}" }
                    val top = project?.let { (it.offset + it.size).coerceAtLeast(0) } ?: 0
                    val header = visible.firstOrNull { it.key == task.key }
                    // Keep the pinned group visible when its scrolled-away members disappear.
                    if (header == null || header.offset < top) listState.requestScrollToItem(group.index, -top)
                }
                if (task.organismId != null) collapsedTasks[task.key] = group.expanded
                else task.rootId?.let { collapsed[it] = group.expanded }
                Unit
            }
            if (task.organismId != null) {
                val status = task.status
                PaperTreeGroupHeader(task.title, group.expanded, toggle,
                    modifier = Modifier.padding(start = 20.dp, end = 8.dp, top = 6.dp, bottom = 2.dp),
                    active = task.sessions.any { it.session.id == ui.activeSessionIdOf(it.session.projectId) },
                    leading = { StatusTooltip(status) { ActivityDot(status, size = 8) } })
            } else group.root?.let { root ->
                val session = root.item
                SessionRow(session, session.session.id == ui.activeSessionIdOf(session.session.projectId),
                    { onSelectSession(session.session.id) }, { onDeleteSession(session.session.id) },
                    { onAbortSession(session.session.id) },
                    onArchive = { onArchiveSession(session.session.id) },
                    childCount = root.childCount, expanded = group.expanded, onToggleChildren = toggle)
            }
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
                        ProjectHeaderPaperPanel(pinned) {
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
                            item(key = group.task.key) { sessionHeader(group) }
                            group.children.forEach { row ->
                                val child = row.item
                                item(key = "session-${child.session.id}") {
                                    SessionRow(child, child.session.id == activeId,
                                        { onSelectSession(child.session.id) }, { onDeleteSession(child.session.id) },
                                        { onAbortSession(child.session.id) },
                                        onArchive = { onArchiveSession(child.session.id) }, nested = true,
                                        nestedDepth = row.depth + if (group.task.organismId != null) 1 else 0,
                                        displayName = if (group.task.organismId != null && child.session.id == group.task.rootId) "Зигота" else child.session.name,
                                        childCount = row.childCount,
                                        expanded = collapsed[child.session.id] != true,
                                        onToggleChildren = { collapsed[child.session.id] = collapsed[child.session.id] != true })
                                }
                            }
                        }
                    }
                }
            }
            ProjectPinnedSession(listState, "project-${ui.current?.id}", groups, sessionHeader)
        }
        PaperTextAction(onClick = onAddProject, modifier = Modifier.padding(8.dp)) {
            PaperText("+ Новый проект", style = LocalPaperTypography.current.chrome)
        }
    }
}

private data class ProjectSessionGroup(
    val task: ProjectSessionTask,
    val root: ProjectSessionTreeRow?,
    val children: List<ProjectSessionTreeRow>,
    val expanded: Boolean,
    val index: Int,
    val endIndex: Int,
)

/** Animate the surface, never the lazy item's height: scroll anchors remain stable. */
@Composable
private fun ProjectHeaderPaperPanel(pinned: Boolean, content: @Composable () -> Unit) {
    val progress by animateFloatAsState(
        if (pinned) 1f else 0f,
        tween(200, easing = FastOutSlowInEasing),
        label = "projectHeaderPin",
    )
    val surface = LocalPaperColors.current.surface
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
    if (group.task.organismId == null && group.root?.item?.session?.planningMode != true && group.children.isEmpty()) return
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
            key(group.task.key) {
                Column(Modifier.fillMaxWidth().background(LocalPaperColors.current.surface)) { content(group) }
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
            .clip(RoundedCornerShape(6.dp))
            .hoverable(hoverInteraction)
            .paperClickable(onClick = onSelect)
            .padding(start = 8.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(14.dp)) {
            PaperText(
                if (expanded) "▾" else "▸",
                style = LocalPaperTypography.current.label,
                color = LocalPaperColors.current.border,
            )
        }
        StatusTooltip(status) { ActivityDot(status, size = 9) }
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            FadingSingleLineText(
                project.name,
                fontWeight = FontWeight.SemiBold,
                style = LocalPaperTypography.current.chrome,
                color = if (selected) LocalPaperColors.current.action else LocalPaperColors.current.text,
            )
            FadingSingleLineText(
                buildString {
                    append("$sessionCount ${sessionCountWord(sessionCount)}")
                    if (sessionCount > 0) {
                        if (runningSessions > 0) append(", $runningSessions работают")
                    }
                },
                style = LocalPaperTypography.current.chrome,
                color = LocalPaperColors.current.secondaryText,
            )
        }
        HoverActions(visible = showActions) {
            // The action is deliberately available only on the current project: the
            // creation dialog saves into the ViewModel's current project.
            if (selected) {
                PaperTextAction(
                    onClick = onAddSession,
                    modifier = Modifier.semantics { contentDescription = "Новая сессия" },
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                ) {
                    PaperText("Новая сессия", style = LocalPaperTypography.current.label)
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
    displayName: String = item.session.name,
    nested: Boolean = false,
    nestedDepth: Int = if (nested) 1 else 0,
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
            .padding(start = if (nested) 42.dp + 12.dp * (nestedDepth - 1).coerceAtLeast(0) else 20.dp, end = 8.dp, top = 2.dp, bottom = 2.dp)
            .clip(RoundedCornerShape(6.dp))
            .hoverable(hoverInteraction)
            .background(
                if (selected) {
                    LocalPaperColors.current.selected.copy(alpha = 0.55f)
                } else {
                    Color.Transparent
                }
            )
            .paperClickable(
                onClickLabel = if (selected && childCount > 0) {
                    if (expanded) "Свернуть этапы" else "Раскрыть этапы"
                } else null,
            ) {
                if (selected && childCount > 0) onToggleChildren() else onSelect()
            }
            .padding(horizontal = 6.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusTooltip(status) { ActivityDot(status, size = 8) }
        Spacer(Modifier.width(7.dp))
        FadingSingleLineText(
            displayName,
            modifier = Modifier.weight(1f),
            style = LocalPaperTypography.current.chrome,
            fontWeight = FontWeight.Normal,
            color = if (selected) LocalPaperColors.current.text else LocalPaperColors.current.text,
        )
        HoverActions(visible = showActions) {
            if (childCount > 0) {
                Box(Modifier.size(24.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .semantics { contentDescription = if (expanded) "Свернуть этапы" else "Раскрыть этапы" }
                    .paperClickable(
                        onClick = onToggleChildren,
                    ),
                    contentAlignment = Alignment.Center) {
                    PaperText(if (expanded) "▾" else "▸", color = LocalPaperColors.current.action)
                }
            }
            PaperTooltip("В архив") {
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
        PaperText(
            "⋯",
            style = LocalPaperTypography.current.label,
            color = LocalPaperColors.current.secondaryText,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .paperClickable { onOpenChange(true) }
                .semantics { contentDescription = "Действия" }
                .padding(horizontal = 6.dp),
        )
        if (open) {
            PaperMenuHost(expanded = true, onDismissRequest = { onOpenChange(false) }) {
                entries.forEach { (label, action) ->
                    PaperRichMenuAction(
                        text = { PaperText(label) },
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
    PaperTooltip(status.label) { content() }
}

@Composable
private fun ProjectsEmptyHint(hasProject: Boolean, onCreate: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        PaperText("✦", style = LocalPaperTypography.current.title, color = LocalPaperColors.current.action)
        Spacer(Modifier.height(8.dp))
        PaperText(
            if (hasProject) {
                "Создайте первую сессию"
            } else {
                "Откройте папку проекта"
            },
            style = LocalPaperTypography.current.body,
            color = LocalPaperColors.current.secondaryText,
        )
        Spacer(Modifier.height(16.dp))
        io.aequicor.magicpaper.designsystem.PaperButton(if (hasProject) "Новая сессия" else "Открыть проект", onCreate)
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
    contextUsage: io.aequicor.magicpaper.domain.ContextUsageSnapshot? = null,
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
    // Only arrivals during this open session animate; lazy reuse and saved history do not.
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
    val eventWaitLabel = session.session.stageId?.let { session.plan?.eventWaitLabel(it) }?.takeIf { it.isNotBlank() }
    var systemHeaderHeight by remember(session.session.id) { mutableStateOf(0.dp) }
    val protectedBottom = maxOf(systemHeaderHeight, with(density) { scroll.requestPinsBounds?.bottom?.toDp() ?: 0.dp })
    val effectHeight = if (protectedBottom > 0.dp) maxOf(32.dp, protectedBottom + 12.dp) else 32.dp
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val questionHeight = maxHeight * 0.75f
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().chatScrollInput(scroll)
                    .paperChatTopShadow(scrolled, effectHeight = effectHeight)
                    .paperTranscriptFade(topShadowVisible = scrolled, effectHeight = effectHeight),
                contentPadding = PaddingValues(start = 8.dp, top = systemHeaderHeight + 12.dp,
                    end = 8.dp, bottom = footerHeight + 4.dp),
                verticalArrangement = Arrangement.Top,
            ) {
                item(key = "project-header", contentType = "header") {
                    PaperWorkspaceHeading(session.session.name,
                        "${project.name}  /  ${session.session.interactionMode.title}")
                }
                items(fragments, key = { it.key }, contentType = { it.item.step?.kind ?: it.item.row.message.role }) { fragment ->
                    val item = fragment.item
                    val row = item.row
                    val message = row.message
                    val isDraft = message.id == draftRow?.message?.id
                    val rowStatus = status.takeIf { busy && statusMessageId != null &&
                        (message.id == statusMessageId || row.planCard?.id == statusMessageId) }
                    PaperContentEntrance(animate = fragment.parts == null && isDraft && draft.active &&
                        item.key in arrivingKeys && item.step?.kind in listOf(CodingStepKind.TOOL, CodingStepKind.EXEC)) {
                        SavedCodingHistoryItem(item, scroll, session.session, messages, planningService, onOpenSession, rowStatus,
                            pinNumber = pinNumbers[message.id], onShowPins = { browserMessageId = message.id },
                            live = isDraft && draft.active && (item.last || item.step?.kind in listOf(CodingStepKind.TOOL, CodingStepKind.EXEC)),
                            continued = isDraft && busy, fragment = fragment,
                            onExpand = { expandedMessages = expandedMessages + item.key },
                            onCollapse = {
                                scroll.preserveCollapsedItem(item.key, fragments.indexOfFirst { it.item.key == item.key } + 1)
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
            Column(Modifier.align(Alignment.TopStart).fillMaxWidth()
                .onSizeChanged { systemHeaderHeight = with(density) { it.height.toDp() } }) {
                if (showOrchestrationStatus)
                    OrchestrationStatus(session, planningService, onOpenSession, Modifier, scrolled = false)
                else if (interactions.isEmpty()) session.blockingReason?.let { reason ->
                    PaperStatusPanel(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                        PaperStatus("Выполнение остановлено. $reason", isError = true,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp))
                    }
                }
                if (eventWaitLabel != null) {
                    PaperPanel(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), color = LocalPaperColors.current.raisedSurface) {
                        PaperText(eventWaitLabel, Modifier.padding(10.dp), style = LocalPaperTypography.current.body)
                    }
                }
            }
            RequestPinsOverlay(pins, pinIndices, listState, scroll, Modifier.align(Alignment.TopEnd).offset(y = systemHeaderHeight),
                browserMessageId = browserMessageId, onCloseBrowser = { browserMessageId = null }, itemKeys = pinKeys, compact = true)
            ChatScrollToBottomButton(scroll,
                Modifier.align(Alignment.BottomEnd).padding(end = 8.dp, bottom = (footerHeight - 8.dp).coerceAtLeast(0.dp)))
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
                    contextUsage = contextUsage,
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
                            showHeader = fragment.first, toolPhase = step.toolPhase, body = {
                                parts.Content(fragment.index,
                                    style = LocalPaperTypography.current.body.copy(fontFamily = PaperFonts.code),
                                    color = if (step.ok) LocalPaperColors.current.secondaryText else LocalPaperColors.current.error)
                            })
                    } else parts.Content(fragment.index,
                        style = when (step?.kind) {
                            CodingStepKind.INFO -> LocalPaperTypography.current.body
                            CodingStepKind.ERROR -> LocalPaperTypography.current.body
                            else -> LocalPaperTypography.current.body
                        },
                        color = when {
                            step?.kind == CodingStepKind.ERROR || message.failed -> LocalPaperColors.current.error
                            step?.kind == CodingStepKind.INFO -> LocalPaperColors.current.secondaryText
                            else -> LocalPaperColors.current.text
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
                    PaperDivider(color = LocalPaperColors.current.border)
                    Spacer(Modifier.height(12.dp))
                    ChatMarkdown(card.text)
                    if (planningService != null) {
                        Spacer(Modifier.height(6.dp))
                        PlanningChatMessage(card, session, messages, planningService, onOpenSession)
                    }
                }
                OrchestrationMessageInputStatus(message, session.id, planningService)
                if (message.pendingDelivery) PaperText("Ожидает передачи после текущего хода", style = LocalPaperTypography.current.label)
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
    val systemStep = step?.kind in listOf(CodingStepKind.SYSTEM, CodingStepKind.INFO)
    if (message.systemNotice || systemStep) {
        PaperSystemMessage {
            PaperText("Системное сообщение", role = PaperTextRole.LABEL)
            header?.invoke()
            if (body != null) body() else ChatPlainText(step?.title ?: message.text,
                style = LocalPaperTypography.current.body, color = LocalPaperColors.current.systemText)
            if (showFooter) footer?.invoke()
        }
        return
    }
    val previewState = rememberSaveableStateHolder()
    val isUser = message.role == CodingRole.USER
    Row(
        modifier = Modifier.fillMaxWidth()
            .padding(top = if (first) 8.dp else 0.dp),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        MessagePinColumn(
            number = pinNumber.takeIf { isUser && last },
            onClick = onShowPins,
            modifier = Modifier
                .widthIn(max = 820.dp)
                .then(if (step != null || forceWidth) Modifier.fillMaxWidth() else Modifier)
                .paperConversationMessage(isUser, first, last),
        ) {
            if (first && message.systemNotice) PaperText("Системное сообщение", style = LocalPaperTypography.current.label,
                color = LocalPaperColors.current.secondaryText)
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
                                style = LocalPaperTypography.current.body,
                                color = if (message.failed) LocalPaperColors.current.error else LocalPaperColors.current.text,
                            )
                            if (message.activity.isNotEmpty()) {
                                Spacer(Modifier.height(6.dp))
                                PaperDivider(color = LocalPaperColors.current.border)
                                Spacer(Modifier.height(6.dp))
                                PaperText(
                                    "Действия агента:",
                                    style = LocalPaperTypography.current.label,
                                    color = LocalPaperColors.current.secondaryText,
                                )
                                message.activity.forEach { line ->
                                    PaperText(
                                        text = line,
                                        style = LocalPaperTypography.current.body,
                                        color = LocalPaperColors.current.secondaryText,
                                    )
                                }
                            }
                        }
                    }
                }
            }
            if (body != null && showFooter && step == null && !isUser && message.activity.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                PaperDivider(color = LocalPaperColors.current.border)
                PaperText("Действия агента:", style = LocalPaperTypography.current.label,
                    color = LocalPaperColors.current.secondaryText)
                message.activity.forEach { line ->
                    PaperText(line, style = LocalPaperTypography.current.body, color = LocalPaperColors.current.secondaryText)
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
                style = LocalPaperTypography.current.body,
                color = LocalPaperColors.current.error,
                modifier = Modifier.padding(vertical = 3.dp),
            )
        }
        CodingStepKind.INFO, CodingStepKind.SYSTEM -> {
            ChatPlainText(
                step.title,
                style = LocalPaperTypography.current.body,
                color = LocalPaperColors.current.secondaryText,
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
    PaperWorkSurface(Modifier.padding(vertical = 2.dp), expanded = expanded) {
        Row(Modifier.fillMaxWidth().chatDisclosure(interaction) { expanded = !expanded }
            .padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            PaperText(
                "·",
                style = LocalPaperTypography.current.body,
                color = LocalPaperColors.current.border,
            )
            Spacer(Modifier.width(8.dp))
            PaperText(
                if (expanded) "Размышление агента" else "Размышление агента",
                style = LocalPaperTypography.current.body,
                color = LocalPaperColors.current.secondaryText,
                modifier = Modifier.weight(1f),
            )
            PaperText(
                if (expanded) "▴" else "▾",
                style = LocalPaperTypography.current.body,
                color = LocalPaperColors.current.secondaryText,
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
        toolPhase = step.toolPhase,
        expanded = expanded,
        onToggle = { expanded = !expanded },
    )
}

private enum class ToolStepStatus { RUNNING, WAITING, SUCCEEDED, FAILED, CANCELLED, UNKNOWN }

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
    toolPhase: ToolPhase? = null,
    body: (@Composable () -> Unit)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val status = when { toolPhase == ToolPhase.WAITING && running -> ToolStepStatus.WAITING
        toolPhase == ToolPhase.UNKNOWN -> ToolStepStatus.UNKNOWN
        toolPhase == ToolPhase.CANCELLED -> ToolStepStatus.CANCELLED
        running -> ToolStepStatus.RUNNING; ok -> ToolStepStatus.SUCCEEDED; else -> ToolStepStatus.FAILED }
    val statusColor by animateColorAsState(when (status) {
        ToolStepStatus.RUNNING, ToolStepStatus.WAITING -> LocalPaperColors.current.action
        ToolStepStatus.FAILED, ToolStepStatus.UNKNOWN -> LocalPaperColors.current.error
        ToolStepStatus.SUCCEEDED, ToolStepStatus.CANCELLED -> LocalPaperColors.current.secondaryText
    }, animationSpec = tween(180), label = "Tool status color")
    PaperWorkSurface(Modifier.padding(vertical = 2.dp), expanded = expanded) {
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
                        ToolStepStatus.WAITING -> "Ожидается ответ пользователя"
                        ToolStepStatus.CANCELLED -> "Вызов отменён"
                        ToolStepStatus.FAILED -> "Ошибка выполнения"
                        ToolStepStatus.SUCCEEDED -> "Выполнено"
                        ToolStepStatus.UNKNOWN -> "Исход неизвестен; требуется сверка"
                    }
                },
            ) { iconStatus ->
                Canvas(Modifier.fillMaxSize()) {
                    val stroke = 1.4.dp.toPx()
                    // Draw every status inside the icon bounds, independent of text line height.
                    when (iconStatus) {
                        ToolStepStatus.RUNNING, ToolStepStatus.WAITING, ToolStepStatus.UNKNOWN -> {
                            drawCircle(statusColor, radius = size.minDimension / 2 - stroke / 2, style = Stroke(stroke))
                            drawLine(statusColor, center, Offset(center.x, size.height * 0.25f), stroke, StrokeCap.Round)
                            drawLine(statusColor, center, Offset(size.width * 0.72f, center.y), stroke, StrokeCap.Round)
                        }
                        ToolStepStatus.SUCCEEDED -> {
                            val bend = Offset(size.width * 0.4f, size.height * 0.76f)
                            drawLine(statusColor, Offset(size.width * 0.16f, size.height * 0.52f), bend, stroke, StrokeCap.Round)
                            drawLine(statusColor, bend, Offset(size.width * 0.84f, size.height * 0.24f), stroke, StrokeCap.Round)
                        }
                        ToolStepStatus.FAILED, ToolStepStatus.CANCELLED -> {
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
                Modifier.weight(1f), style = LocalPaperTypography.current.chrome,
                color = LocalPaperColors.current.secondaryText)
            else PaperText((if (status == ToolStepStatus.UNKNOWN) "Исход неизвестен · " else "") + title.take(6000), style = LocalPaperTypography.current.chrome,
                color = LocalPaperColors.current.secondaryText, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            PaperText(
                if (expanded) "▴" else "▾",
                style = LocalPaperTypography.current.chrome,
                color = LocalPaperColors.current.secondaryText,
            )
        }
        AnimatedVisibility(showHeader && running && live,
            enter = fadeIn(tween(160)) + expandVertically(tween(200), expandFrom = Alignment.Top),
            exit = fadeOut(tween(120)) + shrinkVertically(tween(200), shrinkTowards = Alignment.Top),
        ) {
            PaperText(
                if (isExec) "Выполняется команда…" else "Выполняется действие…",
                modifier = Modifier.padding(start = 10.dp, end = 10.dp, bottom = 6.dp),
                style = LocalPaperTypography.current.chrome,
                color = LocalPaperColors.current.secondaryText,
            )
        }
        if (body != null) Box(Modifier.padding(horizontal = 10.dp)) { body() }
        if (body == null && expanded && result.isNotBlank() && title.length <= 6000) {
            ChatPlainText(result, Modifier.padding(start = 10.dp, end = 10.dp, bottom = 6.dp),
                style = LocalPaperTypography.current.code,
                color = if (ok) LocalPaperColors.current.secondaryText else LocalPaperColors.current.error)
        }
    }
}

/** Текущий ответ агента со статусом и раскрываемыми размышлениями внизу. */
@Composable
private fun DraftFragment(first: Boolean, last: Boolean, content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = if (first) 8.dp else 0.dp)) {
        Column(Modifier.widthIn(max = 820.dp).fillMaxWidth()
            .paperConversationMessage(user = false, first = first, last = last)) { content() }
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
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ActivityDot(
                when {
                    draft.failedMessage != null -> CodingSessionStatus.BLOCKED
                    waitingForUser || draft.awaitingApproval -> CodingSessionStatus.WAITING
                    draft.active -> CodingSessionStatus.WORKING
                    else -> CodingSessionStatus.IDLE
                },
                size = 10,
            )
            Spacer(Modifier.width(8.dp))
            PaperText(label, style = LocalPaperTypography.current.chrome,
                color = LocalPaperColors.current.text,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false))
        }
        if (hasThinking) {
            val interaction = remember { MutableInteractionSource() }
            PaperWorkSurface(Modifier.padding(top = 6.dp), expanded = expanded) {
                Row(
                    Modifier.fillMaxWidth().chatDisclosure(interaction, onToggle).semantics {
                        contentDescription = if (expanded) "Свернуть размышления" else "Развернуть размышления"
                    }.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PaperText("Размышления агента", style = LocalPaperTypography.current.chrome,
                        color = LocalPaperColors.current.secondaryText,
                        modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    PaperText(if (expanded) "▴" else "▾", style = LocalPaperTypography.current.chrome,
                        color = LocalPaperColors.current.secondaryText)
                }
                if (expanded) {
                    val thinking = remember(fragments) { fragments.joinToString("\n\n").trim() }
                    PaperDivider(color = LocalPaperColors.current.border.copy(alpha = 0.5f))
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
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
    PaperText(if (research) "Исследование · код защищён" else if (planning) "Планирование" else "Обычный режим",
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        style = LocalPaperTypography.current.chrome, color = LocalPaperColors.current.secondaryText)
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
    contextUsage: io.aequicor.magicpaper.domain.ContextUsageSnapshot? = null,
) {
    var text by state.text
    var attachments by state.attachments
    val resumeSubmission = onResume != null && (!planning || (text.isBlank() && attachments.isEmpty()))
    fun submit() {
        if (!enabled || busy || (onResume == null && text.isBlank() && attachments.isEmpty())) return
        (if (resumeSubmission) onResume else onSend)(text, attachments)
        text = ""
        attachments = emptyList()
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val trailingLimit = maxWidth * 0.40f
        val narrowContext = maxWidth < 600.dp
        PaperWorkspaceComposer {
            PendingAttachmentsRow(attachments, { target -> attachments = attachments.filterNot { it.id == target.id } })
                PaperPromptField(
                    value = text, onValueChange = { text = it },
                    modifier = Modifier
                        .onPreviewKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown && event.key == Key.V &&
                                (event.isCtrlPressed || event.isMetaPressed)) {
                                onPasteAttachments(attachments.size) {
                                    attachments = (attachments + it).take(MAX_ATTACHMENTS_PER_MESSAGE)
                                }
                            } else if (event.type == KeyEventType.KeyDown && (event.isMetaPressed || event.isCtrlPressed) && event.key == Key.Enter) {
                                submit()
                                true
                            } else {
                                false
                            }
                        },
                    maxLines = 6,
                    placeholder = if (research) "Вопрос о проекте…" else "Что нужно сделать?",
                )
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box {
                    var menuOpen by remember { mutableStateOf(false) }
                    var searchMenuOpen by remember { mutableStateOf(false) }
                    fun closeMenu() {
                        menuOpen = false
                        searchMenuOpen = false
                    }
                    PaperTextAction(onClick = { searchMenuOpen = false; menuOpen = true },
                        modifier = Modifier.size(32.dp).semantics { contentDescription = "Инструменты и параметры сессии" },
                        contentPadding = PaddingValues(0.dp)) {
                        PaperText("+", style = LocalPaperTypography.current.title, color = LocalPaperColors.current.action)
                    }
                    PaperMenuHost(menuOpen, ::closeMenu) {
                        if (searchMenuOpen && onSearchProvider != null) {
                            PaperRichMenuAction(
                                text = { PaperText("Поисковый движок") },
                                leadingIcon = { PaperText("‹") },
                                onClick = { searchMenuOpen = false },
                            )
                            PaperDivider()
                            SearchProvider.entries.forEach { provider ->
                                PaperRichMenuAction(
                                    text = { PaperText(provider.menuLabel) },
                                    trailingIcon = if (searchProvider == provider) { { PaperText("✓") } } else null,
                                    onClick = { closeMenu(); onSearchProvider(provider) },
                                )
                            }
                        } else {
                            PaperRichMenuAction(text = { PaperText("Навыки") }, enabled = onSkills != null,
                                onClick = { closeMenu(); onSkills?.invoke() })
                            PaperRichMenuAction(
                                text = { PaperText("Прикрепить файлы") },
                                leadingIcon = { PaperText("📎") },
                                onClick = {
                                    closeMenu()
                                    onPickAttachments(attachments.size) { attachments = attachments + it }
                                },
                            )
                            if (onInteractionMode != null) {
                                val currentMode = if (planning) CodingInteractionMode.PLANNING else if (research) CodingInteractionMode.RESEARCH else CodingInteractionMode.CODE
                                CodingInteractionMode.entries.forEach { mode ->
                                    PaperRichMenuAction(
                                        text = { PaperText(when (mode) {
                                            CodingInteractionMode.CODE -> "Обычный режим"
                                            CodingInteractionMode.RESEARCH -> "Режим исследования"
                                            CodingInteractionMode.PLANNING -> "Режим планирования"
                                        }) },
                                        trailingIcon = if (currentMode == mode) { { PaperText("✓") } } else null,
                                        enabled = modeSwitchEnabled && !busy && (!planning || mode == CodingInteractionMode.PLANNING),
                                        onClick = { closeMenu(); if (currentMode != mode) onInteractionMode(mode) },
                                    )
                                }
                            } else if (onPlanning != null) {
                                PaperRichMenuAction(
                                    text = { PaperText("Режим планирования") },
                                    leadingIcon = { PaperText("🔀") },
                                    trailingIcon = if (planning) { { PaperText("✓") } } else null,
                                    onClick = {
                                        closeMenu()
                                        if (!planning) onPlanning()
                                    },
                                )
                            }
                            if (onSearchProvider != null || engine != null) PaperDivider()
                            if (onSearchProvider != null) {
                                PaperRichMenuAction(
                                    text = {
                                        Column {
                                            PaperText("Поисковый движок")
                                            PaperText(searchProvider.menuLabel, style = LocalPaperTypography.current.body,
                                                color = LocalPaperColors.current.secondaryText)
                                        }
                                    },
                                    trailingIcon = { PaperText("›") },
                                    onClick = { searchMenuOpen = true },
                                )
                            }
                            if (engine != null) {
                                Column(Modifier.padding(horizontal = 12.dp, vertical = 12.dp)) {
                                    PaperText("Движок", style = LocalPaperTypography.current.body)
                                    PaperText(engine.title, style = LocalPaperTypography.current.body,
                                        color = LocalPaperColors.current.secondaryText)
                                }
                            }
                        }
                    }
                }
                if (!narrowContext && (onInteractionMode != null || planning || research)) CodingModeLabel(planning, research)
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                    Row(Modifier.widthIn(max = trailingLimit),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(0.dp, Alignment.End)) {
                        if (!narrowContext) io.aequicor.magicpaper.ui.components.ContextUsageIndicator(contextUsage)
                        controls?.invoke()
                    }
                }
                PaperVerticalDivider(
                    modifier = Modifier.padding(horizontal = 6.dp).height(24.dp),
                    color = LocalPaperColors.current.border,
                )
                if (busy) io.aequicor.magicpaper.designsystem.PaperButton("Прервать", onAbort,
                    kind = io.aequicor.magicpaper.designsystem.PaperButtonKind.SECONDARY)
                else io.aequicor.magicpaper.designsystem.PaperButton(
                    if (!enabled) "Движок не готов" else if (resumeSubmission) "Продолжить" else "Отправить",
                    onClick = ::submit,
                    enabled = enabled && (onResume != null || text.isNotBlank() || attachments.isNotEmpty()))
            }
            if (narrowContext) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (onInteractionMode != null || planning || research) CodingModeLabel(planning, research)
                Spacer(Modifier.weight(1f))
                io.aequicor.magicpaper.ui.components.ContextUsageIndicator(contextUsage)
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
