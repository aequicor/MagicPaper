package io.aequicor.magicpaper.domain

import io.aequicor.magicpaper.machine.Branch
import io.aequicor.magicpaper.machine.EffectId
import io.aequicor.magicpaper.machine.InputId
import io.aequicor.magicpaper.machine.InputSpec
import io.aequicor.magicpaper.machine.PhaseId
import io.aequicor.magicpaper.machine.StateSpace

/**
 * The state space of [SessionOrganismMachine], declared so it can be read without running anything: twenty-one positions,
 * fifty-three inputs and six effects.
 *
 * The state is an aggregate — one organism holding sessions, results, deliveries, operations, integrations, auxiliary
 * runs and interventions — and it has no phase of its own. A position is what acceptance depends on, read in the order
 * the reducer evaluates its guards:
 *
 *  - three fences that outrank everything: an outcome the journal could not confirm (`persistence-unknown`, which refuses
 *    everything but its own fact), a deleted organism (`deleted`; deleting history and stopping the organism by the user are
 *    one fence), and an organism nobody has adopted yet (`empty`, which refuses everything but the two inputs that create
 *    one and the persistence fact);
 *  - an intervention the immunity session proposed or the user accepted (`proposed`, `intervening`). Both stand on a
 *    diagnosis, and a diagnosis always leaves a quarantine behind it;
 *  - a quarantine nobody has confirmed, which outranks the root wherever it is held and is named by what the root can still do:
 *    `quarantined-unknown` when the root's own run is unknown (its next turn is refused with a quarantine, which is what opens the
 *    recovery dialog, and a stop cannot be finished), `quarantined-branch` when a child holds it and the root goes on working, and
 *    `quarantined` when the root's run has ended. Then a session whose run is unknown, named the same way: `unknown-branch`
 *    when a child's run is unknown and the root goes on working, and `unknown` when the root's own run is unknown or the root cannot
 *    work;
 *  - the lifecycle of the root session, which is what `actor()` checks before anything else: an archived root, a root that
 *    is stopping, a root that finished (`finished`, desired state RUN) and one that was stopped (`stopped`). These refuse
 *    the inputs that need a root that accepts work, and differ in what they accept beside that: an archived root can be
 *    restored, a finished one can be run again, a stopped one only reopened by a user turn;
 *  - only while the root still accepts work, the records around it: an integration in flight (`integrating`), an
 *    unsettled auxiliary run (`auxiliary`), a result the parent accepted (`integrable`), a result nobody reviewed
 *    (`result`), a child (`child`), and at last the root alone, `running` or `pending`.
 *
 * One reducer input class is several input names where its payload changes acceptance: `Command` is twelve names, one per
 * action, because each has its own guards; `Observe` is three, because reporting a run needs a root that accepts work,
 * reporting a run settled needs every child and auxiliary run settled, and any other report needs neither.
 *
 * Effects are the ones [SessionOrganismMachine.step] bridges: the five outputs of a transition, each under its own name,
 * and `Reject`. This machine's `Transition` keeps its outputs and its refusal apart and is unchanged, so refusal is
 * recognised by [rejected] on the value, never by a shared type.
 *
 * "Accepted" means not refused, and several inputs are accepted as no-ops: archiving a session that is not ready, resolving
 * a quarantine nobody holds, proposing an intervention with no diagnosis behind it, adopting an organism that is already
 * adopted, and a limit change, a stop or a dismissal addressed to an organism whose history was deleted. The matrix does not
 * say that the state changed.
 *
 * What the declaration cannot express, and leaves to the tests around it:
 *  - identity. Every input names an organism, a session and usually a generation, and the matrix speaks about the one
 *    session `root` under generation 1 and the ids the representatives carry. A stale generation or version, another
 *    organism or project, an operation id already used with other arguments, a blank stamp (which refuses *everything*,
 *    even the persistence fact, and is pinned by a test rather than by a position): all refuse, and none is a position;
 *  - which record of many. The harness drives one record of each kind, so a position names the organism by its most
 *    pressing fact and an input addresses exactly one record. `child` is an organism holding several children in different
 *    states (two ordinary ones that work, and three plan workers: one running, one stopping, one stopped), and `CommandSend`
 *    reads as accepted there only because one of them takes work. A settled child, a second result, a delivery already
 *    processed: none is named. A record that does not exist is not refused with a `Reject` at all: several inputs escape
 *    `reduce` as a `NoSuchElementException`
 *    (`getValue`, `first` or `single` on a missing session, delivery, auxiliary run or proposal), and the test that drives
 *    the space wraps the machine to turn exactly that exception into the refusal its guard clearly means;
 *  - the root beside the record. Records are named only while the root accepts work, and their rows are read off an organism
 *    whose root is running. Beside a root that is only pending they are the same position, and `Charge`, which needs a run
 *    that started, is refused there though the row accepts it. Beside a root that finished, stopped, is stopping, is unknown or
 *    archived the organism is named by the root, and the record's own inputs (an acknowledgement, a result, a review) read as
 *    refused, because the representative of that position holds no record; the reducer would accept some of them. A root
 *    waiting on the user reads as `running`, which the reducer treats alike;
 *  - the deleted organism. Deleting history moves every session's generation on, so its row is read off an organism that every
 *    input carrying the old generation is refused by, for that reason and not for the position; the matrix cannot tell the two
 *    apart. What it can say is pinned by a test that deletes an organism from every position: no input that carries no
 *    generation changes it, except `FinishStop` (which replays a deletion that crashed half way), `FinishImmunityIntervention`
 *    (which closes an intervention whose action was the deletion, and so runs after it) and the fence on unconfirmed persistence;
 *  - the root beside a quarantine or an unknown run. A quarantine held while the root is stopping or archived is `quarantined`,
 *    which is read off a root whose run ended, and `FinishStop` there differs from the row. An unknown run held by a child under a
 *    root that is stopping or archived is `unknown`, which is read off a root whose own run is unknown: `ReconcileInterruptedRun`
 *    (it addresses the root), `ObserveSettled` (the child is not settled) and, under an archived root, `CommandSignal` and
 *    `ResolveSessionQuarantine` differ from the row, and `Charge` and `RecordResult` are accepted where it refuses them;
 *  - who clears an unknown run. Asking for a stop does not confirm one, and the writers disagree about it: a stop the user or a
 *    parent asks for (`RequestUserStop`, `CommandStop`, `CommandPause`, `CommandArchive`) moves the run to `stopping`, after which a
 *    report of completion settles it and `unknown()` is false; a stop the application raises on failure, and one that carries a
 *    quarantine, keep it unknown until the runtime reports it stopped. The position after a stop is `stopping` either way, so the
 *    row cannot show the difference. It is a recorded debt, not a decision, and is pinned by
 *    `aStopRequestClearsAnUnknownRunOnlyWhereNoQuarantineAndNoFailureKeepsIt`;
 *  - the immunity session. Every organism that belongs to a plan, a stage or planning has one, and the representatives are built
 *    on such a root wherever a diagnosis is needed, and on one that has none everywhere else. It never starts in practice, and
 *    `DeleteHistoryByUser` counts it as settled for that reason, as `RequestUserStop` does; one that is running is not, and
 *    refuses a deletion that the row accepts;
 *  - limits. The representatives set none (every ceiling is unbounded), so a session, depth, token, queue, retry, duration
 *    or context limit refuses admissions the matrix accepts, in every position;
 *  - the payload behind a name. A pending or completed report of a session that is not meant to run again is recorded as a
 *    stop, or as an unknown when the run was unknown or a quarantine is held; any other report of the current generation
 *    replaces the state of a session whose run was unknown, and so does a completion while the session is still meant to run.
 *    `BeginRun` that moves a generation on records no previous generation, because the run continues the session's
 *    conversation; only a restart names the generation it replaced, and the coding projection ends that generation's run
 *    and conversation by it.
 *    `DeleteHistoryByUser` is the whole organism, not one session; `RequestUserStop` archives or not, `SetArchiveVisibility`
 *    hides or shows, `CommandReviewResult` accepts or rejects, and a retry admission (`AdmitPlanWorker` with an
 *    authorization) is not the fresh one the representative is;
 *  - what a name covers beyond its row. `AuthorizePlanRetry` is accepted everywhere: it answers null for a session that is
 *    missing or has not stopped, and only `child`, where a worker has stopped, authorizes. `ResolveSessionQuarantine` is
 *    accepted wherever nothing is held, which is not the same as resolving anything.
 *
 * [label] is total for every organism the machine builds: it returns null only for one that lacks its zygote, which an
 * import refuses. It names the most pressing fact, so an organism carrying two is named by the first in the order above;
 * that order is what the test that sweeps every pair pins, since the harness only sees one fact at a time.
 *
 * `unknown(state)` and the position can disagree. It is true for unconfirmed persistence, any session whose run is unknown or
 * whose workspace outcome is, any operation, integration, auxiliary run or intervention in that state, and any quarantine
 * nobody confirmed. Four of those have no position of their own: an operation, an integration, a workspace and an
 * auxiliary run whose outcome is unknown are named by the root beside them, and unknown() still reports them.
 */
