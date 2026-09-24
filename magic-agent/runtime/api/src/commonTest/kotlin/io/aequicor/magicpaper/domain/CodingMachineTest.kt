package io.aequicor.magicpaper.domain

import kotlin.test.*

class CodingMachineTest {
    private val project = CodingProject("project", "Project", "/project", 1)
    private val session = CodingSession("session", project.id, "Новая сессия", 2, engine = CodingEngine.PI)
    private fun request(id: String = "request") = CodingRunCheckpoint("message-$id", "Task $id", responseId = "reply-$id",
        runId = id, responseTimelineId = "timeline-$id")
    private fun apply(state: CodingMachine.State, input: CodingMachine.Input): CodingMachine.State =
        CodingMachine.reduce(state, input).also { assertTrue(it.effects.none { effect -> effect is CodingMachine.Effect.Reject }) }.state
    private fun ready(): CodingMachine.State = apply(apply(CodingMachine.initial(), CodingMachine.Intent.CreateProject(project)),
        CodingMachine.Intent.CreateSession(session))
    private fun running(): CodingMachine.State {
        val queued = apply(ready(), CodingMachine.Intent.Enqueue(CodingMachine.ref(session), request()))
        return apply(queued, CodingMachine.Intent.BeginRun(CodingMachine.ref(session), request(), 3))
    }
    private fun CodingMachine.State.ref() = runs.getValue(session.id).ref
    private fun rejected(state: CodingMachine.State, input: CodingMachine.Input) {
        val result = CodingMachine.reduce(state, input)
        assertEquals(state, result.state)
        assertIs<CodingMachine.Effect.Reject>(result.effects.single())
    }

    @Test fun nativeModelChoiceIsStoredBesideTheLegacySelectionAndNeverReplacesIt() {
        val legacy = ModelSelection("profile", "legacy-model")
        val withLegacy = apply(ready(), CodingMachine.Intent.SetSessionModel(CodingMachine.ref(session), legacy))
        val native = CodingModelSelection(CodingEngine.PI, "qwen-token-plan", "qwen3.8-max", "xhigh")
        val chosen = apply(withLegacy, CodingMachine.Intent.SetSessionCodingModel(CodingMachine.ref(session), native))
        assertEquals(native, chosen.sessions.getValue(session.id).codingModel)
        assertEquals(legacy, chosen.sessions.getValue(session.id).modelSelection)
        val cleared = apply(chosen, CodingMachine.Intent.SetSessionCodingModel(CodingMachine.ref(session), null))
        assertNull(cleared.sessions.getValue(session.id).codingModel)
    }

    @Test fun nativeModelOfAnotherEngineIsRejectedAndTheSessionKeepsItsChoice() {
        val own = CodingModelSelection(CodingEngine.PI, "qwen-token-plan", "qwen3.8-max")
        val chosen = apply(ready(), CodingMachine.Intent.SetSessionCodingModel(CodingMachine.ref(session), own))
        rejected(chosen, CodingMachine.Intent.SetSessionCodingModel(CodingMachine.ref(session),
            CodingModelSelection(CodingEngine.CODEX, "openai", "gpt-6-astra")))
        assertEquals(own, chosen.sessions.getValue(session.id).codingModel)
    }

    @Test fun projectKeepsANativeDefaultForNewSessions() {
        val native = CodingModelSelection(CodingEngine.CODEX, "openai", "gpt-6-astra", "max")
        val state = apply(ready(), CodingMachine.Intent.SetProjectCodingModel(native))
        assertEquals(native, state.project?.codingModel)
        assertNull(apply(state, CodingMachine.Intent.SetProjectCodingModel(null)).project?.codingModel)
    }

    @Test fun onlyAcceptedFreshRequestsProduceExecutionEffects() {
        val state = ready()
        val queued = apply(state, CodingMachine.Intent.Enqueue(CodingMachine.ref(session), request()))
        assertEquals(listOf(request()), queued.sessions.getValue(session.id).queuedPrompts)
        val accepted = CodingMachine.reduce(queued, CodingMachine.Intent.BeginRun(CodingMachine.ref(session), request(), 3))
        assertEquals(request(), assertIs<CodingMachine.Effect.RunRequest>(accepted.effects.single()).request.copy(interactionMode = null))
        assertTrue(accepted.state.sessions.getValue(session.id).queuedPrompts.isEmpty())
        rejected(accepted.state, CodingMachine.Intent.BeginRun(CodingMachine.ref(session), request("other"), 4))
    }

