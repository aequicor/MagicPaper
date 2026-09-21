package io.aequicor.magicpaper.domain

import io.aequicor.magicpaper.machine.Branch
import io.aequicor.magicpaper.machine.EffectId
import io.aequicor.magicpaper.machine.InputId
import io.aequicor.magicpaper.machine.InputSpec
import io.aequicor.magicpaper.machine.PhaseId
import io.aequicor.magicpaper.machine.StateSpace

/**
 * The state space of [CodingMachine], declared so it can be read without running anything.
 *
 * The state is an aggregate — a project, a map of sessions, their histories and at most one run per
 * session — and it has no phase of its own. A position is what acceptance depends on, read in the
 * order the reducer evaluates its guards:
 *
 *  - three fences that outrank everything: an outcome the journal could not confirm
 *    (`persistence-unknown`), a deleted project, and a project nobody has restored yet;
 *  - an initialized project with no session (`project-only`), which refuses everything addressed to
 *    a session;
 *  - a session with a run, split by [CodingMachine.Phase]. Two of those phases are not one position
 *    each. `RUNNING` is three, because what a run accepts depends on evidence it already holds: a
 *    recovery acknowledgement inherited from an abandoned predecessor (`RecoveryAcknowledgementConsumed`
 *    needs it) and a task worktree in conflict (`BeginRepair` needs it). `ABANDONING` is two, because
 *    it waits for a different acknowledgement depending on how it was entered — after a decision
 *    about an attempt, or after proof that nothing was dispatched — and each acknowledgement is
 *    refused by the other. With one position for each of them the acknowledgement facts could not be
 *    told apart from the representative;
 *  - a session without a run, split by the facts that gate admission: an unknown workspace outcome
 *    (`BeginRun` refuses it), an archived session, a session with queued prompts, a history that ends
 *    on a request nobody answered (the only place `DeferRecovery` applies without a run), a history
 *    that ends on a finished response (the only place `VerifyResponse` applies), and the rest. A
 *    session carrying several of these facts is named by the first in that order: an unknown
 *    workspace outcome comes first because it is an unknown outcome, and an archived session precedes
 *    a queue because it refuses more admission.
 *
 * One reducer input class is several input names where its payload changes acceptance or where it
 * lands: `ArchiveSession` is `Archive` and `Unarchive` (a running session cannot be archived),
 * `WorktreeProjected` is a known projection or an unknown one (the second is the only way to reach
 * `workspace-unknown`), and `RunFinished` and `RunStopped` are split by whether their outcome is
 * known, which decides between `INTERRUPTED` and `UNKNOWN`.
 *
 * The matrix is written by input rather than by position, unlike the smaller owners, because
 * fifty-two columns of flags cannot be read; [acceptance] would produce the same map.
 *
 * [label] names the session that matters most when there are several, in the order of [PRECEDENCE]:
 * an unknown outcome outranks everything, then the runs that still need a decision, then the rest.
 *
 * What the declaration cannot express, and leaves to `CodingMachineTest`:
 *  - identity. Every input carries a session reference (id and runtime generation), and most carry a
 *    run reference (request, generation, message and response ids). A stale generation, another
 *    request id, a decision, acknowledgement or proof that names another attempt, an engine that does
 *    not match, a revision of a child journal from a replaced stream or an older sequence: all refuse,
 *    and none of it is a position. The matrix speaks about the one session `session` and the one run
 *    `run`, and about references that match them;
 *  - which record of many. A project with two sessions is one position, named by the most pressing
 *    session, while an input addresses exactly one of them;
 *  - payload validation: blank or duplicate ids, a response that belongs to another request, a request
 *    whose mode differs from its session, a name already changed, a task replaced by another;
 *  - `expected`. `EditRequest` and `ReplaceHistory` compare the history they saw against the current
 *    one. The representatives send an empty `expected`, so every position whose history is not empty
 *    (`unfinished-history`, `answered`) reads as a refusal for a reason of payload, not of position;
 *  - what a run holds beyond its position. A queue beside a run, a recovery acknowledgement beside any
 *    phase but `RUNNING`, an archived session beside a run, a task worktree that is not the conflict
 *    or that is `COMPLETE` (`SetWorktreeEnabled` looks at it): none is named, so `QueuedClarified`,
 *    `RecoveryAcknowledgementConsumed` and `SetWorktreeEnabled` read as refusals there although the
 *    reducer would accept or refuse them by that evidence. A run that both holds an acknowledgement
 *    and stands in a conflict is named by the conflict;
 *  - `BeginRun` accepts a request that was never queued, which the production sequence never sends.
 *
 * `unknown(state)` and the position can disagree. It is true for a run in `UNKNOWN` or `ABANDONING`,
 * for an unknown workspace child and for unconfirmed persistence, and none of those is a position of
 * its own beside a live run: a `RUNNING` run whose workspace outcome is unknown is labelled `running`
 * and still reports unknown. It is false for `INTERRUPTED`, which is a stop that was observed.
 */
