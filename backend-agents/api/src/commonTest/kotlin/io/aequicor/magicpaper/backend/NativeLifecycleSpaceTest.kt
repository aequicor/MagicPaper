package io.aequicor.magicpaper.backend

import io.aequicor.magicpaper.backend.NativeLifecycleMachine.Fact
import io.aequicor.magicpaper.backend.NativeLifecycleMachine.Intent
import io.aequicor.magicpaper.machine.PhaseId
import io.aequicor.magicpaper.machine.verifyStateSpace
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The representatives of [NativeLifecycleSpace], kept here rather than in the api so a shipped binary
 * carries no fixtures.
 *
 * Each one is built by running the machine from `initial`, never by constructing a state, which is what
 * the `internal constructor` on `State` is there to enforce; [step] refuses to continue past a step the
 * machine rejected, so a representative cannot be silently something else. Every store holds one run of
 * one session with one attempt, which is all the harness can drive; the targeted tests below cover what
 * a single record cannot: the ranking in [NativeLifecycleSpace.label] between several runs and
 * attempts, and each clause of [NativeLifecycleSpace.unknown].
 */
class NativeLifecycleSpaceTest {
    private val run = NativeRunRef("session", "request")
    private val attempt = NativeAttemptRef(run, 0)
    private val process = NativeProcessIdentity("receipt", 42, 123)
    private val proof = NativeNoDispatchProof(run, "proof", "epoch")
    private val recovery = NativeRecoveryAcknowledgement("recovery", attempt, "decision")
    private val noDispatch = NativeNoDispatchAcknowledgement("no-dispatch", proof, "decision")
    private val fresh = run.copy(requestId = "fresh")
    private val elsewhere = NativeRunRef("other", "request")

    // The two acknowledgements only take effect where they name the representatives' own attempt and
    // proof, and the run of both is the one every input names. They are derived from `run` above, so a
    // change to it moves all of them together; this fails if one is ever written out by hand instead.
    init {
        assertEquals(attempt, recovery.predecessor, "the recovery acknowledgement must name the representatives' attempt")
        assertEquals(run, proof.run, "the no-dispatch proof must name the representatives' run")
        assertEquals(proof, noDispatch.proof, "the no-dispatch acknowledgement must carry the representatives' proof")
        assertEquals(run.sessionId, fresh.sessionId, "a fresh request must share the representatives' session")
        assertTrue(elsewhere.sessionId != run.sessionId, "the other request must name another session")
    }

    private fun step(from: NativeLifecycleMachine.State, vararg inputs: NativeLifecycleMachine.Input) =
        inputs.fold(from) { current, input ->
            NativeLifecycleMachine.reduce(current, input).also { next ->
                assertTrue(next.effects.none { it is NativeLifecycleMachine.Effect.Reject }, "refused while building: $input")
            }.state
        }

    private fun refuses(state: NativeLifecycleMachine.State, input: NativeLifecycleMachine.Input) =
        NativeLifecycleMachine.reduce(state, input).effects.any { it is NativeLifecycleMachine.Effect.Reject }

    private fun ended(state: NativeLifecycleMachine.State) = step(state, Fact.RunFinished(run))
    private fun acknowledged(state: NativeLifecycleMachine.State) = step(state, Intent.Acknowledge(recovery))

    private val empty = NativeLifecycleMachine.initial()
    private val admitted = step(empty, Intent.Begin(run))
    private val launched = step(admitted, Fact.LaunchRequested(run))
    private val attached = step(launched, Fact.Attached(attempt, process))
    private val delivered = step(attached, Fact.DeliveryRequested(attempt, NativeDelivery.PI_STDIN))
    private val outcomeKnown = step(delivered, Fact.Terminal(attempt, NativeOutcome.SUCCEEDED))
    private val lostUndelivered = step(attached, Fact.Stopping(attempt))
    private val lostDelivered = step(delivered, Fact.Stopping(attempt))
    private val stoppedUndelivered = step(launched, Fact.Stopped(attempt))
    private val stoppedUnknown = step(delivered, Fact.Stopped(attempt))
    private val stoppedSucceeded = step(outcomeKnown, Fact.Stopped(attempt))
    private val stoppedFailed = step(delivered, Fact.Terminal(attempt, NativeOutcome.FAILED), Fact.Stopped(attempt))
    // A stopped attempt that owns a process goes back to `UNKNOWN` when it is reported unavailable, and
    // keeps what was acknowledged.
    private val lostAcknowledged = step(acknowledged(stoppedUnknown), Fact.Unavailable(attempt))
    private val unlaunched = ended(admitted)
    private val proved = step(unlaunched, Fact.NoDispatchConfirmed(proof))
    private val decided = step(proved, Intent.AcknowledgeNoDispatch(noDispatch))
    private val closingUnstopped = step(attached, Intent.Close)
    private val closingDrainedRunActive = step(stoppedSucceeded, Intent.Close)
    private val closingDrained = step(empty, Intent.Close)
    private val closed = step(closingDrained, Fact.Closed)

