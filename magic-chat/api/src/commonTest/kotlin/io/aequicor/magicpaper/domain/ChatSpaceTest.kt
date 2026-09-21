package io.aequicor.magicpaper.domain

import io.aequicor.magicpaper.domain.ChatMachine.Effect
import io.aequicor.magicpaper.domain.ChatMachine.Fact
import io.aequicor.magicpaper.domain.ChatMachine.Intent
import io.aequicor.magicpaper.machine.Branch
import io.aequicor.magicpaper.machine.MachineId
import io.aequicor.magicpaper.machine.PhaseId
import io.aequicor.magicpaper.machine.Step
import io.aequicor.magicpaper.machine.verifyStateSpace
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The representatives of [ChatSpace], kept here rather than in the api so a shipped binary — the browser
 * bundle included — carries no fixtures.
 *
 * Each one is built by running the machine from `initial`, never by constructing a state, which is what
 * the `internal constructor` on `State` is there to enforce. Every position that holds a run goes through
 * the same first request, because the run reference names its generation and every representative of a
 * run position must carry the same reference for the one input value per name to address it. Every live
 * representative holds the one resource `site`, so that the inputs that name a resource are refused for
 * the reason the matrix describes and not because the record is missing.
 */
class ChatSpaceTest {
    private val site = ResearchResource("site", "Reference", "https://example.test/", discovered = true)
    private val found = ResearchResource("found", "Found", "https://example.test/found", discovered = true)

    /** Ids carry the session, so two sessions of one notebook never share a request, message or response. */
    private fun tag(id: String) = if (id == "chat") "" else "-$id"
    private fun request(id: String, messageId: String = id, text: String = "Question") =
        CodingRunCheckpoint(messageId, text, runId = id, responseId = "$id:answer", responseTimelineId = "$id:timeline")
    private fun reply(ref: ChatMachine.RunRef) = ChatMessage(ref.responseId, ChatRole.AGENT, "Answer", 5, followUps = listOf("Explain"))
    private fun step(state: ChatMachine.State, vararg inputs: ChatMachine.Input) = inputs.fold(state) { current, input ->
        ChatMachine.step(current, input).also { assertTrue(it.effects.none(ChatSpace::rejected), "$input was refused: ${it.effects}") }.state
    }
    private fun refused(state: ChatMachine.State, input: ChatMachine.Input) = ChatMachine.step(state, input).let {
        assertEquals(state, it.state, "$input changed the state it refused")
        assertTrue(it.effects.single() is Effect.Reject, "$input was accepted: ${it.effects}")
    }

    private val initial = ChatMachine.initial()
    private val created = step(initial, Intent.CreateNotebook("chat", 1))
    private val idle = step(created, Intent.AddResources("chat", false, listOf(site)))

    /**
     * Places session [id] at [at], on a notebook that already holds it. One builder for the representatives
     * and for the several-sessions tests, so both mean the same thing by a position.
     */
    private fun standing(base: ChatMachine.State, id: String, at: PhaseId): ChatMachine.State {
        val tag = tag(id)
        val started by lazy { step(base, Intent.Submit(id, request("request$tag"), 2)) }
        fun ref(state: ChatMachine.State) = state.runs.getValue(id).ref
        val unknown by lazy { step(started, Fact.RunStopped(ref(started), unknown = true, at = 3)) }
        return when (at) {
            ChatSpace.EMPTY -> base
            ChatSpace.SETTLED -> step(started, Fact.ReplyStored(ref(started), reply(ref(started))))
            // A request that waited behind the one that just completed: nothing has advanced the queue yet.
            ChatSpace.QUEUED -> step(started, Intent.Submit(id, request("queued$tag"), 3), Fact.ReplyStored(ref(started), reply(ref(started))))
            ChatSpace.RUNNING -> started
            ChatSpace.STOPPING -> step(started, Intent.Pause(ref(started)))
            ChatSpace.INTERRUPTED -> step(started, Fact.RunStopped(ref(started), unknown = false, at = 3))
            ChatSpace.UNKNOWN -> unknown
            ChatSpace.RECOVERING -> step(unknown, Intent.Recover(ref(unknown)))
            else -> error("${at.name} is not a position of a session")
        }
    }

    private val sessionPositions = listOf(
        ChatSpace.UNKNOWN, ChatSpace.RECOVERING, ChatSpace.INTERRUPTED, ChatSpace.STOPPING,
        ChatSpace.RUNNING, ChatSpace.QUEUED, ChatSpace.SETTLED, ChatSpace.EMPTY,
    )
    private val two = step(idle, Intent.CreateQuestion("child", "chat", 2))