    @Test fun everyUnfinishedPhaseRestoresWithoutEffectsOrFreshAdmission() {
        val active = running()
        val stopped = apply(active, CodingMachine.Intent.Pause(active.ref()))
        val unknown = apply(stopped, CodingMachine.Fact.RunStopped(active.ref(), true))
        val abandoning = apply(unknown, CodingMachine.Intent.Abandon(active.ref(), "decision", NativeRunRecoveryRef(CodingEngine.PI, session.id, active.ref().requestId, 1)))
        for (state in listOf(active, stopped, unknown, abandoning)) {
            val restored = CodingMachine.reduce(state, CodingMachine.Fact.Restored)
            assertTrue(restored.effects.isEmpty())
            assertEquals(CodingMachine.Phase.UNKNOWN, restored.state.runs.getValue(session.id).phase)
            rejected(restored.state, CodingMachine.Intent.BeginRun(CodingMachine.ref(session), request("fresh"), 4))
        }
    }

    @Test fun legacyStopIsUnknownAndCannotAuthorizeFreshWork() {
        for (intent in ExecutionIntent.entries) {
            val imported = apply(CodingMachine.initial(), CodingMachine.Fact.LegacyImported(project,
                listOf(session.copy(pendingRun = request().copy(intent = intent))), emptyMap()))
            assertEquals(CodingMachine.Phase.UNKNOWN, imported.runs.getValue(session.id).phase)
            rejected(imported, CodingMachine.Intent.DiscardInterrupted(imported.ref()))
            rejected(imported, CodingMachine.Intent.BeginRun(CodingMachine.ref(session), request("fresh"), 3))
        }
    }

    @Test fun exactAbandonAcknowledgementPreservesOldUnknownAndAllowsOnlyFreshRequestId() {
        val unknown = apply(running(), CodingMachine.Fact.Restored)
        val ref = unknown.ref()
        rejected(unknown, CodingMachine.Intent.Abandon(ref, "decision", NativeRunRecoveryRef(CodingEngine.PI, session.id, ref.requestId, -1)))
        val abandoning = apply(unknown, CodingMachine.Intent.Abandon(ref, "decision", NativeRunRecoveryRef(CodingEngine.PI, session.id, ref.requestId, 0)))
        val proof = NativeRunRecoveryAcknowledgement("ack", NativeRunRecoveryRef(CodingEngine.PI, session.id, ref.requestId, 0), "decision")
        rejected(abandoning, CodingMachine.Fact.AbandonAcknowledged(ref, proof.copy(parentDecisionId = "foreign")))
        val acknowledged = apply(abandoning, CodingMachine.Fact.AbandonAcknowledged(ref, proof))
        assertTrue(ref.requestId in acknowledged.startedRequests)
        assertNull(acknowledged.sessions.getValue(session.id).pendingRun)
        rejected(acknowledged, CodingMachine.Intent.BeginRun(CodingMachine.ref(session), request(), 4))
        val accepted = CodingMachine.reduce(acknowledged, CodingMachine.Intent.BeginRun(CodingMachine.ref(session), request("fresh"), 4))
        assertEquals(proof, assertIs<CodingMachine.Effect.RunRequest>(accepted.effects.single()).previousAcknowledgement)
        val consumer = accepted.state.ref()
        val consumed = apply(accepted.state, CodingMachine.Fact.RecoveryAcknowledgementConsumed(consumer,
            NativeRunRecoveryConsumption(proof.id, CodingEngine.PI, session.id, consumer.requestId)))
        assertTrue(consumed.acknowledgements.isEmpty())
    }

    @Test fun noDispatchProofIsAnExactAlternativeToAttemptRecovery() {
        val unknown = apply(running(), CodingMachine.Fact.Restored)
        val ref = unknown.ref()
        val proof = NativeRunNoDispatchProof(CodingEngine.PI, session.id, ref.requestId, "proof", "epoch")
        for (invalid in listOf(proof.copy(engine = CodingEngine.CODEX), proof.copy(sessionId = "other"),
            proof.copy(requestId = "other"), proof.copy(proofId = ""), proof.copy(journalGeneration = ""))) {
            rejected(unknown, CodingMachine.Intent.AbandonNotDispatched(ref, "decision", invalid))
        }
        rejected(running(), CodingMachine.Intent.AbandonNotDispatched(ref, "decision", proof))
        val decision = CodingMachine.reduce(unknown, CodingMachine.Intent.AbandonNotDispatched(ref, "decision", proof))
        assertEquals(CodingMachine.Effect.AcknowledgeNotDispatched(ref, "decision", proof), decision.effects.single())
        assertNull(decision.state.runs.getValue(session.id).abandonAttempt)
        val ack = NativeRunNoDispatchAcknowledgement("ack", proof, "decision")
        for (invalid in listOf(ack.copy(id = ""), ack.copy(parentDecisionId = "foreign"),
            ack.copy(proof = proof.copy(proofId = "other")), ack.copy(proof = proof.copy(journalGeneration = "reset")))) {
            rejected(decision.state, CodingMachine.Fact.NoDispatchAcknowledged(ref, invalid))
        }
        val approved = apply(decision.state, CodingMachine.Fact.NoDispatchAcknowledged(ref, ack))
        assertNull(approved.sessions.getValue(session.id).pendingRun)
        assertTrue(ref.requestId in approved.startedRequests)
        rejected(approved, CodingMachine.Intent.BeginRun(CodingMachine.ref(session), request(), 4))
        val next = CodingMachine.reduce(apply(approved, CodingMachine.Fact.Restored),
            CodingMachine.Intent.BeginRun(CodingMachine.ref(session), request("fresh"), 4))
        val effect = assertIs<CodingMachine.Effect.RunRequest>(next.effects.single())
        assertNull(effect.previousAcknowledgement)
        assertEquals(ack, effect.previousNoDispatchAcknowledgement)
        assertEquals(ack, next.state.noDispatchAcknowledgements[session.id], "Parent admission is not native consumption")
    }

