package io.aequicor.magicpaper.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.aequicor.magicpaper.designsystem.LocalPaperColors
import io.aequicor.magicpaper.designsystem.LocalPaperTypography
import io.aequicor.magicpaper.designsystem.PaperActivityIndicator
import io.aequicor.magicpaper.designsystem.PaperActivityShape
import io.aequicor.magicpaper.designsystem.PaperActivityTone
import io.aequicor.magicpaper.designsystem.PaperButton
import io.aequicor.magicpaper.designsystem.PaperButtonKind
import io.aequicor.magicpaper.designsystem.PaperDivider
import io.aequicor.magicpaper.designsystem.PaperText
import io.aequicor.magicpaper.designsystem.PaperTextAction
import io.aequicor.magicpaper.designsystem.PaperTextRole
import io.aequicor.magicpaper.designsystem.PaperTooltip
import io.aequicor.magicpaper.designsystem.PaperTreeGroupHeader
import io.aequicor.magicpaper.designsystem.paperClickable
import io.aequicor.magicpaper.domain.ChatSession
import io.aequicor.magicpaper.domain.CodingSessionStatus
import io.aequicor.magicpaper.domain.SessionKind
import io.aequicor.magicpaper.domain.aggregateCodingStatus
import io.aequicor.magicpaper.ui.CodingUi
import io.aequicor.magicpaper.ui.MagicPaperViewModel
import io.aequicor.magicpaper.ui.components.ToolbarButton
import io.aequicor.magicpaper.ui.components.ToolbarIcon

/** Элемент единого списка боковой панели. */
internal data class UnifiedSidebarItem(
    val id: String,
    val displayName: String,
    val sortTime: Long,
    val isCoding: Boolean,
    val projectName: String? = null,
    val projectId: String? = null,
    val codingStatus: CodingSessionStatus? = null,
    /** Иммунитет, привязанный к этой зиготе (если есть). */
    val immunity: ImmunityInfo? = null,
    /** Дочерние сессии (для зигот с организмом). */
    val children: List<UnifiedSidebarItem> = emptyList(),
    /** Признак группы организма: заголовок раскрывает дерево дочерних сессий. */
    val isOrganism: Boolean = false,
)

/** Данные об иммунитете, привязанном к зиготе. */
internal data class ImmunityInfo(
    val sessionId: String,
    val status: CodingSessionStatus,
    val selected: Boolean,
)