    private val running = standing(idle, "chat", ChatSpace.RUNNING)
    private val ref = running.runs.getValue("chat").ref
    private val proof = ChatMachine.OutputProof(ref.runId, "attempt", "identity", "digest")
    private val source = running.sessions.getValue("chat").copy(nativeSessionId = "native", engine = CodingEngine.CODEX)
    private val legacy = ChatSession("chat", "Old", 1, 2, pendingRun = request("legacy"))
    private val imported = ChatSession("chat", "Imported", 1, 2, pendingRun = request("imported"))

    init {
        // Every representative that holds a run must hold the same one, or an input naming it is refused for the wrong reason.
        for (position in listOf(ChatSpace.RUNNING, ChatSpace.STOPPING, ChatSpace.INTERRUPTED, ChatSpace.UNKNOWN, ChatSpace.RECOVERING)) {
            assertEquals(ref, standing(idle, "chat", position).runs.getValue("chat").ref, "${position.name} runs under another reference")
        }
        assertEquals(setOf("request"), source.messages.map { it.id }.toSet())
    }

    private val representatives = mapOf(
        ChatSpace.CREATE_NOTEBOOK to Intent.CreateNotebook("chat", 1),
        ChatSpace.IMPORT_NOTEBOOK to Intent.ImportNotebook(listOf(imported)),
        ChatSpace.FORK_NOTEBOOK to Intent.ForkNotebook(source, source, "fork", 10, mapOf("request" to "copy")),
        ChatSpace.CREATE_QUESTION to Intent.CreateQuestion("child", "chat", 4),
        ChatSpace.SELECT_QUESTION to Intent.SelectQuestion("chat"),
        ChatSpace.SUBMIT to Intent.Submit("chat", request("next"), 4),
        ChatSpace.FOLLOW_UP to Intent.FollowUp("chat", ref.responseId, request("follow", text = "Explain"), 6),
        ChatSpace.ADVANCE_QUEUE to Intent.AdvanceQueue("chat", 4),
        ChatSpace.PAUSE to Intent.Pause(ref),
        ChatSpace.CLARIFY to Intent.Clarify(ref, request("clarified", ref.messageId, "Question\nClarification"), 3),
        ChatSpace.RECOVER to Intent.Recover(ref),
        ChatSpace.DISCARD to Intent.Discard(ref, 4),
        ChatSpace.EDIT_MESSAGE to Intent.EditMessage("chat", ref.messageId, "Question", request("edit", ref.messageId), 7),
        ChatSpace.DELETE_MESSAGE to Intent.DeleteMessage("chat", ref.messageId, 7),
        ChatSpace.SET_MODEL to Intent.SetModel("chat", ModelSelection("profile", "model")),
        ChatSpace.UNLINK_PROFILE to Intent.UnlinkProfile("profile"),
        ChatSpace.SET_MEDIA_TOOL to Intent.SetMediaTool(MediaKind.IMAGE, enabled = false),
        ChatSpace.ADD_RESOURCES to Intent.AddResources("chat", false, listOf(found)),
        ChatSpace.REMOVE_RESOURCE to Intent.RemoveResource("chat", false, site.id),
        ChatSpace.SHARE_RESOURCE to Intent.ShareResource("chat", site.id),
        ChatSpace.SELECT_RESOURCES to Intent.SelectResources("chat", setOf(site.key), false),
        ChatSpace.ARCHIVE to Intent.Archive("chat", archived = true, at = 8),
        ChatSpace.UNARCHIVE to Intent.Archive("chat", archived = false, at = 8),
        ChatSpace.DELETE to Intent.Delete("chat"),
        ChatSpace.LEGACY_IMPORTED to Fact.LegacyImported("chat", listOf(legacy)),
        ChatSpace.LEGACY_HYDRATED to Fact.LegacyHydrated("chat", emptyList(), emptyList(), null),
        ChatSpace.EXTENSION_BOUND to Fact.ExtensionBound(ref, "binding"),
        ChatSpace.PROGRESS to Fact.Progress(ref, emptyList(), emptyList()),
        ChatSpace.RESPONSE_CONTEXT_PREPARED to Fact.ResponseContextPrepared(ref, ChatMachine.ResponseContext(false, emptyList(), emptyList())),
        ChatSpace.NATIVE_SESSION_BOUND to Fact.NativeSessionBound(ref, "native"),
        ChatSpace.SOURCES_DISCOVERED to Fact.SourcesDiscovered(ref, listOf(found), shared = false),
        ChatSpace.REPLY_STORED to Fact.ReplyStored(ref, reply(ref)),
        ChatSpace.RUN_STOPPED to Fact.RunStopped(ref, unknown = false, at = 4),
        ChatSpace.RUN_STOPPED_UNKNOWN to Fact.RunStopped(ref, unknown = true, at = 4),
        ChatSpace.RECOVERED_REPLY to Fact.RecoveredReply(ref, proof, reply(ref)),
        ChatSpace.RECOVERY_UNAVAILABLE to Fact.RecoveryUnavailable(ref, unknown = false),
        ChatSpace.RECOVERY_UNAVAILABLE_UNKNOWN to Fact.RecoveryUnavailable(ref, unknown = true),
        ChatSpace.RESTORED to Fact.Restored,
        ChatSpace.PERSISTENCE_UNKNOWN_FACT to Fact.PersistenceUnknown,
    )

