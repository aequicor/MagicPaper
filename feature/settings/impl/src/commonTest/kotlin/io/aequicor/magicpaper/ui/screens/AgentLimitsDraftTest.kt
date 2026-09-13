package io.aequicor.magicpaper.ui.screens

import io.aequicor.magicpaper.domain.OrganismLimits
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgentLimitsDraftTest {
    @Test fun emptyAndWhitespaceFieldsRemoveEveryLimit() {
        assertEquals(OrganismLimits(), AgentLimitsDraft().limits())
        val empty = AgentLimitsDraft(AgentLimitField.entries.associateWith { "  " })
        assertTrue(empty.valid)
        assertEquals(OrganismLimits(), empty.limits())
    }

    @Test fun allExplicitLimitsRoundTripIncludingZeroRetries() {
        val limits = OrganismLimits(tokens = 900_000, durationMillis = 5_400_000, activeSessions = 3,
            depth = 4, retries = 0, queueSize = 20, contextCharacters = 40_000)
        assertEquals("90", AgentLimitsDraft.from(limits)[AgentLimitField.DURATION])
        assertEquals(limits, AgentLimitsDraft.from(limits).limits())
    }

    @Test fun invalidInputStaysInDraftAndPreventsSavingOtherValidFields() {
        for (invalid in listOf("-1", "1e5", "1.5", "abc", "99999999999999999999999")) {
            val draft = AgentLimitsDraft().edited(AgentLimitField.ACTIVE_SESSIONS, "2")
                .edited(AgentLimitField.TOKENS, invalid)
            assertEquals(invalid, draft[AgentLimitField.TOKENS])
            assertFalse(draft.valid)
            assertNotNull(draft.error(AgentLimitField.TOKENS))
            assertNull(draft.limits())
        }
    }

    @Test fun zeroOnlyDisablesRetriesAndNeverSilentlyMeansUnlimited() {
        for (field in AgentLimitField.entries) {
            val draft = AgentLimitsDraft().edited(field, "0")
            assertEquals(field == AgentLimitField.RETRIES, draft.valid)
        }
        assertEquals(0, AgentLimitsDraft().edited(AgentLimitField.RETRIES, "0").limits()?.retries)
        assertEquals("Введите число больше 0", AgentLimitsDraft().edited(AgentLimitField.TOKENS, "-2")
            .error(AgentLimitField.TOKENS))
        assertEquals("Введите 0 или больше", AgentLimitsDraft().edited(AgentLimitField.RETRIES, "-2")
            .error(AgentLimitField.RETRIES))
    }

    @Test fun representableLimitsAreAcceptedAndIntegerOverflowIsRejected() {
        assertEquals(Long.MAX_VALUE, AgentLimitsDraft().edited(AgentLimitField.TOKENS, Long.MAX_VALUE.toString()).limits()?.tokens)
        for (field in AgentLimitField.entries.filter { it != AgentLimitField.TOKENS && it != AgentLimitField.DURATION }) {
            assertTrue(AgentLimitsDraft().edited(field, Int.MAX_VALUE.toString()).valid)
            assertFalse(AgentLimitsDraft().edited(field, (Int.MAX_VALUE.toLong() + 1).toString()).valid)
        }
        assertNull(AgentLimitsDraft().edited(AgentLimitField.DURATION, Long.MAX_VALUE.toString()).limits())
    }

    @Test fun fractionalMinutesAndImportedDurationsKeepExactMilliseconds() {
        assertEquals(90_000L, AgentLimitsDraft().edited(AgentLimitField.DURATION, "1,5").limits()?.durationMillis)
        for (millis in listOf(1L, 59_999L, 60_001L, Long.MAX_VALUE)) {
            val limits = OrganismLimits(durationMillis = millis)
            assertEquals(limits, AgentLimitsDraft.from(limits).limits())
        }
        assertFalse(AgentLimitsDraft().edited(AgentLimitField.DURATION, "0.0000001").valid)
    }

    @Test fun clearingOneFieldPreservesTheOtherExplicitLimits() {
        val limits = OrganismLimits(tokens = 10_000, activeSessions = 2, durationMillis = 60_000)
        val changed = AgentLimitsDraft.from(limits).edited(AgentLimitField.TOKENS, "")
        assertEquals(limits.copy(tokens = null), changed.limits())
    }
}
