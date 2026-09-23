package io.aequicor.magicpaper.domain

import io.aequicor.magicpaper.domain.CodingMachine.ChildRevision
import io.aequicor.magicpaper.domain.CodingMachine.Effect
import io.aequicor.magicpaper.domain.CodingMachine.Fact
import io.aequicor.magicpaper.domain.CodingMachine.Intent
import io.aequicor.magicpaper.domain.planning.OrchestrationEvent
import io.aequicor.magicpaper.machine.PhaseId
import io.aequicor.magicpaper.machine.verifyStateSpace
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The representatives of [CodingSpace], kept here rather than in the api so a shipped binary — the
 * browser bundle included — carries no fixtures.
 *
 * Each one is built by running the machine from `initial`, never by constructing a state, which is
 * what the `internal constructor` on `State` is there to enforce. Every position that holds a run
 * goes through the same prefix — a first run, abandoned and acknowledged — and then starts `run`,
 * because the run reference names its generation and every representative of the run positions must
 * carry the same reference for the one input value per name to address it. That prefix is also what
 * leaves the recovery acknowledgement that makes `running-recovery-held` a position.
 */
class CodingSpaceTest {
    private val project = CodingProject("project", "Project", "/project", 1)
    private val session = CodingSession("session", project.id, "Новая сессия", 2, engine = CodingEngine.PI)
    private val sessionRef = CodingMachine.ref(session)
    private val other = session.copy(id = "other")
    private val task = TaskWorktree("task", "/project", "main", "base", "/task", "branch", phase = TaskWorktreePhase.CONFLICT)
    /** A merge the agent's checks refused: the worktree publishes their report as the merged record's error. */
    private val checked = task.copy(phase = TaskWorktreePhase.MERGING, resultCommit = "result", mergeCommit = "merged",
        error = "Проверка результата завершилась с ошибкой")

    /** Ids carry the session, so two sessions of one project never share a request, message or response. */
    private fun tag(owner: CodingSession) = if (owner.id == session.id) "" else "-${owner.id}"
    private fun request(id: String) = CodingRunCheckpoint("message-$id", "Task $id", responseId = "reply-$id",
        runId = id, responseTimelineId = "timeline-$id")
    private fun step(state: CodingMachine.State, vararg inputs: CodingMachine.Input) = inputs.fold(state) { current, input ->
        CodingMachine.reduce(current, input).also { assertTrue(it.effects.none(CodingSpace::rejected), "$input was refused") }.state
    }

    private val initial = CodingMachine.initial()
    private val projectOnly = step(initial, Intent.CreateProject(project))
    private val idle = step(projectOnly, Intent.CreateSession(session))