    /**
     * The harness drives single-session representatives, so it cannot see how [ChatSpace.label] ranks a
     * notebook that holds several. Reversing that order would leave it green, and the order is the part
     * that says an unknown outcome outranks everything else and a run outranks its own queue.
     */
    @Test fun theSessionThatMattersMostDecidesThePositionOfANotebookHoldingSeveral() {
        for ((index, higher) in sessionPositions.withIndex()) for (lower in sessionPositions.drop(index + 1)) {
            // Whichever session holds the more pressing position, and in whichever order they were built.
            val first = standing(standing(two, "chat", higher), "child", lower)
            val second = standing(standing(two, "child", lower), "chat", higher)
            val swapped = standing(standing(two, "chat", lower), "child", higher)
            assertEquals(higher, ChatSpace.label(first), "${higher.name} over ${lower.name}")
            assertEquals(higher, ChatSpace.label(second), "${higher.name} over ${lower.name}, built the other way round")
            assertEquals(higher, ChatSpace.label(swapped), "${lower.name} under ${higher.name}")
        }
    }

    /** What a session holds beside its run, or a queue beside nothing, is named only where the reducer looks at it. */
    @Test fun aSessionIsNamedByItsRunFirstThenItsQueueThenItsHistory() {
        // A queue beside a run does not change what any input does, so the run names the position.
        assertEquals(ChatSpace.RUNNING, ChatSpace.label(step(running, Intent.Submit("chat", request("waiting"), 3))))
        assertEquals(ChatSpace.UNKNOWN, ChatSpace.label(step(standing(idle, "chat", ChatSpace.UNKNOWN), Intent.Submit("chat", request("waiting"), 3))))
        // The queue names a session whether or not it has a history, and outranks the history it does have.
        val queuedOnly = step(initial, Fact.LegacyImported("chat", listOf(ChatSession("chat", "Old", 1, 2, queuedPrompts = listOf(request("queued"))))))
        assertTrue(queuedOnly.notebook!!.messages.isEmpty())
        assertEquals(ChatSpace.QUEUED, ChatSpace.label(queuedOnly))
        assertTrue(standing(idle, "chat", ChatSpace.QUEUED).notebook!!.messages.isNotEmpty())
        assertEquals(ChatSpace.QUEUED, ChatSpace.label(standing(idle, "chat", ChatSpace.QUEUED)))
        // A notebook whose only message is in a question is settled, and a question with nothing is empty.
        assertEquals(ChatSpace.SETTLED, ChatSpace.label(standing(two, "child", ChatSpace.SETTLED)))
        assertEquals(ChatSpace.EMPTY, ChatSpace.label(two))
    }

