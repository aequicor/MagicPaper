package io.aequicor.magicpaper.domain

import io.aequicor.magicpaper.machine.Branch
import io.aequicor.magicpaper.machine.EffectId
import io.aequicor.magicpaper.machine.InputId
import io.aequicor.magicpaper.machine.InputSpec
import io.aequicor.magicpaper.machine.PhaseId
import io.aequicor.magicpaper.machine.StateSpace
import io.aequicor.magicpaper.machine.acceptance

/**
 * The state space of [ChatMachine], declared so it can be read without running anything.
 *
 * The state is a notebook: one root session, its questions, and at most one run per session. It has no
 * phase of its own, so a position is what acceptance depends on, read in the order the reducer evaluates
 * its guards:
 *
 *  - three fences that outrank everything. An outcome the journal could not confirm
 *    (`persistence-unknown`) refuses every input but `PersistenceUnknown`, `Restored` included; a deleted
 *    notebook (`deleted`) takes only `Restored` and `PersistenceUnknown`; a notebook nobody has created,
 *    imported or forked yet (`unrestored`) takes only the four inputs that give it a root, and the two facts
 *    every position takes;
 *  - a session with a run, split by [ChatMachine.Phase]. Only `running` accepts a pause, and the
 *    facts that need a live attempt (`Progress` and `ResponseContextPrepared` also while stopping,
 *    `NativeSessionBound`, `ExtensionBound` and `SourcesDiscovered` only while running) follow the phase.
 *    `interrupted` and `unknown-outcome` accept the same inputs and differ in what they do with them — a
 *    clarification starts the request again from one and only queues it beside the other — and in
 *    `unknown(state)`, so they are two positions. `recovering` is a saved output being inspected;
 *  - a session without a run, split by what gates history and admission: a waiting queue (`queued`) is
 *    the only place `AdvanceQueue` applies and refuses every edit of history, a session with messages
 *    (`settled`) is the only place a message can be edited, deleted or followed up, and the rest
 *    (`empty`) holds nothing to edit.
 *
 * One reducer input class is several input names where its payload changes acceptance or where it
 * lands: `Archive` is `Archive` and `Unarchive` (a session that holds work cannot be archived, and
 * nothing prevents unarchiving it), `RunStopped` is a stop whose outcome is known — the run is
 * `interrupted` — or unknown, and `RecoveryUnavailable` splits the same way. The branch declared for
 * each input is the family it belongs to.
 *
 * [label] names the session that matters most when there are several, in the order of [PRECEDENCE]: an
 * unknown outcome outranks everything, then a run that is being inspected, then the runs that still need
 * a decision, then work in flight, then what is at rest. A session with a run is named by the run and
 * never by its queue, because a queue beside a run does not change what any input does.
 *
 * `unknown(state)` and the position agree: a state is unknown exactly when it stands at
 * `persistence-unknown`, `unknown-outcome` or `recovering`. `recovering` counts because no proof has
 * yet established the outcome of the run, and a restart turns it into `unknown-outcome`. `stopping` and
 * `running` do not: their outcome is expected, not lost.
 *
 * What the declaration cannot express, and leaves to `ChatMachineTest`:
 *  - identity. Every input that acts on a run carries its whole reference (session, request, generation,
 *    message, response and timeline ids), and a stale or foreign one is refused as "the request changed".
 *    The matrix speaks about the one session `chat` and the one run `request`, and about references that
 *    match them. Inputs that name a record are accepted where the record exists: a message to edit or
 *    delete and the answer a follow-up refers to exist exactly at `settled`, which is why that position is
 *    not `empty`, and every live representative holds the one resource that a removal, a share or a
 *    selection names;
 *  - payload validation: blank or aliasing ids, a request already started, a reply that belongs to
 *    another response, a proof that does not match the run, a resource whose address is not navigable, a
 *    model choice with a blank part, an archive that is automatic and too early or not an archive at all;
 *  - which record of many. A notebook with several questions is one position, named by the most
 *    pressing of them, while an input addresses exactly one;
 *  - what an accepted input does: the state it leaves and the effects it owes are derived by running the
 *    machine, and a payload that lands elsewhere from the same position — a stop that carries a
 *    clarification restarts the run instead of interrupting it, a saved notebook holding no unfinished
 *    request lands at rest instead of at `unknown-outcome` — is covered by tests that name it.
 */
