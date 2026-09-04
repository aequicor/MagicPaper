package io.aequicor.magicpaper.plugins.builtin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CalcPluginTest {

    @Test
    fun evaluatesPrecedence() {
        assertEquals(8.0, CalcPlugin.evaluate("2 + 2 * 3").getOrThrow())
    }

    @Test
    fun evaluatesParentheses() {
        assertEquals(12.0, CalcPlugin.evaluate("(2 + 2) * 3").getOrThrow())
    }

    @Test
    fun evaluatesDivision() {
        assertEquals(2.5, CalcPlugin.evaluate("5 / 2").getOrThrow())
    }

    @Test
    fun rejectsDivisionByZero() {
        assertTrue(CalcPlugin.evaluate("1 / 0").isFailure)
    }

    @Test
    fun rejectsGarbage() {
        assertTrue(CalcPlugin.evaluate("2 + + 2x").isFailure)
    }
}
