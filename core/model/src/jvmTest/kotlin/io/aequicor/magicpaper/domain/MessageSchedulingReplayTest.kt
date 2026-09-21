package io.aequicor.magicpaper.domain

import java.util.TimeZone
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class MessageSchedulingReplayTest {
    @Test fun timeoutReplayProducesTheSameDurablePayloadAfterTheSystemTimezoneChanges() = synchronized(TimeZone::class.java) {
        val originalZone = TimeZone.getDefault()
        try {
            val plan = Plan("plan", "project", "Goal", runId = "run", intent = ExecutionIntent.RUN,
                confirmedRevision = 1, phase = ExecutionPhase.EXECUTING, scheduledMessages = listOf(
                    ScheduledMessage("rule", "plan", "run", "parent",
                        MessageTrigger(MessageTriggerKind.EVENT, event = MessageEventKind.RUN_COMPLETED, deadline = 1_000),
                        "parent", null, "Review the timeout", createdAt = 0, deliveryId = "delivery")))
            val input = Json.encodeToString(Plan.serializer(), plan)
            fun replay(zone: String): Plan {
                TimeZone.setDefault(TimeZone.getTimeZone(zone))
                return Json.decodeFromString(Plan.serializer(), input).advanceScheduledMessages(2_000)
            }
            val utc = replay("UTC")
            assertEquals(utc, replay("Pacific/Honolulu"))
            assertEquals(utc, replay("Asia/Tokyo"))
            val timeout = utc.scheduledMessages.single()
            assertTrue(timeout.timeout)
            assertEquals(ScheduledMessageStatus.READY, timeout.status)
            assertTrue(timeout.payload.contains("1970-01-01T00:00:01 (UTC)"))
            assertEquals(2_000L, timeout.firedAt)

            // Localized display is still a presentation choice; it never changes the saved delivery bytes.
            TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Honolulu"))
            assertNotEquals(timeout.trigger.describe(plan), timeout.trigger.describe(plan, kotlinx.datetime.TimeZone.UTC))
        } finally { TimeZone.setDefault(originalZone) }
    }
}