/** Элементы единого списка: чаты и кодинг-сессии, отсортированные по обновлению. */
@Composable
internal fun rememberUnifiedItems(
    chatSessions: List<ChatSession>,
    coding: CodingUi,
    selectedId: String?,
    viewingCoding: Boolean,
): List<UnifiedSidebarItem> {
    val chatItems = remember(chatSessions) {
        chatSessions.map { session ->
            UnifiedSidebarItem(
                id = session.id,
                displayName = session.title,
                sortTime = session.updatedAt,
                isCoding = false,
            )
        }
    }
    // Иммунитет-сессии исключены из списка — они доступны через ромбик на зиготе.
    // Сопоставляем зиготу с иммунитетом через organisms (надёжная привязка).
    val immunityByZygote = remember(coding.organisms, coding.sessions, selectedId, viewingCoding) {
        val result = mutableMapOf<String, ImmunityInfo>()
        coding.organisms.values.forEach { organism ->
            val immId = organism.immunityId ?: return@forEach
            val immSession = coding.sessions.firstOrNull { it.session.id == immId } ?: return@forEach
            result[organism.zygoteId] = ImmunityInfo(
                sessionId = immId,
                status = immSession.status,
                selected = viewingCoding && immId == selectedId,
            )
        }
        result
    }
    val codingItems = remember(coding.sessions, coding.projects, coding.organisms, immunityByZygote) {
        // Все сессии (включая архивные) для построения дерева; видимые фильтруются ниже.
        val allSessions = coding.sessions
        val sessionById = allSessions.associateBy { it.session.id }
        // Группируем участников по организмам.
        val organismMemberIds = mutableMapOf<String, MutableSet<String>>()
        coding.organisms.values.forEach { organism ->
            (organism.sessions.keys + organism.zygoteId + organism.immunityId?.let { listOf(it) }.orEmpty())
                .forEach { id -> organismMemberIds.getOrPut(id) { mutableSetOf() }.add(organism.id) }
        }
        val membership = allSessions.associate { item ->
            item.session.id to (item.session.organismId ?: organismMemberIds[item.session.id]?.singleOrNull())
        }
        // Мапа родительских связей внутри организма (originParentId из узла).
        val organismParents = mutableMapOf<String, String?>()
        coding.organisms.values.forEach { organism ->
            organism.sessions.forEach { (id, node) ->
                if (id != organism.zygoteId && id != organism.immunityId) {
                    organismParents[id] = node.originParentId
                }
            }
        }
        // Прямые потомки сессии: из дерева организма (если есть) или по parentSessionId.
        fun childIdsOf(parentId: String): List<String> {
            val organismId = membership[parentId]
            return if (organismId != null) {
                organismParents.filterValues { it == parentId }.keys.toList()
            } else {
                allSessions.filter { it.session.parentSessionId == parentId }.map { it.session.id }
            }
        }
        // Рекурсивный сбор видимых потомков: архивные скрыты, но их дети показаны.
        fun collectVisibleChildren(parentId: String, excludeIds: Set<String>): List<UnifiedSidebarItem> {
            val result = mutableListOf<UnifiedSidebarItem>()
            val pending = ArrayDeque<String>()
            childIdsOf(parentId).filter { it !in excludeIds }.forEach { pending.addLast(it) }
            while (pending.isNotEmpty()) {
                val childId = pending.removeLast()
                val childUi = sessionById[childId] ?: continue
                if (childUi.session.archived || childUi.session.sessionKind == SessionKind.IMMUNITY) {
                    // Архивный родитель скрыт, но его дети показаны.
                    childIdsOf(childId).filter { it !in excludeIds }.forEach { pending.addLast(it) }
                    continue
                }
                val childItem = UnifiedSidebarItem(
                    id = childUi.session.id,
                    displayName = childUi.session.name,
                    sortTime = childUi.session.createdAt,
                    isCoding = true,
                    projectId = childUi.session.projectId,
                    codingStatus = childUi.status,
                )
                result.add(childItem)
                // Рекурсивно добавляем потомков этого ребёнка.
                childIdsOf(childId).filter { it !in excludeIds }.forEach { pending.addLast(it) }
            }
            return result
        }
        // Корневые элементы: зиготы организмов и автономные сессии без родителя.
        val visible = allSessions.filter { !it.session.archived && it.session.sessionKind != SessionKind.IMMUNITY }
        visible
            .filter { item ->
                val parent = item.session.parentSessionId
                val organismId = membership[item.session.id]
                if (organismId != null) {
                    val organism = coding.organisms[organismId]
                    // Зигота организма — корневой элемент; остальные участники — дети.
                    organism?.zygoteId == item.session.id
                } else {
                    parent == null || sessionById[parent]?.session?.archived == true
                }
            }
            .map { sessionUi ->
                val organismId = membership[sessionUi.session.id]
                val organism = organismId?.let { coding.organisms[it] }
                if (organism != null && sessionUi.session.id == organism.zygoteId) {
                    val excludeIds = setOfNotNull(organism.zygoteId, organism.immunityId)
                    val children = collectVisibleChildren(organism.zygoteId, excludeIds)
                    val memberUis = allSessions.filter { membership[it.session.id] == organismId &&
                        it.session.id !in excludeIds && !it.session.archived }
                    val status = aggregateCodingStatus(
                        (memberUis + sessionUi).map { it.status }
                    )
                    UnifiedSidebarItem(
                        id = sessionUi.session.id,
                        displayName = sessionUi.session.name,
                        sortTime = sessionUi.session.createdAt,
                        isCoding = true,
                        projectName = coding.projects.firstOrNull { it.id == sessionUi.session.projectId }?.name,
                        projectId = sessionUi.session.projectId,
                        codingStatus = status,
                        immunity = immunityByZygote[sessionUi.session.id],
                        children = children,
                        isOrganism = true,
                    )
                } else {
                    UnifiedSidebarItem(
                        id = sessionUi.session.id,
                        displayName = sessionUi.session.name,
                        sortTime = sessionUi.session.createdAt,
                        isCoding = true,
                        projectName = coding.projects.firstOrNull { it.id == sessionUi.session.projectId }?.name,
                        projectId = sessionUi.session.projectId,
                        codingStatus = sessionUi.status,
                    )
                }
            }
    }
    return remember(chatItems, codingItems) {
        (chatItems + codingItems).sortedByDescending { it.sortTime }
    }
}

