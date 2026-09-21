package io.aequicor.magicpaper.domain

import io.aequicor.magicpaper.domain.ChatMachine.Effect
import io.aequicor.magicpaper.domain.ChatMachine.Fact
import io.aequicor.magicpaper.domain.ChatMachine.Input
import io.aequicor.magicpaper.domain.ChatMachine.Intent
import io.aequicor.magicpaper.domain.ChatMachine.Phase
import io.aequicor.magicpaper.domain.ChatMachine.State
import kotlin.test.*

class ChatMachineTest {
    private fun request(id: String = "request", messageId: String = id, text: String = "Question") =
        CodingRunCheckpoint(messageId, text, runId = id, responseId = "$id:answer", responseTimelineId = "$id:timeline")
    private fun step(state: State, input: Input): State {
        val result = ChatMachine.reduce(state, input)
        assertTrue(result.effects.none { it is Effect.Reject }, "$input: ${result.effects}")
        return result.state
    }
    private fun created() = step(ChatMachine.initial(), Intent.CreateNotebook("chat", 1))
    private fun running() = step(created(), Intent.Submit("chat", request(), 2))
    private fun stopped(unknown: Boolean): State {
        val running = running()
        return step(running, Fact.RunStopped(running.runs.getValue("chat").ref, unknown, at = 3))
    }
    private fun reply(ref: ChatMachine.RunRef) = ChatMessage(ref.responseId, ChatRole.AGENT, "Answer", 5)
    private fun rejected(state: State, input: Input) {
        val transition = ChatMachine.reduce(state, input)
        assertEquals(state, transition.state)
        assertTrue(transition.effects.single() is Effect.Reject, "$input: ${transition.effects}")
    }

    @Test fun stateInputTableGuardsExecutionRecoveryAndDeletion() {
        val absent = ChatMachine.initial()
        val idle = created()
        val running = running()
        val ref = running.runs.getValue("chat").ref
        val stopping = step(running, Intent.Pause(ref))
        val stopped = step(stopping, Fact.RunStopped(ref, false, at = 3))
        val unknown = step(running, Fact.RunStopped(ref, true, at = 3))
        val recovering = step(unknown, Intent.Recover(ref))
        val deleted = step(running, Intent.Delete("chat"))
        val persistence = step(running, Fact.PersistenceUnknown)
        val states = listOf(absent, idle, running, stopping, stopped, unknown, recovering, deleted, persistence)
        val inputs = listOf(Intent.CreateQuestion("child", "chat", 4), Intent.Submit("chat", request("next"), 4),
            Intent.Pause(ref), Intent.Recover(ref), Intent.Discard(ref, 4), Intent.Delete("chat"),
            Fact.ReplyStored(ref, reply(ref)), Fact.Progress(ref, emptyList(), emptyList()))
        // Explicit acceptance table: each row is a state, each column an input above.
        val allowed = listOf(
            "00000000", "11000100", "11100111", "11000111", "11011100",
            "11011100", "11000100", "00000000", "00000000",
        )
        states.forEachIndexed { i, state -> inputs.forEachIndexed { j, input ->
            val next = ChatMachine.reduce(state, input)
            assertEquals(allowed[i][j] == '1', next.effects.none { it is Effect.Reject }, "row=$i col=$j input=$input")
            if (allowed[i][j] == '0') assertEquals(state, next.state)
        } }
    }

    @Test fun acceptedRequestOwnsHistoryAndQueueBeforeAnyRunEffect() {
        val accepted = ChatMachine.reduce(created(), Intent.Submit("chat", request(), 2))
        val session = accepted.state.sessions.getValue("chat")
        assertEquals(listOf("request"), session.messages.map { it.id })
        assertEquals(request(), session.pendingRun)
        assertEquals(listOf(Effect.RequestAccepted("chat", "request"), Effect.RunRequest(accepted.state.runs.getValue("chat").ref)), accepted.effects)
        val queued = ChatMachine.reduce(accepted.state, Intent.Submit("chat", request("next"), 3))
        assertEquals(listOf(request("next")), queued.state.sessions.getValue("chat").queuedPrompts)
        assertTrue(queued.effects.none { it is Effect.RunRequest })
        rejected(queued.state, Intent.Submit("chat", request("next"), 3))
        rejected(queued.state, Intent.AdvanceQueue("chat", 4))
    }

