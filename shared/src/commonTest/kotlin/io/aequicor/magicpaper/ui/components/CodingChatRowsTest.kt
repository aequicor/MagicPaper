package io.aequicor.magicpaper.ui.components

import io.aequicor.magicpaper.domain.*
import kotlin.test.*

class CodingChatRowsTest {
    @Test fun summariesStayOutOfChatEvenWhenSystemStepsAreVisible() {
        val message = CodingMessage("m", CodingRole.AGENT, "", createdAt = 0, steps = listOf(
            CodingStep(CodingStepKind.SUMMARY, "Running final verification")))
        assertTrue(codingChatRows(listOf(message), hideSystemSteps = false).isEmpty())
        val withThinking = message.copy(steps = message.steps + CodingStep(CodingStepKind.THINKING, "Detailed explanation"))
        assertEquals(listOf(CodingStepKind.THINKING), codingChatRows(listOf(withThinking)).single().message.steps.map { it.kind })
    }
    private val reply = CodingMessage("reply", CodingRole.AGENT, "Explanation", createdAt = 1,
        planning = PlanningChatBlock("plan"))
    private val card = CodingMessage("card", CodingRole.AGENT, "Ready", createdAt = 2,
        planning = PlanningChatBlock("plan", graph = true))

    private val system = CodingMessage("system", CodingRole.AGENT, "", createdAt = 3,
        steps = listOf(CodingStep(CodingStepKind.INFO, "SKILLS: подключённых пакетов нет")))

    @Test fun savedSystemOnlyTurnsLeaveNoRowsAndCanBeShownAgain() {
        val history = listOf(reply) + (1..30).map { system.copy(id = "system-$it") }
        assertEquals(listOf(reply), codingChatRows(history, hideSystemSteps = true).map { it.message })
        assertEquals(31, codingChatRows(history, hideSystemSteps = false).size)
        assertEquals(31, history.size) // Presentation never removes the audit trail from storage.
        assertEquals(listOf(reply), codingChatRows(history, hideSystemSteps = true).map { it.message })
    }

    @Test fun emptyStepsAndLegacyWhitespaceDoNotCreateRows() {
        val empty = CodingMessage("empty", CodingRole.AGENT, " ", createdAt = 1,
            activity = listOf(" ", ""), steps = listOf(CodingStep(CodingStepKind.ANSWER, "  "), CodingStep(CodingStepKind.THINKING, ""),
                CodingStep(CodingStepKind.INFO, "")))
        for (hidden in listOf(false, true)) assertTrue(codingChatRows(listOf(empty), hidden).isEmpty())
        assertTrue(codingChatRows(listOf(empty.copy(steps = emptyList()))).isEmpty())
    }

    @Test fun filteringKeepsTextErrorsAttachmentsQuestionsAndDeliveryStatus() {
        val error = CodingStep(CodingStepKind.ERROR, "Не удалось запустить агента", ok = false)
        val retained = listOf(
            system.copy(id = "text", text = "Ответ агента"),
            system.copy(id = "error", steps = system.steps + error),
            system.copy(id = "failed", failed = true),
            system.copy(id = "attachment", role = CodingRole.USER,
                attachments = listOf(AttachmentMeta("a.txt", "text/plain", 1, AttachmentKind.TEXT))),
            system.copy(id = "question", planning = PlanningChatBlock("plan", questions = listOf(PlanningQuestion("q", "Формат?")))),
            system.copy(id = "delivery", pendingDelivery = true),
            system.copy(id = "input", inputStatus = OrchestrationInputStatus.QUEUED),
            card.copy(text = ""),
        )
        val rows = codingChatRows(retained)
        assertEquals(retained.map { it.id }, rows.map { it.message.id })
        assertEquals("Ответ агента", rows.first().message.text)
        assertEquals(listOf(error), rows[1].message.steps)
        assertTrue(rows[2].message.text.isNotBlank())
    }

    @Test fun legacyActivityAndToolResultsRemainVisibleButHiddenTimelineIsNotRepeatedAsActivity() {
        val duplicate = system.copy(activity = listOf("◷ SKILLS: подключённых пакетов нет"))
        assertTrue(codingChatRows(listOf(duplicate)).isEmpty())
        val legacy = system.copy(steps = emptyList(), activity = listOf("Команда выполнена"))
        val tool = system.copy(id = "tool", steps = listOf(CodingStep(CodingStepKind.TOOL, "read", result = "")))
        assertEquals(2, codingChatRows(listOf(legacy, tool)).size)
    }

    @Test fun liveContentHidesSystemStepsButRetainsStandaloneFailureAndThinking() {
        assertTrue(CodingDraft(steps = system.steps).visibleChatContent(true).steps.isEmpty())
        assertEquals(system.steps, CodingDraft(steps = system.steps).visibleChatContent(false).steps)
        val error = CodingDraft(failedMessage = "Не удалось запустить агента").visibleChatContent(true)
        assertEquals(CodingStepKind.ERROR, error.steps.single().kind)
        val thinking = CodingDraft(thinking = "Проверяю результат").visibleChatContent(true)
        assertEquals(CodingStepKind.THINKING, thinking.steps.single().kind)
    }

    @Test fun existingPlanAndExplanationShareOneRowWithStableKey() {
        val before = codingChatRows(listOf(reply)).single()
        val after = codingChatRows(listOf(reply, card)).single()
        assertEquals(before.message.id, after.message.id)
        assertEquals(reply, after.message)
        assertEquals(card, after.planCard)
    }

    @Test fun unrelatedMessagesAndStandaloneCardsStaySeparate() {
        val user = CodingMessage("user", CodingRole.USER, "Next", createdAt = 2)
        assertEquals(3, codingChatRows(listOf(reply, user, card)).size)
        assertEquals(2, codingChatRows(listOf(reply, card.copy(planning = PlanningChatBlock("other", graph = true)))).size)
        assertNull(codingChatRows(listOf(card)).single().planCard)
    }
}