    private val states: Map<PhaseId, NativeLifecycleMachine.State> = mapOf(
        NativeLifecycleSpace.EMPTY to empty,
        NativeLifecycleSpace.ADMITTED to admitted,
        NativeLifecycleSpace.LAUNCHED to launched,
        NativeLifecycleSpace.ATTACHED to attached,
        NativeLifecycleSpace.DELIVERED to delivered,
        NativeLifecycleSpace.OUTCOME_KNOWN_UNSTOPPED to outcomeKnown,
        NativeLifecycleSpace.STOP_UNCONFIRMED_UNDELIVERED to lostUndelivered,
        NativeLifecycleSpace.STOP_UNCONFIRMED_DELIVERED to lostDelivered,
        NativeLifecycleSpace.STOPPED_UNDELIVERED to stoppedUndelivered,
        NativeLifecycleSpace.STOPPED_OUTCOME_UNKNOWN to stoppedUnknown,
        NativeLifecycleSpace.STOPPED_SUCCEEDED to stoppedSucceeded,
        NativeLifecycleSpace.STOPPED_FAILED to stoppedFailed,
        NativeLifecycleSpace.ACKNOWLEDGED_UNDELIVERED to acknowledged(stoppedUndelivered),
        NativeLifecycleSpace.ACKNOWLEDGED_OUTCOME_UNKNOWN to acknowledged(stoppedUnknown),
        NativeLifecycleSpace.ACKNOWLEDGED_SUCCEEDED to acknowledged(stoppedSucceeded),
        NativeLifecycleSpace.ACKNOWLEDGED_FAILED to acknowledged(stoppedFailed),
        NativeLifecycleSpace.STOP_UNCONFIRMED_DELIVERED_ACKNOWLEDGED to lostAcknowledged,
        NativeLifecycleSpace.ENDED_UNLAUNCHED to unlaunched,
        NativeLifecycleSpace.NO_DISPATCH_PROVED to proved,
        NativeLifecycleSpace.NO_DISPATCH_ACKNOWLEDGED to decided,
        NativeLifecycleSpace.ENDED_LAUNCHED to ended(launched),
        NativeLifecycleSpace.ENDED_ATTACHED to ended(attached),
        NativeLifecycleSpace.ENDED_DELIVERED to ended(delivered),
        NativeLifecycleSpace.ENDED_OUTCOME_KNOWN_UNSTOPPED to ended(outcomeKnown),
        NativeLifecycleSpace.ENDED_STOP_UNCONFIRMED_UNDELIVERED to ended(lostUndelivered),
        NativeLifecycleSpace.ENDED_STOP_UNCONFIRMED_DELIVERED to ended(lostDelivered),
        NativeLifecycleSpace.ENDED_STOPPED_UNDELIVERED to ended(stoppedUndelivered),
        NativeLifecycleSpace.ENDED_STOPPED_OUTCOME_UNKNOWN to ended(stoppedUnknown),
        NativeLifecycleSpace.ENDED_STOPPED_SUCCEEDED to ended(stoppedSucceeded),
        NativeLifecycleSpace.ENDED_STOPPED_FAILED to ended(stoppedFailed),
        NativeLifecycleSpace.ENDED_ACKNOWLEDGED_UNDELIVERED to ended(acknowledged(stoppedUndelivered)),
        NativeLifecycleSpace.ENDED_ACKNOWLEDGED_OUTCOME_UNKNOWN to ended(acknowledged(stoppedUnknown)),
        NativeLifecycleSpace.ENDED_ACKNOWLEDGED_SUCCEEDED to ended(acknowledged(stoppedSucceeded)),
        NativeLifecycleSpace.ENDED_ACKNOWLEDGED_FAILED to ended(acknowledged(stoppedFailed)),
        NativeLifecycleSpace.ENDED_STOP_UNCONFIRMED_DELIVERED_ACKNOWLEDGED to ended(lostAcknowledged),
        NativeLifecycleSpace.CLOSING_UNSTOPPED to closingUnstopped,
        NativeLifecycleSpace.CLOSING_DRAINED_RUN_ACTIVE to closingDrainedRunActive,
        NativeLifecycleSpace.CLOSING_DRAINED to closingDrained,
        NativeLifecycleSpace.CLOSED to closed,
        NativeLifecycleSpace.PERSISTENCE_UNKNOWN to step(attached, Fact.PersistenceUnknown),
    )

