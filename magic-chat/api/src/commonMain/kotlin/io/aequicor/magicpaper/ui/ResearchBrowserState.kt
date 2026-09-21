package io.aequicor.magicpaper.ui

enum class ResearchBrowserPhase { OPENING, READY, READING, FAILED }

data class ResearchBrowserState(
    val questionId: String,
    val resourceKey: String,
    val title: String,
    val url: String,
    val phase: ResearchBrowserPhase = ResearchBrowserPhase.OPENING,
    val pageOpen: Boolean = false,
    val problem: String? = null,
)