object SessionOrganismSpace : StateSpace<SessionOrganismMachine.State, SessionOrganismMachine.Input, SessionOrganismMachine.Effect> {
    val EMPTY = PhaseId("empty")
    val PERSISTENCE_UNKNOWN = PhaseId("persistence-unknown")
    val DELETED = PhaseId("deleted")
    val INTERVENING = PhaseId("intervening")
    val PROPOSED = PhaseId("proposed")
    val QUARANTINED = PhaseId("quarantined")
    val QUARANTINED_UNKNOWN = PhaseId("quarantined-unknown")
    val QUARANTINED_BRANCH = PhaseId("quarantined-branch")
    val UNKNOWN = PhaseId("unknown")
    val UNKNOWN_BRANCH = PhaseId("unknown-branch")
    val ARCHIVED = PhaseId("archived")
    val STOPPING = PhaseId("stopping")
    val FINISHED = PhaseId("finished")
    val STOPPED = PhaseId("stopped")
    val INTEGRATING = PhaseId("integrating")
    val AUXILIARY = PhaseId("auxiliary")
    val INTEGRABLE = PhaseId("integrable")
    val RESULT = PhaseId("result")
    val CHILD = PhaseId("child")
    val RUNNING = PhaseId("running")
    val PENDING = PhaseId("pending")