    private fun label(state: NativeLifecycleMachine.State) = NativeLifecycleSpace.label(state)

    /**
     * The harness only drives stores of one run, so it cannot see how [NativeLifecycleSpace.label] ranks a
     * store that holds several. Reading the first run instead of the newest would leave it green: the
     * newest run is the one the next fact names, and the older ones only reach a position through history.
     */
    @Test fun theNewestRunNamesThePositionOfAStoreHoldingSeveral() {
        val older = ended(acknowledged(stoppedSucceeded))
        assertEquals(NativeLifecycleSpace.ENDED_ACKNOWLEDGED_SUCCEEDED, label(older))
        val second = step(older, Intent.Begin(fresh, recovery))
        assertEquals(NativeLifecycleSpace.ADMITTED, label(second), "a run admitted after the acknowledged one")
        assertEquals(NativeLifecycleSpace.ENDED_UNLAUNCHED, label(step(second, Fact.RunFinished(fresh))))
        // The newest run of another session names the position too: the map holds runs of every session.
        assertEquals(NativeLifecycleSpace.ADMITTED, label(step(delivered, Intent.Begin(elsewhere))))
    }

    /** Continuing a run adds an attempt, and the newest one is what `Attached` and `Terminal` will name. */
    @Test fun theNewestAttemptOfARunNamesItsPosition() {
        val continued = step(stoppedSucceeded, Fact.LaunchRequested(run))
        assertEquals(2, continued.runs.getValue(run).attempts.size)
        assertEquals(NativeLifecycleSpace.LAUNCHED, label(continued), "the second attempt was not started, the first was stopped")
        val second = NativeAttemptRef(run, 1)
        assertEquals(NativeLifecycleSpace.ATTACHED, label(step(continued, Fact.Attached(second, process))))
        assertEquals(NativeLifecycleSpace.DELIVERED,
            label(step(continued, Fact.Attached(second, process), Fact.DeliveryRequested(second, NativeDelivery.PI_STDIN))))
    }

    /**
     * A closing store is named by an attempt still unstopped first, then by the flag, then by an active
     * run. The representatives isolate one fence each but none holds two runs, so none of these orders
     * is visible to the harness, and each reversal would stay green.
     */
    @Test fun aClosingStoreIsNamedByWhatIsUnstoppedThenByTheFlagThenByAnActiveRun() {
        // The flag outranks an active run: `closed` says the engine is done, whatever else is recorded.
        val closedOverActiveRun = step(stoppedSucceeded, Intent.Close, Fact.Closed)
        assertTrue(closedOverActiveRun.runs.getValue(run).active)
        assertEquals(NativeLifecycleSpace.CLOSED, label(closedOverActiveRun))
        // An attempt unstopped outranks the flag. `Unavailable` moves a stopped attempt that owns a process
        // back to `UNKNOWN` after `Closed` was accepted, and the store then refuses `Closed` again.
        val reopened = step(step(stoppedUnknown, Intent.Close, Fact.Closed), Fact.Unavailable(attempt))
        assertTrue(reopened.closed)
        assertEquals(NativeLifecycleSpace.CLOSING_UNSTOPPED, label(reopened))
        assertTrue(refuses(reopened, Fact.Closed))
        // The unstopped attempt may belong to an older run, of another session, than the newest one.
        assertEquals(NativeLifecycleSpace.CLOSING_UNSTOPPED, label(step(attached, Intent.Begin(elsewhere), Intent.Close)))
        // An active run may be an older one as well, while the newest has ended.
        assertEquals(NativeLifecycleSpace.CLOSING_DRAINED_RUN_ACTIVE,
            label(step(stoppedSucceeded, Intent.Begin(elsewhere), Fact.RunFinished(elsewhere), Intent.Close)))
        // Attempts that are all stopped, in a run that ended, leave nothing to wait for.
        assertEquals(NativeLifecycleSpace.CLOSING_DRAINED, label(step(ended(stoppedSucceeded), Intent.Close)))
    }