    @Test fun restoreNeverEmitsWorkAndUnknownAllowsOnlySavedOutputInspection() {
        var state = running()
        state = step(state, Intent.Submit("chat", request("next"), 3))
        val restored = ChatMachine.reduce(state, Fact.Restored)
        assertTrue(restored.effects.isEmpty())
        assertEquals(Phase.UNKNOWN, restored.state.runs.getValue("chat").phase)
        assertEquals(listOf(request("next")), restored.state.sessions.getValue("chat").queuedPrompts)
        rejected(restored.state, Intent.AdvanceQueue("chat", 4))
        val recovery = ChatMachine.reduce(restored.state, Intent.Recover(restored.state.runs.getValue("chat").ref))
        assertTrue(recovery.effects.single() is Effect.InspectSavedOutput)
        assertTrue(recovery.effects.none { it is Effect.RunRequest })
    }

    @Test fun recoveredReplyNeedsExactCurrentRunAndProofAndDoesNotDrainOldQueue() {
        var state = stopped(true)
        val ref = state.runs.getValue("chat").ref
        state = step(state, Intent.Submit("chat", request("queued"), 3))
        state = step(state, Intent.Recover(ref))
        val proof = ChatMachine.OutputProof(ref.runId, "provider-attempt", "immutable-input", "digest")
        rejected(state, Fact.RecoveredReply(ref.copy(generation = ref.generation + 1), proof, reply(ref)))
        rejected(state, Fact.RecoveredReply(ref, proof.copy(runId = "other"), reply(ref)))
        rejected(state, Fact.RecoveredReply(ref, proof.copy(digest = ""), reply(ref)))
        val recovered = ChatMachine.reduce(state, Fact.RecoveredReply(ref, proof, reply(ref)))
        assertTrue(recovered.effects.isEmpty())
        assertNull(recovered.state.sessions.getValue("chat").pendingRun)
        assertEquals("Answer", recovered.state.sessions.getValue("chat").messages.last().text)
        assertEquals(listOf(request("queued")), recovered.state.sessions.getValue("chat").queuedPrompts)
        rejected(recovered.state, Fact.RecoveredReply(ref, proof, reply(ref)))
    }

    @Test fun clarificationIsDurableBeforeAbortAndOnlyKnownStopMayContinueIt() {
        val active = running()
        val ref = active.runs.getValue("chat").ref
        val clarification = request("clarified", ref.messageId, "Question\nClarification")
        val accepted = ChatMachine.reduce(active, Intent.Clarify(ref, clarification, 3))
        assertEquals(listOf(clarification), accepted.state.sessions.getValue("chat").queuedPrompts)
        assertTrue(accepted.effects.last() is Effect.AbortRequest)
        assertTrue(accepted.effects.none { it is Effect.RunRequest })
        val known = ChatMachine.reduce(accepted.state, Fact.RunStopped(ref, false, at = 4))
        assertEquals("clarified", known.state.runs.getValue("chat").ref.runId)
        assertTrue(known.effects.any { it is Effect.RunRequest })
        val unknown = ChatMachine.reduce(accepted.state, Fact.RunStopped(ref, true, at = 4))
        assertEquals(Phase.UNKNOWN, unknown.state.runs.getValue("chat").phase)
        assertEquals(listOf(clarification), unknown.state.sessions.getValue("chat").queuedPrompts)
        assertTrue(unknown.effects.isEmpty())
        val restored = ChatMachine.reduce(accepted.state, Fact.Restored)
        assertNull(restored.state.runs.getValue("chat").clarification)
        assertTrue(restored.effects.isEmpty())
        rejected(restored.state, Fact.RunStopped(ref, false, at = 4))
    }

    @Test fun discardRetainsPartialHistoryAndOldReceiptIdentityWithoutStartingQueue() {
        val active = running()
        val ref = active.runs.getValue("chat").ref
        val partial = TranscriptBlock.Markdown("partial", "Saved fragment")
        var state = step(active, Fact.Progress(ref, emptyList(), listOf(partial)))
        state = step(state, Fact.RunStopped(ref, true, at = 3))
        state = step(state, Intent.Submit("chat", request("next"), 4))
        val discarded = ChatMachine.reduce(state, Intent.Discard(ref, 5))
        assertTrue(discarded.effects.isEmpty())
        assertEquals("Saved fragment", discarded.state.sessions.getValue("chat").messages.last().text)
        assertEquals(listOf(request("next")), discarded.state.sessions.getValue("chat").queuedPrompts)
        rejected(discarded.state, Intent.Submit("chat", request(), 6))
        val continued = ChatMachine.reduce(discarded.state, Intent.AdvanceQueue("chat", 6))
        assertEquals("next", continued.state.runs.getValue("chat").ref.runId)
        assertTrue(continued.state.runs.getValue("chat").ref.generation > ref.generation)
        rejected(continued.state, Fact.ReplyStored(ref, reply(ref)))
    }