    /**
     * Places [owner] at [at], on a project that already holds it. One builder for the representatives
     * and for the several-sessions test, so both mean the same thing by a position. A run is stopped
     * with `RunStopped(unknown = true)` rather than restored, because a restore turns *every* run of
     * the project unknown and would drag the other session along.
     */
    private fun standing(base: CodingMachine.State, owner: CodingSession, at: PhaseId): CodingMachine.State {
        val ref = CodingMachine.ref(owner)
        val tag = tag(owner)
        fun runOf(state: CodingMachine.State) = state.runs.getValue(owner.id).ref
        val held by lazy {
            val old = step(base, Intent.BeginRun(ref, request("old$tag"), 3))
            val previous = NativeRunRecoveryRef(CodingEngine.PI, owner.id, "old$tag", 0)
            val settled = step(old, Fact.RunStopped(runOf(old), unknown = true), Intent.Abandon(runOf(old), "decision-old", previous),
                Fact.AbandonAcknowledged(runOf(old), NativeRunRecoveryAcknowledgement("ack$tag", previous, "decision-old")))
            step(settled, Intent.BeginRun(ref, request("run$tag"), 4))
        }
        val running by lazy {
            step(held, Fact.RecoveryAcknowledgementConsumed(runOf(held), NativeRunRecoveryConsumption("ack$tag", CodingEngine.PI, owner.id, "run$tag")))
        }
        val unknown by lazy { step(running, Fact.RunStopped(runOf(running), unknown = true)) }
        return when (at) {
            CodingSpace.IDLE -> base
            CodingSpace.QUEUED -> step(base, Intent.Enqueue(ref, request("held$tag")))
            CodingSpace.ARCHIVED -> step(base, Intent.ArchiveSession(ref, true))
            CodingSpace.UNFINISHED_HISTORY -> step(base, Fact.HistoryPublished(ref, listOf(unfinishedRequest(owner))))
            CodingSpace.ANSWERED -> step(running, Fact.RunFinished(runOf(running), CodingMessage(runOf(running).responseId, CodingRole.AGENT, "Done", createdAt = 5)))
            CodingSpace.WORKSPACE_UNKNOWN -> step(base, Fact.WorktreeProjected(ref, null, ChildRevision("workspace", 1, 0, "one"), unknown = true))
            CodingSpace.RUNNING -> running
            CodingSpace.RUNNING_CONFLICT -> step(running, Fact.WorktreeProjected(ref, task, ChildRevision("workspace", 1, 0, "one")))
            CodingSpace.RUNNING_CHECKS_FAILED -> step(running, Fact.WorktreeProjected(ref, checked, ChildRevision("workspace", 1, 0, "one")))
            CodingSpace.RUNNING_RECOVERY_HELD -> held
            CodingSpace.STOPPING -> step(running, Intent.Pause(runOf(running)))
            CodingSpace.INTERRUPTED -> step(running, Fact.RunStopped(runOf(running), unknown = false))
            CodingSpace.UNKNOWN -> unknown
            CodingSpace.ABANDONING_ATTEMPT -> step(unknown, Intent.Abandon(runOf(unknown), "decision", NativeRunRecoveryRef(CodingEngine.PI, owner.id, "run$tag", 0)))
            CodingSpace.ABANDONING_NO_DISPATCH -> step(unknown, Intent.AbandonNotDispatched(runOf(unknown), "decision",
                NativeRunNoDispatchProof(CodingEngine.PI, owner.id, "run$tag", "proof", "epoch")))
            else -> error("${at.name} is not a position of a session")
        }
    }

    private fun unfinishedRequest(owner: CodingSession) = CodingMessage("message-run${tag(owner)}", CodingRole.USER, "Task run", createdAt = 3)
    private fun at(position: PhaseId) = standing(idle, session, position)

    private val running = at(CodingSpace.RUNNING)
    private val ref = running.runs.getValue(session.id).ref
    private val recovery = NativeRunRecoveryRef(CodingEngine.PI, session.id, ref.requestId, 0)
    private val proof = NativeRunNoDispatchProof(CodingEngine.PI, session.id, ref.requestId, "proof", "epoch")
    private val organism = SessionOrganism("organism", project.id, session.id, createdAt = 2,
        sessions = mapOf(session.id to SessionNode(session.id, SessionKind.ZYGOTE, session.name, generation = 0, mode = CodingInteractionMode.CODE)))
    private val response = CodingMessage(ref.responseId, CodingRole.AGENT, "Done", createdAt = 5)
    private val edited = request("edit")
    private val codingModel = CodingModelSelection(CodingEngine.PI, "provider", "model")

    init {
        // Every input that addresses a run carries one reference, and `DeferRecovery` names the message
        // of that run. A representative that ran under another generation, or an unfinished history
        // holding another message, would be refused for a reason the matrix does not describe.
        val holders = listOf(CodingSpace.RUNNING, CodingSpace.RUNNING_CONFLICT, CodingSpace.RUNNING_CHECKS_FAILED, CodingSpace.RUNNING_RECOVERY_HELD, CodingSpace.STOPPING,
            CodingSpace.INTERRUPTED, CodingSpace.UNKNOWN, CodingSpace.ABANDONING_ATTEMPT, CodingSpace.ABANDONING_NO_DISPATCH)
        holders.forEach { assertEquals(ref, at(it).runs.getValue(session.id).ref, "${it.name} runs under another reference") }
        assertEquals(ref.messageId, unfinishedRequest(session).id)
        assertEquals(2, ref.generation)
    }

