package io.aequicor.magicpaper.domain

import io.aequicor.magicpaper.domain.tools.QuestionnaireContract

/** IDs come from the active tool catalog. An absent capability is never advertised by this policy. */
fun toolPromptInstructions(toolIds: Set<String>): List<String> = buildList {
    if ("questionnaire" in toolIds) add(QuestionnaireContract.instructions)
}

/** Callers provide native-only policy as text; common assembly never chooses an engine or acquires a capability. */
fun assembleSystemPrompt(sections: List<String>): String = sections.filter { it.isNotBlank() }.joinToString("\n\n")