    @Test fun notebookDeletionTombstonesAllQuestionsBeforeCleanupAndRejectsLateResults() {
        var state = step(created(), Intent.CreateQuestion("child", "chat", 2))
        state = step(state, Intent.Submit("child", request(), 3))
        val ref = state.runs.getValue("child").ref
        val deleted = ChatMachine.reduce(state, Intent.Delete("chat"))
        assertTrue(deleted.state.deleted)
        assertTrue(deleted.state.sessions.isEmpty())
        assertEquals(setOf("chat", "child"), deleted.state.removedIds)
        assertEquals(listOf(Effect.AbortRequest(ref), Effect.CleanupDeleted(setOf("chat", "child"))), deleted.effects)
        rejected(deleted.state, Fact.ReplyStored(ref, reply(ref)))
        rejected(deleted.state, Fact.LegacyImported("chat", state.sessions.values.toList()))
        assertTrue(ChatMachine.reduce(deleted.state, Fact.Restored).effects.isEmpty())
    }

    @Test fun resourcePromotionAndSelectionAreAtomicAndLateDiscoveryCannotUndoRemoval() {
        val source = ResearchResource("site", "Reference", "https://example.test/", discovered = true)
        var state = step(created(), Intent.CreateQuestion("child", "chat", 2))
        state = step(state, Intent.AddResources("child", false, listOf(source)))
        state = step(state, Intent.ShareResource("child", source.id))
        assertEquals(listOf(source), state.notebook!!.resources)
        assertTrue(state.sessions.getValue("child").questionResources.isEmpty())
        state = step(state, Intent.SelectResources("child", setOf(source.key), false))
        state = step(state, Intent.Submit("child", request(), 3))
        val ref = state.runs.getValue("child").ref
        state = step(state, Intent.RemoveResource("child", true, source.id))
        state = step(state, Fact.SourcesDiscovered(ref, listOf(source), true))
        assertTrue(state.notebook!!.resources.isEmpty())
        assertTrue(source.key in state.sessions.getValue("child").disabledResourceKeys)
        state = step(state, Intent.AddResources("child", true, listOf(source)))
        assertEquals(listOf(source), state.notebook!!.resources)
        assertTrue(state.sessions.getValue("child").disabledResourceKeys.isEmpty())
    }

    @Test fun legacyImportPreservesSavedIdentityAndNeverAcquiresExecution() {
        val legacy = ChatSession("chat", "Old", 1, 2, engine = CodingEngine.CODEX, nativeSessionId = "saved-native",
            pendingRun = request(), queuedPrompts = listOf(request("next")))
        val imported = ChatMachine.reduce(ChatMachine.initial(), Fact.LegacyImported("chat", listOf(legacy)))
        assertEquals(legacy, imported.state.sessions.getValue("chat"))
        assertEquals(Phase.UNKNOWN, imported.state.runs.getValue("chat").phase)
        assertTrue(imported.effects.isEmpty())
        rejected(imported.state, Intent.AdvanceQueue("chat", 3))
        rejected(ChatMachine.initial(), Fact.LegacyImported("chat", listOf(legacy.copy(researchParentId = "missing"))))
    }

    @Test fun profileDeletionChangesOnlyFutureSelectionAndKeepsActiveRunIdentity() {
        var state = step(created(), Intent.SetModel("chat", ModelSelection("profile", "model")))
        state = step(state, Intent.Submit("chat", request(), 2))
        val ref = state.runs.getValue("chat").ref
        state = step(state, Intent.UnlinkProfile("profile"))
        assertNull(state.sessions.getValue("chat").llmProfileId)
        assertNull(state.sessions.getValue("chat").modelSelection)
        assertEquals(ref, state.runs.getValue("chat").ref)
    }
    @Test fun identifiersCannotAliasMessagesAnswersTimelinesOrQueuedRuns() {
        val active = running()
        rejected(created(), Intent.Submit("chat", request().copy(responseId = "request"), 2))
        rejected(active, Intent.Submit("chat", request("next").copy(responseId = "request:answer"), 3))
        rejected(active, Intent.Submit("chat", request("next").copy(responseTimelineId = "request"), 3))
        val queued = step(active, Intent.Submit("chat", request("queued"), 3))
        rejected(queued, Intent.Submit("chat", request("next").copy(responseId = "queued:timeline"), 4))
    }

