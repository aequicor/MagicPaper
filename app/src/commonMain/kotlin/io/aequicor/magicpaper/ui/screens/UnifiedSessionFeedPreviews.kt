package io.aequicor.magicpaper.ui.screens

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import io.aequicor.magicpaper.designsystem.PaperTheme
import io.aequicor.magicpaper.domain.CodingSessionStatus

internal fun sidebarPreviewGroups(): List<UnifiedSidebarGroup> {
    fun session(id: String, title: String, time: Long, children: List<UnifiedSidebarItem> = emptyList()) =
        UnifiedSidebarItem(id, title, time, true, projectName = "MagicPaper", projectId = "paper",
            codingStatus = CodingSessionStatus.IDLE, children = children)
    val parent = session("parent", "Список сессий и навигация", 90,
        List(12) { session("child-$it", "Этап ${it + 1}: проверка интерфейса", 80L - it) })
    val task = session("task", "Обновить рабочее пространство", 100, listOf(parent))
        .copy(isOrganism = true, codingStatus = CodingSessionStatus.WAITING)
    return groupUnifiedSidebarItems(listOf(
        task,
        UnifiedSidebarItem("chat", "Обсуждение дизайна", 50, false),
        session("older", "Восстановление сессий", 40),
    ) + List(12) { session("other-$it", "Предыдущая задача ${it + 1}", 30L - it) })
}

@Preview(name = "Chronology and tree", group = "Session list", widthDp = 320, heightDp = 720)
@Preview(name = "Narrow large text", group = "Session list", widthDp = 240, heightDp = 720, fontScale = 2f)
@Composable
internal fun UnifiedSessionFeedPreview() {
    var selected by remember { mutableStateOf("parent") }
    var coding by remember { mutableStateOf(true) }
    var collapsedGroups by remember { mutableStateOf(emptySet<String>()) }
    PaperTheme {
        UnifiedSessionFeed(
            groups = remember { sidebarPreviewGroups() }, selectedId = selected, viewingCoding = coding,
            collapsedGroups = collapsedGroups,
            onToggleGroup = { collapsedGroups = if (it.key in collapsedGroups) collapsedGroups - it.key else collapsedGroups + it.key },
            onSelect = { id, isCoding -> selected = id; coding = isCoding },
            onArchive = {}, onDelete = {}, onAddSession = {}, modifier = Modifier.fillMaxSize(),
        )
    }
}