    val SET_ARCHIVE_VISIBILITY = InputId("SetArchiveVisibility")
    val APPLY_LIMITS = InputId("ApplyLimits")
    val ADMIT_INTEGRATION = InputId("AdmitIntegration")
    val RENAME_BY_USER = InputId("RenameByUser")
    val CHECK = InputId("Check")
    val AUTHORIZE_PLAN_RETRY = InputId("AuthorizePlanRetry")
    val ADMIT_PLAN_WORKER = InputId("AdmitPlanWorker")
    val CHANGE_ROOT_MODE = InputId("ChangeRootMode")
    val PREPARE_USER_TURN = InputId("PrepareUserTurn")
    val BEGIN_RUN = InputId("BeginRun")
    val REQUEST_USER_STOP = InputId("RequestUserStop")
    val RESTORE_BY_USER = InputId("RestoreByUser")
    val COMMAND_CREATE = InputId("CommandCreate")
    val COMMAND_SEND = InputId("CommandSend")
    val COMMAND_ROUTE = InputId("CommandRoute")
    val COMMAND_REVIEW_RESULT = InputId("CommandReviewResult")
    val COMMAND_WAIT = InputId("CommandWait")
    val COMMAND_STOP = InputId("CommandStop")
    val COMMAND_PAUSE = InputId("CommandPause")
    val COMMAND_QUARANTINE = InputId("CommandQuarantine")
    val COMMAND_ARCHIVE = InputId("CommandArchive")
    val COMMAND_RESTORE = InputId("CommandRestore")
    val COMMAND_RENAME = InputId("CommandRename")
    val COMMAND_SIGNAL = InputId("CommandSignal")
    val BEGIN_AUXILIARY = InputId("BeginAuxiliary")
    val DELETE_HISTORY_BY_USER = InputId("DeleteHistoryByUser")
    val PROPOSE_IMMUNITY_INTERVENTIONS = InputId("ProposeImmunityInterventions")
    val ACCEPT_IMMUNITY_INTERVENTION = InputId("AcceptImmunityIntervention")
    val DISMISS_IMMUNITY_INTERVENTION = InputId("DismissImmunityIntervention")
    val INSPECT_SIGNALS = InputId("InspectSignals")
    val CHECKPOINT_INTEGRATION = InputId("CheckpointIntegration")
    val ADOPT = InputId("Adopt")
    val REQUEST_FAILURE_STOP = InputId("RequestFailureStop")
    val RECONCILE_AND_AUTHORIZE_PLAN_RETRY = InputId("ReconcileAndAuthorizePlanRetry")
    val ACCEPT_PLAN_RESULT = InputId("AcceptPlanResult")
    val RECORD_WORKSPACE = InputId("RecordWorkspace")
    val RESOLVE_SESSION_QUARANTINE = InputId("ResolveSessionQuarantine")
    val RECONCILE_INTERRUPTED_RUN = InputId("ReconcileInterruptedRun")
    val FINISH_STOP = InputId("FinishStop")
    val OBSERVE_RUNNING = InputId("ObserveRunning")
    val OBSERVE_SETTLED = InputId("ObserveSettled")
    val OBSERVE_OTHER = InputId("ObserveOther")
    val ACKNOWLEDGE = InputId("Acknowledge")
    val CHARGE = InputId("Charge")
    val CHARGE_AUXILIARY = InputId("ChargeAuxiliary")
    val FINISH_AUXILIARY = InputId("FinishAuxiliary")
    val RESTORED = InputId("Restored")
    val RECORD_RESULT = InputId("RecordResult")
    val FINISH_IMMUNITY_INTERVENTION = InputId("FinishImmunityIntervention")
    val QUARANTINE_FACT = InputId("Quarantine")
    val LEGACY_IMPORTED = InputId("LegacyImported")
    val LIMIT_POLICY_MIGRATED = InputId("LimitPolicyMigrated")
    val PERSISTENCE_UNKNOWN_FACT = InputId("PersistenceUnknown")