object CodingSpace : StateSpace<CodingMachine.State, CodingMachine.Input, CodingMachine.Effect> {
    val UNRESTORED = PhaseId("unrestored")
    val PROJECT_ONLY = PhaseId("project-only")
    val IDLE = PhaseId("idle")
    val QUEUED = PhaseId("queued")
    val ARCHIVED = PhaseId("archived")
    val UNFINISHED_HISTORY = PhaseId("unfinished-history")
    val ANSWERED = PhaseId("answered")
    val WORKSPACE_UNKNOWN = PhaseId("workspace-unknown")
    val RUNNING = PhaseId("running")
    val RUNNING_CONFLICT = PhaseId("running-conflict")
    val RUNNING_RECOVERY_HELD = PhaseId("running-recovery-held")
    val STOPPING = PhaseId("stopping")
    val INTERRUPTED = PhaseId("interrupted")
    val UNKNOWN = PhaseId("unknown-outcome")
    val ABANDONING_ATTEMPT = PhaseId("abandoning-attempt")
    val ABANDONING_NO_DISPATCH = PhaseId("abandoning-no-dispatch")
    val DELETED = PhaseId("deleted")
    val PERSISTENCE_UNKNOWN = PhaseId("persistence-unknown")

    val CREATE_PROJECT = InputId("CreateProject")
    val SET_PROJECT_MODEL = InputId("SetProjectModel")
    val DELETE_PROJECT = InputId("DeleteProject")
    val CREATE_SESSION = InputId("CreateSession")
    val DELETE_SESSION = InputId("DeleteSession")
    val RENAME_SESSION = InputId("RenameSession")
    val ARCHIVE = InputId("Archive")
    val UNARCHIVE = InputId("Unarchive")
    val SET_SESSION_MODEL = InputId("SetSessionModel")
    val SET_SEARCH_PROVIDER = InputId("SetSearchProvider")
    val CHANGE_MODE = InputId("ChangeMode")
    val SET_MEDIA_TOOL = InputId("SetMediaTool")
    val SET_FEATURE_FLAG = InputId("SetFeatureFlag")
    val SET_WORKTREE_ENABLED = InputId("SetWorktreeEnabled")
    val VERIFY_RESPONSE = InputId("VerifyResponse")
    val UNLINK_PROFILE = InputId("UnlinkProfile")
    val ENQUEUE = InputId("Enqueue")
    val QUEUED_CLARIFIED = InputId("QueuedClarified")
    val BEGIN_RUN = InputId("BeginRun")
    val BEGIN_REPAIR = InputId("BeginRepair")
    val PAUSE = InputId("Pause")
    val DEFER_RECOVERY = InputId("DeferRecovery")
    val CLARIFY = InputId("Clarify")
    val ABANDON = InputId("Abandon")
    val ABANDON_NOT_DISPATCHED = InputId("AbandonNotDispatched")
    val DISCARD_INTERRUPTED = InputId("DiscardInterrupted")
    val EDIT_REQUEST = InputId("EditRequest")
    val REPLACE_HISTORY = InputId("ReplaceHistory")
    val ORCHESTRATE = InputId("Orchestrate")
    val LEGACY_IMPORTED = InputId("LegacyImported")
    val HISTORY_PUBLISHED = InputId("HistoryPublished")
    val STATUS_OBSERVED = InputId("StatusObserved")
    val ARCHIVE_READINESS_OBSERVED = InputId("ArchiveReadinessObserved")
    val NATIVE_SESSION_BOUND = InputId("NativeSessionBound")
    val RUN_FINISHED = InputId("RunFinished")
    val RUN_FINISHED_UNKNOWN = InputId("RunFinishedUnknown")
    val RUN_OUTPUT_PUBLISHED = InputId("RunOutputPublished")
    val RUN_STOPPED = InputId("RunStopped")
    val RUN_STOPPED_UNKNOWN = InputId("RunStoppedUnknown")
    val ABANDON_ACKNOWLEDGED = InputId("AbandonAcknowledged")
    val NO_DISPATCH_ACKNOWLEDGED = InputId("NoDispatchAcknowledged")
    val RECOVERY_ACKNOWLEDGEMENT_CONSUMED = InputId("RecoveryAcknowledgementConsumed")
    val TITLE_REQUESTED = InputId("TitleRequested")
    val PROMPT_NAMED = InputId("PromptNamed")
    val TITLE_OBSERVED = InputId("TitleObserved")
    val PLAN_SESSION_BOUND = InputId("PlanSessionBound")
    val ORGANISM_PROJECTED = InputId("OrganismProjected")
    val WORKTREE_PROJECTED = InputId("WorktreeProjected")
    val WORKTREE_UNKNOWN = InputId("WorktreeUnknown")
    val WORKTREE_UNAVAILABLE = InputId("WorktreeUnavailable")
    val RESTORED = InputId("Restored")
    val PERSISTENCE_UNKNOWN_FACT = InputId("PersistenceUnknown")

