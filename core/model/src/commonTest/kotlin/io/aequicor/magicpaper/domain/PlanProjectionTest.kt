package io.aequicor.magicpaper.domain

import io.aequicor.magicpaper.domain.planning.*
import kotlinx.serialization.json.*
import kotlin.test.*

class PlanProjectionTest {
    private val initial = Plan("plan", "project", "Original", revision = 17, createdAt = 123,
        dialogue = listOf(PlanningMessage("old", "user", "Kept verbatim")))

    @Test fun replayPreservesEveryAcceptedValueAndOnlyAppendsNewHistory() {
        val event = MessageEvent("event", "source", "plan", "run", MessageEventKind.RUN_COMPLETED, 456, "Done")
        val next = initial.copy(revision = 18, updatedAt = 456, goal = "Accepted",
            dialogue = initial.dialogue + PlanningMessage("new", "assistant", "Answer"), messageEvents = listOf(event))
        val delta = recordPlanState(initial, next)
        assertFalse("dialogue" in delta.fields)
        assertEquals(1, delta.appended.getValue("dialogue").size)
        assertFalse("projectId" in delta.fields)
        val records = listOf(recordPlanState(null, initial), delta).map { PlanStateRecord.decode(it.encode()) }
        fun replay() = records.fold<PlanStateRecord, Plan?>(null, ::projectPlanState)
        assertEquals(next, replay())
        assertEquals(replay(), replay())
        assertEquals(event, replay()!!.messageEvents.single())
    }

    @Test fun replacingOrClearingAnArrayDoesNotAppendToItsOldContents() {
        val replaced = initial.copy(revision = 18, dialogue = listOf(PlanningMessage("other", "user", "Replacement")))
        val cleared = replaced.copy(revision = 19, dialogue = emptyList())
        assertTrue(recordPlanState(initial, replaced).appended.isEmpty())
        assertEquals(replaced, projectPlanState(initial, recordPlanState(initial, replaced)))
        assertEquals(cleared, projectPlanState(replaced, recordPlanState(replaced, cleared)))
    }

    @Test fun missingReorderedRepeatedAndForeignRevisionsAreRejected() {
        val delta = recordPlanState(initial, initial.copy(revision = 18, goal = "new"))
        assertFailsWith<IllegalArgumentException> { projectPlanState(null, delta) }
        assertFailsWith<IllegalArgumentException> { projectPlanState(initial.copy(revision = 16), delta) }
        assertFailsWith<IllegalArgumentException> { projectPlanState(projectPlanState(initial, delta), delta) }
        assertFailsWith<IllegalArgumentException> { projectPlanState(initial, delta.copy(planId = "other")) }
        assertFailsWith<IllegalArgumentException> { recordPlanState(initial, initial.copy(revision = 18, projectId = "other")) }
        assertFailsWith<IllegalArgumentException> { recordPlanState(initial, initial.copy(revision = 17)) }
        assertFailsWith<IllegalArgumentException> { recordPlanState(initial.copy(revision = Long.MAX_VALUE), initial.copy(revision = Long.MIN_VALUE)) }
    }

    @Test fun malformedOrFutureRecordsCannotBecomeAValidDefaultPlan() {
        val delta = recordPlanState(initial, initial.copy(revision = 18))
        assertFailsWith<IllegalArgumentException> { projectPlanState(initial, delta.copy(version = 2)) }
        assertFailsWith<IllegalArgumentException> { projectPlanState(initial, delta.copy(appended = mapOf("revision" to listOf(JsonPrimitive(1))))) }
        assertFailsWith<IllegalArgumentException> { projectPlanState(initial, delta.copy(appended = mapOf("dialogue" to emptyList()))) }
        assertFailsWith<IllegalArgumentException> { projectPlanState(initial, delta.copy(fields = JsonObject(delta.fields + ("id" to JsonPrimitive("other"))))) }
        assertFailsWith<IllegalArgumentException> { projectPlanState(initial, delta.copy(fields = JsonObject(delta.fields + ("projectId" to JsonPrimitive("other"))))) }
        assertFailsWith<IllegalArgumentException> { projectPlanState(initial, delta.copy(fields = JsonObject(delta.fields + ("unknown" to JsonPrimitive(true))))) }
    }
}
