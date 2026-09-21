package io.aequicor.magicpaper.domain

import io.aequicor.magicpaper.domain.planning.recoveredAssignments
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class NativeAssignmentRecoveryTest {
    private val astra = CodingModel("openai", "gpt-6-astra", "GPT-6 Astra", levels = listOf("low", "medium", "max"), defaultLevel = "medium")
    private val luna = CodingModel("openai", "gpt-luna", "Luna", levels = listOf("low", "high"))
    private val snapshot = CodingModelSnapshot(CodingEngine.CODEX, listOf(astra, luna), 1)
    private val subscription = LlmProfile("chatgpt", "ChatGPT", provider = ProviderType.OPENAI_SUBSCRIPTION, modelId = "x")
    private val roster = listOf(subscription)
    private val gone = nativeStageAssignment(subscription, CodingEngine.CODEX, astra.copy(id = "gpt-gone", name = "Gone"), "max", "why", manual = true)
    private val healthy = nativeStageAssignment(subscription, CodingEngine.CODEX, astra, "max")

    private fun plan(assignment: StageAssignment, attempt: StageAssignment = assignment): Plan {
        val stage = Milestone("stage", "Stage", assignment = assignment, status = MilestoneStatus.ACTIVE,
            attempts = listOf(StageAttempt("attempt", "worker", attempt, phase = AttemptPhase.EXECUTING)))
        return Plan("plan", "project", "Goal", milestones = listOf(stage), parentSessionId = "parent")
    }
    private fun parent(choice: CodingModelSelection?) =
        CodingSession("parent", "project", "n", 1, engine = CodingEngine.CODEX, codingModel = choice)
    private fun Plan.stageAssignment() = milestones.single().assignment
    private fun Plan.attemptAssignment() = milestones.single().attempts.single().assignment

    @Test fun healthyNativeAssignmentsAreLeftUntouched() {
        val p = plan(healthy)
        assertSame(p.milestones.single().assignment, p.recoveredAssignments(roster, listOf(parent(null)), snapshot).stageAssignment())
    }

    @Test fun vanishedModelIsReplacedByTheOrchestratorsOwnNativeChoiceKeepingWhyAndManual() {
        val choice = CodingModelSelection(CodingEngine.CODEX, "openai", "gpt-luna", "high")
        val recovered = plan(gone).recoveredAssignments(roster, listOf(parent(choice)), snapshot)
        val expected = nativeStageAssignment(subscription, CodingEngine.CODEX, luna, "high", "why", manual = true)
        assertEquals(expected, recovered.stageAssignment())
        assertEquals(expected, recovered.attemptAssignment())
    }

    @Test fun withoutAnAvailableNativeChoiceTheAssignmentStaysSoAdmissionCanRefuseItByName() {
        val p = plan(gone)
        assertEquals(gone, p.recoveredAssignments(roster, listOf(parent(null)), snapshot).stageAssignment(), "no native choice on the parent")
        val alsoGone = CodingModelSelection(CodingEngine.CODEX, "openai", "gpt-also-gone")
        assertEquals(gone, p.recoveredAssignments(roster, listOf(parent(alsoGone)), snapshot).stageAssignment(), "the parent's choice left the catalog too")
        assertEquals(gone, p.recoveredAssignments(roster, listOf(parent(CodingModelSelection(CodingEngine.CODEX, "openai", "gpt-luna"))), null)
            .stageAssignment(), "no snapshot, no evidence that the replacement exists")
    }

    @Test fun unsupportedLevelOfAKnownModelCountsAsVanishedAndIsRecovered() {
        val staleLevel = nativeStageAssignment(subscription, CodingEngine.CODEX, luna, "max")
        val choice = CodingModelSelection(CodingEngine.CODEX, "openai", "gpt-6-astra")
        val recovered = plan(staleLevel).recoveredAssignments(roster, listOf(parent(choice)), snapshot).stageAssignment()
        assertEquals("gpt-6-astra", recovered?.native?.modelId)
        assertEquals(null, recovered?.native?.level, "default level stays default, it is not replaced by a guess")
    }

    @Test fun replacementNeedsTheEnginesOwnConnection() {
        val choice = CodingModelSelection(CodingEngine.CODEX, "openai", "gpt-luna")
        val noSubscription = listOf(LlmProfile("api", "API", baseUrl = "http://x/v1", modelId = "m", provider = ProviderType.OPENAI_COMPATIBLE))
        assertEquals(gone, plan(gone).recoveredAssignments(noSubscription, listOf(parent(choice)), snapshot).stageAssignment())
    }

    @Test fun legacyAssignmentsKeepTheirOldRecoveryRules() {
        val legacy = StageAssignment("missing-profile", "model")
        val p = plan(legacy)
        assertEquals(legacy, p.recoveredAssignments(roster, listOf(parent(null)), snapshot).stageAssignment())
    }
}