    override val phases = listOf(
        UNRESTORED, PROJECT_ONLY,
        IDLE, QUEUED, ARCHIVED, UNFINISHED_HISTORY, ANSWERED, WORKSPACE_UNKNOWN,
        RUNNING, RUNNING_CONFLICT, RUNNING_RECOVERY_HELD, STOPPING, INTERRUPTED, UNKNOWN,
        ABANDONING_ATTEMPT, ABANDONING_NO_DISPATCH,
        DELETED, PERSISTENCE_UNKNOWN,
    )

    override val inputs = listOf(
        InputSpec(CREATE_PROJECT, Branch.INTENT),
        InputSpec(SET_PROJECT_MODEL, Branch.INTENT),
        InputSpec(DELETE_PROJECT, Branch.INTENT),
        InputSpec(CREATE_SESSION, Branch.INTENT),
        InputSpec(DELETE_SESSION, Branch.INTENT),
        InputSpec(RENAME_SESSION, Branch.INTENT),
        InputSpec(ARCHIVE, Branch.INTENT),
        InputSpec(UNARCHIVE, Branch.INTENT),
        InputSpec(SET_SESSION_MODEL, Branch.INTENT),
        InputSpec(SET_SEARCH_PROVIDER, Branch.INTENT),
        InputSpec(CHANGE_MODE, Branch.INTENT),
        InputSpec(SET_MEDIA_TOOL, Branch.INTENT),
        InputSpec(SET_FEATURE_FLAG, Branch.INTENT),
        InputSpec(SET_WORKTREE_ENABLED, Branch.INTENT),
        InputSpec(VERIFY_RESPONSE, Branch.INTENT),
        InputSpec(UNLINK_PROFILE, Branch.INTENT),
        InputSpec(ENQUEUE, Branch.INTENT),
        InputSpec(QUEUED_CLARIFIED, Branch.INTENT),
        InputSpec(BEGIN_RUN, Branch.INTENT),
        InputSpec(BEGIN_REPAIR, Branch.INTENT),
        InputSpec(PAUSE, Branch.INTENT),
        InputSpec(DEFER_RECOVERY, Branch.INTENT),
        InputSpec(CLARIFY, Branch.INTENT),
        InputSpec(ABANDON, Branch.INTENT),
        InputSpec(ABANDON_NOT_DISPATCHED, Branch.INTENT),
        InputSpec(DISCARD_INTERRUPTED, Branch.INTENT),
        InputSpec(EDIT_REQUEST, Branch.INTENT),
        InputSpec(REPLACE_HISTORY, Branch.INTENT),
        InputSpec(ORCHESTRATE, Branch.INTENT),
        InputSpec(LEGACY_IMPORTED, Branch.FACT),
        InputSpec(HISTORY_PUBLISHED, Branch.FACT),
        InputSpec(STATUS_OBSERVED, Branch.FACT),
        InputSpec(ARCHIVE_READINESS_OBSERVED, Branch.FACT),
        InputSpec(NATIVE_SESSION_BOUND, Branch.FACT),
        InputSpec(RUN_FINISHED, Branch.FACT),
        InputSpec(RUN_FINISHED_UNKNOWN, Branch.FACT),
        InputSpec(RUN_OUTPUT_PUBLISHED, Branch.FACT),
        InputSpec(RUN_STOPPED, Branch.FACT),
        InputSpec(RUN_STOPPED_UNKNOWN, Branch.FACT),
        InputSpec(ABANDON_ACKNOWLEDGED, Branch.FACT),
        InputSpec(NO_DISPATCH_ACKNOWLEDGED, Branch.FACT),
        InputSpec(RECOVERY_ACKNOWLEDGEMENT_CONSUMED, Branch.FACT),
        InputSpec(TITLE_REQUESTED, Branch.FACT),
        InputSpec(PROMPT_NAMED, Branch.FACT),
        InputSpec(TITLE_OBSERVED, Branch.FACT),
        InputSpec(PLAN_SESSION_BOUND, Branch.FACT),
        InputSpec(ORGANISM_PROJECTED, Branch.FACT),
        InputSpec(WORKTREE_PROJECTED, Branch.FACT),
        InputSpec(WORKTREE_UNKNOWN, Branch.FACT),
        InputSpec(WORKTREE_UNAVAILABLE, Branch.FACT),
        InputSpec(RESTORED, Branch.FACT),
        InputSpec(PERSISTENCE_UNKNOWN_FACT, Branch.FACT),
    )