    @Test fun discardUndispatchedSettlesOnlyAnUnknownRunTheServiceProvedNeverReachedAnEngine() {
        val unknown = apply(running(), CodingMachine.Fact.Restored)
        val ref = unknown.ref()
        val discarded = apply(unknown, CodingMachine.Intent.DiscardUndispatched(ref))
        assertNull(discarded.runs[session.id])
        assertNull(discarded.sessions.getValue(session.id).pendingRun)
        rejected(unknown, CodingMachine.Intent.DiscardUndispatched(ref.copy(requestId = "other")))
        val active = running()
        rejected(active, CodingMachine.Intent.DiscardUndispatched(active.ref()))
        val stopped = apply(active, CodingMachine.Intent.Pause(active.ref()))
        val interrupted = apply(stopped, CodingMachine.Fact.RunStopped(active.ref(), false))
        assertEquals(CodingMachine.Phase.INTERRUPTED, interrupted.runs.getValue(session.id).phase)
        rejected(interrupted, CodingMachine.Intent.DiscardUndispatched(interrupted.ref()))
        // A settled session admits fresh work again.
        val accepted = CodingMachine.reduce(discarded, CodingMachine.Intent.BeginRun(CodingMachine.ref(session), request("fresh"), 4))
        assertIs<CodingMachine.Effect.RunRequest>(accepted.effects.single())
    }

    @Test fun deferRecoveryRecordsItsOwnNativeDecisionAndContinuationReusesOnlyIt() {
        val unknown = apply(running(), CodingMachine.Fact.Restored)
        val ref = unknown.ref()
        val attempt = NativeRunRecoveryRef(CodingEngine.PI, session.id, ref.requestId, 0)
        val deferred = apply(unknown, CodingMachine.Intent.DeferRecovery(CodingMachine.ref(session), request().messageId,
            decidedAttempt = attempt, decisionId = "decision"))
        val deferredRun = deferred.runs.getValue(session.id)
        assertEquals(attempt, deferredRun.abandonAttempt)
        assertEquals("decision", deferredRun.abandonDecisionId)
        assertNull(deferredRun.abandonNoDispatchProof)
        assertTrue(deferred.sessions.getValue(session.id).pendingRun!!.stoppedByUser)
        // Continuation settles with exactly the recorded decision: the acknowledgement fact matches only it.
        val abandoning = apply(deferred, CodingMachine.Intent.Abandon(ref, "decision", attempt))
        val acknowledged = apply(abandoning, CodingMachine.Fact.AbandonAcknowledged(ref,
            NativeRunRecoveryAcknowledgement("ack", attempt, "decision")))
        assertNull(acknowledged.sessions.getValue(session.id).pendingRun)
        // The deferral records at most one decision, never a foreign session's and never a blank one.
        rejected(unknown, CodingMachine.Intent.DeferRecovery(CodingMachine.ref(session), request().messageId,
            decidedAttempt = attempt, decisionId = null))
        rejected(unknown, CodingMachine.Intent.DeferRecovery(CodingMachine.ref(session), request().messageId,
            decidedAttempt = attempt.copy(sessionId = "other"), decisionId = "decision"))
        rejected(unknown, CodingMachine.Intent.DeferRecovery(CodingMachine.ref(session), request().messageId,
            decidedAttempt = attempt, decidedNoDispatch = NativeRunNoDispatchProof(CodingEngine.PI, session.id, ref.requestId, "proof", "epoch"),
            decisionId = "decision"))
    }

    @Test fun deferredNoDispatchDecisionIsTheOnlyOneContinuationAccepts() {
        val unknown = apply(running(), CodingMachine.Fact.Restored)
        val ref = unknown.ref()
        val proof = NativeRunNoDispatchProof(CodingEngine.PI, session.id, ref.requestId, "proof", "epoch")
        val deferred = apply(unknown, CodingMachine.Intent.DeferRecovery(CodingMachine.ref(session), request().messageId,
            decidedNoDispatch = proof, decisionId = "decision"))
        assertEquals(proof, deferred.runs.getValue(session.id).abandonNoDispatchProof)
        assertNull(deferred.runs.getValue(session.id).abandonAttempt)
        val decided = apply(deferred, CodingMachine.Intent.AbandonNotDispatched(ref, "decision", proof))
        val acknowledged = apply(decided, CodingMachine.Fact.NoDispatchAcknowledged(ref,
            NativeRunNoDispatchAcknowledgement("ack", proof, "decision")))
        assertNull(acknowledged.sessions.getValue(session.id).pendingRun)
        rejected(decided, CodingMachine.Fact.NoDispatchAcknowledged(ref,
            NativeRunNoDispatchAcknowledgement("ack", proof, "foreign")))
    }

