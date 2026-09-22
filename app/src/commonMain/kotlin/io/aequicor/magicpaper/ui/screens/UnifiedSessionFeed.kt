package io.aequicor.magicpaper.ui.screens

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.aequicor.magicpaper.designsystem.*
import io.aequicor.magicpaper.domain.CodingSessionStatus

internal data class SidebarFeedRow(
    val entry: PaperStickyTreeEntry,
    val group: UnifiedSidebarGroup,
    val session: UnifiedSidebarItem? = null,
)

internal fun UnifiedSidebarItem.isStickySession(selectedId: String?, viewingCoding: Boolean): Boolean =
    (id == selectedId && isCoding == viewingCoding) ||
        codingStatus == CodingSessionStatus.WORKING ||
        codingStatus == CodingSessionStatus.WAITING ||
        codingStatus == CodingSessionStatus.CONFIRMATION ||
        codingStatus == CodingSessionStatus.UNREAD || (!isCoding && unread)

internal fun sidebarFeedRows(
    groups: List<UnifiedSidebarGroup>,
    collapsedGroups: Set<String>,
    sticky: (UnifiedSidebarItem) -> Boolean = { false },
    retained: (UnifiedSidebarItem) -> Boolean = { false },
): List<SidebarFeedRow> = buildList {
    fun session(item: UnifiedSidebarItem, group: UnifiedSidebarGroup, stickyAncestors: MutableList<String>) {
        val key = "${if (item.isCoding) "coding" else "chat"}:${item.id}"
        val header = sticky(item)
        add(SidebarFeedRow(PaperStickyTreeEntry(key, stickyAncestors.toList(), header,
            retainAfterBranch = header && retained(item)), group, item))
        if (header) stickyAncestors += key
        item.children.forEach { child ->
            session(child, group, stickyAncestors)
        }
    }
    groups.forEach { group ->
        val headerKey = "header:${group.key}"
        if (group.showsProjectHeader) add(SidebarFeedRow(PaperStickyTreeEntry(headerKey, header = true), group))
        if (!group.showsProjectHeader || group.key !in collapsedGroups) {
            val stickyAncestors = mutableListOf<String>()
            if (group.showsProjectHeader) stickyAncestors += headerKey
            group.items.forEach { session(it, group, stickyAncestors) }
        }
    }
}

@Composable
internal fun UnifiedSessionFeed(
    groups: List<UnifiedSidebarGroup>,
    selectedId: String?,
    viewingCoding: Boolean,
    collapsedGroups: Set<String>,
    onToggleGroup: (UnifiedSidebarGroup) -> Unit,
    onSelect: (String, String) -> Unit,
    onArchive: (UnifiedSidebarItem) -> Unit,
    onDelete: (UnifiedSidebarItem) -> Unit,
    onAddSession: (String, String) -> Unit,
    onStop: (UnifiedSidebarItem) -> Unit = {},
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
) {
    val rows = sidebarFeedRows(
        groups, collapsedGroups,
        sticky = { it.isStickySession(selectedId, viewingCoding) },
        retained = { it.id == selectedId && it.isCoding == viewingCoding },
    )
    val byKey = rows.associateBy { it.entry.key }
    fun retainViewport() {
        state.requestScrollToItem(state.firstVisibleItemIndex, state.firstVisibleItemScrollOffset)
    }
    // Hoist menus above lazy/pinned copies: scrolling cannot reset an open menu.
    var menuKey by rememberSaveable { mutableStateOf<String?>(null) }
    // A working session must warn before archiving stops it; an idle session archives right away.
    var confirmArchive by remember { mutableStateOf<UnifiedSidebarItem?>(null) }
    PaperStickyTree(rows.map { it.entry }, modifier, state) { key, retainPosition ->
        val row = byKey.getValue(key)
        val item = row.session
        if (item == null) {
            PaperSessionRow(
                title = row.group.projectName.orEmpty(),
                onClick = { retainPosition(); onToggleGroup(row.group) },
                expanded = row.group.key !in collapsedGroups,
                onToggle = { retainPosition(); onToggleGroup(row.group) },
                indicator = { PaperText("▱", role = PaperTextRole.CHROME) },
                actions = {
                    PaperIconButton(label = "Новая сессия: ${row.group.projectName.orEmpty()}",
                        onClick = { row.group.projectId?.let { onAddSession(row.group.items.first().sourceId, it) } }) { PaperNoteAddIcon() }
                },
            )
        } else {
            PaperSessionRow(
                title = item.displayName,
                selected = item.id == selectedId && item.isCoding == viewingCoding,
                depth = if (row.group.showsProjectHeader) 1 else 0,
                subtitle = item.sidebarSubtitle(showProject = false),
                onClick = { retainViewport(); onSelect(item.id, item.sourceId) },
                keepActionsVisible = menuKey == key,
                indicator = {
                    if (item.isCoding) ActivityDot(item.codingStatus ?: io.aequicor.magicpaper.domain.CodingSessionStatus.IDLE, size = 10)
                    else PaperText("✦", role = PaperTextRole.CHROME)
                },
                actions = {
                    if (item.unread && !item.isCoding) {
                        PaperActivityIndicator(PaperActivityTone.UNREAD, "Непрочитанное сообщение")
                    }
                    item.immunity?.let { immunity ->
                        ImmunityDiamondButton(immunity.status, immunity.selected, onClick = {
                            retainViewport()
                            onSelect(immunity.sessionId, item.sourceId)
                        })
                    }
                    if (item.isCoding && item.codingStatus == CodingSessionStatus.WORKING) {
                        PaperTooltip("Остановить") {
                            PaperToolbarButton(
                                icon = PaperToolbarIcon.Stop,
                                label = "Остановить сессию",
                                size = 24.dp,
                                onClick = { onStop(item) },
                            )
                        }
                    }
                    PaperTooltip("В архив") {
                        PaperToolbarButton(
                            icon = PaperToolbarIcon.Archive,
                            label = "Архивировать ${if (item.isCoding) "сессию" else "чат"}",
                            size = 24.dp,
                            onClick = {
                                if (item.isCoding && item.codingStatus == CodingSessionStatus.WORKING) confirmArchive = item
                                else onArchive(item)
                            },
                        )
                    }
                    PaperRowMenu(
                        open = menuKey == key,
                        onOpenChange = { open -> menuKey = key.takeIf { open } },
                        entries = listOf(
                            (if (item.isCoding) "Удалить сессию" else "Удалить чат") to { onDelete(item) },
                        ),
                    )
                },
            )
        }
    }
    confirmArchive?.let { item ->
        PaperDialog(
            title = "Архивировать сессию?",
            onDismissRequest = { confirmArchive = null },
            confirmLabel = "Остановить и архивировать",
            onConfirm = { onArchive(item); confirmArchive = null },
            dismissLabel = "Отмена",
        ) {
            PaperText("Сессия «${item.displayName}» ещё работает. Она будет остановлена и заархивирована.")
        }
    }
}
