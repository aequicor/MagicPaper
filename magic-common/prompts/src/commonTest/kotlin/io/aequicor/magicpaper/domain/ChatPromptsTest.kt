package io.aequicor.magicpaper.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChatPromptsTest {
    private val profile = LlmProfile("profile", "Connection")
    private val attachment = Attachment("current", "note.txt", "text/plain", 3, "YWJj", AttachmentKind.TEXT)

    @Test fun defaultSystemTextRemainsStableAndRequiresNoNativeEngine() {
        val expected = "Ты — MagicPaper, волшебный ассистент в мире мягкой магии. Отвечай кратко, ясно\n" +
            "и по делу, лёгким дружелюбным тоном, без пафоса. Если дан контекст из документации\n" +
            "или результатов поиска — опирайся на него и упоминай источники. Если информации\n" +
            "недостаточно — честно скажи об этом."
        assertEquals(listOf(LlmMessage(LlmChatRole.SYSTEM, expected), LlmMessage(LlmChatRole.USER, "Question")),
            chatPromptMessages(profile, emptyList(), "Question"))
    }

    @Test fun overrideSkillsContextAndRecentHistoryKeepTheirOrderWithoutRepeatingOldFiles() {
        val connection = profile.copy(advanced = AdvancedLlmOptions(systemPromptOverride = "Custom system", contextMessages = 2))
        val history = listOf(
            ChatMessage("old", ChatRole.USER, "Omitted", 0),
            ChatMessage("user", ChatRole.USER, "Previous question", 1, attachments = listOf(attachment.copy(id = "old"))),
            ChatMessage("answer", ChatRole.AGENT, "Previous answer", 2),
        )
        val skills = listOf(Skill("first", "First", "Purpose", "  First instruction  "),
            Skill("second", "Second", "Other purpose", "Second instruction\n"))
        val expectedSkills = "У тебя есть навыки, подходящие к этой задаче. Следуй их инструкциям:\n\n" +
            "1. First — Purpose\nFirst instruction\n\n2. Second — Other purpose\nSecond instruction\n"
        assertEquals(listOf(
            LlmMessage(LlmChatRole.SYSTEM, "Custom system"),
            LlmMessage(LlmChatRole.SYSTEM, expectedSkills),
            LlmMessage(LlmChatRole.SYSTEM, "Verified sources"),
            LlmMessage(LlmChatRole.USER, "Previous question"),
            LlmMessage(LlmChatRole.ASSISTANT, "Previous answer"),
            LlmMessage(LlmChatRole.USER, "Current question", listOf(attachment)),
        ), chatPromptMessages(connection, history, "Current question", listOf(attachment), skills, "Verified sources", "Ignored default"))
        assertEquals(1, history[1].attachments.size, "Building a request does not mutate saved history")
    }

    @Test fun whitespaceOverrideFallsBackAndExplicitEmptyContextRemainsPresent() {
        val connection = profile.copy(advanced = AdvancedLlmOptions(systemPromptOverride = " \n", contextMessages = 0))
        val messages = chatPromptMessages(connection, listOf(ChatMessage("old", ChatRole.USER, "Old", 0)),
            "Current", context = "", system = "Mode policy")
        assertEquals(listOf(LlmMessage(LlmChatRole.SYSTEM, "Mode policy"), LlmMessage(LlmChatRole.SYSTEM, ""),
            LlmMessage(LlmChatRole.USER, "Current")), messages)
        assertTrue(messages.all { it.attachments.isEmpty() })
    }
}
