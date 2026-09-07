package io.aequicor.magicpaper.ui.components

/** Unknown power information fails closed; null battery with powerKnown means a mains-only PC. */
internal data class PaperEnvironment(
    val capable: Boolean = false,
    val powerKnown: Boolean = false,
    val batteryPercent: Int? = null,
    val powerSave: Boolean = false,
    val reduceMotion: Boolean = false,
) {
    val allowsAnimation: Boolean
        get() = capable && powerKnown && !powerSave && !reduceMotion &&
            (batteryPercent == null || batteryPercent in 21..100)
}

/** A conservative eligibility heuristic, not a GPU benchmark. */
internal fun isPaperHardwareCapable(processors: Int, memoryBytes: Long, android: Boolean): Boolean =
    processors >= 4 && memoryBytes >= (if (android) 5_500_000_000L else 7_000_000_000L)