    @Test fun explicitPauseSuppressesLateCompletionQueueAdvanceAndKnownStoppedClarificationCanRun() {
        val active = step(running(), Intent.Submit("chat", request("queued"), 3))
        val ref = active.runs.getValue("chat").ref
        val paused = step(active, Intent.Pause(ref))
        val late = ChatMachine.reduce(paused, Fact.ReplyStored(ref, reply(ref)))
        assertTrue(late.effects.isEmpty())
        assertEquals(listOf(request("queued")), late.state.sessions.getValue("chat").queuedPrompts)
        val interrupted = step(paused, Fact.RunStopped(ref, false, at = 4))
        val clarified = ChatMachine.reduce(interrupted, Intent.Clarify(ref, request("clarified", ref.messageId, "Clarified"), 5))
        assertEquals("clarified", clarified.state.runs.getValue("chat").ref.runId)
        assertTrue(clarified.effects.any { it is Effect.RunRequest })
        assertEquals(listOf(request("queued")), clarified.state.sessions.getValue("chat").queuedPrompts)
    }

    @Test fun followUpChecksTheExactCurrentAnswerBeforeAcceptingAndCannotDoubleQueue() {
        val active = running()
        val ref = active.runs.getValue("chat").ref
        val completed = step(active, Fact.ReplyStored(ref, reply(ref).copy(followUps = listOf("Explain"))))
        val input = Intent.FollowUp("chat", ref.responseId, request("follow", text = "Explain"), 6)
        val accepted = step(completed, input)
        rejected(accepted, input.copy(request = request("double", text = "Explain")))
        rejected(completed, input.copy(answerId = "stale"))
        rejected(completed, input.copy(request = request("unknown", text = "Not offered")))
    }

    @Test fun forkAndImportedReplacementPreserveValuesWithoutExecutionAuthority() {
        val source = running().sessions.getValue("chat").copy(nativeSessionId = "native", engine = CodingEngine.CODEX)
        val fork = ChatMachine.reduce(ChatMachine.initial(), Intent.ForkNotebook(source, source, "fork", 10, mapOf("request" to "copy")))
        assertTrue(fork.effects.isEmpty())
        assertEquals("copy", fork.state.notebook!!.messages.single().id)
        assertEquals(CodingEngine.CODEX, fork.state.notebook!!.engine)
        assertNull(fork.state.notebook!!.pendingRun)
        assertEquals("", fork.state.notebook!!.nativeSessionId)
        val original = stopped(false)
        val discarded = step(original, Intent.Discard(original.runs.getValue("chat").ref, 5))
        val restored = step(discarded, Intent.ImportNotebook(listOf(source.copy(pendingRun = request("imported")))))
        assertTrue(restored.runs.getValue("chat").ref.generation > original.runs.getValue("chat").ref.generation)
        assertEquals(Phase.UNKNOWN, restored.runs.getValue("chat").phase)
        rejected(restored, Fact.ReplyStored(original.runs.getValue("chat").ref, reply(original.runs.getValue("chat").ref)))
    }

    @Test fun legacyStopIntentDoesNotProveAnExternalOutcomeAndMissingResponseIdsAreStable() {
        val legacy = ChatSession("chat", "Legacy", 1, 2, messages = listOf(ChatMessage("request", ChatRole.USER, "Question", 1)),
            pendingRun = CodingRunCheckpoint("request", "Question", intent = ExecutionIntent.STOP),
            queuedPrompts = listOf(CodingRunCheckpoint("queued", "Queued")))
        val restored = step(ChatMachine.initial(), Fact.LegacyImported("chat", listOf(legacy)))
        val ref = restored.runs.getValue("chat").ref
        assertEquals(Phase.UNKNOWN, restored.runs.getValue("chat").phase)
        assertTrue(ref.responseId.isNotBlank() && ref.timelineId.isNotBlank())
        assertEquals(restored, step(ChatMachine.initial(), Fact.LegacyImported("chat", listOf(legacy))))
        val clarified = ChatMachine.reduce(restored, Intent.Clarify(ref, request("new", "request", "Clarified question"), 3))
        assertTrue(clarified.effects.none { it is Effect.RunRequest || it is Effect.AbortRequest })
        assertEquals(Phase.UNKNOWN, clarified.state.runs.getValue("chat").phase)
        assertEquals(listOf("new", "queued"), clarified.state.notebook!!.queuedPrompts.map { it.runId })
    }

    @Test fun knownClarificationKeepsExactUserTextAndAttachmentsAfterCompletion() {
        val state = stopped(false)
        val ref = state.runs.getValue("chat").ref
        val accepted = step(state, Intent.Clarify(ref, request("new", ref.messageId, "Question and clarification"), 4))
        val newRef = accepted.runs.getValue("chat").ref
        val completed = step(accepted, Fact.ReplyStored(newRef, reply(newRef)))
        assertEquals("Question and clarification", completed.notebook!!.messages.single { it.role == ChatRole.USER }.text)
        assertEquals(ref.messageId, completed.notebook!!.messages.single { it.role == ChatRole.USER }.id)
    }

}