/** Единая боковая панель: чаты и кодинг-сессии в одном списке с группировкой по проектам. */
@Composable
fun UnifiedSidebar(
    vm: MagicPaperViewModel,
    chatSessions: List<ChatSession>,
    coding: CodingUi,
    selectedId: String?,
    viewingCoding: Boolean,
    modifier: Modifier = Modifier,
) {
    val items = rememberUnifiedItems(chatSessions, coding, selectedId, viewingCoding)
    val codingByProject = items.filter { it.isCoding }.groupBy { it.projectId }
    val projectOrder = coding.projects.map { it.id }
    val chatItems = items.filter { !it.isCoding }
    val collapsedProjects = remember { mutableSetOf<String>() }
    val collapsedOrganisms = remember { mutableStateMapOf<String, Boolean>() }
    val collapsedSessions = remember { mutableStateMapOf<String, Boolean>() }

    Column(modifier.fillMaxHeight()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PaperText("Сессии", role = PaperTextRole.TITLE)
        }
        PaperDivider()
        LazyColumn(
            state = rememberLazyListState(),
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) {
            projectOrder.forEach { projectId ->
                val project = coding.projects.firstOrNull { it.id == projectId } ?: return@forEach
                val sessions = codingByProject[projectId].orEmpty().sortedByDescending { it.sortTime }
                val collapsed = projectId in collapsedProjects
                item(key = "project:$projectId") {
                    ProjectSectionHeader(
                        name = project.name,
                        collapsed = collapsed,
                        onToggle = {
                            if (collapsed) collapsedProjects.remove(projectId)
                            else collapsedProjects.add(projectId)
                        },
                        onAddSession = { vm.requestCodingSessionInProject(projectId) },
                    )
                }
                if (!collapsed) {
                    sessions.forEach { item ->
                        val selected = item.id == selectedId && viewingCoding
                        if (item.isOrganism) {
                            val organismExpanded = collapsedOrganisms[item.id] != false
                            item(key = "organism:${item.id}") {
                                var menuOpen by rememberSaveable(item.id) { mutableStateOf(false) }
                                PaperTreeGroupHeader(
                                    title = item.displayName,
                                    expanded = organismExpanded,
                                    onToggle = { collapsedOrganisms[item.id] = !organismExpanded },
                                    modifier = Modifier.padding(start = 12.dp, end = 8.dp, top = 6.dp, bottom = 2.dp),
                                    active = selected,
                                    leading = {
                                        if (item.codingStatus != null) {
                                            ActivityDot(item.codingStatus, size = 8)
                                        }
                                    },
                                    onClick = { vm.selectUnifiedSession(item.id, true) },
                                    childCount = item.children.size,
                                    hoverActions = { isHovered ->
                                        HoverActions(visible = isHovered || menuOpen) {
                                            PaperTooltip("В архив") {
                                                ToolbarButton(
                                                    icon = ToolbarIcon.Archive,
                                                    label = "Архивировать задачу",
                                                    size = 24.dp,
                                                    onClick = { vm.archiveCodingSession(item.id) },
                                                )
                                            }
                                            Spacer(Modifier.width(4.dp))
                                            RowMenu(
                                                open = menuOpen,
                                                onOpenChange = { menuOpen = it },
                                                entries = listOf("Удалить задачу" to { vm.deleteCodingSession(item.id) }),
                                            )
                                        }
                                    },
                                    keepActionsVisible = menuOpen,
                                    trailing = item.immunity?.let { immunity ->
                                        {
                                            ImmunityDiamondButton(
                                                status = immunity.status,
                                                selected = immunity.selected,
                                                onClick = { vm.selectUnifiedSession(immunity.sessionId, true) },
                                            )
                                        }
                                    },
                                )
                            }
                            if (organismExpanded) {
                                item.children.forEach { child ->
                                    val childSelected = child.id == selectedId && viewingCoding
                                    val childExpanded = collapsedSessions[child.id] != false
                                    item(key = "session:${child.id}") {
                                        UnifiedChildSessionRow(
                                            item = child,
                                            selected = childSelected,
                                            expanded = childExpanded,
                                            onToggle = { collapsedSessions[child.id] = !childExpanded },
                                            onClick = { vm.selectUnifiedSession(child.id, true) },
                                            onDelete = { vm.deleteCodingSession(child.id) },
                                            onArchive = { vm.archiveCodingSession(child.id) },
                                            depth = 1,
                                        )
                                    }
                                }
                            }
                        } else {
                            item(key = "c:${item.id}") {
                                UnifiedSessionRow(
                                    item = item,
                                    selected = selected,
                                    onClick = { vm.selectUnifiedSession(item.id, true) },
                                    onDelete = { vm.deleteCodingSession(item.id) },
                                    onArchive = { vm.archiveCodingSession(item.id) },
                                    onImmunityClick = { item.immunity?.let { vm.selectUnifiedSession(it.sessionId, true) } },
                                )
                            }
                        }
                    }
                }
            }
            if (chatItems.isNotEmpty()) {
                item(key = "section:chats") {
                    ChatSectionHeader()
                }
                items(chatItems, key = { "h:${it.id}" }) { item ->
                    val selected = item.id == selectedId && !viewingCoding
                    UnifiedSessionRow(
                        item = item,
                        selected = selected,
                        onClick = { vm.selectUnifiedSession(item.id, false) },
                        onDelete = { vm.deleteSession(item.id) },
                        onArchive = null,
                        onImmunityClick = null,
                    )
                }
            }
        }
        PaperDivider()
        Column(Modifier.padding(8.dp)) {
            PaperButton(
                "✦ Новый чат",
                vm::newSession,
                Modifier.fillMaxWidth(),
                kind = PaperButtonKind.QUIET,
            )
            Spacer(Modifier.height(2.dp))
            PaperButton(
                "📂 Новый проект",
                vm::addCodingProject,
                Modifier.fillMaxWidth(),
                kind = PaperButtonKind.QUIET,
            )
        }
    }
}

