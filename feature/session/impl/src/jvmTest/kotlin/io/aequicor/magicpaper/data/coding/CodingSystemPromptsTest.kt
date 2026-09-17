package io.aequicor.magicpaper.data.coding

import io.aequicor.magicpaper.domain.CodingEngine
import io.aequicor.magicpaper.domain.PlanningRulesSettings
import io.aequicor.magicpaper.domain.PLANNING_INSTRUCTIONS
import io.aequicor.magicpaper.domain.CodingSession
import io.aequicor.magicpaper.domain.SessionKind
import io.aequicor.magicpaper.domain.runtimePlanningRules
import io.aequicor.magicpaper.domain.CodingRunCheckpoint
import io.aequicor.magicpaper.domain.TaskWorktree
import io.aequicor.magicpaper.domain.TaskWorktreePhase
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CodingSystemPromptsTest {
    @Test fun explicitInteractionChannelWinsAndUnavailableBrowserIsNotAdvertised() {
        for (engine in CodingEngine.entries) {
            val prompt = codingSystemPrompt(engine, false, "", browserAvailable = false)
            assertTrue(INTERACTION_CHANNEL_INSTRUCTIONS in prompt)
            assertFalse(BROWSER_INSTRUCTIONS in prompt)
            assertFalse("magicpaper_browser_" in prompt)
            assertTrue("Не пытайся запускать его повторно" in prompt)
        }
    }
    @Test fun browserAndUiGuidesReachBothEnginesInEveryMode() {
        for (engine in CodingEngine.entries) for ((planning, research) in listOf(false to false, true to false, false to true)) {
            val prompt = codingSystemPrompt(engine, planning, "PROJECT RULES", research)
            assertTrue("magicpaper_browser_" in prompt)
            assertTrue("browser.validate_html" in prompt)
            assertTrue("https://github.com/willyp713/awesome-ui-guides" in prompt)
            assertTrue("PROJECT RULES" in prompt)
        }
    }

    @Test fun activeWorktreePolicyReachesBothEnginesAndRefreshesForNextTask() {
        val task = TaskWorktree("task", "/source", "feature/current", "base", "/pool/session", "codex/task")
        val session = CodingSession("session", "project", "Task", 1,
            piSessionId = "existing-native-history", taskWorktree = task,
            pendingRun = CodingRunCheckpoint("input", "Task", responseId = "response", runId = task.taskId, worktreeEnabled = true))
        for (engine in CodingEngine.entries) {
            val prompt = codingSystemPrompt(engine, false, "PROJECT RULES", session = session)
            assertTrue("task.handoff" in prompt)
            assertTrue("outcome=RESULT" in prompt)
            assertTrue("отдельное\nподтверждение пользователя" in prompt)
            assertTrue("не повторяй BLOCKED по той же причине" in prompt)
            assertTrue("пустым checks" in prompt)
            assertTrue("упавшую автоматическую проверку" in prompt)
            assertTrue(task.path in prompt && task.targetBranch in prompt)
            val next = session.copy(taskWorktree = task.copy(taskId = "next", targetBranch = "feature/next"),
                pendingRun = session.pendingRun!!.copy(runId = "next"))
            val resumed = codingSystemPrompt(engine, false, "", session = next)
            assertTrue("feature/next" in resumed)
            assertFalse(task.targetBranch in resumed)
            for (inactive in listOf(session.copy(taskWorktree = null), session.copy(pendingRun = null),
                session.copy(taskWorktree = task.copy(phase = TaskWorktreePhase.COMPLETE)),
                session.copy(pendingRun = session.pendingRun!!.copy(runId = "another")), session.copy(stageId = "stage"))) {
                assertFalse("task.handoff" in codingSystemPrompt(engine, false, "", session = inactive))
            }
            assertFalse("task.handoff" in codingSystemPrompt(engine, true, "", session = session))
            assertFalse("task.handoff" in codingSystemPrompt(engine, false, "", research = true, session = session))
        }
    }

    @Test fun destinationDistanceAndPreRunUpdateReachTheAgent() {
        val task = TaskWorktree("task", "/source", "feature/current", "base", "/pool/session", "codex/task")
        val session = CodingSession("session", "project", "Task", 1, taskWorktree = task,
            pendingRun = CodingRunCheckpoint("input", "Task", responseId = "response", runId = task.taskId, worktreeEnabled = true))
        val upToDate = codingSystemPrompt(CodingEngine.PI, false, "", session = session)
        assertFalse("HEAD..feature/current" in upToDate || "подтянута" in upToDate)
        val stale = codingSystemPrompt(CodingEngine.PI, false, "", session = session.copy(taskWorktree = task.copy(
            behindCommits = 4, refreshNote = "В копии есть несохранённые изменения")))
        assertTrue("(4)" in stale && "HEAD..feature/current" in stale && "несохранённые" in stale, stale)
        val updated = codingSystemPrompt(CodingEngine.PI, false, "", session = session.copy(taskWorktree = task.copy(integratedCommit = "tip")))
        assertTrue("подтянута к ветке назначения feature/current" in updated, updated)
        assertFalse("HEAD..feature/current" in updated)
    }

    @Test fun ordinarySessionDoesNotInheritPlannerMethodologyFromLifecycleAdoption() {
        val rules = PlanningRulesSettings().edited("Create milestones and wait for plan approval").snapshot()
        val ordinary = CodingSession("root", "project", "New session", 1,
            organismId = "organism", sessionKind = SessionKind.ZYGOTE, planningRulesSnapshot = rules)
        for (engine in CodingEngine.entries) {
            val prompt = codingSystemPrompt(engine, false, "PROJECT RULES", planningRules = ordinary.runtimePlanningRules)
            assertFalse(rules.text in prompt)
            assertFalse(PLANNING_INSTRUCTIONS in prompt)
            assertTrue(CODING_FILE_TOOL_INSTRUCTIONS in prompt)
            for (planned in listOf(ordinary.copy(planningMode = true), ordinary.copy(planId = "plan", stageId = "stage"))) {
                assertTrue(rules.text in codingSystemPrompt(engine, planned.planningMode, "", planningRules = planned.runtimePlanningRules))
            }
        }
    }

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
