package io.aequicor.magicpaper.data.coding

import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.data.llm.CodexAppServerOpenAiSubscription
import io.aequicor.magicpaper.data.questionnaire.QuestionnaireTool

internal val PI_CODING_INSTRUCTIONS = """
            Files may contain Russian typography: em dashes (—), guillemets («»…«»), the letter ё.
            In edit tools, copy oldText/newText EXACTLY as read() returned them: do not replace
            an em dash with a hyphen or guillemets with straight quotes, do not drop characters.
            If an edit fails to match, re-read that region and retry with the exact text.
            """.trimIndent()

internal fun codingSystemPrompt(engine: CodingEngine?, planning: Boolean, override: String, research: Boolean = false): String =
    (if (planning) listOf(PLANNING_INSTRUCTIONS, override, QuestionnaireTool.instructions) else if (research) listOf(override, RESEARCH_INSTRUCTIONS, QuestionnaireTool.instructions) else when (engine) {
        CodingEngine.CODEX -> listOf(QuestionnaireTool.instructions, CodexAppServerOpenAiSubscription.CODING_INSTRUCTIONS, override)
        CodingEngine.PI -> listOf(PI_CODING_INSTRUCTIONS, QuestionnaireTool.instructions, override)
        null -> listOf("Движок не выбран", override)
    }).filter { it.isNotBlank() }.joinToString("\n\n")
