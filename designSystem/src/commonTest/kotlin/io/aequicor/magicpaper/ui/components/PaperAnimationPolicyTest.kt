package io.aequicor.magicpaper.ui.components

import io.aequicor.magicpaper.designsystem.PaperEnvironment
import io.aequicor.magicpaper.designsystem.isPaperHardwareCapable
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PaperAnimationPolicyTest {
    private val capable = PaperEnvironment(capable = true, powerKnown = true)

    @Test
    fun lowBatteryStopsAndRecoveredBatteryResumes() {
        assertTrue(capable.copy(batteryPercent = 21).allowsAnimation)
        assertFalse(capable.copy(batteryPercent = 20).allowsAnimation)
        assertFalse(capable.copy(batteryPercent = 0).allowsAnimation)
        assertTrue(capable.copy(batteryPercent = 55).allowsAnimation)
    }

    @Test
    fun desktopWithoutBatteryCanAnimateButUnknownOrInvalidPowerCannot() {
        assertTrue(capable.allowsAnimation)
        assertFalse(capable.copy(powerKnown = false).allowsAnimation)
        assertFalse(capable.copy(batteryPercent = -1).allowsAnimation)
        assertFalse(capable.copy(batteryPercent = 101).allowsAnimation)
    }

    @Test
    fun capabilityPowerSavingAndReducedMotionAreIndependentVetoes() {
        assertFalse(capable.copy(capable = false).allowsAnimation)
        assertFalse(capable.copy(powerSave = true).allowsAnimation)
        assertFalse(capable.copy(reduceMotion = true).allowsAnimation)
        assertFalse(isPaperHardwareCapable(2, 16_000_000_000, android = false))
        assertFalse(isPaperHardwareCapable(8, 4_000_000_000, android = true))
        assertTrue(isPaperHardwareCapable(8, 6_000_000_000, android = true))
        assertTrue(isPaperHardwareCapable(4, 8_000_000_000, android = false))
    }

}
