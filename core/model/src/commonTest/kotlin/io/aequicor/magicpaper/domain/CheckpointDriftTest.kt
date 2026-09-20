package io.aequicor.magicpaper.domain

import kotlin.test.Test
import kotlin.test.assertEquals

class CheckpointDriftTest {
    private fun attempt(
        id: String = "a",
        phase: AttemptPhase = AttemptPhase.EXECUTING,
        turnIndex: Int = 2,
        sessionGeneration: Long = 7,
    ) = StageAttempt(id, "s", StageAssignment("profile", "model"), phase = phase,
        turnIndex = turnIndex, sessionGeneration = sessionGeneration)

    private fun plan(runId: String = "run-1", attempts: List<StageAttempt> = listOf(attempt())) =
        Plan("p", "project", goal = "цель", runId = runId,
            milestones = listOf(Milestone("stage", "Этап", attempts = attempts)))

    private val held = plan()
    private val holding = attempt()

    private fun drift(durable: Plan?, stageId: String = "stage") =
        CheckpointDrift(held, holding, durable, stageId)

    @Test fun anUntouchedPlanIsNoDrift() {
        assertEquals(CheckpointDrift.None, drift(plan()))
    }

    @Test fun nothingToContinueIsNotTheSameAsSomeoneElseContinuing() {
        assertEquals(CheckpointDrift.Gone, drift(null), "плана нет")
        assertEquals(CheckpointDrift.Gone, drift(plan(), stageId = "удалённый"), "этапа нет")
        assertEquals(CheckpointDrift.Gone, drift(plan(attempts = emptyList())), "попыток нет")
    }

    @Test fun everyPartOfTheIdentityIsCompared() {
        // Each of these distinguishes work that must never be confused: a fresh run, a new
        // attempt of the stage, the next turn of the same attempt, a newly admitted generation.
        // Dropping any one of them attributes a native turn to work it never belonged to.
        assertEquals(CheckpointDrift.TakenOver, drift(plan(runId = "run-2")), "runId")
        assertEquals(CheckpointDrift.TakenOver, drift(plan(attempts = listOf(attempt(id = "b")))), "id")
        assertEquals(CheckpointDrift.TakenOver, drift(plan(attempts = listOf(attempt(turnIndex = 3)))), "turnIndex")
        assertEquals(CheckpointDrift.TakenOver, drift(plan(attempts = listOf(attempt(sessionGeneration = 8)))), "sessionGeneration")
    }

    @Test fun onlyTheLastAttemptOfTheStageIsTheOneInHand() {
        // An older attempt left in the history is not the work being executed.
        val superseded = plan(attempts = listOf(attempt(), attempt(id = "b")))
        assertEquals(CheckpointDrift.TakenOver, drift(superseded))
    }

    @Test fun aSubstitutionIsNeverMistakenForProgress() {
        // Identity is compared first on purpose. A foreign attempt in a different phase must
        // not be adopted just because its phase moved on.
        val foreign = plan(runId = "run-2", attempts = listOf(attempt(id = "b", phase = AttemptPhase.VERIFYING)))
        assertEquals(CheckpointDrift.TakenOver, drift(foreign))
    }

    @Test fun theSameTurnInANewPhaseCarriesItsDurableValues() {
        AttemptPhase.entries.filter { it != holding.phase }.forEach { phase ->
            val recorded = attempt(phase = phase)
            val advanced = plan(attempts = listOf(recorded))
            assertEquals(CheckpointDrift.Advanced(advanced.milestones.single(), recorded), drift(advanced), phase.name)
        }
    }
}
