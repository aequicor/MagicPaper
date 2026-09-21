package io.aequicor.magicpaper.ui.screens

import io.aequicor.magicpaper.domain.*
import kotlin.test.*

class SessionSearchTest {
    private val chats = listOf(ChatSession("chat", "Планы", 1, 4,
        messages = listOf(ChatMessage("m", ChatRole.USER, "Починить АРХИВАЦИЮ", 4)), archived = true))
    private val coding = listOf(
        SessionSearchDocument("child", "Дочерняя сессия", "agent", true, 3, "MagicPaper", messages = listOf("Архивацию проверили")),
        SessionSearchDocument("active", "Активная сессия", "agent", false, 2, "MagicPaper"))

    @Test fun searchesArchivedChatAndChildMessageTextWithoutCaseSensitivity() {
        val result = searchSessions(chats, coding, "  архивацию  ", false)
        assertEquals(listOf("chat", "child"), result.map { it.id })
        assertTrue(result.all { it.archived && it.excerpt != null })
    }
    @Test fun titleProjectAndArchiveFiltersDoNotHideMatchingChildren() {
        assertEquals(listOf("child", "active"), searchSessions(chats, coding, "magicpaper", false).map { it.id })
        assertEquals(listOf("child"), searchSessions(chats, coding, "дочерняя", false).map { it.id })
        assertEquals(listOf("chat", "child"), searchSessions(chats, coding, "", true).map { it.id })
        assertTrue(searchSessions(chats, coding, "нет совпадений", false).isEmpty())
    }
}
