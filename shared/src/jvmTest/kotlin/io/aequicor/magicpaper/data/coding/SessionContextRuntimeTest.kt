package io.aequicor.magicpaper.data.coding

import io.aequicor.magicpaper.data.llm.CodexAppServerOpenAiSubscription
import io.aequicor.magicpaper.domain.*
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.test.*

class SessionContextRuntimeTest {
    @Test fun snapshotUsesPinnedSkillsAndTheSamePromptBuilderAsTheAdapters() = runBlocking {
        val root = Files.createTempDirectory("session-context-test")
        val client = CodexAppServerOpenAiSubscription(Json, root.resolve("codex"))
        try {
            var reads = 0
            val runtime = DesktopCodingRuntime(PiCodingRuntime(root.resolve("pi").toFile()), client,
                skillSelection = {
                    reads++
                    CodingSkillSelection(listOf(SkillInstruction("skill", "1.2.3", "checksum", "Skill", "Description",
                        emptySet(), "EXACT SKILL TEXT")), trustedText = true, freshSession = true)
                })
            val project = CodingProject("p", "Project", root.toString(), 1)
            val profile = LlmProfile("profile", "Profile", modelId = "model").forModel().copy(
                advanced = AdvancedLlmOptions(systemPromptOverride = "CUSTOM PROMPT"))
            for (engine in CodingEngine.entries) {
                val session = CodingSession("s", "p", "Session", 1, engine = engine)
                val text = runtime.sessionContext(project, session, profile)
                assertTrue(codingSystemPrompt(engine, false, "CUSTOM PROMPT") in text)
                assertTrue("skill@1.2.3" in text)
                assertTrue("EXACT SKILL TEXT" in text)
                assertTrue("checksum" in text)
                val planning = runtime.sessionContext(project, session.copy(planningMode = true), profile)
                assertTrue(PLANNING_INSTRUCTIONS in planning)
                assertFalse("EXACT SKILL TEXT" in planning)
                val research = runtime.sessionContext(project, session.copy(researchMode = true), profile)
                assertTrue(RESEARCH_INSTRUCTIONS in research)
                assertTrue("Исследование" in research)
                assertTrue("повышение прав и управление компьютером отключены" in research)
                assertTrue("EXACT SKILL TEXT" in research)
            }
            assertEquals(4, reads)
        } finally {
            client.close()
            root.toFile().deleteRecursively()
        }
    }
}