    override val phases = listOf(
        EMPTY, PERSISTENCE_UNKNOWN, DELETED, INTERVENING, PROPOSED, QUARANTINED, QUARANTINED_UNKNOWN, QUARANTINED_BRANCH,
        UNKNOWN, UNKNOWN_BRANCH, ARCHIVED, STOPPING, FINISHED, STOPPED, INTEGRATING,
        AUXILIARY, INTEGRABLE, RESULT, CHILD, RUNNING, PENDING,
    )

    override val inputs = listOf(
        InputSpec(SET_ARCHIVE_VISIBILITY, Branch.INTENT), InputSpec(APPLY_LIMITS, Branch.INTENT),
        InputSpec(ADMIT_INTEGRATION, Branch.INTENT), InputSpec(RENAME_BY_USER, Branch.INTENT),
        InputSpec(CHECK, Branch.INTENT), InputSpec(AUTHORIZE_PLAN_RETRY, Branch.INTENT),
        InputSpec(ADMIT_PLAN_WORKER, Branch.INTENT), InputSpec(CHANGE_ROOT_MODE, Branch.INTENT),
        InputSpec(PREPARE_USER_TURN, Branch.INTENT), InputSpec(BEGIN_RUN, Branch.INTENT),
        InputSpec(REQUEST_USER_STOP, Branch.INTENT), InputSpec(RESTORE_BY_USER, Branch.INTENT),
        InputSpec(COMMAND_CREATE, Branch.INTENT), InputSpec(COMMAND_SEND, Branch.INTENT),
        InputSpec(COMMAND_ROUTE, Branch.INTENT), InputSpec(COMMAND_REVIEW_RESULT, Branch.INTENT),
        InputSpec(COMMAND_WAIT, Branch.INTENT), InputSpec(COMMAND_STOP, Branch.INTENT),
        InputSpec(COMMAND_PAUSE, Branch.INTENT), InputSpec(COMMAND_QUARANTINE, Branch.INTENT),
        InputSpec(COMMAND_ARCHIVE, Branch.INTENT), InputSpec(COMMAND_RESTORE, Branch.INTENT),
        InputSpec(COMMAND_RENAME, Branch.INTENT), InputSpec(COMMAND_SIGNAL, Branch.INTENT),
        InputSpec(BEGIN_AUXILIARY, Branch.INTENT), InputSpec(DELETE_HISTORY_BY_USER, Branch.INTENT),
        InputSpec(PROPOSE_IMMUNITY_INTERVENTIONS, Branch.INTENT), InputSpec(ACCEPT_IMMUNITY_INTERVENTION, Branch.INTENT),
        InputSpec(DISMISS_IMMUNITY_INTERVENTION, Branch.INTENT), InputSpec(INSPECT_SIGNALS, Branch.INTENT),
        InputSpec(CHECKPOINT_INTEGRATION, Branch.FACT), InputSpec(ADOPT, Branch.FACT),
        InputSpec(REQUEST_FAILURE_STOP, Branch.FACT), InputSpec(RECONCILE_AND_AUTHORIZE_PLAN_RETRY, Branch.FACT),
        InputSpec(ACCEPT_PLAN_RESULT, Branch.FACT), InputSpec(RECORD_WORKSPACE, Branch.FACT),
        InputSpec(RESOLVE_SESSION_QUARANTINE, Branch.FACT), InputSpec(RECONCILE_INTERRUPTED_RUN, Branch.FACT),
        InputSpec(FINISH_STOP, Branch.FACT), InputSpec(OBSERVE_RUNNING, Branch.FACT),
        InputSpec(OBSERVE_SETTLED, Branch.FACT), InputSpec(OBSERVE_OTHER, Branch.FACT),
        InputSpec(ACKNOWLEDGE, Branch.FACT), InputSpec(CHARGE, Branch.FACT),
        InputSpec(CHARGE_AUXILIARY, Branch.FACT), InputSpec(FINISH_AUXILIARY, Branch.FACT),
        InputSpec(RESTORED, Branch.FACT), InputSpec(RECORD_RESULT, Branch.FACT),
        InputSpec(FINISH_IMMUNITY_INTERVENTION, Branch.FACT), InputSpec(QUARANTINE_FACT, Branch.FACT),
        InputSpec(LEGACY_IMPORTED, Branch.FACT), InputSpec(LIMIT_POLICY_MIGRATED, Branch.FACT),
        InputSpec(PERSISTENCE_UNKNOWN_FACT, Branch.FACT),
    )

