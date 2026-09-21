package io.aequicor.magicpaper.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.aequicor.magicpaper.designsystem.PaperButton
import io.aequicor.magicpaper.designsystem.PaperNoteAddIcon
import io.aequicor.magicpaper.designsystem.PaperButtonKind
import io.aequicor.magicpaper.designsystem.PaperDivider
import io.aequicor.magicpaper.domain.ChatSession
import io.aequicor.magicpaper.domain.CodingSessionStatus
import io.aequicor.magicpaper.ui.SidebarProjection
import io.aequicor.magicpaper.ui.SidebarCommand
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
    val sourceId: String = if (isCoding) "agent" else "chat",
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

/** Единая боковая панель: чаты и кодинг-сессии в одном списке с группировкой по проектам. */
@Composable
internal fun UnifiedSidebar(
    vm: SidebarActions,
    chatSessions: List<ChatSession>,
    projections: List<SidebarProjection>,
    selectedId: String?,
    viewingCoding: Boolean,
    modifier: Modifier = Modifier,
    listState: LazyListState,
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
    val searchDocuments = remember(projections) { projections.flatMap { it.search } }
    val projects = remember(projections) { projections.flatMap { it.projects } }
    val results by produceState<List<SessionSearchResult>?>(null, query, archivesOnly, chatSessions, searchDocuments) {
        value = null
        if (browsing) {
            if (query.isNotBlank()) delay(150)
            value = withContext(Dispatchers.Default) { searchSessions(chatSessions, searchDocuments, query, archivesOnly) }
        }
    }
    val items = remember(chatSessions, projections) {
        (chatSessions.filterNot { it.archived }.map { UnifiedSidebarItem(it.id, it.title, it.updatedAt, false) } +
            projections.flatMap { it.items }).sortedWith(unifiedSidebarItemComparator)
    }
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
    var searchExpanded by key("sidebar-search-expanded") {
        rememberSaveable { mutableStateOf(query.isNotBlank()) }
    }

    Column(modifier.fillMaxHeight()) {
        SessionBrowserControls(query, { query = it }, searchExpanded, {
            searchExpanded = !searchExpanded
            if (!searchExpanded) query = ""
        }, archivesOnly,
            chatSessions.count { it.archived } + searchDocuments.count { it.archived },
            { archivesOnly = !archivesOnly },
            statusFilter = statusFilter,
            onStatusFilter = { statusFilter = it },
            sourceFilterKey = sourceFilterKey,
            onSourceFilter = { sourceFilterKey = it },
            projects = projects.map { it.id to it.title },
            availableStatusFilters = (listOf(SidebarStatusFilter.ALL, SidebarStatusFilter.UNREAD) + projections.flatMap { it.statusFilters }).distinct(),
        )
        PaperDivider()
        if (browsing) {
            SessionBrowserResults(results.orEmpty(), archivesOnly, query.isNotBlank(), selectedId, viewingCoding,
                onSelect = { vm.dispatch(it.sourceId, SidebarCommand.Select(it.id)) },
                onRestore = { vm.dispatch(it.sourceId, SidebarCommand.Restore(it.id)) },
                modifier = Modifier.weight(1f), loading = results == null)
        }
        if (!browsing) UnifiedSessionFeed(
            groups = groups,
            selectedId = selectedId,
            viewingCoding = viewingCoding,
            collapsedGroups = collapsedGroups.keys,
            onToggleGroup = { group ->
                collapsedGroups = if (group.key in collapsedGroups) collapsedGroups - group.key
                else collapsedGroups + (group.key to true)
            },
            onSelect = { id, source -> vm.dispatch(source, SidebarCommand.Select(id)) },
            onArchive = { item -> vm.dispatch(item.sourceId, SidebarCommand.Archive(item.id)) },
            onDelete = { item -> vm.dispatch(item.sourceId, SidebarCommand.Delete(item.id)) },
            onAddSession = { source, project -> vm.dispatch(source, SidebarCommand.CreateInProject(project)) },
            modifier = Modifier.weight(1f).fillMaxWidth(),
            state = listState,
        )
        PaperDivider()
        Column(Modifier.padding(8.dp)) {
            PaperButton(
                "Новый чат",
                { vm.dispatch("chat", SidebarCommand.Create) },
                Modifier.fillMaxWidth(),
                kind = PaperButtonKind.QUIET,
                leadingIcon = { PaperNoteAddIcon() },
            )
            projections.flatMap { it.creationActions }.forEach { action ->
                Spacer(Modifier.height(2.dp))
                PaperButton(
                    action.label,
                    { vm.dispatch(action.sourceId, SidebarCommand.Create) },
                    Modifier.fillMaxWidth(),
                    kind = PaperButtonKind.QUIET,
                )
            }
        }
    }
}