    /** The fences rank in the order the reducer checks them, and none of them is a run position. */
    @Test fun anUnconfirmedWriteOutranksADeletedNotebookWhichOutranksOneNobodyCreated() {
        val deleted = step(idle, Intent.Delete("chat"))
        assertEquals(ChatSpace.UNRESTORED, ChatSpace.label(initial))
        assertEquals(ChatSpace.DELETED, ChatSpace.label(deleted))
        assertEquals(ChatSpace.DELETED, ChatSpace.label(step(deleted, Fact.Restored)))
        assertEquals(ChatSpace.PERSISTENCE_UNKNOWN, ChatSpace.label(step(deleted, Fact.PersistenceUnknown)))
        assertEquals(ChatSpace.PERSISTENCE_UNKNOWN, ChatSpace.label(step(initial, Fact.PersistenceUnknown)))
        // A run in flight is hidden behind the fence, and so is every other position.
        for (position in sessionPositions) {
            assertEquals(ChatSpace.PERSISTENCE_UNKNOWN, ChatSpace.label(step(standing(idle, "chat", position), Fact.PersistenceUnknown)), position.name)
            assertEquals(ChatSpace.DELETED, ChatSpace.label(step(standing(idle, "chat", position), Intent.Delete("chat"))), position.name)
        }
        // Deleting one question leaves the notebook where its remaining sessions put it.
        assertEquals(ChatSpace.RUNNING, ChatSpace.label(step(standing(two, "chat", ChatSpace.RUNNING), Intent.Delete("child"))))
        assertEquals(ChatSpace.EMPTY, ChatSpace.label(step(standing(two, "child", ChatSpace.RUNNING), Intent.Delete("child"))))
    }

    /**
     * `unknown(state)` is not a second question but the same one: the states it holds for are the three
     * positions that name an outcome nobody confirmed, wherever they come from and whatever else the
     * notebook holds beside them. Each clause of the predicate is the only reason for one of them.
     */
    @Test fun unknownIsExactlyThePositionsThatNameAnOutcomeNobodyConfirmed() {
        val unconfirmed = setOf(ChatSpace.UNKNOWN, ChatSpace.RECOVERING, ChatSpace.PERSISTENCE_UNKNOWN)
        val states = sessionPositions.map { standing(idle, "chat", it) } + listOf(initial, created, step(idle, Intent.Delete("chat")),
            step(idle, Fact.PersistenceUnknown))
        for (state in states) assertEquals(ChatSpace.label(state) in unconfirmed, ChatSpace.unknown(state), "${ChatSpace.label(state)}")
        // A notebook that only holds the fence: no run says anything, so the persistence flag is the whole reason.
        assertTrue(step(idle, Fact.PersistenceUnknown).runs.isEmpty())
        assertTrue(ChatSpace.unknown(step(idle, Fact.PersistenceUnknown)))
        // Each run phase alone, and beside a run that is only in flight.
        assertTrue(ChatSpace.unknown(standing(idle, "chat", ChatSpace.UNKNOWN)))
        assertTrue(ChatSpace.unknown(standing(idle, "chat", ChatSpace.RECOVERING)))
        for (inFlight in listOf(ChatSpace.RUNNING, ChatSpace.STOPPING, ChatSpace.INTERRUPTED)) {
            assertFalse(ChatSpace.unknown(standing(idle, "chat", inFlight)), inFlight.name)
            assertTrue(ChatSpace.unknown(standing(standing(two, "chat", inFlight), "child", ChatSpace.UNKNOWN)), "unknown beside ${inFlight.name}")
            assertTrue(ChatSpace.unknown(standing(standing(two, "chat", inFlight), "child", ChatSpace.RECOVERING)), "recovering beside ${inFlight.name}")
        }
        assertFalse(ChatSpace.unknown(standing(standing(two, "chat", ChatSpace.RUNNING), "child", ChatSpace.STOPPING)))
        // A restart turns a run in flight or under inspection into an unknown one, and leaves a known stop alone.
        assertEquals(ChatSpace.UNKNOWN, ChatSpace.label(step(standing(idle, "chat", ChatSpace.RUNNING), Fact.Restored)))
        assertEquals(ChatSpace.UNKNOWN, ChatSpace.label(step(standing(idle, "chat", ChatSpace.STOPPING), Fact.Restored)))
        assertEquals(ChatSpace.UNKNOWN, ChatSpace.label(step(standing(idle, "chat", ChatSpace.RECOVERING), Fact.Restored)))
        assertEquals(ChatSpace.INTERRUPTED, ChatSpace.label(step(standing(idle, "chat", ChatSpace.INTERRUPTED), Fact.Restored)))
        assertEquals(ChatSpace.UNKNOWN, ChatSpace.label(step(standing(idle, "chat", ChatSpace.UNKNOWN), Fact.Restored)))
    }

