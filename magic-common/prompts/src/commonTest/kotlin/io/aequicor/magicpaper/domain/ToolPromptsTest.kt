package io.aequicor.magicpaper.domain

import io.aequicor.magicpaper.domain.tools.QuestionnaireContract
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ToolPromptsTest {
    @Test fun instructionsAdvertiseOnlyCapabilitiesInTheActiveCatalog() {
        assertTrue(toolPromptInstructions(emptySet()).isEmpty())
        assertTrue(toolPromptInstructions(setOf("search", "browser.open")).isEmpty())
        assertEquals(listOf(QuestionnaireContract.instructions), toolPromptInstructions(setOf("search", "questionnaire")))
    }

    @Test fun compositionPreservesExactSectionTextAndOrderWhileRemovingAbsentPolicies() {
        assertEquals("  First\n\n\nSecond\n", assembleSystemPrompt(listOf("", "  First\n", " \n", "Second\n")))
        assertEquals("", assembleSystemPrompt(emptyList()))
    }
}