    @Test fun clarifyingARequestThatIsNotQueuedIsRefusedInsteadOfEscapingTheReducer() {
        val note = CodingMessage("note", CodingRole.USER, "More detail", createdAt = 4)
        // Nothing is queued under the id; a run that already started is no longer queued; a queue holds another id.
        val cases = listOf(idle to "held", at(CodingSpace.RUNNING) to ref.requestId, at(CodingSpace.QUEUED) to "another")
        for ((state, id) in cases) {
            val step = CodingMachine.reduce(state, Intent.QueuedClarified(sessionRef, id, note, emptyList()))
            assertEquals(state, step.state, "a clarification of $id changed the state")
            assertTrue(step.effects.single() is Effect.Reject, "a clarification of $id was not refused")
        }
    }

    /**
     * The harness drives single-session representatives, so it cannot see how [CodingSpace.label] ranks a
     * project that holds several. Reversing that order would leave it green, and the order is the part
     * that says an unknown outcome outranks everything else.
     */
    @Test fun theSessionThatMattersMostDecidesThePositionOfAProjectHoldingSeveral() {
        val two = step(idle, Intent.CreateSession(other))
        val ranked = listOf(
            CodingSpace.UNKNOWN, CodingSpace.ABANDONING_ATTEMPT, CodingSpace.ABANDONING_NO_DISPATCH, CodingSpace.WORKSPACE_UNKNOWN,
            CodingSpace.INTERRUPTED, CodingSpace.STOPPING, CodingSpace.RUNNING_CONFLICT, CodingSpace.RUNNING_CHECKS_FAILED,
            CodingSpace.RUNNING_RECOVERY_HELD, CodingSpace.RUNNING, CodingSpace.UNFINISHED_HISTORY, CodingSpace.QUEUED, CodingSpace.ANSWERED, CodingSpace.IDLE,
            CodingSpace.ARCHIVED,
        )
        for ((index, higher) in ranked.withIndex()) for (lower in ranked.drop(index + 1)) {
            // Whichever session holds the more pressing position, and in whichever order they were built.
            val first = standing(standing(two, session, higher), other, lower)
            val second = standing(standing(two, other, lower), session, higher)
            val swapped = standing(standing(two, session, lower), other, higher)
            assertEquals(higher, CodingSpace.label(first), "${higher.name} over ${lower.name}")
            assertEquals(higher, CodingSpace.label(second), "${higher.name} over ${lower.name}, built the other way round")
            assertEquals(higher, CodingSpace.label(swapped), "${lower.name} under ${higher.name}")
        }
    }

    /**
     * The harness drives representatives that each carry one fact, so it cannot see which fact wins when
     * a session carries two, nor how the fences rank. Swapping any of these orders would leave it green.
     */
    @Test fun aSessionWithoutARunIsNamedByTheFirstOfItsFactsAndTheFencesOutrankEverything() {
        val workspace = ChildRevision("workspace", 1, 0, "one")
        // An unknown workspace outcome outranks an archived session, which outranks a queue, which outranks a history that asks.
        assertEquals(CodingSpace.WORKSPACE_UNKNOWN, CodingSpace.label(step(at(CodingSpace.ARCHIVED), Fact.WorktreeProjected(sessionRef, null, workspace, unknown = true))))
        assertEquals(CodingSpace.ARCHIVED, CodingSpace.label(step(at(CodingSpace.QUEUED), Intent.ArchiveSession(sessionRef, true))))
        assertEquals(CodingSpace.QUEUED, CodingSpace.label(step(at(CodingSpace.UNFINISHED_HISTORY), Intent.Enqueue(sessionRef, request("held")))))
        // A request asked after a response was given is unfinished, not answered.
        assertEquals(CodingSpace.UNFINISHED_HISTORY, CodingSpace.label(step(at(CodingSpace.ANSWERED), Fact.HistoryPublished(sessionRef, listOf(unfinishedRequest(session))))))
        // Only a response that did not fail is an answer: a history holding nothing but a failure is at rest.
        val failed = CodingMessage("failed", CodingRole.AGENT, "Error", failed = true, createdAt = 3)
        assertEquals(CodingSpace.IDLE, CodingSpace.label(step(idle, Fact.HistoryPublished(sessionRef, listOf(failed)))))
        // An unconfirmed persistence outranks a deleted project, and a project nobody restored.
        assertEquals(CodingSpace.PERSISTENCE_UNKNOWN, CodingSpace.label(step(step(idle, Intent.DeleteProject), Fact.PersistenceUnknown)))
        assertEquals(CodingSpace.PERSISTENCE_UNKNOWN, CodingSpace.label(step(initial, Fact.PersistenceUnknown)))
        assertEquals(CodingSpace.DELETED, CodingSpace.label(step(idle, Intent.DeleteProject)))
    }

