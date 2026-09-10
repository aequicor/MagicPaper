package io.aequicor.magicpaper.data.coding

import io.aequicor.magicpaper.domain.CodingEngine
import io.aequicor.magicpaper.domain.PlanningRulesSettings
import io.aequicor.magicpaper.domain.PLANNING_INSTRUCTIONS
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CodingSystemPromptsTest {
    @Test fun customRulesReplaceMethodologyWhileReadOnlyPolicyRemainsEffective() {
        val rules = PlanningRulesSettings().edited("Investigate from experiments").snapshot()
        for (engine in CodingEngine.entries) {
            val prompt = codingSystemPrompt(engine, planning = true, override = "", planningRules = rules)
            assertTrue(rules.text in prompt)
            assertTrue(PLANNING_INSTRUCTIONS in prompt)
            assertTrue(prompt.indexOf(PLANNING_INSTRUCTIONS) > prompt.indexOf(rules.text))
            assertFalse("Для разрешённой разработки используй feature-ветку" in prompt)
            val worker = codingSystemPrompt(engine, planning = false, override = "", planningRules = rules)
            assertTrue(rules.text in worker)
        }
    }

    @Test fun codingUsesTheSelectedBackendsFileToolsWithCustomInstructions() {
        for (engine in CodingEngine.entries) {
            val prompt = codingSystemPrompt(engine, planning = false, override = "PROJECT RULES")
            assertTrue(CODING_FILE_TOOL_INSTRUCTIONS in prompt)
            assertTrue("PROJECT RULES" in prompt)
            assertTrue(if (engine == CodingEngine.CODEX) "apply_patch" in prompt else "read" in prompt && "edit" in prompt && "write" in prompt)
            assertFalse(if (engine == CodingEngine.CODEX) PI_CODING_INSTRUCTIONS in prompt else CODEX_FILE_TOOL_INSTRUCTIONS in prompt)
        }
    }

    @Test fun readOnlyModesDoNotReceiveFileEditingInstructions() {
        for (engine in CodingEngine.entries) {
            for (prompt in listOf(
                codingSystemPrompt(engine, planning = true, override = ""),
                codingSystemPrompt(engine, planning = false, override = "", research = true),
            )) {
                assertFalse(CODING_FILE_TOOL_INSTRUCTIONS in prompt)
                assertFalse(CODEX_FILE_TOOL_INSTRUCTIONS in prompt)
                assertFalse(PI_CODING_INSTRUCTIONS in prompt)
            }
        }
    }
}
