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
    children.isNotEmpty() ||
        (id == selectedId && isCoding == viewingCoding) ||
        codingStatus == CodingSessionStatus.WORKING ||
        codingStatus == CodingSessionStatus.WAITING ||
        codingStatus == CodingSessionStatus.CONFIRMATION ||
        codingStatus == CodingSessionStatus.UNREAD || unread

internal fun sidebarFeedRows(
    groups: List<UnifiedSidebarGroup>,
    collapsedGroups: Set<String>,
    sticky: (UnifiedSidebarItem) -> Boolean = { it.children.isNotEmpty() },
    expanded: (UnifiedSidebarItem) -> Boolean,
): List<SidebarFeedRow> = buildList {
    fun session(item: UnifiedSidebarItem, group: UnifiedSidebarGroup, ancestors: List<String>) {
        val key = "${if (item.isCoding) "coding" else "chat"}:${item.id}"
        val header = sticky(item)
        add(SidebarFeedRow(PaperStickyTreeEntry(key, ancestors, header), group, item))
        if (expanded(item)) item.children.forEach { child ->
            session(child, group, if (header) ancestors + key else ancestors)
        }
    }
    groups.forEach { group ->
        val headerKey = "header:${group.key}"
        if (group.showsProjectHeader) add(SidebarFeedRow(PaperStickyTreeEntry(headerKey, header = true), group))
        if (!group.showsProjectHeader || group.key !in collapsedGroups) {
            group.items.forEach { session(it, group, if (group.showsProjectHeader) listOf(headerKey) else emptyList()) }
        }
    }
}

@Composable
internal fun UnifiedSessionFeed(
    groups: List<UnifiedSidebarGroup>,
    selectedId: String?,
    viewingCoding: Boolean,
    collapsedGroups: Set<String>,
    expanded: (UnifiedSidebarItem) -> Boolean,
    onToggleGroup: (UnifiedSidebarGroup) -> Unit,
    onToggleSession: (UnifiedSidebarItem) -> Unit,
    onSelect: (String, Boolean) -> Unit,
    onArchive: (UnifiedSidebarItem) -> Unit,
    onDelete: (UnifiedSidebarItem) -> Unit,
    onAddSession: (String) -> Unit,
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
) {
    val rows = sidebarFeedRows(
        groups, collapsedGroups,
        sticky = { it.isStickySession(selectedId, viewingCoding) },
        expanded = expanded,
    )
    val byKey = rows.associateBy { it.entry.key }
    // Hoist menus above lazy/pinned copies: scrolling cannot reset an open menu.
    var menuKey by rememberSaveable { mutableStateOf<String?>(null) }
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
                        onClick = { row.group.projectId?.let(onAddSession) }) { PaperText("+", role = PaperTextRole.CHROME) }
                },
            )
        } else {
            PaperSessionRow(
                title = item.displayName,
                selected = item.id == selectedId && item.isCoding == viewingCoding,
                depth = row.entry.ancestors.size,
                subtitle = item.sidebarSubtitle(showProject = false),
                expanded = expanded(item).takeIf { item.children.isNotEmpty() },
                onToggle = { retainPosition(); onToggleSession(item) },
                onClick = { onSelect(item.id, item.isCoding) },
                keepActionsVisible = menuKey == key,
                indicator = {
                    if (item.isCoding) ActivityDot(item.codingStatus ?: io.aequicor.magicpaper.domain.CodingSessionStatus.IDLE, size = 10)
                    else PaperText("✦", role = PaperTextRole.CHROME)
                },
                actions = {
                    if (item.unread) PaperActivityIndicator(PaperActivityTone.UNREAD, "Непрочитанное сообщение")
                    item.immunity?.let { immunity ->
                        ImmunityDiamondButton(immunity.status, immunity.selected, onClick = { onSelect(immunity.sessionId, true) })
                    }
                    PaperTooltip("В архив") {
                        PaperToolbarButton(
                            icon = PaperToolbarIcon.Archive,
                            label = "Архивировать ${if (item.isCoding) "сессию" else "чат"}",
                            size = 24.dp,
                            onClick = { onArchive(item) },
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
}