@Composable
private fun ProjectSectionHeader(
    name: String,
    collapsed: Boolean,
    onToggle: () -> Unit,
    onAddSession: () -> Unit,
) {
    val hoverInteraction = remember { MutableInteractionSource() }
    val hovered by hoverInteraction.collectIsHoveredAsState()
    var menuOpen by rememberSaveable(name) { mutableStateOf(false) }
    val showActions = hovered || menuOpen
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(6.dp))
            .hoverable(hoverInteraction)
            .paperClickable(onClick = onToggle)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PaperText(
            if (collapsed) "▸" else "▾",
            style = LocalPaperTypography.current.label,
            color = LocalPaperColors.current.secondaryText,
        )
        Spacer(Modifier.width(6.dp))
        PaperText(
            name,
            style = LocalPaperTypography.current.label,
            color = LocalPaperColors.current.secondaryText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        HoverActions(visible = showActions) {
            PaperTooltip("Новая сессия") {
                PaperTextAction(
                    onClick = onAddSession,
                    modifier = Modifier.semantics { contentDescription = "Новая сессия" },
                ) {
                    PaperText("+", style = LocalPaperTypography.current.label)
                }
            }
            Spacer(Modifier.width(4.dp))
            RowMenu(
                open = menuOpen,
                onOpenChange = { menuOpen = it },
                entries = listOf("Удалить проект" to {}),
            )
        }
    }
}

@Composable
private fun ChatSectionHeader() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PaperText(
            "Чаты",
            style = LocalPaperTypography.current.label,
            color = LocalPaperColors.current.secondaryText,
        )
    }
}

