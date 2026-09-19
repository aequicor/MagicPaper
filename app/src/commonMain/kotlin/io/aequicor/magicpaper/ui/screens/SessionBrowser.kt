package io.aequicor.magicpaper.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.tooling.preview.Preview
import io.aequicor.magicpaper.designsystem.*
import io.aequicor.magicpaper.domain.*

internal data class SessionSearchResult(
    val id: String,
    val title: String,
    val isCoding: Boolean,
    val archived: Boolean,
    val updatedAt: Long,
    val project: String? = null,
    val excerpt: String? = null,
)

/** Search uses saved conversations, including children hidden by the normal tree. */
internal fun searchSessions(
    chats: List<ChatSession>,
    coding: List<Pair<CodingSession, List<CodingMessage>>>,
    projects: List<CodingProject>,
    query: String,
    archivesOnly: Boolean,
): List<SessionSearchResult> {
    val needle = query.trim()
    fun matches(text: String) = text.contains(needle, ignoreCase = true)
    fun excerpt(text: String): String {
        val start = (text.indexOf(needle, ignoreCase = true) - 30).coerceAtLeast(0)
        return (if (start > 0) "…" else "") + text.substring(start).take(160).replace('\n', ' ')
    }
    val projectNames = projects.associate { it.id to it.name }
    return buildList {
        for (chat in chats) {
            if (archivesOnly && !chat.archived) continue
            val message = if (needle.isEmpty()) null else chat.messages.firstOrNull { matches(it.text) }
            if (!matches(chat.title) && message == null) continue
            add(SessionSearchResult(chat.id, chat.title, false, chat.archived, chat.updatedAt,
                excerpt = message?.text?.let(::excerpt)))
        }
        for ((session, messages) in coding) {
            if (archivesOnly && !session.archived) continue
            val project = projectNames[session.projectId]
            val message = if (needle.isEmpty()) null else messages.firstOrNull { !it.systemContext && !it.systemNotice && matches(it.text) }
            if (!matches(session.sidebarTitle()) && !matches(session.name) && project?.let(::matches) != true && message == null) continue
            add(SessionSearchResult(session.id, session.sidebarTitle(), true, session.archived,
                messages.lastOrNull()?.createdAt ?: session.createdAt, project, message?.text?.let(::excerpt)))
        }
    }.sortedByDescending { it.updatedAt }
}

private val chatStatusFilters = setOf(SidebarStatusFilter.ALL, SidebarStatusFilter.UNREAD)