    @Test fun recoveryConsumptionRequiresExactNativeConsumerAndSurvivesPreNativeFailure() {
        val unknown = apply(running(), CodingMachine.Fact.Restored)
        val oldRef = unknown.ref()
        val proof = NativeRunNoDispatchProof(CodingEngine.PI, session.id, oldRef.requestId, "proof", "epoch")
        val ack = NativeRunNoDispatchAcknowledgement("ack", proof, "decision")
        val approved = apply(apply(unknown, CodingMachine.Intent.AbandonNotDispatched(oldRef, "decision", proof)),
            CodingMachine.Fact.NoDispatchAcknowledged(oldRef, ack))
        val first = apply(approved, CodingMachine.Intent.BeginRun(CodingMachine.ref(session), request("fresh"), 4))
        val preNativeStopped = apply(first, CodingMachine.Fact.RunStopped(first.ref(), unknown = false))
        val released = apply(preNativeStopped, CodingMachine.Intent.DiscardInterrupted(first.ref()))
        val next = CodingMachine.reduce(released, CodingMachine.Intent.BeginRun(CodingMachine.ref(session), request("after-git"), 5))
        assertEquals(ack, assertIs<CodingMachine.Effect.RunRequest>(next.effects.single()).previousNoDispatchAcknowledgement)
        val consumer = next.state.ref()
        val consumed = NativeRunRecoveryConsumption(ack.id, CodingEngine.PI, session.id, consumer.requestId)
        for (invalid in listOf(consumed.copy(acknowledgementId = "other"), consumed.copy(engine = CodingEngine.CODEX),
            consumed.copy(sessionId = "other"), consumed.copy(requestId = first.ref().requestId))) {
            rejected(next.state, CodingMachine.Fact.RecoveryAcknowledgementConsumed(consumer, invalid))
        }
        val transferred = apply(next.state, CodingMachine.Fact.RecoveryAcknowledgementConsumed(consumer, consumed))
        assertTrue(transferred.noDispatchAcknowledgements.isEmpty())
        val finished = apply(transferred, CodingMachine.Fact.RunFinished(consumer,
            CodingMessage(consumer.responseId, CodingRole.AGENT, "Done", createdAt = 6)))
        val third = CodingMachine.reduce(finished, CodingMachine.Intent.BeginRun(CodingMachine.ref(session), request("third"), 7))
        assertNull(assertIs<CodingMachine.Effect.RunRequest>(third.effects.single()).previousNoDispatchAcknowledgement)
        rejected(third.state, CodingMachine.Fact.RecoveryAcknowledgementConsumed(consumer, consumed))
    }

    @Test fun clarificationIsSavedBeforeAbortAndNeverStartsFromUnknown() {
        for (state in listOf(running(), apply(running(), CodingMachine.Fact.Restored))) {
            val next = request("clarification")
            val message = CodingMessage(next.messageId, CodingRole.USER, "A clarification", createdAt = 4)
            val result = CodingMachine.reduce(state, CodingMachine.Intent.Clarify(state.ref(), next, message))
            assertEquals(listOf(next), result.state.sessions.getValue(session.id).queuedPrompts)
            assertEquals(listOf(message), result.state.histories.getValue(session.id))
            assertTrue(result.effects.none { it is CodingMachine.Effect.RunRequest })
            assertEquals(state.runs.getValue(session.id).phase == CodingMachine.Phase.RUNNING,
                result.effects.any { it is CodingMachine.Effect.AbortRequest })
        }
    }

    @Test fun deferringLegacyHistoryIsDurableWithoutInventingARequestOrStoppingProof() {
        val message = CodingMessage("legacy", CodingRole.USER, "Unfinished", createdAt = 3)
        val base = apply(ready(), CodingMachine.Fact.HistoryPublished(CodingMachine.ref(session), listOf(message)))
        rejected(base, CodingMachine.Intent.DeferRecovery(CodingMachine.ref(session), "foreign"))
        val transition = CodingMachine.reduce(base, CodingMachine.Intent.DeferRecovery(CodingMachine.ref(session), message.id))
        assertTrue(transition.effects.isEmpty())
        val deferred = transition.state
        assertEquals(listOf(message), deferred.histories.getValue(session.id))
        assertEquals(message.id, deferred.sessions.getValue(session.id).pendingRun!!.messageId)
        assertTrue(deferred.sessions.getValue(session.id).pendingRun!!.stoppedByUser)
        assertEquals(CodingMachine.Phase.UNKNOWN, deferred.runs.getValue(session.id).phase)
        assertFalse(deferred.runs.getValue(session.id).knownStopped)
        assertEquals(deferred, apply(deferred, CodingMachine.Intent.DeferRecovery(CodingMachine.ref(session), message.id)))
        val restored = apply(deferred, CodingMachine.Fact.Restored)
        rejected(restored, CodingMachine.Intent.DiscardInterrupted(restored.ref()))
        rejected(restored, CodingMachine.Intent.BeginRun(CodingMachine.ref(session), request("new"), 4))
        rejected(running(), CodingMachine.Intent.DeferRecovery(CodingMachine.ref(session), request().messageId))
    }

