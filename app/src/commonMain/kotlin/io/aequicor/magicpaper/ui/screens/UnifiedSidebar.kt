package io.aequicor.magicpaper.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.aequicor.magicpaper.designsystem.PaperButton
import io.aequicor.magicpaper.designsystem.PaperButtonKind
import io.aequicor.magicpaper.designsystem.PaperDivider
import io.aequicor.magicpaper.domain.ChatSession
import io.aequicor.magicpaper.domain.CodingSessionStatus
import io.aequicor.magicpaper.domain.SessionKind
import io.aequicor.magicpaper.domain.aggregateCodingStatus
import io.aequicor.magicpaper.domain.sidebarTitle
import io.aequicor.magicpaper.ui.CodingUi
import io.aequicor.magicpaper.ui.SidebarActions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

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
    /** Агент ответил, пока сессия не была в фокусе. */
    val unread: Boolean = false,
)

/** Данные об иммунитете, привязанном к зиготе. */
internal data class ImmunityInfo(
    val sessionId: String,
    val status: CodingSessionStatus,
    val selected: Boolean,
)

internal val CodingSessionStatus.sidebarSubtitle: String?
    get() = when (this) {
        CodingSessionStatus.WORKING -> "Работает"
        CodingSessionStatus.WAITING -> "Ждёт ответа"
        CodingSessionStatus.CONFIRMATION -> "Ждёт подтверждения"
        CodingSessionStatus.BLOCKED -> "Работа остановлена"
        CodingSessionStatus.QUEUED -> "Ждёт родителя"
        CodingSessionStatus.SCHEDULED -> "Ждёт события"
        CodingSessionStatus.UNREAD -> "Не прочитано"
        CodingSessionStatus.NEEDS_TESTING -> "Нужна проверка"
        CodingSessionStatus.IDLE -> null
    }

internal val unifiedSidebarItemComparator =
    compareByDescending<UnifiedSidebarItem> { it.sortTime }

/** A contiguous part of the activity feed. Chats interrupt project groups. */
internal data class UnifiedSidebarGroup(
    val key: String,
    val projectId: String?,
    val projectName: String?,
    val items: List<UnifiedSidebarItem>,
) {
    val showsProjectHeader: Boolean get() = projectId != null
}

internal enum class SidebarStatusFilter(val label: String) {
    ALL("Все статусы"), WORKING("В работе"), UNREAD("Непрочитанные"),
    WAITING("Ждут ответа"), CONFIRMATION("Ждут подтверждения"), BLOCKED("Остановлены"),
    QUEUED("Ждут родителя"), SCHEDULED("Ждут события"), NEEDS_TESTING("Нужна проверка"), READY("Готовы"),
}

// Presentation snapshots store primitive values, not enum instances or ordinals.
private val sidebarStatusFilterSaver = Saver<SidebarStatusFilter, String>(
    save = { it.name },
    restore = { name -> SidebarStatusFilter.entries.firstOrNull { it.name == name } },
)

internal sealed interface SidebarSourceFilter {
    data object All : SidebarSourceFilter
    data object Chats : SidebarSourceFilter
    data class Project(val id: String) : SidebarSourceFilter
}

internal fun filterUnifiedSidebarItems(
    items: List<UnifiedSidebarItem>, status: SidebarStatusFilter, source: SidebarSourceFilter,
): List<UnifiedSidebarItem> {
    fun matchesStatus(item: UnifiedSidebarItem) = when (status) {
        SidebarStatusFilter.ALL -> true
        SidebarStatusFilter.WORKING -> item.codingStatus == CodingSessionStatus.WORKING
        SidebarStatusFilter.UNREAD -> item.unread || item.codingStatus == CodingSessionStatus.UNREAD
        SidebarStatusFilter.WAITING -> item.codingStatus == CodingSessionStatus.WAITING
        SidebarStatusFilter.CONFIRMATION -> item.codingStatus == CodingSessionStatus.CONFIRMATION
        SidebarStatusFilter.BLOCKED -> item.codingStatus == CodingSessionStatus.BLOCKED
        SidebarStatusFilter.QUEUED -> item.codingStatus == CodingSessionStatus.QUEUED
        SidebarStatusFilter.SCHEDULED -> item.codingStatus == CodingSessionStatus.SCHEDULED
        SidebarStatusFilter.NEEDS_TESTING -> item.codingStatus == CodingSessionStatus.NEEDS_TESTING
        SidebarStatusFilter.READY -> item.codingStatus == CodingSessionStatus.IDLE
    }
    fun matchesSource(item: UnifiedSidebarItem) = when (source) {
        SidebarSourceFilter.All -> true
        SidebarSourceFilter.Chats -> !item.isCoding
        is SidebarSourceFilter.Project -> item.projectId == source.id
    }
    fun filtered(item: UnifiedSidebarItem): UnifiedSidebarItem? {
        val children = item.children.mapNotNull(::filtered)
        return item.copy(children = children).takeIf {
            matchesSource(item) && (matchesStatus(item) || children.isNotEmpty())
        }
    }
    return items.mapNotNull(::filtered)
}