    override val effects = listOf(
        EffectId("RunAdmitted"), EffectId("AuxiliaryAdmitted"), EffectId("RetryAuthorized"),
        EffectId("IntegrationAdmitted"), EffectId("CommandAccepted"), EffectId("Reject"),
    )

    /** An organism exists and no fence has closed it: everything but `empty` and `persistence-unknown`. */
    private val present = phases.toSet() - EMPTY - PERSISTENCE_UNKNOWN

    /** The root accepts work, so `actor()` lets an input that needs authority through. */
    private val working = setOf(INTEGRATING, AUXILIARY, INTEGRABLE, RESULT, CHILD, RUNNING, PENDING, QUARANTINED_BRANCH, UNKNOWN_BRANCH)

    /** A child exists that the root created and can address. */
    private val holdsChild = setOf(INTEGRATING, INTEGRABLE, RESULT, CHILD, QUARANTINED_BRANCH, UNKNOWN_BRANCH)

    /**
     * By input rather than by position: fifty-three columns of flags cannot be read, and each line names what its input
     * needs. [io.aequicor.magicpaper.machine.acceptance] would produce the same map from rows.
     */
    override val accepts: Map<InputId, Set<PhaseId>> = mapOf(
        SET_ARCHIVE_VISIBILITY to present,
        APPLY_LIMITS to present,
        ADMIT_INTEGRATION to setOf(INTEGRABLE),
        RENAME_BY_USER to present - DELETED,
        CHECK to working,
        AUTHORIZE_PLAN_RETRY to present,
        ADMIT_PLAN_WORKER to working,
        CHANGE_ROOT_MODE to setOf(INTERVENING, FINISHED, STOPPED, PENDING),
        PREPARE_USER_TURN to present - DELETED - QUARANTINED_UNKNOWN,
        BEGIN_RUN to working - AUXILIARY + FINISHED,
        REQUEST_USER_STOP to present,
        RESTORE_BY_USER to setOf(ARCHIVED),
        COMMAND_CREATE to working,
        COMMAND_SEND to setOf(CHILD),
        COMMAND_ROUTE to setOf(CHILD),
        COMMAND_REVIEW_RESULT to setOf(RESULT),
        COMMAND_WAIT to holdsChild,
        COMMAND_STOP to holdsChild,
        COMMAND_PAUSE to holdsChild,
        COMMAND_QUARANTINE to holdsChild,
        COMMAND_ARCHIVE to holdsChild,
        COMMAND_RESTORE to setOf(RESULT),
        COMMAND_RENAME to holdsChild,
        COMMAND_SIGNAL to working + setOf(INTERVENING, PROPOSED, QUARANTINED, QUARANTINED_UNKNOWN, UNKNOWN, STOPPING, FINISHED, STOPPED),
        BEGIN_AUXILIARY to working - AUXILIARY,
        DELETE_HISTORY_BY_USER to setOf(DELETED, INTERVENING, PROPOSED, QUARANTINED, ARCHIVED, FINISHED, STOPPED),
        PROPOSE_IMMUNITY_INTERVENTIONS to present,
        ACCEPT_IMMUNITY_INTERVENTION to setOf(INTERVENING, PROPOSED),
        DISMISS_IMMUNITY_INTERVENTION to setOf(PROPOSED),
        INSPECT_SIGNALS to present,
        CHECKPOINT_INTEGRATION to setOf(INTEGRATING),
        ADOPT to present + EMPTY,
        REQUEST_FAILURE_STOP to present - DELETED,
        RECONCILE_AND_AUTHORIZE_PLAN_RETRY to setOf(CHILD),
        ACCEPT_PLAN_RESULT to setOf(CHILD),
        RECORD_WORKSPACE to present - DELETED,
        RESOLVE_SESSION_QUARANTINE to present - setOf(DELETED, ARCHIVED, INTERVENING),
        RECONCILE_INTERRUPTED_RUN to setOf(UNKNOWN),
        FINISH_STOP to setOf(DELETED, INTERVENING, PROPOSED, QUARANTINED, ARCHIVED, FINISHED, STOPPED),
        OBSERVE_RUNNING to working,
        OBSERVE_SETTLED to present - setOf(DELETED, AUXILIARY, CHILD, UNKNOWN_BRANCH),
        OBSERVE_OTHER to present - DELETED,
        ACKNOWLEDGE to setOf(INTEGRATING, INTEGRABLE, RESULT),
        CHARGE to working - PENDING + STOPPING,
        CHARGE_AUXILIARY to setOf(AUXILIARY),
        FINISH_AUXILIARY to setOf(AUXILIARY),
        RESTORED to present,
        RECORD_RESULT to holdsChild,
        FINISH_IMMUNITY_INTERVENTION to setOf(INTERVENING),
        QUARANTINE_FACT to present - DELETED,
        LEGACY_IMPORTED to setOf(EMPTY),
        LIMIT_POLICY_MIGRATED to present,
        PERSISTENCE_UNKNOWN_FACT to phases.toSet(),
    )