    /**
     * The harness runs each input once, from each position, with one payload. Where the payload decides
     * where a transition lands, the other landing is named here, so that closure covers it too.
     */
    @Test fun payloadsThatLandElsewhereFromTheSamePositionStayInsideTheDeclaredSpace() {
        // A clarification that stopped a running request restarts it when the stop is known, and leaves it unknown otherwise.
        val clarifying = step(running, Intent.Clarify(ref, request("clarified", ref.messageId, "Question\nClarification"), 3))
        assertEquals(ChatSpace.STOPPING, ChatSpace.label(clarifying))
        val restarted = step(clarifying, Fact.RunStopped(ref, unknown = false, at = 4))
        assertEquals(ChatSpace.RUNNING, ChatSpace.label(restarted))
        assertEquals("clarified", restarted.runs.getValue("chat").ref.runId)
        assertEquals(ChatSpace.UNKNOWN, ChatSpace.label(step(clarifying, Fact.RunStopped(ref, unknown = true, at = 4))))
        // A reply that beats the stop completes the run and leaves the clarification waiting in the queue.
        assertEquals(ChatSpace.QUEUED, ChatSpace.label(step(clarifying, Fact.ReplyStored(ref, reply(ref)))))
        // Discarding leaves the queue where it was.
        val unknown = standing(idle, "chat", ChatSpace.UNKNOWN)
        assertEquals(ChatSpace.SETTLED, ChatSpace.label(step(unknown, Intent.Discard(ref, 5))))
        assertEquals(ChatSpace.QUEUED, ChatSpace.label(step(step(unknown, Intent.Submit("chat", request("waiting"), 4)), Intent.Discard(ref, 5))))
        // A saved notebook lands at rest unless it holds a request nobody finished.
        val plain = ChatSession("chat", "Old", 1, 2, messages = listOf(ChatMessage("request", ChatRole.USER, "Question", 1)))
        assertEquals(ChatSpace.UNKNOWN, ChatSpace.label(step(initial, Fact.LegacyImported("chat", listOf(legacy)))))
        assertEquals(ChatSpace.SETTLED, ChatSpace.label(step(initial, Fact.LegacyImported("chat", listOf(plain)))))
        assertEquals(ChatSpace.EMPTY, ChatSpace.label(step(initial, Fact.LegacyImported("chat", listOf(plain.copy(messages = emptyList()))))))
        assertEquals(ChatSpace.SETTLED, ChatSpace.label(step(idle, Intent.ImportNotebook(listOf(plain)))))
        assertEquals(ChatSpace.EMPTY, ChatSpace.label(step(idle, Intent.ImportNotebook(listOf(plain.copy(messages = emptyList()))))))
        // A fork keeps the history it was given and never a request.
        assertEquals(ChatSpace.SETTLED, ChatSpace.label(step(initial, Intent.ForkNotebook(source, source, "fork", 10, mapOf("request" to "copy")))))
        assertEquals(ChatSpace.EMPTY, ChatSpace.label(step(initial, Intent.ForkNotebook(source.copy(messages = emptyList()), source.copy(messages = emptyList()), "fork", 10, emptyMap()))))
        // An inspection that finds nothing settles at the position the run came from, or at the unknown one.
        val recovering = standing(idle, "chat", ChatSpace.RECOVERING)
        assertEquals(ChatSpace.INTERRUPTED, ChatSpace.label(step(recovering, Fact.RecoveryUnavailable(ref, unknown = false, missing = true))))
        assertEquals(ChatSpace.UNKNOWN, ChatSpace.label(step(recovering, Fact.RecoveryUnavailable(ref, unknown = true))))
    }

    /**
     * What the matrix leaves to identity and payload. Each of these is refused at a position that accepts
     * the input for another payload, and none of them changes the position.
     */
    @Test fun refusalsThatDependOnWhatTheInputNamesAreNotPositions() {
        val settled = standing(idle, "chat", ChatSpace.SETTLED)
        // An answer that offers no follow-up, a message that is not there, a resource nobody added.
        refused(settled, Intent.FollowUp("chat", ref.responseId, request("follow", text = "Not offered"), 6))
        refused(settled, Intent.FollowUp("chat", "stale", request("follow", text = "Explain"), 6))
        refused(settled, Intent.EditMessage("chat", "missing", "Question", request("edit", "missing"), 7))
        refused(settled, Intent.DeleteMessage("chat", "missing", 7))
        refused(created, Intent.RemoveResource("chat", false, "missing"))
        refused(created, Intent.ShareResource("chat", "missing"))
        refused(created, Intent.SelectQuestion("missing"))
        // A run reference that is not the current one.
        refused(running, Intent.Pause(ref.copy(generation = ref.generation + 1)))
        refused(running, Fact.Progress(ref.copy(runId = "other"), emptyList(), emptyList()))
        // An archive that is automatic is refused before its time and is never an unarchive; a manual one is not timed.
        val idleAt = settled.notebook!!.updatedAt
        refused(settled, Intent.Archive("chat", archived = true, at = idleAt + 1, automatic = true))
        refused(settled, Intent.Archive("chat", archived = false, at = Long.MAX_VALUE / 2, automatic = true))
        assertEquals(ChatSpace.SETTLED, ChatSpace.label(step(settled, Intent.Archive("chat", archived = true, at = Long.MAX_VALUE / 2, automatic = true))))
        assertTrue(step(settled, Intent.Archive("chat", archived = true, at = 8)).notebook!!.archived)
    }