    override val effects = listOf(
        EffectId("RunRequest"), EffectId("AbortRequest"), EffectId("AcknowledgePrevious"),
        EffectId("AcknowledgeNotDispatched"), EffectId("SessionCreated"), EffectId("CleanupDeleted"),
        EffectId("Orchestrated"), EffectId("Reject"),
    )

    /** A run in `RUNNING` or `STOPPING`: the phases that still take output, a binding and a finish. */
    private val LIVE = setOf(RUNNING, RUNNING_CONFLICT, RUNNING_RECOVERY_HELD, STOPPING)

    /** Every position that holds a run. `Pause`, `Clarify` and a stop apply to all of them. */
    private val RUNS = LIVE + setOf(INTERRUPTED, UNKNOWN, ABANDONING_ATTEMPT, ABANDONING_NO_DISPATCH)

    /** A session without a run. */
    private val QUIET = setOf(IDLE, QUEUED, ARCHIVED, UNFINISHED_HISTORY, ANSWERED, WORKSPACE_UNKNOWN)

    /** A session exists. Everything addressed to a session needs one. */
    private val SESSION = QUIET + RUNS

    /** An initialized, live project: not fenced and not deleted. */
    private val PROJECT = SESSION + PROJECT_ONLY

    // By input rather than by position: see the KDoc. `Restored` is refused only by unconfirmed
    // persistence, `LegacyImported` and `CreateProject` only before the project exists, and nothing
    // but `Restored` and `PersistenceUnknown` gets past a deleted project.
    // `Enqueue` is accepted beside a run and refused only by an archived session. `ChangeMode` and
    // `SetWorktreeEnabled` refuse a queue and a run, and the first also an archived session.
    // `EditRequest` and `ReplaceHistory` are accepted only where the history is empty, which is what
    // the representatives send as `expected`. `Abandon` and `AbandonNotDispatched` are accepted from
    // `INTERRUPTED` and `UNKNOWN`, and `DeferRecovery` from those two and from an unfinished history.
    override val accepts: Map<InputId, Set<PhaseId>> = mapOf(
        CREATE_PROJECT to setOf(UNRESTORED),
        SET_PROJECT_MODEL to PROJECT,
        DELETE_PROJECT to PROJECT,
        CREATE_SESSION to PROJECT,
        DELETE_SESSION to SESSION,
        RENAME_SESSION to SESSION,
        ARCHIVE to SESSION - LIVE,
        UNARCHIVE to SESSION,
        SET_SESSION_MODEL to SESSION,
        SET_SEARCH_PROVIDER to SESSION,
        CHANGE_MODE to setOf(IDLE, UNFINISHED_HISTORY, ANSWERED, WORKSPACE_UNKNOWN),
        SET_MEDIA_TOOL to SESSION,
        SET_FEATURE_FLAG to SESSION,
        SET_WORKTREE_ENABLED to setOf(IDLE, ARCHIVED, UNFINISHED_HISTORY, ANSWERED, WORKSPACE_UNKNOWN),
        VERIFY_RESPONSE to setOf(ANSWERED),
        UNLINK_PROFILE to PROJECT,
        ENQUEUE to SESSION - ARCHIVED,
        QUEUED_CLARIFIED to setOf(QUEUED),
        BEGIN_RUN to setOf(IDLE, QUEUED, UNFINISHED_HISTORY, ANSWERED),
        BEGIN_REPAIR to setOf(RUNNING_CONFLICT),
        PAUSE to RUNS,
        DEFER_RECOVERY to setOf(UNFINISHED_HISTORY, INTERRUPTED, UNKNOWN),
        CLARIFY to RUNS,
        ABANDON to setOf(INTERRUPTED, UNKNOWN),
        ABANDON_NOT_DISPATCHED to setOf(INTERRUPTED, UNKNOWN),
        DISCARD_INTERRUPTED to setOf(INTERRUPTED),
        EDIT_REQUEST to setOf(IDLE, ARCHIVED, WORKSPACE_UNKNOWN),
        REPLACE_HISTORY to setOf(IDLE, ARCHIVED, WORKSPACE_UNKNOWN),
        ORCHESTRATE to SESSION,
        LEGACY_IMPORTED to setOf(UNRESTORED),
        HISTORY_PUBLISHED to SESSION,
        STATUS_OBSERVED to SESSION,
        ARCHIVE_READINESS_OBSERVED to SESSION,
        NATIVE_SESSION_BOUND to LIVE,
        RUN_FINISHED to LIVE,
        RUN_FINISHED_UNKNOWN to LIVE,
        RUN_OUTPUT_PUBLISHED to LIVE,
        RUN_STOPPED to RUNS,
        RUN_STOPPED_UNKNOWN to RUNS,
        ABANDON_ACKNOWLEDGED to setOf(ABANDONING_ATTEMPT),
        NO_DISPATCH_ACKNOWLEDGED to setOf(ABANDONING_NO_DISPATCH),
        RECOVERY_ACKNOWLEDGEMENT_CONSUMED to setOf(RUNNING_RECOVERY_HELD),
        TITLE_REQUESTED to SESSION,
        PROMPT_NAMED to SESSION,
        TITLE_OBSERVED to SESSION,
        PLAN_SESSION_BOUND to SESSION,
        ORGANISM_PROJECTED to SESSION,
        WORKTREE_PROJECTED to SESSION,
        WORKTREE_UNKNOWN to SESSION,
        WORKTREE_UNAVAILABLE to RUNS,
        RESTORED to phases.toSet() - PERSISTENCE_UNKNOWN,
        PERSISTENCE_UNKNOWN_FACT to phases.toSet(),
    )