    @Test fun responseIdentityCannotReplaceInputOrAnotherQueuedResponse() {
        val base = ready()
        rejected(base, CodingMachine.Intent.Enqueue(CodingMachine.ref(session), request().copy(responseId = request().messageId)))
        val queued = apply(base, CodingMachine.Intent.Enqueue(CodingMachine.ref(session), request()))
        rejected(queued, CodingMachine.Intent.Enqueue(CodingMachine.ref(session), request("other").copy(responseId = request().responseId)))
        rejected(queued, CodingMachine.Intent.BeginRun(CodingMachine.ref(session), request().copy(prompt = "changed"), 3))
    }

    @Test fun organismAdmissionAdoptsExecutionGenerationWithoutLosingRequestIdentity() {
        val active = running()
        val organism = SessionOrganism("organism", project.id, session.id, createdAt = 2,
            sessions = mapOf(session.id to SessionNode(session.id, SessionKind.ZYGOTE, session.name, generation = 1, mode = CodingInteractionMode.CODE)))
        val projected = apply(active, CodingMachine.Fact.OrganismProjected(organism, CodingMachine.ChildRevision("organism", 1, 0, "input")))
        assertEquals(active.ref(), projected.ref())
        assertEquals(1, projected.runs.getValue(session.id).executionGeneration)
        assertEquals(request(), projected.sessions.getValue(session.id).pendingRun?.copy(interactionMode = null))
        val bound = apply(projected, CodingMachine.Fact.NativeSessionBound(active.ref(), "native"))
        assertEquals("native", bound.sessions.getValue(session.id).piSessionId)
        val finished = apply(bound, CodingMachine.Fact.RunFinished(active.ref(), CodingMessage(request().responseId, CodingRole.AGENT, "Done", createdAt = 4)))
        assertNull(finished.sessions.getValue(session.id).pendingRun)
    }

    @Test fun explicitChildRecreationKeepsOldOutcomeUnknownAndRejectsLateNativeReply() {
        val active = running()
        val organism = SessionOrganism("organism", project.id, session.id, createdAt = 2,
            sessions = mapOf(session.id to SessionNode(session.id, SessionKind.ZYGOTE, session.name, generation = 1,
                previousGeneration = 0, mode = CodingInteractionMode.CODE)))
        val projected = apply(active, CodingMachine.Fact.OrganismProjected(organism, CodingMachine.ChildRevision("organism", 1, 0, "input")))
        assertEquals(CodingMachine.Phase.UNKNOWN, projected.runs.getValue(session.id).phase)
        assertNotNull(projected.sessions.getValue(session.id).pendingRun)
        rejected(projected, CodingMachine.Fact.RunFinished(active.ref(), CodingMessage(request().responseId, CodingRole.AGENT, "Old", createdAt = 4)))
        // The replaced generation's engine may still report its session; the recreated session does not take it.
        val late = CodingMachine.reduce(projected, CodingMachine.Fact.NativeSessionBound(active.ref(), "old-native"))
        assertEquals(projected, late.state)
        assertTrue(late.effects.isEmpty())
    }

    /** The engine reports its session while it runs, so the report can land after the run's owner settled the run. */
    @Test fun nativeSessionReportedAfterTheRunWasSettledChangesNothingAndFailsNothing() {
        val active = running()
        val settled = listOf(
            apply(active, CodingMachine.Fact.RunStopped(active.ref(), unknown = false)),
            apply(active, CodingMachine.Fact.RunStopped(active.ref(), unknown = true)),
            apply(apply(active, CodingMachine.Fact.RunStopped(active.ref(), unknown = true)), CodingMachine.Intent.Abandon(active.ref(), "decision",
                NativeRunRecoveryRef(CodingEngine.PI, session.id, active.ref().requestId, 0))),
        )
        for (state in settled) {
            val late = CodingMachine.reduce(state, CodingMachine.Fact.NativeSessionBound(active.ref(), "late"))
            assertEquals(state, late.state, "${state.runs.getValue(session.id).phase}")
            assertTrue(late.effects.isEmpty(), "${state.runs.getValue(session.id).phase}")
        }
        // Another request's report is still refused, as a report for a deleted session is.
        rejected(settled.first(), CodingMachine.Fact.NativeSessionBound(active.ref().copy(requestId = "other"), "late"))
    }

