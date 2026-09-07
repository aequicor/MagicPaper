package io.aequicor.magicpaper.ui.screens

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.aequicor.magicpaper.domain.Attachment
import io.aequicor.magicpaper.domain.CodingDraft
import io.aequicor.magicpaper.domain.CodingMessage
import io.aequicor.magicpaper.domain.CodingProject
import io.aequicor.magicpaper.domain.CodingRole
import io.aequicor.magicpaper.domain.CodingSessionStatus
import io.aequicor.magicpaper.domain.CodingStep
import io.aequicor.magicpaper.domain.CodingStepKind
import io.aequicor.magicpaper.domain.LlmProfile
import io.aequicor.magicpaper.domain.RuntimePhase
import io.aequicor.magicpaper.domain.RuntimeStatus
import io.aequicor.magicpaper.plugins.CodingSessionPanel
import io.aequicor.magicpaper.ui.CodingSessionMode
import io.aequicor.magicpaper.ui.CodingSessionUi
import io.aequicor.magicpaper.ui.CodingUi
import io.aequicor.magicpaper.ui.MagicPaperViewModel
import io.aequicor.magicpaper.ui.components.ChatMarkdown
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
        Row(modifier = Modifier.weight(1f)) {
            ProjectsPanel(
                ui = ui,
                onAddProject = vm::addCodingProject,
                onSelectProject = vm::selectCodingProject,
                onDeleteProject = vm::deleteCodingProject,
                onSelectSession = vm::selectCodingSession,
                onAddSession = vm::addCodingSession,
                onDeleteSession = vm::deleteCodingSession,
                onAbortSession = vm::abortCodingSession,
                onReply = { id, text -> vm.sendCodingPromptTo(id, text) },
                modifier = Modifier.width(272.dp),
            )
            VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Box(modifier = Modifier.weight(1f)) {
                val project = ui.current
                val activeId = project?.let { ui.activeSessionIdOf(it.id) }
                val active = ui.sessions.firstOrNull { it.session.id == activeId }
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
        if (panelPlugin != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SessionTab(
                    label = "Диалог",
                    selected = ui.sessionMode == CodingSessionMode.DIALOG,
                    onClick = { vm.setCodingSessionMode(CodingSessionMode.DIALOG) },
                )
                Spacer(Modifier.width(8.dp))
                SessionTab(
                    label = "План",
                    selected = ui.sessionMode == CodingSessionMode.PLUGIN_PANEL,
                    onClick = { vm.setCodingSessionMode(CodingSessionMode.PLUGIN_PANEL) },
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
        if (ui.sessionMode == CodingSessionMode.PLUGIN_PANEL && panelPlugin != null) {
            panelPlugin.SessionPanel(
                project,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
        } else {
            CodingChat(
                project = project,
                session = active,
                busy = active.running,
                engineReady = ui.runtime.ready,
                onSend = { text, attachments -> vm.sendCodingPromptTo(active.session.id, text, attachments) },
                onAbort = { vm.abortCodingSession(active.session.id) },
                onPickAttachments = { already, onPicked -> vm.pickAttachments(already, onPicked) },
                modelChip = {
                    CodingModelChip(
                        profile = vm.codingProfileOf(active.session),
                        overridden = active.session.llmProfileId != null,
                        onClick = { switcherOpen = true },
                    )
                },
            )
        }
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

private val CodingSessionStatus.label: String
    get() = when (this) {
        CodingSessionStatus.WORKING -> "работает"
        CodingSessionStatus.WAITING -> "ждёт ответа или подтверждения"
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
            .size((size * scale).dp)
            .clip(androidx.compose.foundation.shape.CircleShape)
            .background(color),
    )
}

/**
 * Левое меню раздела: список проектов, а под выбранным — его кодинг-сессии.
 * У каждой сессии свой кружок статуса, быстрый ответ и меню (прервать, удалить).
 */
@Composable
private fun ProjectsPanel(
    ui: CodingUi,
    onAddProject: () -> Unit,
    onSelectProject: (String) -> Unit,
    onDeleteProject: (String) -> Unit,
    onSelectSession: (String) -> Unit,
    onAddSession: () -> Unit,
    onDeleteSession: (String) -> Unit,
    onAbortSession: (String) -> Unit,
    onReply: (String, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        Text(
            "Проекты",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(ui.projects, key = { it.id }) { project ->
                val expanded = project.id == ui.current?.id
                val own = ui.sessionsOf(project.id)
                ProjectRow(
                    project = project,
                    selected = expanded,
                    expanded = expanded,
                    status = ui.statusOf(project.id),
                    runningSessions = own.count { it.running },
                    sessionCount = own.size,
                    onSelect = { onSelectProject(project.id) },
                    onDelete = { onDeleteProject(project.id) },
                )
                if (expanded) {
                    val activeId = ui.activeSessionIdOf(project.id)
                    own.forEach { item ->
                        SessionRow(
                            item = item,
                            selected = item.session.id == activeId,
                            onSelect = { onSelectSession(item.session.id) },
                            onDelete = { onDeleteSession(item.session.id) },
                            onAbort = { onAbortSession(item.session.id) },
                            onReply = { text -> onReply(item.session.id, text) },
                        )
                    }
                    AddSessionRow(onAdd = onAddSession)
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
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .clip(MaterialTheme.shapes.small)
            .background(
                if (selected) {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                } else {
                    Color.Transparent
                }
            )
            .clickable(onClick = onSelect)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Стрелка-маркер: под выбранным проектом раскрыт список его сессий.
        Text(
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
                style = MaterialTheme.typography.bodyLarge,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            Text(
                buildString {
                    append(project.path)
                    if (sessionCount > 0) {
                        append(" · $sessionCount ${sessionCountWord(sessionCount)}")
                        if (runningSessions > 0) append(", $runningSessions работают")
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        RowMenu(key = "project-${project.id}", entries = listOf("Удалить проект" to onDelete))
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
    onReply: (String) -> Unit,
) {
    val status = item.status
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 8.dp)
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
        Text(
            item.session.name,
            style = MaterialTheme.typography.bodyMedium,
            color = if (selected) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        // Быстрый ответ агенту без перехода в сессию.
        if (status == CodingSessionStatus.WAITING && !item.running) {
            TextButton(onClick = { onReply("Продолжай") }, modifier = Modifier.heightIn(min = 28.dp)) {
                Text("↩", style = MaterialTheme.typography.labelMedium)
            }
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
private fun CodingChat(
    project: CodingProject,
    session: CodingSessionUi,
    busy: Boolean,
    engineReady: Boolean,
    onSend: (String, List<Attachment>) -> Unit,
    onAbort: () -> Unit,
    onPickAttachments: (Int, (List<Attachment>) -> Unit) -> Unit,
    modelChip: (@Composable () -> Unit)? = null,
) {
    val listState = rememberLazyListState()
    val messages = session.messages
    val draft = session.draft
    // Живая лента держит конец: новый шаг прогона или доросший ответ видны сразу,
    // а не «с начала сообщения». Открутил журнал вверх — не мешаем читать.
    stickToBottom(listState, session.session.id)
    Column(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Text(
                    "Проект «${project.name}» · сессия «${session.session.name}» · ${project.path}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(messages, key = { it.id }) { message -> CodingMessageBubble(message) }
            if (draft.steps.isNotEmpty() || draft.failedMessage != null) {
                item(key = "draft") { DraftBubble(draft) }
            }
        }
        // Ряд с чипом модели: тап открывает переключатель источника/модели/усилия.
        if (modelChip != null) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) { modelChip() }
        }
        // Статус работы агента — снизу, над полем ввода: чем занят и о чём думает.
        AgentStatusPanel(draft = session.draft, running = busy)
        CodingComposer(
            enabled = engineReady && !busy,
            busy = busy,
            onSend = onSend,
            onAbort = onAbort,
            onPickAttachments = onPickAttachments,
        )
    }
}

/** Бабл записи журнала: лента прогона в хронологическом порядке либо просто текст. */
@Composable
private fun CodingMessageBubble(message: CodingMessage) {
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
                Text(message.text, style = MaterialTheme.typography.bodyLarge)
                // Прикреплённые к запросу файлы (лежат в изолированной папке рантайма).
                CodingAttachments(message.attachments)
            } else if (message.steps.isNotEmpty()) {
                // Лента: текст и действия идут как приходили — в хронологическом порядке.
                message.steps.forEach { step -> CodingStepRow(step, live = false) }
            } else {
                // Совместимость со старыми журналами без ленты.
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
}

/**
 * Строка ленты прогона: текст ответа — как есть, действия — сворачиваемой строкой
 * с раскрывающимся выводом инструмента (что реально пришло в ответ).
 */
@Composable
private fun CodingStepRow(step: CodingStep, live: Boolean) {
    when (step.kind) {
        CodingStepKind.ANSWER -> {
            Spacer(Modifier.height(4.dp))
            ChatMarkdown(text = step.title)
            Spacer(Modifier.height(4.dp))
        }
        CodingStepKind.THINKING -> ThinkingStepRow(step)
        CodingStepKind.ERROR -> Text(
            "✕ ${step.title}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(vertical = 3.dp),
        )
        CodingStepKind.INFO -> Text(
            "◷ ${step.title}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(vertical = 2.dp),
        )
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
            .clickable { expanded = !expanded }
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
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
            Text(
                step.title,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = MagicFonts.code),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Строка рассуждения модели: сворачиваемый «💭 …» с полным текстом по тапу. */
@Composable
private fun ToolStepRow(step: CodingStep, live: Boolean) {
    var expanded by rememberSaveable(step.callId.ifBlank { step.title }) { mutableStateOf(false) }
    val statusGlyph = when {
        step.running -> "◷"
        !step.ok -> "✕"
        else -> "✔"
    }
    val hasDetail = step.result.isNotBlank()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.5f))
            .then(if (hasDetail) Modifier.clickable { expanded = !expanded } else Modifier)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                statusGlyph,
                style = MaterialTheme.typography.bodySmall,
                color = when {
                    step.running -> MaterialTheme.colorScheme.primary
                    !step.ok -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Spacer(Modifier.width(8.dp))
            Text(
                step.title,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = if (expanded) Int.MAX_VALUE else 2,
                modifier = Modifier.weight(1f),
            )
            if (hasDetail) {
                Text(
                    if (expanded) "▴" else "▾",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (step.running && live && step.result.isBlank()) {
            Text(
                "выполняется…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        if (expanded && hasDetail) {
            Text(
                step.result,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = MagicFonts.code,
                ),
                color = if (step.ok) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.error
                },
            )
        }
    }
}

/**
 * Живая лента прогона: зафиксированные шаги (текст, действия, рассуждения) —
 * статус и полный текст размышления показывает нижняя панель [AgentStatusPanel].
 */
@Composable
private fun DraftBubble(draft: CodingDraft) {
    Column(
        modifier = Modifier
            .widthIn(max = 680.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.7f))
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        draft.steps.forEach { step -> CodingStepRow(step, live = true) }
    }
}

/**
 * Нижняя панель статуса кодинг-агента: пульсирующий кружок, строка «чем занят»
 * (думает / выполняет инструмент / ждёт движок) и весь текст текущего
 * размышления — живой, приклеенный к нижнему краю, чтобы последние мысли
 * были видны без прокрутки.
 */
@Composable
private fun AgentStatusPanel(draft: CodingDraft, running: Boolean) {
    if (!running) return
    // Все рассуждения прогона: лента timeline() уже содержит живой хвост
    // последним шагом THINKING — отдельно draft.thinking не добавляем, иначе дубль.
    val thinking = draft.steps
        .filter { it.kind == CodingStepKind.THINKING }
        .joinToString("\n\n") { it.title }
        .trim()
    // Текст размышлений по умолчанию свёрнут: снизу видна только строка статуса.
    var thinkingExpanded by rememberSaveable("agent-thinking-panel") { mutableStateOf(false) }
    val activeTool = draft.steps.lastOrNull { it.running }
    val status = when {
        activeTool != null -> "Агент выполняет: ${activeTool.title.removePrefix("⚒ ")}"
        thinking.isNotBlank() -> "Агент думает…"
        draft.awaitingModel -> "Жду ответ движка…"
        else -> "Агент работает…"
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.45f))
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            ActivityDot(CodingSessionStatus.WORKING, size = 9)
            Spacer(Modifier.width(8.dp))
            Text(
                status,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            // Раскрыть/свернуть полный текст рассуждений.
            if (thinking.isNotBlank()) {
                Spacer(Modifier.width(8.dp))
                Text(
                    "💭 ${if (thinkingExpanded) "свернуть" else "размышления"} ${if (thinkingExpanded) "▾" else "▴"}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clip(MaterialTheme.shapes.small)
                        .clickable { thinkingExpanded = !thinkingExpanded }
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                )
            }
        }
        if (thinking.isNotBlank() && thinkingExpanded) {
            Spacer(Modifier.height(4.dp))
            val scrollState = rememberScrollState()
            Box(modifier = Modifier.fillMaxWidth().heightIn(max = 190.dp).verticalScroll(scrollState)) {
                Text(
                    thinking,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = MagicFonts.code),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // Рассуждение растёт вниз: держим нижний край — видно последние мысли.
            LaunchedEffect(thinking.length) { scrollState.scrollTo(scrollState.maxValue) }
        }
    }
}

@Composable
private fun CodingComposer(
    enabled: Boolean,
    busy: Boolean,
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
    Column(modifier = Modifier.fillMaxWidth()) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        PendingAttachmentsRow(
            attachments = attachments,
            onRemove = { target -> attachments = attachments.filterNot { it.id == target.id } },
            modifier = Modifier.padding(top = 8.dp),
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                onClick = { onPickAttachments(attachments.size) { attachments = attachments + it } },
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text("📎", style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.width(4.dp))
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Поручение агенту в папке проекта…") },
                minLines = 1,
                maxLines = 5,
                shape = MaterialTheme.shapes.large,
            )
            Spacer(Modifier.width(8.dp))
            if (busy) {
                TextButton(onClick = onAbort, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("Прервать", fontWeight = FontWeight.Medium)
                }
            } else {
                TextButton(
                    enabled = enabled && (text.isNotBlank() || attachments.isNotEmpty()),
                    onClick = ::submit,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(if (enabled) "Отправить" else "Движок не готов")
                }
            }
        }
    }
}
