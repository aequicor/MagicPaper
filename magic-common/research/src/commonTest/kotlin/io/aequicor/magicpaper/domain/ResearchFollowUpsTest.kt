package io.aequicor.magicpaper.domain

import kotlin.test.*

class ResearchFollowUpsTest {
    private val block = "\n\n$RESEARCH_FOLLOW_UPS_MARKER\n[\"Разобрать пример\",\"Написать статью: Kotlin\"]\n-->"

    @Test fun completeSuggestionsAreSeparateFromTheAnswerAndNeverAppearWhileStreaming() {
        val reply = researchReply("Ответ по существу.$block")
        assertEquals("Ответ по существу.", reply.text)
        assertEquals(listOf("Разобрать пример", "Написать статью: Kotlin"), reply.followUps)
        for (end in 1..block.length) {
            val partial = researchReply("Ответ по существу." + block.take(end), streaming = true)
            assertTrue(partial.followUps.isEmpty())
            assertEquals("Ответ по существу.", partial.text.trimEnd())
        }
    }

    @Test fun codeExamplesAndOrdinaryNumberedListsAreNotActions() {
        for (text in listOf("Ответ.\n\n1. Откройте файл.\n2. Измените настройки.",
            "Ответ.\n\n1. Сравнить данные\n2. Проверить результаты",
            "Пример:\n```html$block\n```", "Пример:\n```\n1. Разобрать пример\n2. Написать статью: Kotlin")) {
            assertEquals(ResearchReply(text), researchReply(text))
        }
    }

    @Test fun malformedOrIncompleteMetadataPreservesTheSavedAnswerWithoutAction() {
        for (payload in listOf("not json", "[1,2]", "[\"\"]", "[\"Вопрос\",\"Вопрос\"]",
            "[\"${"а".repeat(241)}\"]", "[\"1\",\"2\",\"3\",\"4\"]")) {
            val text = "Ответ.\n\n$RESEARCH_FOLLOW_UPS_MARKER\n$payload\n-->"
            assertEquals(ResearchReply(text), researchReply(text))
        }
        val partial = "Ответ.\n\n$RESEARCH_FOLLOW_UPS_MARKER\n[\"Недописано"
        assertEquals(ResearchReply(partial), researchReply(partial))
    }

    @Test fun distinctiveOldContinuationMenusBecomeActionsWithoutChangingUserMessages() {
        val text = "Ответ.\n\n1. Разобрать вашу последнюю попытку знакомства.\n" +
            "2. Составить несколько естественных фраз.\n3. Написать статью: как начать отношения."
        val reply = researchReply(text)
        assertEquals("Ответ.", reply.text)
        assertEquals(3, reply.followUps.size)
        assertEquals(text, ChatMessage("user", ChatRole.USER, text, 1).researchReply().text)
        assertTrue(ChatMessage("user", ChatRole.USER, text, 1).researchReply().followUps.isEmpty())
        assertEquals(reply, ChatMessage("agent", ChatRole.AGENT, text, 2).researchReply())
    }
}