@Composable
private fun UnifiedSessionRow(
    item: UnifiedSidebarItem,
    selected: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onArchive: (() -> Unit)?,
    onImmunityClick: (() -> Unit)? = null,
) {
    val hoverInteraction = remember { MutableInteractionSource() }
    val hovered by hoverInteraction.collectIsHoveredAsState()
    var menuOpen by rememberSaveable(item.id) { mutableStateOf(false) }
    val showActions = hovered || menuOpen
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(
                if (selected) LocalPaperColors.current.selected.copy(alpha = 0.55f)
                else Color.Transparent
            )
            .hoverable(hoverInteraction)
            .paperClickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (item.isCoding && item.codingStatus != null) {
            ActivityDot(item.codingStatus, size = 8)
            Spacer(Modifier.width(8.dp))
        } else if (!item.isCoding) {
            PaperText("✦", role = PaperTextRole.LABEL, color = LocalPaperColors.current.secondaryText)
            Spacer(Modifier.width(8.dp))
        }

        Column(modifier = Modifier.weight(1f)) {
            PaperText(
                item.displayName,
                style = LocalPaperTypography.current.body,
                color = if (selected) LocalPaperColors.current.action else LocalPaperColors.current.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // Ромбик иммунитета на строке зиготы.
        val immunity = item.immunity
        if (immunity != null && onImmunityClick != null) {
            Spacer(Modifier.width(4.dp))
            ImmunityDiamond(
                status = immunity.status,
                selected = immunity.selected,
                onClick = onImmunityClick,
            )
        }
        HoverActions(visible = showActions) {
            if (onArchive != null) {
                PaperTooltip("В архив") {
                    ToolbarButton(
                        icon = ToolbarIcon.Archive,
                        label = "Архивировать сессию",
                        size = 24.dp,
                        onClick = onArchive,
                    )
                }
                Spacer(Modifier.width(4.dp))
            }
            val entries = buildList<Pair<String, () -> Unit>> {
                add((if (item.isCoding) "Удалить сессию" else "Удалить чат") to onDelete)
            }
            RowMenu(
                open = menuOpen,
                onOpenChange = { menuOpen = it },
                entries = entries,
            )
        }
    }
}

/** Строка дочерней сессии организма с отступом и поддержкой раскрытия вложенных потомков. */
@Composable
private fun UnifiedChildSessionRow(
    item: UnifiedSidebarItem,
    selected: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onArchive: () -> Unit,
    depth: Int,
) {
    val hoverInteraction = remember { MutableInteractionSource() }
    val hovered by hoverInteraction.collectIsHoveredAsState()
    var menuOpen by rememberSaveable(item.id) { mutableStateOf(false) }
    val showActions = hovered || menuOpen
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp + 12.dp * (depth - 1).coerceAtLeast(0), end = 8.dp, top = 2.dp, bottom = 2.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(
                if (selected) LocalPaperColors.current.selected.copy(alpha = 0.55f)
                else Color.Transparent
            )
            .hoverable(hoverInteraction)
            .paperClickable(
                onClickLabel = if (selected && item.children.isNotEmpty()) {
                    if (expanded) "Свернуть этапы" else "Раскрыть этапы"
                } else null,
            ) {
                if (selected && item.children.isNotEmpty()) onToggle() else onClick()
            }
            .padding(horizontal = 6.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (item.codingStatus != null) {
            ActivityDot(item.codingStatus, size = 8)
            Spacer(Modifier.width(7.dp))
        }
        PaperText(
            item.displayName,
            modifier = Modifier.weight(1f),
            style = LocalPaperTypography.current.chrome,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = if (selected) LocalPaperColors.current.text else LocalPaperColors.current.text,
        )
        HoverActions(visible = showActions) {
            if (item.children.isNotEmpty()) {
                Box(
                    Modifier.size(24.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .semantics { contentDescription = if (expanded) "Свернуть этапы" else "Раскрыть этапы" }
                        .paperClickable(onClick = onToggle),
                    contentAlignment = Alignment.Center,
                ) {
                    PaperText(if (expanded) "▾" else "▸", color = LocalPaperColors.current.action)
                }
            }
            PaperTooltip("В архив") {
                ToolbarButton(
                    icon = ToolbarIcon.Archive,
                    label = "Архивировать сессию",
                    size = 24.dp,
                    onClick = onArchive,
                )
            }
            RowMenu(
                open = menuOpen,
                onOpenChange = { menuOpen = it },
                entries = listOf("Удалить сессию" to onDelete),
            )
        }
    }
    // Вложенные потомки (дочерние сессии дочерней сессии).
    if (expanded && item.children.isNotEmpty()) {
        item.children.forEach { grandChild ->
            val gcExpanded = remember(item.id, grandChild.id) { mutableStateOf(false) }
            UnifiedChildSessionRow(
                item = grandChild,
                selected = false,
                expanded = gcExpanded.value,
                onToggle = { gcExpanded.value = !gcExpanded.value },
                onClick = {},
                onDelete = {},
                onArchive = {},
                depth = depth + 1,
            )
        }
    }
}

/** Ромбик иммунитета на строке зиготы (аналог ImmunityDiamondButton из CodingScreen). */
@Composable
private fun ImmunityDiamond(
    status: CodingSessionStatus,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val size = if (selected) 12.dp else 10.dp
    Box(
        modifier = Modifier
            .size(size)
            .paperClickable(
                onClick = onClick,
                onClickLabel = "Открыть чат иммунитета",
            )
            .semantics { contentDescription = "Иммунитет: ${status.label}" },
        contentAlignment = Alignment.Center,
    ) {
        PaperActivityIndicator(
            tone = when (status) {
                CodingSessionStatus.IDLE -> PaperActivityTone.READY
                CodingSessionStatus.WORKING -> PaperActivityTone.WORKING
                CodingSessionStatus.BLOCKED, CodingSessionStatus.WAITING,
                CodingSessionStatus.CONFIRMATION -> PaperActivityTone.ATTENTION
                CodingSessionStatus.QUEUED, CodingSessionStatus.SCHEDULED -> PaperActivityTone.QUEUED
            },
            label = status.label,
            running = status == CodingSessionStatus.WORKING,
            size = size,
            shape = PaperActivityShape.DIAMOND,
        )
    }
}
