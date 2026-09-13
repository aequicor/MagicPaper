package io.aequicor.magicpaper.ui.components

import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.domain.tools.ToolCategory
import kotlin.test.*

class CodingChatRowsTest {
    @Test fun repeatedScopedCallsHaveOneCardWithTheCompletedResult() {
        val started = CodingStep(CodingStepKind.EXEC, "Check", tool = "command", callId = "p/s/request/2",
            running = true, id = "parent-step", toolCategory = ToolCategory.EXEC)
        val finished = started.copy(id = "child-step", running = false, result = "Done")
        fun message(id: String, step: CodingStep) = CodingMessage(id, CodingRole.AGENT, "", createdAt = 0, steps = listOf(step))
        val liveKey = codingHistoryItems(codingChatRows(listOf(message("parent", started)))).single().key
        for (steps in listOf(listOf(started, finished), listOf(finished, started), listOf(finished, finished))) {
            val messages = steps.mapIndexed { index, step -> message("message-$index", step) }
            val item = codingHistoryItems(codingChatRows(messages)).single()
            assertEquals(liveKey, item.key)
            assertEquals(finished, item.step)
            assertEquals(2, messages.sumOf { it.steps.size }) // Stored history is untouched.
            val withinOneMessage = codingHistoryItems(codingChatRows(listOf(messages.first().copy(steps = steps)))).single()
            assertEquals(item.key, withinOneMessage.key)
            assertEquals(finished, withinOneMessage.step)
        }
    }

    @Test fun differentToolsWithTheSameCallIdSurviveDraftPersistence() {
        val read = CodingStep(CodingStepKind.TOOL, "Read", tool = "read", callId = "p/s/request/2",
            running = true, id = "read", toolCategory = ToolCategory.READ)
        val command = read.copy(kind = CodingStepKind.EXEC, tool = "command", id = "command", toolCategory = ToolCategory.EXEC)
        val draft = CodingDraft(active = true, timelineId = "parent", steps = listOf(read, command))
        val before = codingHistoryItems(listOf(codingDraftRow(draft, emptyList(), "fallback", true, true)!!))
        assertEquals(2, before.map { it.key }.distinct().size)
        val saved = CodingMessage("child", CodingRole.AGENT, "", createdAt = 0, steps = listOf(read.copy(running = false)))
        val pending = codingDraftRow(draft, listOf(saved), "fallback", true, true)!!
        assertEquals(listOf(command), pending.message.steps)
        val after = codingHistoryItems(codingChatRows(listOf(saved)) + pending)
        assertEquals(before.map { it.key }, after.map { it.key })
    }

    @Test fun savedCoordinatorIsRemovedFromAMixedDraftWithoutHidingAnIdenticalParallelAnswer() {
        fun answer(source: String) = CodingStep(CodingStepKind.ANSWER, "Результат передан на проверку", id = "answer",
            sourceTimelineId = source)
        val first = answer("first-coordinator")
        val second = answer("second-coordinator")
        val request = answer("request-response")
        val draft = CodingDraft(active = true, timelineId = "request-response", steps = listOf(request, first, second))
        val before = codingHistoryItems(listOf(codingDraftRow(draft, emptyList(), "fallback", true, true)!!))
        val saved = CodingMessage("first-coordinator", CodingRole.AGENT, "Оркестратор: ${first.title}", createdAt = 1,
            steps = listOf(first.copy(title = "Оркестратор: ${first.title}")))
        val remaining = codingDraftRow(draft, listOf(saved), "fallback", true, true)!!
        assertEquals(listOf(request, second), remaining.message.steps)
        val after = codingHistoryItems(codingChatRows(listOf(saved)) + remaining)
        assertEquals(before.map { it.key }.toSet(), after.map { it.key }.toSet())
        assertEquals(3, after.map { it.key }.distinct().size)
        val savedRequest = saved.copy(id = "request-response", steps = listOf(request))
        assertEquals(listOf(second), codingDraftRow(draft, listOf(saved, savedRequest), "fallback", true, true)!!.message.steps)
        assertNull(codingDraftRow(draft, listOf(saved, savedRequest, saved.copy(id = "second-coordinator", steps = listOf(second))), "fallback", true, true))
        // A single remaining coordinator changes the aggregate draft's own ID, not its step keys.
        val alone = codingDraftRow(CodingDraft(active = true, timelineId = "second-coordinator", steps = listOf(second)),
            listOf(saved), "fallback", true, true)!!
        assertEquals(after.last().key, codingHistoryItems(listOf(alone)).single().key)
    }

    @Test fun savedFailureRetiresTheWholeSourceIncludingAnUnpersistedPartialAnswer() {
        val draft = CodingDraft(active = true, steps = listOf(CodingStep(CodingStepKind.ANSWER, "Partial answer",
            id = "answer", sourceTimelineId = "coordinator")))
        val failure = CodingMessage("review-error", CodingRole.AGENT, "Connection lost", createdAt = 1,
            failed = true, timelineId = "coordinator")
        assertNull(codingDraftRow(draft, listOf(failure), "fallback", true, true))
    }

    @Test fun nestedToolCardsKeepKeysAndDoNotDuplicateWhileTheParentIsStillRunning() {
        fun tool(id: String) = CodingStep(CodingStepKind.TOOL, id, tool = "context.get", callId = "p/s/request/$id",
            running = true, id = id, toolCategory = ToolCategory.READ)
        val parent = tool("parent")
        val child = tool("child")
        val draft = CodingDraft(steps = listOf(parent, child), active = true, timelineId = "parent-response")
        val live = codingHistoryItems(listOf(codingDraftRow(draft, emptyList(), "fallback", true, true)!!))
        val savedChild = CodingMessage("child-response", CodingRole.AGENT, "", createdAt = 0, steps = listOf(child.copy(running = false)))
        val remaining = codingDraftRow(draft, listOf(savedChild), "fallback", true, true)!!
        val combined = codingHistoryItems(codingChatRows(listOf(savedChild)) + remaining)
        assertEquals(live.map { it.key }.toSet(), combined.map { it.key }.toSet())
        assertEquals(2, combined.size)
        assertEquals(parent, remaining.message.steps.single())
        val savedParent = savedChild.copy(id = "parent-response", steps = listOf(parent.copy(running = false)))
        assertNull(codingDraftRow(draft, listOf(savedParent, savedChild), "fallback", true, true))
        val next = tool("next-coordinator")
        assertEquals(next, codingDraftRow(draft.copy(steps = draft.steps + next), listOf(savedParent, savedChild),
            "fallback", true, true)!!.message.steps.single())
    }

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
