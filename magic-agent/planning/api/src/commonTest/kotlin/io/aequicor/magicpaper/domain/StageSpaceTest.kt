package io.aequicor.magicpaper.domain

import io.aequicor.magicpaper.domain.planning.StageEvent
import io.aequicor.magicpaper.domain.planning.StageMachine
import io.aequicor.magicpaper.domain.planning.StageRetryInputs
import io.aequicor.magicpaper.domain.planning.StageSpace
import io.aequicor.magicpaper.domain.planning.StageState
import io.aequicor.magicpaper.domain.planning.toState
import io.aequicor.magicpaper.machine.verifyStateSpace
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The representatives of [StageSpace], kept here rather than in the api so a shipped binary carries
 * no fixtures.
 *
 * `StageState` is a checkpoint kept verbatim, so its one entry point is the public adapter
 * [toState]; every position other than the first is reached by running events from it.
 */
class StageSpaceTest {
    private val base = StageAttempt("attempt", "session", StageAssignment("profile", "model"), path = "/work")
    private val retry = StageRetryInputs(limit = 2, now = 10_000, jitter = 17)
    private fun step(state: StageState, event: StageEvent) = StageMachine.step(state, event).state

    private val prepared = base.toState()
    private val executing = step(prepared, StageEvent.WorkerStarting("implement", 10))
    private val interrupted = step(executing, StageEvent.Interrupted(executing.attempt, waiting = false, at = 30))
    private val duringTool = executing.attempt.copy(pendingTool = "upload", pendingToolExternal = true)
    private val unconfirmed = step(executing, StageEvent.Interrupted(duringTool, waiting = false, at = 30))

    /** The three readings that decide the position, in the order they are ranked. */
    @Test fun anUnconfirmedCommandOutranksAStopWhichOutranksThePhase() {
        assertEquals(StageSpace.UNCONFIRMED_TOOL, StageSpace.label(unconfirmed))
        assertEquals(StageSpace.INTERRUPTED, StageSpace.label(interrupted))
        assertEquals(StageSpace.EXECUTING, StageSpace.label(executing))
        // A tool that is running and was not interrupted is labelled by its phase; it is not unknown yet.
        val running = executing.attempt.copy(pendingTool = "upload", pendingToolExternal = true).toState()
        assertEquals(StageSpace.EXECUTING, StageSpace.label(running))
        assertFalse(StageSpace.unknown(running))
        // A local command cut off by a stop is not an unresolved outcome: only an external one is.
        val local = step(executing, StageEvent.Interrupted(executing.attempt.copy(pendingTool = "check", pendingToolExternal = false), false, 30))
        assertEquals(StageSpace.INTERRUPTED, StageSpace.label(local))
        assertFalse(StageSpace.unknown(local))
        // Interrupted, in a phase past execution, is still a stop rather than that phase.
        val verifying = step(executing, StageEvent.PlannerDecided(null))
        assertEquals(StageSpace.VERIFYING, StageSpace.label(verifying))
        assertEquals(StageSpace.INTERRUPTED, StageSpace.label(step(verifying, StageEvent.Interrupted(verifying.attempt, false, 30))))
    }

    /** `unknown(state)` holds exactly at the position that names an unresolved external command. */
    @Test fun unknownIsExactlyAnInterruptedExternalCommand() {
        val states = mapOf(
            StageSpace.PREPARED to prepared, StageSpace.EXECUTING to executing, StageSpace.INTERRUPTED to interrupted,
            StageSpace.UNCONFIRMED_TOOL to unconfirmed,
        )
        for ((position, state) in states) assertEquals(position == StageSpace.UNCONFIRMED_TOOL, StageSpace.unknown(state), position.name)
    }

    /** The only refusals: an event the attempt cannot take, shown as a value instead of an exception. */
    @Test fun anEventTheReducerCannotApplyIsARefusalAndLeavesTheStateAlone() {
        val noTurn = StageMachine.step(prepared, StageEvent.WorkerTurnEnded(20, "done"))
        assertTrue(noTurn.effects.single() is StageMachine.Effect.Reject)
        assertEquals(prepared, noTurn.state)
        val changedIdentity = StageMachine.step(executing, StageEvent.WorkerAdmitted(base.copy(path = "/elsewhere")))
        assertTrue(changedIdentity.effects.single() is StageMachine.Effect.Reject)
        assertEquals(executing, changedIdentity.state)
    }

