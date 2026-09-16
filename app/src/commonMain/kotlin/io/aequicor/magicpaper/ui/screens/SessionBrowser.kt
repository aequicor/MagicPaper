package io.aequicor.magicpaper.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
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

@Composable
internal fun SessionBrowserControls(query: String, onQueryChange: (String) -> Unit, archivesOnly: Boolean,
    archiveCount: Int, onToggleArchive: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PaperField(query, onQueryChange, "Поиск сессий", Modifier.fillMaxWidth())
        if (query.isNotEmpty()) PaperTextAction({ onQueryChange("") }) { PaperText("Очистить поиск") }
        PaperButton(if (archivesOnly) "← Все сессии" else "Архив ($archiveCount)", onToggleArchive,
            Modifier.fillMaxWidth(), kind = PaperButtonKind.QUIET)
    }
}

@Composable
internal fun SessionBrowserResults(results: List<SessionSearchResult>, archivesOnly: Boolean, searching: Boolean,
    selectedId: String?, viewingCoding: Boolean, onSelect: (SessionSearchResult) -> Unit,
    onRestore: (SessionSearchResult) -> Unit, modifier: Modifier = Modifier, loading: Boolean = false) {
    LazyColumn(modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            PaperText(if (archivesOnly) "Архив" else "Результаты поиска", role = PaperTextRole.TITLE,
                modifier = Modifier.padding(horizontal = 8.dp))
        }
        if (loading) item { PaperText("Поиск…", modifier = Modifier.padding(8.dp)) }
        else if (results.isEmpty()) item {
            PaperText(if (searching) "Сессии не найдены" else "Архив пуст", modifier = Modifier.padding(8.dp))
        }
        items(results, key = { "${it.isCoding}:${it.id}" }) { result ->
            Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                PaperListRow(result.title, selected = result.id == selectedId && result.isCoding == viewingCoding,
                    onClick = { onSelect(result) })
                PaperText(listOfNotNull(result.project ?: "Чат", "В архиве".takeIf { result.archived }).joinToString(" · "),
                    role = PaperTextRole.LABEL, modifier = Modifier.padding(horizontal = 8.dp))
                result.excerpt?.let { PaperText(it, role = PaperTextRole.LABEL, modifier = Modifier.padding(horizontal = 8.dp)) }
                if (result.archived) PaperTextAction({ onRestore(result) }) { PaperText("Разархивировать") }
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
            SessionBrowserControls("", {}, true, 1, {})
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
            SessionBrowserControls("Неизвестная сессия", {}, false, 0, {})
            SessionBrowserResults(emptyList(), false, true, null, false, {}, {}, Modifier.weight(1f))
        }
    }
}