    /** An integration between its intent and its verdict: the phases `recover` turns into a quarantine after a restart. */
    private val inFlight = setOf(SessionIntegrationPhase.INTENT, SessionIntegrationPhase.PREPARING,
        SessionIntegrationPhase.MERGING, SessionIntegrationPhase.VERIFYING)

    override fun label(state: SessionOrganismMachine.State): PhaseId? {
        if (state.persistenceUnknown) return PERSISTENCE_UNKNOWN
        val organism = state.organism ?: return EMPTY
        if (organism.deletedAt != null || organism.stoppedByUser) return DELETED
        val root = organism.sessions[organism.zygoteId] ?: return null
        return when {
            organism.interventions.any { it.state == ImmunityInterventionState.ACCEPTED || it.state == ImmunityInterventionState.UNKNOWN } -> INTERVENING
            organism.interventions.any { it.state == ImmunityInterventionState.PROPOSED } -> PROPOSED
            organism.sessions.keys.any { organism.pendingQuarantines(it).isNotEmpty() } -> when {
                root.observed == SessionObservedState.UNKNOWN -> QUARANTINED_UNKNOWN
                root.acceptsWork -> QUARANTINED_BRANCH
                else -> QUARANTINED
            }
            organism.sessions.values.any { it.observed == SessionObservedState.UNKNOWN } ->
                if (root.acceptsWork) UNKNOWN_BRANCH else UNKNOWN
            root.archived -> ARCHIVED
            root.observed == SessionObservedState.STOPPING -> STOPPING
            root.settled -> if (root.desired == SessionDesiredState.RUN) FINISHED else STOPPED
            organism.integrations.values.any { it.phase in inFlight } -> INTEGRATING
            organism.auxiliaryRuns.values.any { !it.settled } -> AUXILIARY
            organism.results.any { it.accepted } -> INTEGRABLE
            organism.results.isNotEmpty() -> RESULT
            organism.sessions.values.any { it.kind == SessionKind.SESSION } -> CHILD
            root.observed == SessionObservedState.PENDING -> PENDING
            else -> RUNNING
        }
    }