internal fun groupUnifiedSidebarItems(items: List<UnifiedSidebarItem>): List<UnifiedSidebarGroup> {
    val groups = mutableListOf<UnifiedSidebarGroup>()
    items.sortedWith(unifiedSidebarItemComparator).forEach { item ->
        val previous = groups.lastOrNull()
        if (item.isCoding && item.projectId != null && previous?.projectId == item.projectId) {
            groups[groups.lastIndex] = previous.copy(items = previous.items + item)
        } else {
            groups += UnifiedSidebarGroup(
                key = "${if (item.isCoding) "project" else "chat"}:${item.id}",
                projectId = item.projectId.takeIf { item.isCoding },
                projectName = item.projectName.takeIf { item.isCoding },
                items = listOf(item),
            )
        }
    }
    return groups
}

internal fun UnifiedSidebarItem.sidebarSubtitle(showProject: Boolean): String? = listOfNotNull(
    codingStatus?.sidebarSubtitle,
    projectName.takeIf { showProject && !it.isNullOrBlank() },
).joinToString(" · ").takeIf { it.isNotBlank() }

/** Keeps a runtime recency timestamp: creation first, then every visible status transition. */
internal class SessionRecencyTracker(private val now: () -> Long) {
    private val statuses = mutableMapOf<String, CodingSessionStatus>()
    private val activityTimes = mutableMapOf<String, Long>()

    fun observe(
        id: String,
        status: CodingSessionStatus,
        createdAt: Long,
        persistedStatus: CodingSessionStatus? = null,
        persistedStatusChangedAt: Long = 0,
    ): Long {
        val previous = statuses.put(id, status)
        return when {
            previous == null -> activityTimes.getOrPut(id) {
                if (persistedStatus == status && persistedStatusChangedAt > 0) persistedStatusChangedAt else createdAt
            }
            previous != status -> now().also { activityTimes[id] = it }
            persistedStatus == status && persistedStatusChangedAt > activityTimes.getValue(id) ->
                persistedStatusChangedAt.also { activityTimes[id] = it }
            else -> activityTimes.getValue(id)
        }
    }
}