@Composable
internal fun SessionBrowserControls(
    query: String,
    onQueryChange: (String) -> Unit,
    searchExpanded: Boolean,
    onToggleSearch: () -> Unit,
    archivesOnly: Boolean,
    archiveCount: Int,
    onToggleArchive: () -> Unit,
    statusFilter: SidebarStatusFilter = SidebarStatusFilter.ALL,
    onStatusFilter: (SidebarStatusFilter) -> Unit = {},
    sourceFilterKey: String = "all",
    onSourceFilter: (String) -> Unit = {},
    projects: List<Pair<String, String>> = emptyList(),
    codingSupported: Boolean = true,
) {
    var filterOpen by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            PaperText("Сессии", role = PaperTextRole.TITLE, modifier = Modifier.weight(1f))
            Box {
                val filtersActive = statusFilter != SidebarStatusFilter.ALL || sourceFilterKey != "all"
                PaperTooltip("Фильтры сессий") {
                    PaperToolbarButton(
                        icon = PaperToolbarIcon.Filter,
                        label = "Фильтры сессий",
                        size = 28.dp,
                        selected = filtersActive,
                        onClick = { filterOpen = true },
                    )
                }
                PaperMenuHost(filterOpen, { filterOpen = false }, Modifier.widthIn(min = 220.dp)) {
                    PaperText("Статус", role = PaperTextRole.LABEL,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
                    // Eight of these describe agent progress; without an agent they can never match.
                    SidebarStatusFilter.entries.filter { codingSupported || it in chatStatusFilters }.forEach { filter ->
                        PaperRichMenuAction(
                            text = { PaperText(if (statusFilter == filter) "✓ ${filter.label}" else filter.label) },
                            onClick = { onStatusFilter(filter); filterOpen = false },
                        )
                    }
                    PaperDivider()
                    PaperText("Источник", role = PaperTextRole.LABEL,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
                    (listOf("all" to "Все сессии", "chats" to "Чаты") +
                        projects.map { (id, name) -> "project:$id" to name }).forEach { (key, label) ->
                        PaperRichMenuAction(
                            text = { PaperText(if (sourceFilterKey == key) "✓ $label" else label) },
                            onClick = { onSourceFilter(key); filterOpen = false },
                        )
                    }
                }
            }
            Spacer(Modifier.width(4.dp))
            PaperTooltip(if (searchExpanded) "Закрыть поиск" else "Поиск сессий") {
                PaperToolbarButton(
                    icon = PaperToolbarIcon.Search,
                    label = if (searchExpanded) "Закрыть поиск" else "Поиск сессий",
                    size = 28.dp,
                    selected = searchExpanded,
                    onClick = onToggleSearch,
                )
            }
            Spacer(Modifier.width(4.dp))
            val archiveLabel = if (archivesOnly) "Вернуться ко всем сессиям" else "Архив: $archiveCount"
            PaperTooltip(archiveLabel) {
                PaperToolbarButton(
                    icon = PaperToolbarIcon.Archive,
                    label = archiveLabel,
                    size = 28.dp,
                    selected = archivesOnly,
                    onClick = onToggleArchive,
                )
            }
        }
        if (searchExpanded) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                PaperField(query, onQueryChange, "Поиск сессий", Modifier.weight(1f))
                if (query.isNotEmpty()) {
                    Spacer(Modifier.width(4.dp))
                    PaperTooltip("Очистить поиск") {
                        PaperIconButton("Очистить поиск", { onQueryChange("") }) {
                            PaperText("×", role = PaperTextRole.CHROME)
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun SessionBrowserResults(results: List<SessionSearchResult>, archivesOnly: Boolean, searching: Boolean,
    selectedId: String?, viewingCoding: Boolean, onSelect: (SessionSearchResult) -> Unit,
    onRestore: (SessionSearchResult) -> Unit, modifier: Modifier = Modifier, loading: Boolean = false) {
    PaperLazyColumn(modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item(key = "session-browser-heading") {
            PaperText(if (archivesOnly) "Архив" else "Результаты поиска", role = PaperTextRole.TITLE,
                modifier = Modifier.padding(horizontal = 8.dp))
        }
        if (loading) item(key = "session-browser-loading") { PaperText("Поиск…", modifier = Modifier.padding(8.dp)) }
        else if (results.isEmpty()) item(key = "session-browser-empty") {
            PaperText(if (searching) "Сессии не найдены" else "Архив пуст", modifier = Modifier.padding(8.dp))
        }
        items(results, key = { "${it.isCoding}:${it.id}" }) { result ->
            val hoverInteraction = remember(result.id, result.isCoding) { MutableInteractionSource() }
            val hovered by hoverInteraction.collectIsHoveredAsState()
            Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp).hoverable(hoverInteraction),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                PaperListRow(result.title, selected = result.id == selectedId && result.isCoding == viewingCoding,
                    onClick = { onSelect(result) }, trailing = {
                        PaperHoverActions(visible = hovered && result.archived) {
                            PaperTooltip("Разархивировать") {
                                PaperToolbarButton(
                                    icon = PaperToolbarIcon.Unarchive,
                                    label = "Разархивировать",
                                    size = 24.dp,
                                    onClick = { onRestore(result) },
                                )
                            }
                        }
                    })
                PaperText(listOfNotNull(result.project ?: "Чат", "В архиве".takeIf { result.archived }).joinToString(" · "),
                    role = PaperTextRole.LABEL, modifier = Modifier.padding(horizontal = 8.dp))
                result.excerpt?.let { PaperText(it, role = PaperTextRole.LABEL, modifier = Modifier.padding(horizontal = 8.dp)) }
                PaperDivider()
            }
        }
    }
}

@Preview(name = "Archive", group = "Session browser", widthDp = 320, heightDp = 620)
@Preview(name = "Narrow archive", group = "Session browser", widthDp = 240, heightDp = 620)
@Composable
internal fun SessionArchivePreview() = PaperTheme {
    PaperSurface(Modifier.fillMaxSize()) {
        Column {
            SessionBrowserControls("", {}, false, {}, true, 1, {})
            SessionBrowserResults(listOf(SessionSearchResult("archived", "Восстановление дочерних сессий после перезапуска", true,
                true, 0, "MagicPaper")), true, false, null, false, {}, {}, Modifier.weight(1f))
        }
    }
}

@Preview(name = "Empty search", group = "Session browser", widthDp = 320, heightDp = 420)
@Composable
internal fun SessionSearchEmptyPreview() = PaperTheme {
    PaperSurface(Modifier.fillMaxSize()) {
        Column {
            SessionBrowserControls("Неизвестная сессия", {}, true, {}, false, 0, {})
            SessionBrowserResults(emptyList(), false, true, null, false, {}, {}, Modifier.weight(1f))
        }
    }
}