    override fun name(input: SessionOrganismMachine.Input): InputId = when (input) {
        is SessionOrganismMachine.Intent.SetArchiveVisibility -> SET_ARCHIVE_VISIBILITY
        is SessionOrganismMachine.Intent.ApplyLimits -> APPLY_LIMITS
        is SessionOrganismMachine.Intent.AdmitIntegration -> ADMIT_INTEGRATION
        is SessionOrganismMachine.Intent.RenameByUser -> RENAME_BY_USER
        is SessionOrganismMachine.Intent.Check -> CHECK
        is SessionOrganismMachine.Intent.AuthorizePlanRetry -> AUTHORIZE_PLAN_RETRY
        is SessionOrganismMachine.Intent.AdmitPlanWorker -> ADMIT_PLAN_WORKER
        is SessionOrganismMachine.Intent.ChangeRootMode -> CHANGE_ROOT_MODE
        is SessionOrganismMachine.Intent.PrepareUserTurn -> PREPARE_USER_TURN
        is SessionOrganismMachine.Intent.BeginRun -> BEGIN_RUN
        is SessionOrganismMachine.Intent.RequestUserStop -> REQUEST_USER_STOP
        is SessionOrganismMachine.Intent.RestoreByUser -> RESTORE_BY_USER
        is SessionOrganismMachine.Intent.Command -> when (input.request.action) {
            OrganismAction.CREATE -> COMMAND_CREATE
            OrganismAction.SEND -> COMMAND_SEND
            OrganismAction.ROUTE -> COMMAND_ROUTE
            OrganismAction.REVIEW_RESULT -> COMMAND_REVIEW_RESULT
            OrganismAction.WAIT -> COMMAND_WAIT
            OrganismAction.STOP -> COMMAND_STOP
            OrganismAction.PAUSE -> COMMAND_PAUSE
            OrganismAction.QUARANTINE -> COMMAND_QUARANTINE
            OrganismAction.ARCHIVE -> COMMAND_ARCHIVE
            OrganismAction.RESTORE -> COMMAND_RESTORE
            OrganismAction.RENAME -> COMMAND_RENAME
            OrganismAction.SIGNAL -> COMMAND_SIGNAL
        }
        is SessionOrganismMachine.Intent.BeginAuxiliary -> BEGIN_AUXILIARY
        is SessionOrganismMachine.Intent.DeleteHistoryByUser -> DELETE_HISTORY_BY_USER
        is SessionOrganismMachine.Intent.ProposeImmunityInterventions -> PROPOSE_IMMUNITY_INTERVENTIONS
        is SessionOrganismMachine.Intent.AcceptImmunityIntervention -> ACCEPT_IMMUNITY_INTERVENTION
        is SessionOrganismMachine.Intent.DismissImmunityIntervention -> DISMISS_IMMUNITY_INTERVENTION
        is SessionOrganismMachine.Intent.InspectSignals -> INSPECT_SIGNALS
        is SessionOrganismMachine.Fact.CheckpointIntegration -> CHECKPOINT_INTEGRATION
        is SessionOrganismMachine.Fact.Adopt -> ADOPT
        is SessionOrganismMachine.Fact.RequestFailureStop -> REQUEST_FAILURE_STOP
        is SessionOrganismMachine.Fact.ReconcileAndAuthorizePlanRetry -> RECONCILE_AND_AUTHORIZE_PLAN_RETRY
        is SessionOrganismMachine.Fact.AcceptPlanResult -> ACCEPT_PLAN_RESULT
        is SessionOrganismMachine.Fact.RecordWorkspace -> RECORD_WORKSPACE
        is SessionOrganismMachine.Fact.ResolveSessionQuarantine -> RESOLVE_SESSION_QUARANTINE
        is SessionOrganismMachine.Fact.ReconcileInterruptedRun -> RECONCILE_INTERRUPTED_RUN
        is SessionOrganismMachine.Fact.FinishStop -> FINISH_STOP
        is SessionOrganismMachine.Fact.Observe -> when (input.observed) {
            SessionObservedState.RUNNING -> OBSERVE_RUNNING
            SessionObservedState.COMPLETED, SessionObservedState.STOPPED, SessionObservedState.FAILED -> OBSERVE_SETTLED
            SessionObservedState.PENDING, SessionObservedState.WAITING_USER,
            SessionObservedState.STOPPING, SessionObservedState.UNKNOWN -> OBSERVE_OTHER
        }
        is SessionOrganismMachine.Fact.Acknowledge -> ACKNOWLEDGE
        is SessionOrganismMachine.Fact.Charge -> CHARGE
        is SessionOrganismMachine.Fact.ChargeAuxiliary -> CHARGE_AUXILIARY
        is SessionOrganismMachine.Fact.FinishAuxiliary -> FINISH_AUXILIARY
        is SessionOrganismMachine.Fact.Restored -> RESTORED
        is SessionOrganismMachine.Fact.RecordResult -> RECORD_RESULT
        is SessionOrganismMachine.Fact.FinishImmunityIntervention -> FINISH_IMMUNITY_INTERVENTION
        is SessionOrganismMachine.Fact.Quarantine -> QUARANTINE_FACT
        is SessionOrganismMachine.Fact.LegacyImported -> LEGACY_IMPORTED
        is SessionOrganismMachine.Fact.LimitPolicyMigrated -> LIMIT_POLICY_MIGRATED
        is SessionOrganismMachine.Fact.PersistenceUnknown -> PERSISTENCE_UNKNOWN_FACT
    }