/** Элементы единого списка: чаты и кодинг-сессии, отсортированные по обновлению. */
@Composable
internal fun rememberUnifiedItems(
    chatSessions: List<ChatSession>,
    coding: CodingUi,
    selectedId: String?,
    viewingCoding: Boolean,
    recencyTracker: SessionRecencyTracker,
): List<UnifiedSidebarItem> {
    val chatItems = remember(chatSessions) {
        chatSessions.filterNot { it.archived }.map { session ->
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
    val codingItems = remember(coding.sessions, coding.projects, coding.organisms, immunityByZygote, recencyTracker) {
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
            for (childId in childIdsOf(parentId).filter { it !in excludeIds }) {
                val childUi = sessionById[childId] ?: continue
                val children = collectVisibleChildren(childId, excludeIds + parentId + childId)
                if (childUi.session.archived || childUi.session.sessionKind == SessionKind.IMMUNITY) {
                    // Архивный родитель скрыт, но его дети показаны.
                    result.addAll(children)
                    continue
                }
                val childItem = UnifiedSidebarItem(
                    id = childUi.session.id,
                    displayName = childUi.session.sidebarTitle(),
                    sortTime = recencyTracker.observe(
                        childUi.session.id,
                        childUi.status,
                        childUi.session.createdAt,
                        childUi.session.lastStatus,
                        childUi.session.statusChangedAt,
                    ),
                    isCoding = true,
                    projectId = childUi.session.projectId,
                    codingStatus = childUi.status,
                    unread = childUi.unread,
                    children = children,
                )
                result.add(childItem)
            }
            return result.sortedWith(unifiedSidebarItemComparator)
        }
        // Корневые элементы: зиготы организмов и автономные сессии без родителя.
        val visible = allSessions.filter { !it.session.archived && it.session.sessionKind != SessionKind.IMMUNITY }
        fun hasVisibleAncestor(id: String): Boolean {
            fun parentOf(childId: String): String? = if (membership[childId] != null) organismParents[childId]
                else sessionById[childId]?.session?.parentSessionId
            val visited = mutableSetOf(id)
            var parent = parentOf(id)
            while (parent != null && visited.add(parent)) {
                val session = sessionById[parent]?.session ?: return false
                if (!session.archived && session.sessionKind != SessionKind.IMMUNITY) return true
                parent = parentOf(parent)
            }
            return false
        }
        visible
            .filter { item ->
                val organismId = membership[item.session.id]
                if (organismId != null) {
                    val organism = coding.organisms[organismId]
                    // Зигота организма — корневой элемент; остальные участники — дети.
                    organism?.zygoteId == item.session.id ||
                        (sessionById[organism?.zygoteId]?.session?.archived != false && !hasVisibleAncestor(item.session.id))
                } else {
                    !hasVisibleAncestor(item.session.id)
                }
            }
            .map { sessionUi ->
                val organismId = membership[sessionUi.session.id]
                val organism = organismId?.let { coding.organisms[it] }
                if (organism != null && sessionUi.session.id == organism.zygoteId) {
                    val excludeIds = setOfNotNull(organism.zygoteId, organism.immunityId)
                    val children = collectVisibleChildren(organism.zygoteId, excludeIds)
                        .sortedWith(unifiedSidebarItemComparator)
                    val memberUis = allSessions.filter { membership[it.session.id] == organismId &&
                        it.session.id !in excludeIds && !it.session.archived }
                    val status = aggregateCodingStatus(
                        (memberUis + sessionUi).map { it.status }
                    )
                    UnifiedSidebarItem(
                        id = sessionUi.session.id,
                        displayName = sessionUi.session.sidebarTitle(),
                        sortTime = recencyTracker.observe(
                            sessionUi.session.id,
                            status,
                            sessionUi.session.createdAt,
                            sessionUi.session.lastStatus,
                            sessionUi.session.statusChangedAt,
                        ),
                        isCoding = true,
                        projectName = coding.projects.firstOrNull { it.id == sessionUi.session.projectId }?.name,
                        projectId = sessionUi.session.projectId,
                        codingStatus = status,
                        immunity = immunityByZygote[sessionUi.session.id],
                        children = children,
                        isOrganism = true,
                        unread = sessionUi.unread,
                    )
                } else {
                    UnifiedSidebarItem(
                        id = sessionUi.session.id,
                        displayName = sessionUi.session.sidebarTitle(),
                        sortTime = recencyTracker.observe(
                            sessionUi.session.id,
                            sessionUi.status,
                            sessionUi.session.createdAt,
                            sessionUi.session.lastStatus,
                            sessionUi.session.statusChangedAt,
                        ),
                        isCoding = true,
                        projectName = coding.projects.firstOrNull { it.id == sessionUi.session.projectId }?.name,
                        projectId = sessionUi.session.projectId,
                        codingStatus = sessionUi.status,
                        unread = sessionUi.unread,
                        children = collectVisibleChildren(sessionUi.session.id, setOf(sessionUi.session.id)),
                    )
                }
            }
    }
    return remember(chatItems, codingItems) {
        (chatItems + codingItems).sortedWith(unifiedSidebarItemComparator)
    }
}

/** Единая боковая панель: чаты и кодинг-сессии в одном списке с группировкой по проектам. */
@Composable
internal fun UnifiedSidebar(
    vm: SidebarActions,
    chatSessions: List<ChatSession>,
    coding: CodingUi,
    selectedId: String?,
    viewingCoding: Boolean,
    modifier: Modifier = Modifier,
    recencyTracker: SessionRecencyTracker,
) {
    // These values are persisted across app upgrades. Separate keyed groups keep
    // newly added controls from consuming the old positional collapse-map slots.
    var query by key("sidebar-query") { rememberSaveable { mutableStateOf("") } }
    var archivesOnly by key("sidebar-archives") { rememberSaveable { mutableStateOf(false) } }
    var statusFilter by key("sidebar-status-filter") {
        rememberSaveable(stateSaver = sidebarStatusFilterSaver) { mutableStateOf(SidebarStatusFilter.ALL) }
    }
    var sourceFilterKey by key("sidebar-source-filter") { rememberSaveable { mutableStateOf("all") } }
    val browsing = archivesOnly || query.isNotBlank()
    val searchCoding = remember(coding.sessions) { coding.sessions.map { it.session to it.messages } }
    val results by produceState<List<SessionSearchResult>?>(null, query, archivesOnly, chatSessions, searchCoding, coding.projects) {
        value = null
        if (browsing) {
            if (query.isNotBlank()) delay(150)
            value = withContext(Dispatchers.Default) { searchSessions(chatSessions, searchCoding, coding.projects, query, archivesOnly) }
        }
    }
    val items = rememberUnifiedItems(chatSessions, coding, selectedId, viewingCoding, recencyTracker)
    val sourceFilter = when (sourceFilterKey) {
        "chats" -> SidebarSourceFilter.Chats
        "all" -> SidebarSourceFilter.All
        else -> SidebarSourceFilter.Project(sourceFilterKey.removePrefix("project:"))
    }
    val filteredItems = remember(items, statusFilter, sourceFilter) {
        filterUnifiedSidebarItems(items, statusFilter, sourceFilter)
    }
    val groups = remember(filteredItems) { groupUnifiedSidebarItems(filteredItems) }
    var collapsedGroups by key("sidebar-collapsed-groups") {
        rememberSaveable { mutableStateOf(emptyMap<String, Boolean>()) }
    }
    var collapsedOrganisms by key("sidebar-collapsed-organisms") {
        rememberSaveable { mutableStateOf(emptyMap<String, Boolean>()) }
    }
    val collapsedSessions = remember { mutableStateMapOf<String, Boolean>() }
    var searchExpanded by key("sidebar-search-expanded") {
        rememberSaveable { mutableStateOf(query.isNotBlank()) }
    }

    Column(modifier.fillMaxHeight()) {
        SessionBrowserControls(query, { query = it }, searchExpanded, {
            searchExpanded = !searchExpanded
            if (!searchExpanded) query = ""
        }, archivesOnly,
            chatSessions.count { it.archived } + coding.sessions.count { it.session.archived },
            { archivesOnly = !archivesOnly },
            statusFilter = statusFilter,
            onStatusFilter = { statusFilter = it },
            sourceFilterKey = sourceFilterKey,
            onSourceFilter = { sourceFilterKey = it },
            projects = coding.projects.map { it.id to it.name },
        )
        PaperDivider()
        if (browsing) {
            SessionBrowserResults(results.orEmpty(), archivesOnly, query.isNotBlank(), selectedId, viewingCoding,
                onSelect = { vm.selectUnifiedSession(it.id, it.isCoding) },
                onRestore = { vm.restoreSession(it.id, it.isCoding) },
                modifier = Modifier.weight(1f), loading = results == null)
        }
        if (!browsing) UnifiedSessionFeed(
            groups = groups,
            selectedId = selectedId,
            viewingCoding = viewingCoding,
            collapsedGroups = collapsedGroups.keys,
            expanded = { item -> if (item.isOrganism) collapsedOrganisms[item.id] != false else collapsedSessions[item.id] != false },
            onToggleGroup = { group ->
                collapsedGroups = if (group.key in collapsedGroups) collapsedGroups - group.key
                else collapsedGroups + (group.key to true)
            },
            onToggleSession = { item ->
                if (item.isOrganism) collapsedOrganisms = collapsedOrganisms + (item.id to !(collapsedOrganisms[item.id] != false))
                else collapsedSessions[item.id] = !(collapsedSessions[item.id] != false)
            },
            onSelect = vm::selectUnifiedSession,
            onArchive = { item -> if (item.isCoding) vm.archiveCodingSession(item.id) else vm.archiveChatSession(item.id) },
            onDelete = { item -> if (item.isCoding) vm.deleteCodingSession(item.id) else vm.deleteSession(item.id) },
            onAddSession = vm::requestCodingSessionInProject,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )
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