object ChatSpace : StateSpace<ChatMachine.State, ChatMachine.Input, ChatMachine.Effect> {
    val UNRESTORED = PhaseId("unrestored")
    val EMPTY = PhaseId("empty")
    val SETTLED = PhaseId("settled")
    val QUEUED = PhaseId("queued")
    val RUNNING = PhaseId("running")
    val STOPPING = PhaseId("stopping")
    val INTERRUPTED = PhaseId("interrupted")
    val UNKNOWN = PhaseId("unknown-outcome")
    val RECOVERING = PhaseId("recovering")
    val DELETED = PhaseId("deleted")
    val PERSISTENCE_UNKNOWN = PhaseId("persistence-unknown")

    val CREATE_NOTEBOOK = InputId("CreateNotebook")
    val IMPORT_NOTEBOOK = InputId("ImportNotebook")
    val FORK_NOTEBOOK = InputId("ForkNotebook")
    val CREATE_QUESTION = InputId("CreateQuestion")
    val SELECT_QUESTION = InputId("SelectQuestion")
    val SUBMIT = InputId("Submit")
    val FOLLOW_UP = InputId("FollowUp")
    val ADVANCE_QUEUE = InputId("AdvanceQueue")
    val PAUSE = InputId("Pause")
    val CLARIFY = InputId("Clarify")
    val RECOVER = InputId("Recover")
    val DISCARD = InputId("Discard")
    val EDIT_MESSAGE = InputId("EditMessage")
    val DELETE_MESSAGE = InputId("DeleteMessage")
    val SET_MODEL = InputId("SetModel")
    val UNLINK_PROFILE = InputId("UnlinkProfile")
    val SET_MEDIA_TOOL = InputId("SetMediaTool")
    val ADD_RESOURCES = InputId("AddResources")
    val REMOVE_RESOURCE = InputId("RemoveResource")
    val SHARE_RESOURCE = InputId("ShareResource")
    val SELECT_RESOURCES = InputId("SelectResources")
    val ARCHIVE = InputId("Archive")
    val UNARCHIVE = InputId("Unarchive")
    val DELETE = InputId("Delete")
    val LEGACY_IMPORTED = InputId("LegacyImported")
    val LEGACY_HYDRATED = InputId("LegacyHydrated")
    val EXTENSION_BOUND = InputId("ExtensionBound")
    val PROGRESS = InputId("Progress")
    val RESPONSE_CONTEXT_PREPARED = InputId("ResponseContextPrepared")
    val NATIVE_SESSION_BOUND = InputId("NativeSessionBound")
    val SOURCES_DISCOVERED = InputId("SourcesDiscovered")
    val REPLY_STORED = InputId("ReplyStored")
    val RUN_STOPPED = InputId("RunStopped")
    val RUN_STOPPED_UNKNOWN = InputId("RunStoppedUnknown")
    val RECOVERED_REPLY = InputId("RecoveredReply")
    val RECOVERY_UNAVAILABLE = InputId("RecoveryUnavailable")
    val RECOVERY_UNAVAILABLE_UNKNOWN = InputId("RecoveryUnavailableUnknown")
    val RESTORED = InputId("Restored")
    val PERSISTENCE_UNKNOWN_FACT = InputId("PersistenceUnknown")

    override val phases = listOf(
        UNRESTORED, EMPTY, SETTLED, QUEUED, RUNNING, STOPPING, INTERRUPTED, UNKNOWN, RECOVERING, DELETED, PERSISTENCE_UNKNOWN,
    )