    /**
     * Most pressing first, for a project whose sessions stand at different positions. An unknown
     * outcome is the one thing that must not be lost or repeated, so it outranks everything; a run
     * that still needs a decision comes before one that only continues, and a session with work to
     * resolve before one at rest. An archived session is the least pressing: it takes no work.
     */
    private val PRECEDENCE = listOf(
        UNKNOWN, ABANDONING_ATTEMPT, ABANDONING_NO_DISPATCH, WORKSPACE_UNKNOWN, INTERRUPTED, STOPPING,
        RUNNING_CONFLICT, RUNNING_RECOVERY_HELD, RUNNING, UNFINISHED_HISTORY, QUEUED, ANSWERED, IDLE, ARCHIVED,
    )

    init {
        // A position a session can stand at and no rank names would make `label` throw for it.
        require(PRECEDENCE.toSet() == SESSION && PRECEDENCE.size == SESSION.size) { "PRECEDENCE must rank every session position exactly once" }
    }

    private fun position(state: CodingMachine.State, session: CodingSession): PhaseId {
        val run = state.runs[session.id]
        if (run != null) return when (run.phase) {
            CodingMachine.Phase.RUNNING -> when {
                session.taskWorktree?.phase == TaskWorktreePhase.CONFLICT -> RUNNING_CONFLICT
                session.id in state.acknowledgements || session.id in state.noDispatchAcknowledgements -> RUNNING_RECOVERY_HELD
                else -> RUNNING
            }
            CodingMachine.Phase.STOPPING -> STOPPING
            CodingMachine.Phase.INTERRUPTED -> INTERRUPTED
            CodingMachine.Phase.UNKNOWN -> UNKNOWN
            CodingMachine.Phase.ABANDONING -> if (run.abandonAttempt != null) ABANDONING_ATTEMPT else ABANDONING_NO_DISPATCH
        }
        val history = state.histories[session.id].orEmpty()
        return when {
            "workspace:${session.id}" in state.unknownChildren -> WORKSPACE_UNKNOWN
            session.archived -> ARCHIVED
            session.queuedPrompts.isNotEmpty() -> QUEUED
            history.interruptedCodingRequest() != null -> UNFINISHED_HISTORY
            history.lastOrNull { it.role == CodingRole.AGENT && !it.systemContext && !it.systemNotice }?.failed == false -> ANSWERED
            else -> IDLE
        }
    }

