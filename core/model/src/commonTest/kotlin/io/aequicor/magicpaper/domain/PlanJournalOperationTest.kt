package io.aequicor.magicpaper.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlanJournalOperationTest {
    @Test fun wireFormsAreUniqueAndRoundTrip() {
        val wires = PlanJournalOperation.entries.map { it.wire }
        assertEquals(wires.size, wires.distinct().size, "Две операции не могут писать одну строку")
        PlanJournalOperation.entries.forEach {
            assertEquals(it, PlanJournalOperation.of(it.wire))
            assertEquals(it.wire, PlanJournalEntry("id", 1, it).operation, "Строку журнала производит только перечисление")
            assertTrue(PlanJournalEntry("id", 1, it).records(it))
        }
    }

    @Test fun anEntryFromAnOlderVersionIsANoticeAndNeverASettledOutcome() {
        val unknown = PlanJournalEntry("id", 1, operation = "retired-operation")
        assertNull(unknown.operationKind)
        assertTrue(PlanJournalOperation.entries.none { unknown.records(it) })
    }

    @Test fun everyStopEntryTakesPartInTheRecoveryComparison() {
        // The comparison stays on the prefix: an unknown stop from an older version must not
        // drop out, or two different run states would compare equal and a stale recovery
        // answer would be accepted.
        PlanJournalOperation.entries.filter { it.wire.startsWith("stop-") }.forEach {
            assertTrue(PlanJournalEntry("id", 1, it).describesStop, it.name)
        }
        assertTrue(PlanJournalEntry("id", 1, operation = "stop-from-an-older-build").describesStop)
        assertTrue(PlanJournalEntry("id", 1, PlanJournalOperation.AGENT_INTENT).describesStop.not())
    }

    @Test fun anIntentIsWrittenBeforeItsEffectAndCannotBeReadAsCompletion() {
        val intents = PlanJournalOperation.entries.filter { it.kind == JournalEntryKind.INTENT }
        assertTrue(intents.isNotEmpty())
        // A journal ending on an intent means the effect may have run without the application
        // learning the result; recovery must resolve it against evidence, not repeat it.
        assertTrue(intents.all { it.wire.endsWith("-intent") }, "Намерение узнаётся и по строке в старых планах")
        assertTrue(PlanJournalOperation.entries.filter { it.kind == JournalEntryKind.OUTCOME }
            .none { it.wire.endsWith("-intent") })
    }
}