    /** Ties and blind spots the KDoc admits to; pinned so the KDoc cannot drift from what `label` does. */
    @Test fun whatThePositionDoesNotNameIsWhatTheDocumentationSays() {
        // A run holding both an acknowledgement and a refusal is named by the refusal.
        val both = step(at(CodingSpace.RUNNING_RECOVERY_HELD), Fact.WorktreeProjected(sessionRef, task, ChildRevision("workspace", 1, 0, "one")))
        assertEquals(CodingSpace.RUNNING_CONFLICT, CodingSpace.label(both))
        val refused = step(at(CodingSpace.RUNNING_RECOVERY_HELD), Fact.WorktreeProjected(sessionRef, checked, ChildRevision("workspace", 1, 0, "one")))
        assertEquals(CodingSpace.RUNNING_CHECKS_FAILED, CodingSpace.label(refused))
        // A merge that nothing refused is not named: the run is only running.
        val merged = step(running, Fact.WorktreeProjected(sessionRef, checked.copy(error = null), ChildRevision("workspace", 1, 0, "one")))
        assertEquals(CodingSpace.RUNNING, CodingSpace.label(merged))
        // An unknown workspace beside a live run is named by the run, and unknown() still reports it.
        val beside = step(running, Fact.WorktreeProjected(sessionRef, null, ChildRevision("workspace", 1, 0, "one"), unknown = true))
        assertEquals(CodingSpace.RUNNING, CodingSpace.label(beside))
        assertTrue(CodingSpace.unknown(beside))
        // A queue beside a run is not named.
        val queuedBeside = step(running, Intent.Enqueue(sessionRef, request("held")))
        assertEquals(CodingSpace.RUNNING, CodingSpace.label(queuedBeside))
        // A stop that was observed is not an unknown outcome; an abandoned one still is.
        assertFalse(CodingSpace.unknown(at(CodingSpace.INTERRUPTED)))
        assertTrue(CodingSpace.unknown(at(CodingSpace.ABANDONING_ATTEMPT)))
        assertTrue(CodingSpace.unknown(at(CodingSpace.ABANDONING_NO_DISPATCH)))
        // A response that failed leaves the run interrupted; only a sound one completes it into `answered`.
        val failedRun = step(at(CodingSpace.RUNNING), Fact.RunFinished(ref, response.copy(failed = true)))
        assertEquals(CodingSpace.INTERRUPTED, CodingSpace.label(failedRun))
    }