    override val inputs = listOf(
        InputSpec(CREATE_NOTEBOOK, Branch.INTENT),
        InputSpec(IMPORT_NOTEBOOK, Branch.INTENT),
        InputSpec(FORK_NOTEBOOK, Branch.INTENT),
        InputSpec(CREATE_QUESTION, Branch.INTENT),
        InputSpec(SELECT_QUESTION, Branch.INTENT),
        InputSpec(SUBMIT, Branch.INTENT),
        InputSpec(FOLLOW_UP, Branch.INTENT),
        InputSpec(ADVANCE_QUEUE, Branch.INTENT),
        InputSpec(PAUSE, Branch.INTENT),
        InputSpec(CLARIFY, Branch.INTENT),
        InputSpec(RECOVER, Branch.INTENT),
        InputSpec(DISCARD, Branch.INTENT),
        InputSpec(EDIT_MESSAGE, Branch.INTENT),
        InputSpec(DELETE_MESSAGE, Branch.INTENT),
        InputSpec(SET_MODEL, Branch.INTENT),
        InputSpec(UNLINK_PROFILE, Branch.INTENT),
        InputSpec(SET_MEDIA_TOOL, Branch.INTENT),
        InputSpec(ADD_RESOURCES, Branch.INTENT),
        InputSpec(REMOVE_RESOURCE, Branch.INTENT),
        InputSpec(SHARE_RESOURCE, Branch.INTENT),
        InputSpec(SELECT_RESOURCES, Branch.INTENT),
        InputSpec(ARCHIVE, Branch.INTENT),
        InputSpec(UNARCHIVE, Branch.INTENT),
        InputSpec(DELETE, Branch.INTENT),
        InputSpec(LEGACY_IMPORTED, Branch.FACT),
        InputSpec(LEGACY_HYDRATED, Branch.FACT),
        InputSpec(EXTENSION_BOUND, Branch.FACT),
        InputSpec(PROGRESS, Branch.FACT),
        InputSpec(RESPONSE_CONTEXT_PREPARED, Branch.FACT),
        InputSpec(NATIVE_SESSION_BOUND, Branch.FACT),
        InputSpec(SOURCES_DISCOVERED, Branch.FACT),
        InputSpec(REPLY_STORED, Branch.FACT),
        InputSpec(RUN_STOPPED, Branch.FACT),
        InputSpec(RUN_STOPPED_UNKNOWN, Branch.FACT),
        InputSpec(RECOVERED_REPLY, Branch.FACT),
        InputSpec(RECOVERY_UNAVAILABLE, Branch.FACT),
        InputSpec(RECOVERY_UNAVAILABLE_UNKNOWN, Branch.FACT),
        InputSpec(RESTORED, Branch.FACT),
        InputSpec(PERSISTENCE_UNKNOWN_FACT, Branch.FACT),
    )

    override val effects = listOf(
        EffectId("RunRequest"), EffectId("AbortRequest"), EffectId("InspectSavedOutput"), EffectId("RequestAccepted"),
        EffectId("RequestCompleted"), EffectId("CleanupDeleted"), EffectId("Reject"),
    )

    /**
     * What every position of an initialized, live notebook takes: none of these looks at a run, at the
     * queue or at the history. `Submit` is among them because a request is started, or queued behind
     * what is running or waiting, wherever the session stands.
     */
    private val LIVE = setOf(
        CREATE_QUESTION, SELECT_QUESTION, SUBMIT, SET_MODEL, UNLINK_PROFILE, SET_MEDIA_TOOL, ADD_RESOURCES, REMOVE_RESOURCE,
        SHARE_RESOURCE, SELECT_RESOURCES, UNARCHIVE, DELETE, LEGACY_HYDRATED, RESTORED, PERSISTENCE_UNKNOWN_FACT,
    )

    /** A row reads as what the position accepts, since thirty-nine columns of flags cannot be read. [acceptance] takes the same shape. */
    private fun accepting(accepted: Set<InputId>) = inputs.map { if (it.id in accepted) '1' else '0' }.joinToString("")