    /**
     * Unconfirmed persistence fences before anything else, a closing or closed store and an empty one
     * included. The representative reaches it only from an open store that holds a run, so it cannot
     * tell this order from the reverse.
     */
    @Test fun unconfirmedPersistenceOutranksAClosingAClosedAndAnEmptyStore() {
        assertEquals(NativeLifecycleSpace.PERSISTENCE_UNKNOWN, label(step(closingDrained, Fact.PersistenceUnknown)))
        assertEquals(NativeLifecycleSpace.PERSISTENCE_UNKNOWN, label(step(closingUnstopped, Fact.PersistenceUnknown)))
        assertEquals(NativeLifecycleSpace.PERSISTENCE_UNKNOWN, label(step(closed, Fact.PersistenceUnknown)))
        assertEquals(NativeLifecycleSpace.PERSISTENCE_UNKNOWN, label(step(empty, Fact.PersistenceUnknown)))
    }

    /** Position and predicate agree exactly at the positions that hold an unconfirmed outcome or process. */
    @Test fun unknownIsExactlyAnUnconfirmedOutcomeAnUnconfirmedTerminationOrUnconfirmedPersistence() {
        val unknown = setOf(
            NativeLifecycleSpace.DELIVERED, NativeLifecycleSpace.STOP_UNCONFIRMED_UNDELIVERED,
            NativeLifecycleSpace.STOP_UNCONFIRMED_DELIVERED, NativeLifecycleSpace.STOPPED_OUTCOME_UNKNOWN,
            NativeLifecycleSpace.ACKNOWLEDGED_OUTCOME_UNKNOWN, NativeLifecycleSpace.STOP_UNCONFIRMED_DELIVERED_ACKNOWLEDGED,
            NativeLifecycleSpace.ENDED_DELIVERED, NativeLifecycleSpace.ENDED_STOP_UNCONFIRMED_UNDELIVERED,
            NativeLifecycleSpace.ENDED_STOP_UNCONFIRMED_DELIVERED, NativeLifecycleSpace.ENDED_STOPPED_OUTCOME_UNKNOWN,
            NativeLifecycleSpace.ENDED_ACKNOWLEDGED_OUTCOME_UNKNOWN,
            NativeLifecycleSpace.ENDED_STOP_UNCONFIRMED_DELIVERED_ACKNOWLEDGED, NativeLifecycleSpace.PERSISTENCE_UNKNOWN,
        )
        for ((position, state) in states) assertEquals(position in unknown, NativeLifecycleSpace.unknown(state), position.name)
    }

    /**
     * `unknown` reads every run of the store, the position only the newest, and they part company here:
     * a run of another session, newest and clean, beside an older one whose delivery was never confirmed.
     * Reading the newest run alone would fix the disagreement and hide the unconfirmed outcome.
     */
    @Test fun anUnconfirmedOutcomeInAnOlderRunIsStillUnknownBesideACleanNewestOne() {
        val beside = step(delivered, Intent.Begin(elsewhere), Fact.RunFinished(elsewhere))
        assertEquals(NativeLifecycleSpace.ENDED_UNLAUNCHED, label(beside))
        assertTrue(NativeLifecycleSpace.unknown(beside))
        // The same for an unconfirmed termination with nothing sent, which no outcome shows.
        val lostBeside = step(lostUndelivered, Intent.Begin(elsewhere), Fact.RunFinished(elsewhere))
        assertEquals(NativeLifecycleSpace.ENDED_UNLAUNCHED, label(lostBeside))
        assertTrue(NativeLifecycleSpace.unknown(lostBeside))
        assertFalse(NativeLifecycleSpace.unknown(step(stoppedSucceeded, Intent.Begin(elsewhere))), "a stopped, known attempt is not in doubt")
    }

    /**
     * The matrix's `Begin` is a fresh request in the representatives' own session, because [name] cannot
     * tell a repeated request or another session from the input alone. Both are held to the machine here,
     * against every representative, instead.
     */
    @Test fun beginNamesOneRequestOfOneSessionWhichTheMatrixCannotSay() {
        for ((position, state) in states) {
            // A request the store already holds is refused wherever a fresh one is admitted.
            if (state.runs.isNotEmpty() && !refuses(state, Intent.Begin(fresh))) {
                assertTrue(refuses(state, Intent.Begin(run)), "a repeated request at ${position.name}")
            }
            // Another session shares nothing of the representatives' history: only a fence refuses it.
            val fenced = state.persistenceUnknown || state.closing || state.closed
            assertEquals(fenced, refuses(state, Intent.Begin(elsewhere)), "another session at ${position.name}")
        }
        assertFalse(refuses(empty, Intent.Begin(run)), "no run to repeat in an empty store")
    }