    @Test fun declaredSpaceIsClosedAndMatchesEveryTransition() = verifyStateSpace(
        CodingMachine,
        states = mapOf(
            CodingSpace.UNRESTORED to initial,
            CodingSpace.PROJECT_ONLY to projectOnly,
            CodingSpace.IDLE to idle,
            CodingSpace.QUEUED to at(CodingSpace.QUEUED),
            CodingSpace.ARCHIVED to at(CodingSpace.ARCHIVED),
            // Legacy history: a request that was saved and never answered, with no run to say what became of it.
            CodingSpace.UNFINISHED_HISTORY to at(CodingSpace.UNFINISHED_HISTORY),
            CodingSpace.ANSWERED to at(CodingSpace.ANSWERED),
            // A child journal write whose outcome was never confirmed.
            CodingSpace.WORKSPACE_UNKNOWN to at(CodingSpace.WORKSPACE_UNKNOWN),
            CodingSpace.RUNNING to running,
            CodingSpace.RUNNING_CONFLICT to at(CodingSpace.RUNNING_CONFLICT),
            // The merged result was refused by the checks the agent handed off with it.
            CodingSpace.RUNNING_CHECKS_FAILED to at(CodingSpace.RUNNING_CHECKS_FAILED),
            // Started right after an abandoned predecessor: the acknowledgement is not yet handed to the native side.
            CodingSpace.RUNNING_RECOVERY_HELD to at(CodingSpace.RUNNING_RECOVERY_HELD),
            CodingSpace.STOPPING to at(CodingSpace.STOPPING),
            CodingSpace.INTERRUPTED to at(CodingSpace.INTERRUPTED),
            // The run was in flight and its outcome was never observed.
            CodingSpace.UNKNOWN to at(CodingSpace.UNKNOWN),
            CodingSpace.ABANDONING_ATTEMPT to at(CodingSpace.ABANDONING_ATTEMPT),
            CodingSpace.ABANDONING_NO_DISPATCH to at(CodingSpace.ABANDONING_NO_DISPATCH),
            CodingSpace.DELETED to step(idle, Intent.DeleteProject),
            CodingSpace.PERSISTENCE_UNKNOWN to step(running, Fact.PersistenceUnknown),
        ),
        inputs = mapOf(
            CodingSpace.CREATE_PROJECT to Intent.CreateProject(project),
            CodingSpace.SET_PROJECT_MODEL to Intent.SetProjectModel(null),
            CodingSpace.SET_PROJECT_CODING_MODEL to Intent.SetProjectCodingModel(codingModel),
            CodingSpace.DELETE_PROJECT to Intent.DeleteProject,
            CodingSpace.CREATE_SESSION to Intent.CreateSession(session.copy(id = "created")),
            CodingSpace.DELETE_SESSION to Intent.DeleteSession(sessionRef),
            CodingSpace.RENAME_SESSION to Intent.RenameSession(sessionRef, "Mine"),
            CodingSpace.ARCHIVE to Intent.ArchiveSession(sessionRef, archived = true),
            CodingSpace.UNARCHIVE to Intent.ArchiveSession(sessionRef, archived = false),
            CodingSpace.SET_SESSION_MODEL to Intent.SetSessionModel(sessionRef, null),
            CodingSpace.SET_SESSION_CODING_MODEL to Intent.SetSessionCodingModel(sessionRef, codingModel),
            CodingSpace.SET_SEARCH_PROVIDER to Intent.SetSearchProvider(sessionRef, SearchProvider.WIKIPEDIA),
            CodingSpace.CHANGE_MODE to Intent.ChangeMode(sessionRef, CodingInteractionMode.RESEARCH),
            CodingSpace.SET_MEDIA_TOOL to Intent.SetMediaTool(sessionRef, MediaKind.IMAGE, enabled = false),
            CodingSpace.SET_FEATURE_FLAG to Intent.SetFeatureFlag(sessionRef, FeatureFlag.entries.first(), enabled = true),
            CodingSpace.SET_WORKTREE_ENABLED to Intent.SetWorktreeEnabled(sessionRef, enabled = false),
            CodingSpace.VERIFY_RESPONSE to Intent.VerifyResponse(sessionRef, ref.responseId, verified = true),
            CodingSpace.UNLINK_PROFILE to Intent.UnlinkProfile("profile"),
            CodingSpace.ENQUEUE to Intent.Enqueue(sessionRef, request("extra")),
            CodingSpace.QUEUED_CLARIFIED to Intent.QueuedClarified(sessionRef, "held",
                CodingMessage("note", CodingRole.USER, "More detail", createdAt = 4), emptyList()),
            CodingSpace.BEGIN_RUN to Intent.BeginRun(sessionRef, request("next"), 6),
            CodingSpace.BEGIN_REPAIR to Intent.BeginRepair(ref, "repair", ChildRevision("workspace", 1, 0, "one")),
            CodingSpace.PAUSE to Intent.Pause(ref),
            CodingSpace.DEFER_RECOVERY to Intent.DeferRecovery(sessionRef, ref.messageId),
            CodingSpace.CLARIFY to Intent.Clarify(ref, request("clarified"),
                CodingMessage("message-clarified", CodingRole.USER, "Task clarified", createdAt = 4)),
            CodingSpace.ABANDON to Intent.Abandon(ref, "decision", recovery),
            CodingSpace.ABANDON_NOT_DISPATCHED to Intent.AbandonNotDispatched(ref, "decision", proof),
            CodingSpace.DISCARD_INTERRUPTED to Intent.DiscardInterrupted(ref),
            CodingSpace.EDIT_REQUEST to Intent.EditRequest(sessionRef, emptyList(),
                listOf(CodingMessage(edited.messageId, CodingRole.USER, edited.prompt, createdAt = 4)), edited),
            CodingSpace.REPLACE_HISTORY to Intent.ReplaceHistory(sessionRef, emptyList(),
                listOf(CodingMessage("replacement", CodingRole.USER, "Replacement", createdAt = 4))),
            CodingSpace.ORCHESTRATE to Intent.Orchestrate(sessionRef, OrchestrationEvent.Restore),
            CodingSpace.LEGACY_IMPORTED to Fact.LegacyImported(project, listOf(session), emptyMap()),
            CodingSpace.HISTORY_PUBLISHED to Fact.HistoryPublished(sessionRef, listOf(unfinishedRequest(session))),
            CodingSpace.STATUS_OBSERVED to Fact.StatusObserved(sessionRef, CodingSessionStatus.WORKING, 5),
            CodingSpace.ARCHIVE_READINESS_OBSERVED to Fact.ArchiveReadinessObserved(sessionRef, session, ready = true, at = 5, delay = 10),
            CodingSpace.NATIVE_SESSION_BOUND to Fact.NativeSessionBound(ref, "native"),
            CodingSpace.RUN_FINISHED to Fact.RunFinished(ref, response),
            CodingSpace.RUN_FINISHED_UNKNOWN to Fact.RunFinished(ref, response, outcomeKnown = false),
            CodingSpace.RUN_OUTPUT_PUBLISHED to Fact.RunOutputPublished(ref, CodingMessage("output", CodingRole.AGENT, "Partial", createdAt = 4)),
            CodingSpace.RUN_STOPPED to Fact.RunStopped(ref, unknown = false),
            CodingSpace.RUN_STOPPED_UNKNOWN to Fact.RunStopped(ref, unknown = true),
            CodingSpace.ABANDON_ACKNOWLEDGED to Fact.AbandonAcknowledged(ref, NativeRunRecoveryAcknowledgement("ack-run", recovery, "decision")),
            CodingSpace.NO_DISPATCH_ACKNOWLEDGED to Fact.NoDispatchAcknowledged(ref, NativeRunNoDispatchAcknowledgement("ack-no-dispatch", proof, "decision")),
            CodingSpace.RECOVERY_ACKNOWLEDGEMENT_CONSUMED to Fact.RecoveryAcknowledgementConsumed(ref,
                NativeRunRecoveryConsumption("ack", CodingEngine.PI, session.id, ref.requestId)),
            CodingSpace.TITLE_REQUESTED to Fact.TitleRequested(sessionRef, "title"),
            CodingSpace.PROMPT_NAMED to Fact.PromptNamed(sessionRef, "Prompt", localSummaryAllowed = false),
            CodingSpace.TITLE_OBSERVED to Fact.TitleObserved(sessionRef, "title", "Short"),
            CodingSpace.PLAN_SESSION_BOUND to Fact.PlanSessionBound(sessionRef, CodingSessionRole.CHAT),
            CodingSpace.ORGANISM_PROJECTED to Fact.OrganismProjected(organism, ChildRevision("organism", 1, 0, "input")),
            // Newer than every revision the representatives hold, so a projection is never a repeat.
            CodingSpace.WORKTREE_PROJECTED to Fact.WorktreeProjected(sessionRef, task, ChildRevision("workspace", 200, 0, "newer")),
            CodingSpace.WORKTREE_UNKNOWN to Fact.WorktreeProjected(sessionRef, null, ChildRevision("workspace", 100, 0, "uncertain"), unknown = true),
            CodingSpace.WORKTREE_UNAVAILABLE to Fact.WorktreeUnavailable(ref),
            CodingSpace.RESTORED to Fact.Restored,
            CodingSpace.PERSISTENCE_UNKNOWN_FACT to Fact.PersistenceUnknown,
        ),
    )
}
