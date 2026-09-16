package io.aequicor.magicpaper.ui.screens

import io.aequicor.magicpaper.domain.*
import kotlin.test.*

class SessionSearchTest {
    private val chats = listOf(ChatSession("chat", "Планы", 1, 4,
        messages = listOf(ChatMessage("m", ChatRole.USER, "Починить АРХИВАЦИЮ", 4)), archived = true))
    private val coding = listOf(CodingSession("child", "p", "Дочерняя сессия", 1, parentSessionId = "root", archived = true) to
        listOf(CodingMessage("m", CodingRole.AGENT, "Архивацию проверили", createdAt = 3)),
        CodingSession("active", "p", "Активная сессия", 2) to emptyList())
    private val projects = listOf(CodingProject("p", "MagicPaper", "/fixture", 1))

    @Test fun searchesArchivedChatAndChildMessageTextWithoutCaseSensitivity() {
        val result = searchSessions(chats, coding, projects, "  архивацию  ", false)
        assertEquals(listOf("chat", "child"), result.map { it.id })
        assertTrue(result.all { it.archived && it.excerpt != null })
    }
    @Test fun titleProjectAndArchiveFiltersDoNotHideMatchingChildren() {
        assertEquals(listOf("child", "active"), searchSessions(chats, coding, projects, "magicpaper", false).map { it.id })
        assertEquals(listOf("child"), searchSessions(chats, coding, projects, "дочерняя", false).map { it.id })
        assertEquals(listOf("chat", "child"), searchSessions(chats, coding, projects, "", true).map { it.id })
        assertTrue(searchSessions(chats, coding, projects, "нет совпадений", false).isEmpty())
    }
}