    /**
     * A decision is spent by the run it admits, and a store that spent it stands at `ended-unlaunched`,
     * whose row admits a plain `Begin` and refuses both decisions. The older run stays in the map and can
     * still block: the newest run cannot show it, and neither can the position.
     */
    @Test fun aSpentDecisionLeavesTheStoreWhereAFreshRunIsAdmittedAndAnOlderAttemptCanStillBlock() {
        val later = run.copy(requestId = "later")
        val recovered = step(ended(acknowledged(stoppedUnknown)), Intent.Begin(fresh, recovery), Fact.RunFinished(fresh))
        assertEquals(NativeLifecycleSpace.ENDED_UNLAUNCHED, label(recovered))
        assertFalse(refuses(recovered, Intent.Begin(later)))
        assertTrue(refuses(recovered, Intent.Begin(later, recovery)), "the recovery acknowledgement was already spent")
        val blocked = step(recovered, Fact.Unavailable(attempt))
        assertEquals(NativeLifecycleSpace.ENDED_UNLAUNCHED, label(blocked), "the older attempt is invisible to the position")
        assertTrue(refuses(blocked, Intent.Begin(later)), "yet its unconfirmed termination blocks the session")
        assertTrue(NativeLifecycleSpace.unknown(blocked))

        val forgone = step(decided, Intent.Begin(fresh, noDispatchAcknowledgement = noDispatch), Fact.RunFinished(fresh))
        assertEquals(NativeLifecycleSpace.ENDED_UNLAUNCHED, label(forgone))
        assertFalse(refuses(forgone, Intent.Begin(later)))
        assertTrue(refuses(forgone, Intent.Begin(later, noDispatchAcknowledgement = noDispatch)), "the no-dispatch decision was already spent")
    }

    /**
     * `BeginBothDecisions` is refused in every representative, but there another guard refuses it first:
     * a single-run store cannot hold a pending recovery acknowledgement and a pending no-dispatch one at
     * once. Two runs can, and only then does the guard against both decide alone, so the matrix cannot
     * show that removing it changes anything.
     */
    @Test fun aBeginCarryingBothDecisionsIsRefusedEvenWhereBothArePending() {
        val second = run.copy(requestId = "second")
        val secondProof = NativeNoDispatchProof(second, "proof-2", "epoch")
        val secondDecision = NativeNoDispatchAcknowledgement("no-dispatch-2", secondProof, "decision")
        // The first run's attempt is acknowledged after the second run was admitted, so that acknowledgement
        // is pending beside the second run's own pending no-dispatch decision.
        val both = step(ended(stoppedSucceeded), Intent.Begin(second), Fact.RunFinished(second),
            Fact.NoDispatchConfirmed(secondProof), Intent.AcknowledgeNoDispatch(secondDecision), Intent.Acknowledge(recovery))
        assertEquals(NativeLifecycleSpace.NO_DISPATCH_ACKNOWLEDGED, label(both))
        val later = run.copy(requestId = "later")
        assertTrue(refuses(both, Intent.Begin(later, recovery, secondDecision)))
        // The pending no-dispatch decision must be carried; the acknowledged outcome was itself reported,
        // so the no-dispatch decision alone admits the next run, and plain still refuses on it.
        assertTrue(refuses(both, Intent.Begin(later, recovery)))
        assertFalse(refuses(both, Intent.Begin(later, noDispatchAcknowledgement = secondDecision)))
        assertTrue(refuses(both, Intent.Begin(later)))
    }

    /** A decision recorded over an outcome the engine reported holds nothing: only an outcome nobody
     * can know demands exactly its decision be carried by the session's next admission. */
    @Test fun aDecisionAboutAnOutcomeTheEngineReportedDoesNotFenceTheNextAdmission() {
        for (reported in listOf(stoppedSucceeded, stoppedFailed, stoppedUndelivered)) {
            val recorded = ended(acknowledged(reported))
            assertFalse(refuses(recorded, Intent.Begin(fresh)), "a reported outcome needs nobody's decision carried")
            assertFalse(refuses(recorded, Intent.Begin(fresh, recovery)), "carrying the recorded decision remains valid")
        }
        val uncertain = ended(acknowledged(stoppedUnknown))
        assertTrue(refuses(uncertain, Intent.Begin(fresh)))
        assertFalse(refuses(uncertain, Intent.Begin(fresh, recovery)))
        val spent = step(uncertain, Intent.Begin(fresh, recovery), Fact.RunFinished(fresh))
        assertTrue(refuses(spent, Intent.Begin(run.copy(requestId = "later"), recovery)), "the carried decision was spent")
    }