    @Test fun historyDeletionIsAtomicWithContextInvalidationAndStopsLateProjectionResurrection() {
        val a = CodingMessage("a", CodingRole.USER, "A", createdAt = 3)
        val b = CodingMessage("b", CodingRole.AGENT, "B", createdAt = 4)
        val state = apply(ready(), CodingMachine.Fact.HistoryPublished(CodingMachine.ref(session), listOf(a, b)))
        val edited = apply(state, CodingMachine.Intent.ReplaceHistory(CodingMachine.ref(session), listOf(a, b), listOf(a)))
        assertTrue(edited.sessions.getValue(session.id).needsHistorySeed)
        assertEquals(setOf("b"), edited.removedMessages[session.id])
        val late = apply(edited, CodingMachine.Fact.HistoryPublished(CodingMachine.ref(session), listOf(a, b)))
        assertEquals(listOf(a), late.histories[session.id])
        rejected(late, CodingMachine.Intent.ReplaceHistory(CodingMachine.ref(session), listOf(a, b), emptyList()))
    }

    @Test fun deleteReservesIdentityAndRejectsEveryOldExecutionFact() {
        val active = running()
        val removed = apply(active, CodingMachine.Intent.DeleteSession(CodingMachine.ref(session)))
        rejected(removed, CodingMachine.Fact.NativeSessionBound(active.ref(), "late"))
        rejected(removed, CodingMachine.Fact.RunStopped(active.ref(), false))
        rejected(removed, CodingMachine.Intent.CreateSession(session))
        assertTrue(session.id in removed.removedSessions)
    }

    @Test fun titleResultCannotOverwriteManualNameOrNewTitleAttempt() {
        val requested = apply(ready(), CodingMachine.Fact.TitleRequested(CodingMachine.ref(session), "old"))
        val renamed = apply(requested, CodingMachine.Intent.RenameSession(CodingMachine.ref(session), "Mine"))
        val late = apply(renamed, CodingMachine.Fact.TitleObserved(CodingMachine.ref(session), "old", "Generated title"))
        assertEquals("Mine", late.sessions.getValue(session.id).name)
        assertEquals("", late.sessions.getValue(session.id).shortTitle)
    }

    @Test fun titleOutlivesTheFirstLaunchThatAdvancedTheSessionWhileTheModelNamedIt() {
        val asked = CodingMachine.ref(session)
        val organism = SessionOrganism("organism", project.id, session.id, createdAt = 2,
            sessions = mapOf(session.id to SessionNode(session.id, SessionKind.ZYGOTE, session.name, generation = 1, mode = CodingInteractionMode.CODE)))
        val launched = apply(running(), CodingMachine.Fact.OrganismProjected(organism, CodingMachine.ChildRevision("organism", 1, 0, "input")))
        assertEquals(1, launched.sessions.getValue(session.id).runtimeGeneration)
        val requested = apply(launched, CodingMachine.Fact.TitleRequested(asked, "title"))
        val stale = apply(requested, CodingMachine.Fact.TitleObserved(asked, "older", "Older title"))
        assertEquals("", stale.sessions.getValue(session.id).shortTitle, "the request id, not the launch, makes an answer late")
        val titled = apply(stale, CodingMachine.Fact.TitleObserved(asked, "title", "Generated title"))
        assertEquals("Generated title", titled.sessions.getValue(session.id).shortTitle)
        rejected(apply(requested, CodingMachine.Intent.DeleteSession(launched.sessions.getValue(session.id).let(CodingMachine::ref))),
            CodingMachine.Fact.TitleObserved(asked, "title", "Generated title"))
    }

    @Test fun unknownPersistenceRejectsAllCommandsAndProducesNoExecution() {
        val unknown = apply(running(), CodingMachine.Fact.PersistenceUnknown)
        for (input in listOf<CodingMachine.Input>(CodingMachine.Intent.Pause(unknown.ref()),
            CodingMachine.Intent.DeleteProject, CodingMachine.Intent.BeginRun(CodingMachine.ref(session), request("new"), 5))) rejected(unknown, input)
    }
    @Test fun unknownWorkspaceDominatesCompleteAndCannotBeClearedByEqualOrOlderProjection() {
        val task = TaskWorktree("task", "/project", "main", "base", "/task", "branch", phase = TaskWorktreePhase.COMPLETE)
        val revision = CodingMachine.ChildRevision("workspace", 4, 0, "four")
        val completed = apply(ready(), CodingMachine.Fact.WorktreeProjected(CodingMachine.ref(session), task, revision))
        val unknown = apply(completed, CodingMachine.Fact.WorktreeProjected(CodingMachine.ref(session), null, revision, unknown = true))
        assertEquals(task, unknown.sessions.getValue(session.id).taskWorktree)
        assertEquals(setOf("workspace:${session.id}"), unknown.unknownChildren)
        val same = apply(unknown, CodingMachine.Fact.WorktreeProjected(CodingMachine.ref(session), task, revision))
        assertEquals(unknown, same)
        val old = apply(unknown, CodingMachine.Fact.WorktreeProjected(CodingMachine.ref(session), task,
            revision.copy(seq = 2, inputId = "two"), unknown = true))
        assertEquals(unknown, old)
        rejected(unknown, CodingMachine.Intent.BeginRun(CodingMachine.ref(session), request(), 5))
        val recovered = apply(unknown, CodingMachine.Fact.WorktreeProjected(CodingMachine.ref(session), task,
            revision.copy(seq = 5, inputId = "five")))
        assertTrue(recovered.unknownChildren.isEmpty())
    }

