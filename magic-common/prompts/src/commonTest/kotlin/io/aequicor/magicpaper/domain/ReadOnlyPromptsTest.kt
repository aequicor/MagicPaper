package io.aequicor.magicpaper.domain

import io.aequicor.magicpaper.domain.tools.QuestionnaireContract
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReadOnlyPromptsTest {
    @Test fun planningAppliesProjectAndMethodologyBeforeTheReadOnlyAndToolPolicies() {
        assertEquals(listOf("Project", "Methodology", PLANNING_INSTRUCTIONS, QuestionnaireContract.instructions),
            planningPromptSections("Project", "Methodology", setOf("questionnaire")))
        val withoutTools = assembleSystemPrompt(planningPromptSections("", "", emptySet()))
        assertEquals(PLANNING_INSTRUCTIONS, withoutTools)
        assertFalse(QuestionnaireContract.instructions in withoutTools)
    }

    @Test fun researchCompactModeKeepsEveryRestrictionAndOnlyChangesTheirOrder() {
        assertEquals(listOf("Project", "Methodology", RESEARCH_INSTRUCTIONS, QuestionnaireContract.instructions),
            researchPromptSections("Project", "Methodology", false, setOf("questionnaire")))
        assertEquals(listOf(RESEARCH_INSTRUCTIONS, QuestionnaireContract.instructions, "Project", "Methodology"),
            researchPromptSections("Project", "Methodology", true, setOf("questionnaire")))
        for (compact in listOf(false, true)) {
            assertEquals(RESEARCH_INSTRUCTIONS, assembleSystemPrompt(researchPromptSections("", "", compact, emptySet())))
            assertTrue("Сборки и тесты запускай только через research_check." in RESEARCH_INSTRUCTIONS)
            assertTrue("реализация требует переключения режима пользователем." in RESEARCH_INSTRUCTIONS)
        }
    }
}