    // Rows follow `phases`. Only a settled session takes an edit of history, only a notebook nobody created
    // takes a root, and every run position keeps `ImportNotebook` out because an import replaces the
    // sessions a run is bound to.
    override val accepts = acceptance(phases, inputs, listOf(
        /* unrestored           */ accepting(setOf(CREATE_NOTEBOOK, IMPORT_NOTEBOOK, FORK_NOTEBOOK, LEGACY_IMPORTED, RESTORED, PERSISTENCE_UNKNOWN_FACT)),
        /* empty                */ accepting(LIVE + setOf(IMPORT_NOTEBOOK, ARCHIVE)),
        /* settled              */ accepting(LIVE + setOf(IMPORT_NOTEBOOK, ARCHIVE, FOLLOW_UP, EDIT_MESSAGE, DELETE_MESSAGE)),
        /* queued               */ accepting(LIVE + setOf(IMPORT_NOTEBOOK, ADVANCE_QUEUE)),
        /* running              */ accepting(LIVE + setOf(PAUSE, CLARIFY, EXTENSION_BOUND, PROGRESS, RESPONSE_CONTEXT_PREPARED,
            NATIVE_SESSION_BOUND, SOURCES_DISCOVERED, REPLY_STORED, RUN_STOPPED, RUN_STOPPED_UNKNOWN)),
        /* stopping             */ accepting(LIVE + setOf(PROGRESS, RESPONSE_CONTEXT_PREPARED, REPLY_STORED, RUN_STOPPED, RUN_STOPPED_UNKNOWN)),
        /* interrupted          */ accepting(LIVE + setOf(CLARIFY, RECOVER, DISCARD, ARCHIVE)),
        /* unknown-outcome      */ accepting(LIVE + setOf(CLARIFY, RECOVER, DISCARD, ARCHIVE)),
        /* recovering           */ accepting(LIVE + setOf(RECOVERED_REPLY, RECOVERY_UNAVAILABLE, RECOVERY_UNAVAILABLE_UNKNOWN)),
        /* deleted              */ accepting(setOf(RESTORED, PERSISTENCE_UNKNOWN_FACT)),
        /* persistence-unknown  */ accepting(setOf(PERSISTENCE_UNKNOWN_FACT)),
    ))

    /**
     * Most pressing first. Exhaustiveness over [ChatMachine.Phase] is enforced by [position]: a new
     * phase does not compile until it is named there, and only then ranked here.
     */
    private val PRECEDENCE = listOf(UNKNOWN, RECOVERING, INTERRUPTED, STOPPING, RUNNING, QUEUED, SETTLED, EMPTY)

    private fun position(phase: ChatMachine.Phase): PhaseId = when (phase) {
        ChatMachine.Phase.RUNNING -> RUNNING
        ChatMachine.Phase.STOPPING -> STOPPING
        ChatMachine.Phase.INTERRUPTED -> INTERRUPTED
        ChatMachine.Phase.UNKNOWN -> UNKNOWN
        ChatMachine.Phase.RECOVERING -> RECOVERING
    }

    private fun position(state: ChatMachine.State, session: ChatSession): PhaseId {
        val run = state.runs[session.id]
        return when {
            run != null -> position(run.phase)
            session.queuedPrompts.isNotEmpty() -> QUEUED
            session.messages.isNotEmpty() -> SETTLED
            else -> EMPTY
        }
    }

    // Mirrors the order of the reducer's guards: the persistence flag refuses everything but its own
    // fact, then a deleted notebook, then one that was never created. An initialized notebook always holds
    // its root, so `firstOrNull` only answers null for a state the space does not name.
    override fun label(state: ChatMachine.State): PhaseId? = when {
        state.persistenceUnknown -> PERSISTENCE_UNKNOWN
        state.deleted -> DELETED
        !state.initialized -> UNRESTORED
        else -> state.sessions.values.map { position(state, it) }.toSet().let { held -> PRECEDENCE.firstOrNull { it in held } }
    }