    override fun name(effect: SessionOrganismMachine.Effect): EffectId = when (effect) {
        is SessionOrganismMachine.Effect.Emit -> when (effect.output) {
            is SessionOrganismMachine.Output.RunAdmitted -> EffectId("RunAdmitted")
            is SessionOrganismMachine.Output.AuxiliaryAdmitted -> EffectId("AuxiliaryAdmitted")
            is SessionOrganismMachine.Output.RetryAuthorized -> EffectId("RetryAuthorized")
            is SessionOrganismMachine.Output.IntegrationAdmitted -> EffectId("IntegrationAdmitted")
            is SessionOrganismMachine.Output.CommandAccepted -> EffectId("CommandAccepted")
        }
        is SessionOrganismMachine.Effect.Reject -> EffectId("Reject")
    }

    override fun unknown(state: SessionOrganismMachine.State): Boolean = state.persistenceUnknown || state.organism?.let { organism ->
        organism.sessions.values.any { it.observed == SessionObservedState.UNKNOWN || it.workspace?.phase == SessionCodingWorkspacePhase.UNKNOWN } ||
            organism.operations.values.any { it.state == SessionOperationState.UNKNOWN } ||
            organism.integrations.values.any { it.phase == SessionIntegrationPhase.UNKNOWN } ||
            organism.auxiliaryRuns.values.any { it.observed == SessionObservedState.UNKNOWN } ||
            organism.interventions.any { it.state == ImmunityInterventionState.UNKNOWN } ||
            organism.sessions.keys.any { organism.pendingQuarantines(it).isNotEmpty() }
    } == true

    override fun rejected(effect: SessionOrganismMachine.Effect): Boolean = effect is SessionOrganismMachine.Effect.Reject
}