    /** The branch the space declares for each of the thirty-nine inputs is the family the input actually belongs to. */
    @Test fun eachInputsDeclaredBranchIsItsFamily() {
        assertEquals(ChatSpace.inputs.map { it.id }.toSet(), representatives.keys)
        for ((id, input) in representatives) {
            assertEquals(if (input is Intent) Branch.INTENT else Branch.FACT, ChatSpace.inputs.single { it.id == id }.branch, id.name)
            assertFalse(input is Intent && input is Fact)
        }
        assertEquals(24, representatives.values.count { it is Intent })
        assertEquals(15, representatives.values.count { it is Fact })
    }

    @Test fun theMachineIsTheSharedContractOverItsOwnReducer() {
        assertEquals(MachineId("chat"), ChatMachine.id)
        assertSame(ChatSpace, ChatMachine.space)
        for (state in listOf(initial, idle, running)) for ((_, input) in representatives) {
            val transition = ChatMachine.reduce(state, input)
            assertEquals(Step(transition.state, transition.effects), ChatMachine.step(state, input))
        }
    }

    private val states = mapOf(
        ChatSpace.UNRESTORED to initial,
        ChatSpace.EMPTY to idle,
        ChatSpace.SETTLED to standing(idle, "chat", ChatSpace.SETTLED),
        // A request that waited behind one that completed: only an explicit action starts it.
        ChatSpace.QUEUED to standing(idle, "chat", ChatSpace.QUEUED),
        ChatSpace.RUNNING to running,
        ChatSpace.STOPPING to standing(idle, "chat", ChatSpace.STOPPING),
        ChatSpace.INTERRUPTED to standing(idle, "chat", ChatSpace.INTERRUPTED),
        // The run was in flight and its outcome was never observed.
        ChatSpace.UNKNOWN to standing(idle, "chat", ChatSpace.UNKNOWN),
        ChatSpace.RECOVERING to standing(idle, "chat", ChatSpace.RECOVERING),
        ChatSpace.DELETED to step(idle, Intent.Delete("chat")),
        ChatSpace.PERSISTENCE_UNKNOWN to step(running, Fact.PersistenceUnknown),
    )

    /**
     * The harness only checks that an effect's name is declared, so two names swapped, or a name nobody
     * ever emits, would leave it green. Each effect is named here for what it asks the executor to do, and
     * the declaration holds exactly the effects some transition from a representative can owe.
     */
    @Test fun eachEffectIsNamedForWhatItAsksTheExecutorToDo() {
        fun owed(state: ChatMachine.State, input: ChatMachine.Input) = ChatMachine.step(state, input).effects.map { ChatSpace.name(it).name }
        assertEquals(listOf("RequestAccepted", "RunRequest"), owed(idle, Intent.Submit("chat", request("next"), 4)))
        assertEquals(listOf("AbortRequest"), owed(running, Intent.Pause(ref)))
        assertEquals(listOf("InspectSavedOutput"), owed(states.getValue(ChatSpace.UNKNOWN), Intent.Recover(ref)))
        assertEquals(listOf("RequestCompleted"), owed(running, Fact.ReplyStored(ref, reply(ref))))
        assertEquals(listOf("AbortRequest", "CleanupDeleted"), owed(running, Intent.Delete("chat")))
        assertEquals(listOf("Reject"), owed(idle, Intent.Pause(ref)))
        val emitted = states.values.flatMap { state -> representatives.values.flatMap { input -> ChatMachine.step(state, input).effects } }
        assertEquals(ChatSpace.effects.toSet(), emitted.map(ChatSpace::name).toSet())
    }

    @Test fun declaredSpaceIsClosedAndMatchesEveryTransition() = verifyStateSpace(ChatMachine, states, representatives)
}