    @Test fun declaredSpaceIsClosedAndMatchesEveryTransition() = verifyStateSpace(
        StageMachine,
        states = mapOf(
            StageSpace.PREPARED to prepared,
            StageSpace.EXECUTING to executing,
            StageSpace.FAILED to step(executing, StageEvent.TransportFailed(PlanningIssue(IssueKind.TRANSIENT, "transport"), retry, workerFailed = true)),
            StageSpace.VERIFYING to step(executing, StageEvent.PlannerDecided(null)),
            StageSpace.INTEGRATING to step(executing, StageEvent.Captured("commit")),
            StageSpace.COMPLETE to step(executing, StageEvent.Completed),
            // The stage was stopped between two turns: there is a checkpoint to resume from.
            StageSpace.INTERRUPTED to interrupted,
            // The stage was stopped with an external command in flight: what it did is not known.
            StageSpace.UNCONFIRMED_TOOL to unconfirmed,
        ),
        inputs = mapOf(
            StageSpace.INSPECT_PREPARATION to StageEvent.InspectPreparation,
            StageSpace.INSPECT to StageEvent.Inspect,
            StageSpace.TURN_REQUESTED to StageEvent.TurnRequested(coordinatorAvailable = false, coordinationRecorded = false),
            StageSpace.WORKER_STARTING to StageEvent.WorkerStarting("implement", 10),
            StageSpace.CONFLICT_REQUESTED to StageEvent.ConflictRequested("/merge", 2),
            StageSpace.MERGE_STARTED to StageEvent.MergeStarted(integrated = false),
            StageSpace.ENGINE_RESOLVED to StageEvent.EngineResolved(CodingEngine.PI),
            StageSpace.WORKSPACE_PREPARED to StageEvent.WorkspacePrepared(base),
            StageSpace.ENGINE_OUTPUT to StageEvent.EngineOutput(CodingEvent.SessionStarted("engine-session"), StageRunTrack.WORK, emptyList()),
            StageSpace.RECONCILED to StageEvent.Reconciled(journalUnsettled = false),
            StageSpace.CHECKPOINT_OBSERVED to StageEvent.CheckpointObserved(CheckpointDrift.None),
            StageSpace.WORKER_ADMITTED to StageEvent.WorkerAdmitted(base),
            StageSpace.WORKER_TURN_ENDED to StageEvent.WorkerTurnEnded(20, "done"),
            StageSpace.WORKER_ACCEPTED to StageEvent.WorkerAccepted("snapshot", coordinatorAvailable = false),
            StageSpace.PLANNER_DECIDED to StageEvent.PlannerDecided(null),
            StageSpace.USER_ANSWERED to StageEvent.UserAnswered,
            StageSpace.EVENT_FIRED to StageEvent.EventFired,
            StageSpace.ACCEPTANCE_RECORDED to StageEvent.AcceptanceRecorded(AcceptanceRecord("run", "attempt", "snapshot",
                listOf(AcceptanceCriterion("check", "Check output")), emptyList())),
            StageSpace.VERIFICATION_DECIDED to StageEvent.VerificationDecided(Verdict(true, "pass"), retry),
            StageSpace.CAPTURED to StageEvent.Captured("commit"),
            StageSpace.CONFLICT_STARTED to StageEvent.ConflictStarted,
            StageSpace.CONFLICT_TURN_ENDED to StageEvent.ConflictTurnEnded,
            StageSpace.MERGE_FINISHED to StageEvent.MergeFinished(merged = true),
            StageSpace.COMPLETED to StageEvent.Completed,
            StageSpace.TRANSPORT_FAILED to StageEvent.TransportFailed(PlanningIssue(IssueKind.TRANSIENT, "transport"), retry, workerFailed = true),
            StageSpace.INTERRUPTED_CLEAN to StageEvent.Interrupted(executing.attempt, waiting = false, at = 30),
            StageSpace.INTERRUPTED_DURING_TOOL to StageEvent.Interrupted(duringTool, waiting = false, at = 30),
        ),
    )
}