    @Test fun workspaceFactOutlivesTheLaunchThatAdvancedTheSessionAfterTheTaskWasBound() {
        val bound = CodingMachine.ref(session)
        val task = TaskWorktree("task", "/project", "main", "base", "/task", "branch", phase = TaskWorktreePhase.CONFLICT)
        val revision = CodingMachine.ChildRevision("workspace", 1, 0, "one")
        val projected = apply(running(), CodingMachine.Fact.WorktreeProjected(bound, task, revision))
        val organism = SessionOrganism("organism", project.id, session.id, createdAt = 2,
            sessions = mapOf(session.id to SessionNode(session.id, SessionKind.ZYGOTE, session.name, generation = 1, mode = CodingInteractionMode.CODE)))
        val launched = apply(projected, CodingMachine.Fact.OrganismProjected(organism, CodingMachine.ChildRevision("organism", 1, 0, "input")))
        assertEquals(1, launched.sessions.getValue(session.id).runtimeGeneration)
        // A repair that never handed off leaves the task bound to the launch before it.
        val noted = apply(launched, CodingMachine.Fact.WorktreeProjected(bound, task.copy(error = "Конфликт требует продолжения"),
            revision.copy(seq = 2, inputId = "two")))
        assertEquals("Конфликт требует продолжения", noted.sessions.getValue(session.id).taskWorktree?.error)
        val uncertain = apply(noted, CodingMachine.Fact.WorktreeProjected(bound, null, revision.copy(seq = 3, inputId = "three"), unknown = true))
        assertEquals(setOf("workspace:${session.id}"), uncertain.unknownChildren, "an unknown outcome still blocks the next launch")
        assertEquals(noted, apply(noted, CodingMachine.Fact.WorktreeProjected(bound, task, revision)), "the revision, not the launch, orders child facts")
        rejected(apply(noted, CodingMachine.Intent.DeleteSession(CodingMachine.ref(noted.sessions.getValue(session.id)))),
            CodingMachine.Fact.WorktreeProjected(bound, task, revision.copy(seq = 4, inputId = "four")))
    }

    /**
     * A repair is a fresh request of the same run, admitted only for a result the worktree refused. A merge that nothing
     * refused is on its way to delivery; a repair there would relaunch the agent on a result that is already accepted.
     */
    @Test fun repairIsAdmittedForAMergeItsChecksRefusedAndNotForOneOnItsWayToDelivery() {
        val merged = TaskWorktree("task", "/project", "main", "base", "/task", "branch", phase = TaskWorktreePhase.MERGING,
            resultCommit = "result", mergeCommit = "merged")
        val revision = CodingMachine.ChildRevision("workspace", 1, 0, "one")
        val onItsWay = apply(running(), CodingMachine.Fact.WorktreeProjected(CodingMachine.ref(session), merged, revision))
        rejected(onItsWay, CodingMachine.Intent.BeginRepair(onItsWay.ref(), "repair", revision))

        val failed = revision.copy(seq = 2, inputId = "two")
        val refused = apply(onItsWay, CodingMachine.Fact.WorktreeProjected(CodingMachine.ref(session),
            merged.copy(error = "Проверка результата завершилась с ошибкой"), failed))
        rejected(refused, CodingMachine.Intent.BeginRepair(refused.ref(), "repair", revision))
        val repair = CodingMachine.reduce(refused, CodingMachine.Intent.BeginRepair(refused.ref(), "repair", failed))
        val effect = assertIs<CodingMachine.Effect.RunRequest>(repair.effects.single())
        assertEquals("repair", effect.request.runId)
        assertEquals(refused.ref().generation + 1, effect.ref.generation)
    }

    @Test fun lateCompleteTaskCannotOverwriteNewTaskAndLateStopCannotClearUnknown() {
        val task = TaskWorktree("old", "/project", "main", "base", "/task", "branch", phase = TaskWorktreePhase.COMPLETE)
        val revision = CodingMachine.ChildRevision("workspace", 1, 0, "one")
        val completed = apply(ready(), CodingMachine.Fact.WorktreeProjected(CodingMachine.ref(session), task, revision))
        val next = apply(completed, CodingMachine.Fact.WorktreeProjected(CodingMachine.ref(session),
            task.copy(taskId = "new", phase = TaskWorktreePhase.RUNNING), revision.copy(seq = 2, inputId = "two")))
        rejected(next, CodingMachine.Fact.WorktreeProjected(CodingMachine.ref(session), task, revision.copy(seq = 3, inputId = "three")))
        val unknown = apply(running(), CodingMachine.Fact.Restored)
        val lateStop = apply(unknown, CodingMachine.Fact.RunStopped(unknown.ref(), false))
        assertEquals(CodingMachine.Phase.UNKNOWN, lateStop.runs.getValue(session.id).phase)
    }