    override fun name(input: ChatMachine.Input): InputId = when (input) {
        is ChatMachine.Intent.CreateNotebook -> CREATE_NOTEBOOK
        is ChatMachine.Intent.ImportNotebook -> IMPORT_NOTEBOOK
        is ChatMachine.Intent.ForkNotebook -> FORK_NOTEBOOK
        is ChatMachine.Intent.CreateQuestion -> CREATE_QUESTION
        is ChatMachine.Intent.SelectQuestion -> SELECT_QUESTION
        is ChatMachine.Intent.Submit -> SUBMIT
        is ChatMachine.Intent.FollowUp -> FOLLOW_UP
        is ChatMachine.Intent.AdvanceQueue -> ADVANCE_QUEUE
        is ChatMachine.Intent.Pause -> PAUSE
        is ChatMachine.Intent.Clarify -> CLARIFY
        is ChatMachine.Intent.Recover -> RECOVER
        is ChatMachine.Intent.Discard -> DISCARD
        is ChatMachine.Intent.EditMessage -> EDIT_MESSAGE
        is ChatMachine.Intent.DeleteMessage -> DELETE_MESSAGE
        is ChatMachine.Intent.SetModel -> SET_MODEL
        is ChatMachine.Intent.UnlinkProfile -> UNLINK_PROFILE
        is ChatMachine.Intent.SetMediaTool -> SET_MEDIA_TOOL
        is ChatMachine.Intent.AddResources -> ADD_RESOURCES
        is ChatMachine.Intent.RemoveResource -> REMOVE_RESOURCE
        is ChatMachine.Intent.ShareResource -> SHARE_RESOURCE
        is ChatMachine.Intent.SelectResources -> SELECT_RESOURCES
        // One intent, two inputs: only archiving is refused for a session that holds work.
        is ChatMachine.Intent.Archive -> if (input.archived) ARCHIVE else UNARCHIVE
        is ChatMachine.Intent.Delete -> DELETE
        is ChatMachine.Fact.LegacyImported -> LEGACY_IMPORTED
        is ChatMachine.Fact.LegacyHydrated -> LEGACY_HYDRATED
        is ChatMachine.Fact.ExtensionBound -> EXTENSION_BOUND
        is ChatMachine.Fact.Progress -> PROGRESS
        is ChatMachine.Fact.ResponseContextPrepared -> RESPONSE_CONTEXT_PREPARED
        is ChatMachine.Fact.NativeSessionBound -> NATIVE_SESSION_BOUND
        is ChatMachine.Fact.SourcesDiscovered -> SOURCES_DISCOVERED
        is ChatMachine.Fact.ReplyStored -> REPLY_STORED
        // A stop whose outcome is not known leaves the run in `unknown-outcome`; a known one interrupts it.
        is ChatMachine.Fact.RunStopped -> if (input.unknown) RUN_STOPPED_UNKNOWN else RUN_STOPPED
        is ChatMachine.Fact.RecoveredReply -> RECOVERED_REPLY
        // Inspection that found nothing returns the run to `unknown-outcome` or, when the stop was known, to `interrupted`.
        is ChatMachine.Fact.RecoveryUnavailable -> if (input.unknown) RECOVERY_UNAVAILABLE_UNKNOWN else RECOVERY_UNAVAILABLE
        ChatMachine.Fact.Restored -> RESTORED
        ChatMachine.Fact.PersistenceUnknown -> PERSISTENCE_UNKNOWN_FACT
    }

    override fun name(effect: ChatMachine.Effect): EffectId = when (effect) {
        is ChatMachine.Effect.RunRequest -> EffectId("RunRequest")
        is ChatMachine.Effect.AbortRequest -> EffectId("AbortRequest")
        is ChatMachine.Effect.InspectSavedOutput -> EffectId("InspectSavedOutput")
        is ChatMachine.Effect.RequestAccepted -> EffectId("RequestAccepted")
        is ChatMachine.Effect.RequestCompleted -> EffectId("RequestCompleted")
        is ChatMachine.Effect.CleanupDeleted -> EffectId("CleanupDeleted")
        is ChatMachine.Effect.Reject -> EffectId("Reject")
    }

    override fun unknown(state: ChatMachine.State) = state.persistenceUnknown ||
        state.runs.values.any { it.phase == ChatMachine.Phase.UNKNOWN || it.phase == ChatMachine.Phase.RECOVERING }

    override fun rejected(effect: ChatMachine.Effect) = effect is ChatMachine.Effect.Reject
}
