package io.aequicor.magicpaper.ui.screens

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ModelTimeoutValidationTest {
    @Test fun acceptsUnlimitedAndUserDeadlinesLongerThanOneHour() {
        assertEquals(0, parseModelTimeoutSeconds("0"))
        assertEquals(120, parseModelTimeoutSeconds("120"))
        assertEquals(7200, parseModelTimeoutSeconds("7200"))
        assertEquals(Int.MAX_VALUE, parseModelTimeoutSeconds(Int.MAX_VALUE.toString()))
    }

    @Test fun rejectsNegativeFractionalAndUnrepresentableDeadlines() {
        for (value in listOf("", "-1", "0.5", "unknown", "2147483648")) assertNull(parseModelTimeoutSeconds(value))
    }
}