    override fun label(state: CodingMachine.State): PhaseId? = when {
        state.persistenceUnknown -> PERSISTENCE_UNKNOWN
        state.deleted -> DELETED
        !state.initialized -> UNRESTORED
        state.sessions.isEmpty() -> PROJECT_ONLY
        else -> state.sessions.values.map { position(state, it) }.toSet().let { held -> PRECEDENCE.first { it in held } }
    }

    override fun name(input: CodingMachine.Input): InputId = when (input) {
        is CodingMachine.Intent.CreateProject -> CREATE_PROJECT
        is CodingMachine.Intent.SetProjectModel -> SET_PROJECT_MODEL
        CodingMachine.Intent.DeleteProject -> DELETE_PROJECT
        is CodingMachine.Intent.CreateSession -> CREATE_SESSION
        is CodingMachine.Intent.DeleteSession -> DELETE_SESSION
        is CodingMachine.Intent.RenameSession -> RENAME_SESSION
        // One intent, two inputs: a running session cannot be archived, and only archiving can be refused for it.
        is CodingMachine.Intent.ArchiveSession -> if (input.archived) ARCHIVE else UNARCHIVE
        is CodingMachine.Intent.SetSessionModel -> SET_SESSION_MODEL
        is CodingMachine.Intent.SetSearchProvider -> SET_SEARCH_PROVIDER
        is CodingMachine.Intent.ChangeMode -> CHANGE_MODE
        is CodingMachine.Intent.SetMediaTool -> SET_MEDIA_TOOL
        is CodingMachine.Intent.SetFeatureFlag -> SET_FEATURE_FLAG
        is CodingMachine.Intent.SetWorktreeEnabled -> SET_WORKTREE_ENABLED
        is CodingMachine.Intent.VerifyResponse -> VERIFY_RESPONSE
        is CodingMachine.Intent.UnlinkProfile -> UNLINK_PROFILE
        is CodingMachine.Intent.Enqueue -> ENQUEUE
        is CodingMachine.Intent.QueuedClarified -> QUEUED_CLARIFIED
        is CodingMachine.Intent.BeginRun -> BEGIN_RUN
        is CodingMachine.Intent.BeginRepair -> BEGIN_REPAIR
        is CodingMachine.Intent.Pause -> PAUSE
        is CodingMachine.Intent.DeferRecovery -> DEFER_RECOVERY
        is CodingMachine.Intent.Clarify -> CLARIFY
        is CodingMachine.Intent.Abandon -> ABANDON
        is CodingMachine.Intent.AbandonNotDispatched -> ABANDON_NOT_DISPATCHED
        is CodingMachine.Intent.DiscardInterrupted -> DISCARD_INTERRUPTED
        is CodingMachine.Intent.EditRequest -> EDIT_REQUEST
        is CodingMachine.Intent.ReplaceHistory -> REPLACE_HISTORY
        is CodingMachine.Intent.Orchestrate -> ORCHESTRATE
        is CodingMachine.Fact.LegacyImported -> LEGACY_IMPORTED
        is CodingMachine.Fact.HistoryPublished -> HISTORY_PUBLISHED
        is CodingMachine.Fact.StatusObserved -> STATUS_OBSERVED
        is CodingMachine.Fact.ArchiveReadinessObserved -> ARCHIVE_READINESS_OBSERVED
        is CodingMachine.Fact.NativeSessionBound -> NATIVE_SESSION_BOUND
        // A finish whose outcome is not known leaves the run in `UNKNOWN`; a known one completes or interrupts it.
        is CodingMachine.Fact.RunFinished -> if (input.outcomeKnown) RUN_FINISHED else RUN_FINISHED_UNKNOWN
        is CodingMachine.Fact.RunOutputPublished -> RUN_OUTPUT_PUBLISHED
        is CodingMachine.Fact.RunStopped -> if (input.unknown) RUN_STOPPED_UNKNOWN else RUN_STOPPED
        is CodingMachine.Fact.AbandonAcknowledged -> ABANDON_ACKNOWLEDGED
        is CodingMachine.Fact.NoDispatchAcknowledged -> NO_DISPATCH_ACKNOWLEDGED
        is CodingMachine.Fact.RecoveryAcknowledgementConsumed -> RECOVERY_ACKNOWLEDGEMENT_CONSUMED
        is CodingMachine.Fact.TitleRequested -> TITLE_REQUESTED
        is CodingMachine.Fact.PromptNamed -> PROMPT_NAMED
        is CodingMachine.Fact.TitleObserved -> TITLE_OBSERVED
        is CodingMachine.Fact.PlanSessionBound -> PLAN_SESSION_BOUND
        is CodingMachine.Fact.OrganismProjected -> ORGANISM_PROJECTED
        // An uncertain child write is the only way into `workspace-unknown`, so it is its own input.
        is CodingMachine.Fact.WorktreeProjected -> if (input.unknown) WORKTREE_UNKNOWN else WORKTREE_PROJECTED
        is CodingMachine.Fact.WorktreeUnavailable -> WORKTREE_UNAVAILABLE
        CodingMachine.Fact.Restored -> RESTORED
        CodingMachine.Fact.PersistenceUnknown -> PERSISTENCE_UNKNOWN_FACT
    }

    override fun name(effect: CodingMachine.Effect): EffectId = when (effect) {
        is CodingMachine.Effect.RunRequest -> EffectId("RunRequest")
        is CodingMachine.Effect.AbortRequest -> EffectId("AbortRequest")
        is CodingMachine.Effect.AcknowledgePrevious -> EffectId("AcknowledgePrevious")
        is CodingMachine.Effect.AcknowledgeNotDispatched -> EffectId("AcknowledgeNotDispatched")
        is CodingMachine.Effect.SessionCreated -> EffectId("SessionCreated")
        is CodingMachine.Effect.CleanupDeleted -> EffectId("CleanupDeleted")
        is CodingMachine.Effect.Orchestrated -> EffectId("Orchestrated")
        is CodingMachine.Effect.Reject -> EffectId("Reject")
    }

    override fun unknown(state: CodingMachine.State) =
        state.persistenceUnknown || state.unknownChildren.isNotEmpty() ||
            state.runs.values.any { it.phase == CodingMachine.Phase.UNKNOWN || it.phase == CodingMachine.Phase.ABANDONING }

    override fun rejected(effect: CodingMachine.Effect) = effect is CodingMachine.Effect.Reject
}