    /** The contract is adopted by the machine object, which is no part of an input, so no journal entry moves. */
    @Test fun adoptingTheContractLeavesTheJournalFormatAlone() {
        val json = kotlinx.serialization.json.Json
        assertEquals(
            """{"type":"io.aequicor.magicpaper.backend.NativeLifecycleMachine.Intent.Begin","run":{"sessionId":"session","requestId":"request"}}""",
            json.encodeToString(NativeLifecycleMachine.Input.serializer(), Intent.Begin(run)),
        )
        assertIs<Fact.Terminal>(json.decodeFromString(NativeLifecycleMachine.Input.serializer(),
            json.encodeToString(NativeLifecycleMachine.Input.serializer(), Fact.Terminal(attempt, NativeOutcome.FAILED))))
        assertEquals("native-lifecycle", NativeLifecycleMachine.id.name)
    }

    @Test fun declaredSpaceIsClosedAndMatchesEveryTransition() = verifyStateSpace(
        NativeLifecycleMachine,
        states = states,
        inputs = mapOf(
            NativeLifecycleSpace.BEGIN to Intent.Begin(fresh),
            NativeLifecycleSpace.BEGIN_RECOVERING to Intent.Begin(fresh, recovery),
            NativeLifecycleSpace.BEGIN_AFTER_NO_DISPATCH to Intent.Begin(fresh, noDispatchAcknowledgement = noDispatch),
            NativeLifecycleSpace.BEGIN_BOTH_DECISIONS to Intent.Begin(fresh, recovery, noDispatch),
            NativeLifecycleSpace.BEGIN_BLANK to Intent.Begin(NativeRunRef("", "")),
            NativeLifecycleSpace.CANCEL to Intent.Cancel(run),
            NativeLifecycleSpace.STOP to Intent.Stop(attempt),
            NativeLifecycleSpace.ACKNOWLEDGE to Intent.Acknowledge(recovery),
            NativeLifecycleSpace.ACKNOWLEDGE_NO_DISPATCH to Intent.AcknowledgeNoDispatch(noDispatch),
            NativeLifecycleSpace.CLOSE to Intent.Close,
            NativeLifecycleSpace.LAUNCH_REQUESTED to Fact.LaunchRequested(run),
            NativeLifecycleSpace.ATTACHED_FACT to Fact.Attached(attempt, process),
            NativeLifecycleSpace.DELIVERY_REQUESTED to Fact.DeliveryRequested(attempt, NativeDelivery.PI_STDIN),
            NativeLifecycleSpace.DELIVERY_REQUESTED_OTHER_STAGE to Fact.DeliveryRequested(attempt, NativeDelivery.CODEX_TURN),
            NativeLifecycleSpace.ACCEPTED to Fact.Accepted(attempt, "thread", "turn"),
            NativeLifecycleSpace.TERMINAL_SUCCEEDED to Fact.Terminal(attempt, NativeOutcome.SUCCEEDED),
            NativeLifecycleSpace.TERMINAL_FAILED to Fact.Terminal(attempt, NativeOutcome.FAILED),
            NativeLifecycleSpace.TERMINAL_UNPROVEN to Fact.Terminal(attempt, NativeOutcome.UNKNOWN),
            NativeLifecycleSpace.STOPPING to Fact.Stopping(attempt),
            NativeLifecycleSpace.STOPPED to Fact.Stopped(attempt),
            NativeLifecycleSpace.UNAVAILABLE to Fact.Unavailable(attempt),
            NativeLifecycleSpace.RUN_FINISHED to Fact.RunFinished(run),
            NativeLifecycleSpace.NO_DISPATCH_CONFIRMED to Fact.NoDispatchConfirmed(proof),
            NativeLifecycleSpace.NEIGHBOUR_MISSING to Fact.NeighbourMissing(run, "key"),
            NativeLifecycleSpace.CLOSED_FACT to Fact.Closed,
            NativeLifecycleSpace.RESTORED to Fact.Restored,
            NativeLifecycleSpace.PERSISTENCE_UNKNOWN_FACT to Fact.PersistenceUnknown,
        ),
    )
}