    @Test fun responseCannotReportSuccessWhileWorkspaceOutcomeIsUnknown() {
        val running = running()
        val task = TaskWorktree("task", "/project", "main", "base", "/task", "branch", phase = TaskWorktreePhase.COMPLETE)
        val unknown = apply(running, CodingMachine.Fact.WorktreeProjected(CodingMachine.ref(session), task,
            CodingMachine.ChildRevision("workspace", 1, 0, "one"), unknown = true))
        val finished = apply(unknown, CodingMachine.Fact.RunFinished(running.ref(),
            CodingMessage(request().responseId, CodingRole.AGENT, "Done", createdAt = 4)))
        assertEquals(CodingMachine.Phase.UNKNOWN, finished.runs.getValue(session.id).phase)
        assertNotNull(finished.sessions.getValue(session.id).pendingRun)
    }

    @Test fun editAtomicallyRewritesHistoryAndAdmitsOnlyTheMatchingSavedInput() {
        val old = CodingMessage("old", CodingRole.USER, "Old request", createdAt = 3)
        val state = apply(ready(), CodingMachine.Fact.HistoryPublished(CodingMachine.ref(session), listOf(old)))
        val request = request("edited")
        val message = CodingMessage(request.messageId, CodingRole.USER, request.prompt, createdAt = 4)
        rejected(state, CodingMachine.Intent.EditRequest(CodingMachine.ref(session), emptyList(), listOf(message), request))
        val accepted = apply(state, CodingMachine.Intent.EditRequest(CodingMachine.ref(session), listOf(old), listOf(message), request))
        assertEquals(listOf(message), accepted.histories[session.id])
        assertEquals(listOf(request), accepted.sessions.getValue(session.id).queuedPrompts)
        assertEquals(setOf(old.id), accepted.removedMessages[session.id])
        assertIs<CodingMachine.Effect.RunRequest>(CodingMachine.reduce(accepted,
            CodingMachine.Intent.BeginRun(CodingMachine.ref(session), request, 5)).effects.single())
        assertTrue(CodingMachine.reduce(accepted, CodingMachine.Fact.Restored).effects.isEmpty())
    }

    @Test fun provenNoNativeDispatchCanContinueOnlyAfterNewVerifiedChildOutcome() {
        val active = running()
        val task = TaskWorktree("task", "/project", "main", "base", "/task", "branch", phase = TaskWorktreePhase.PREPARING)
        val revision = CodingMachine.ChildRevision("workspace", 1, 0, "one")
        val uncertain = apply(active, CodingMachine.Fact.WorktreeProjected(CodingMachine.ref(session), task, revision, unknown = true))
        val stoppedBeforeDispatch = apply(uncertain, CodingMachine.Fact.RunStopped(active.ref(), unknown = false))
        assertEquals(CodingMachine.Phase.UNKNOWN, stoppedBeforeDispatch.runs.getValue(session.id).phase)
        assertTrue(stoppedBeforeDispatch.runs.getValue(session.id).knownStopped)
        val restored = apply(stoppedBeforeDispatch, CodingMachine.Fact.Restored)
        val inspected = apply(restored, CodingMachine.Fact.WorktreeProjected(CodingMachine.ref(session),
            task.copy(phase = TaskWorktreePhase.RUNNING), revision.copy(seq = 2, inputId = "two")))
        assertEquals(CodingMachine.Phase.INTERRUPTED, inspected.runs.getValue(session.id).phase)
        val abandoned = apply(inspected, CodingMachine.Intent.DiscardInterrupted(active.ref()))
        assertIs<CodingMachine.Effect.RunRequest>(CodingMachine.reduce(abandoned,
            CodingMachine.Intent.BeginRun(CodingMachine.ref(session), request("fresh"), 6)).effects.single())
        val lostNative = apply(uncertain, CodingMachine.Fact.Restored)
        val unproven = apply(apply(lostNative, CodingMachine.Fact.RunStopped(active.ref(), false)),
            CodingMachine.Fact.WorktreeProjected(CodingMachine.ref(session), task.copy(phase = TaskWorktreePhase.RUNNING),
                revision.copy(seq = 2, inputId = "two")))
        assertEquals(CodingMachine.Phase.UNKNOWN, unproven.runs.getValue(session.id).phase)
        assertFalse(unproven.runs.getValue(session.id).knownStopped)
    }

}
