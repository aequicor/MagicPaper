package io.aequicor.magicpaper.domain

import io.aequicor.magicpaper.data.coding.*

import io.aequicor.magicpaper.domain.tools.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import kotlin.test.*

class SessionTreeRuntimeTest {
    private class Runtime(val behavior: suspend FlowCollector<CodingEvent>.(CodingSession) -> Unit) : CodingRuntime {
        var reconcileHandler: suspend (String) -> Unit = {}
        override val supported = true
        override val rootPath = "/fixture"
        override suspend fun status() = RuntimeStatus(RuntimePhase.READY)
        override fun ensureReady() = flowOf(RuntimeStatus(RuntimePhase.READY))
        override fun run(project: CodingProject, session: CodingSession, prompt: String, profile: LlmProfile?, attachments: List<Attachment>) = flow { behavior(session) }
        override fun abort(sessionId: String) = Unit
        override fun abortAll() = Unit
        override suspend fun reconcile(sessionId: String) = reconcileHandler(sessionId)
        override suspend fun uninstall() = Unit
    }

    private fun connect(f: SessionOrganismTestFixture, native: CodingRuntime): SessionTreeRuntime {
        val tree = testSessionTree(f.service, f.projects, f.profiles, f.settings, f.ports, clock = { 1_000 }).also { f.ports.tree = it }
        f.ports.nativeRuntime = testToolRuntime(native, testToolSessions(MemoryToolReceiptStore()), tree)
        return tree
    }

    @Test fun cleanupPersistenceFailurePreservesTheOriginalCancellationAndExposesTheFailure() = runTest { withContext(Dispatchers.Default) {
        for (auxiliary in listOf(false, true)) {
            val f = SessionOrganismTestFixture(); f.initialize(CodingInteractionMode.PLANNING)
            val persistenceFailure = IllegalStateException("checkpoint unavailable")
            var failCheckpoint = false
            val store = object : SessionOrganismStore by f.store {
                override suspend fun observe(id: String, sessionId: String, generation: Long, observed: SessionObservedState): SessionOrganism {
                    if (failCheckpoint) throw persistenceFailure
                    return f.store.observe(id, sessionId, generation, observed)
                }
                override suspend fun finishAuxiliary(id: String, runId: String, observed: SessionObservedState): SessionOrganism {
                    if (failCheckpoint) throw persistenceFailure
                    return f.store.finishAuxiliary(id, runId, observed)
                }
            }
            val service = testOrganismService(store, f.projects, f.settings, f.ports)
            val tree = testSessionTree(service, f.projects, f.profiles, f.settings, f.ports, clock = { 1_000 })
            val original = CancellationException("original cancellation")
            val body: suspend (CodingSession) -> Unit = { failCheckpoint = true; throw original }
            try {
                val caught = assertFailsWith<CancellationException> {
                    if (!auxiliary) tree.withScope(f.root, body)
                    else tree.withAuxiliaryScope(CodingSession("alias", f.project.id, "Planner", 1, planId = "plan", parentSessionId = f.root.id, planningMode = true),
                        ToolExecutionContext(f.project.id, f.root.id, "alias", "request", ToolRole.PLANNER,
                            CodingInteractionMode.PLANNING, planId = "plan", runId = "run", organismId = f.root.organismId,
                            runtimeGeneration = f.root.runtimeGeneration), body)
                }
                assertTrue(generateSequence<Throwable>(caught) { it.cause }.any { it === original })
                assertTrue(generateSequence<Throwable>(caught) { it.cause }.flatMap { it.suppressedExceptions.asSequence() }
                    .any { suppressed -> generateSequence(suppressed) { it.cause }.any { it === persistenceFailure } })
                assertTrue(withTimeout(5_000) { service.failures.first { it.isNotEmpty() } }.containsKey(f.root.organismId))
            } finally { service.shutdown() }
        }
    } }

    @Test fun cancellationDuringCleanupRecordsUnknownAndRemainsCancellation() = runTest { withContext(Dispatchers.Default) {
        val f = SessionOrganismTestFixture(); f.initialize()
        val tree = connect(f, Runtime { emit(CodingEvent.Finished) })
        f.ports.cancelQuestions = { throw CancellationException("cleanup cancelled") }
        val cancelled = assertFailsWith<CancellationException> { tree.withScope(f.root) {} }
        assertEquals("cleanup cancelled", cancelled.message)
        assertEquals(SessionObservedState.UNKNOWN, f.store.get(f.root.organismId!!).sessions.getValue(f.root.id).observed)
        assertTrue(withTimeout(5_000) { f.service.failures.first { it.isNotEmpty() } }.containsKey(f.root.organismId))
    } }

    @Test fun immunityResearchRunsAfterParentStopsWithoutChangingParentOrGrantingControl() = runTest { withContext(Dispatchers.Default) {
        val f = SessionOrganismTestFixture(); f.initialize(CodingInteractionMode.PLANNING)
        val tree = connect(f, Runtime { emit(CodingEvent.Finished) })
        val id = f.root.organismId!!
        f.store.requestUserStop(id, f.root.id, "stop-root", archive = false)
        f.store.observe(id, f.root.id, f.root.runtimeGeneration, SessionObservedState.STOPPED)
        val parent = f.store.get(id).sessions.getValue(f.root.id)
        val immunity = f.projects.sessions(f.project.id).single { it.sessionKind == SessionKind.IMMUNITY }
        repeat(2) {
            tree.withScope(immunity) { admitted ->
                assertEquals(CodingInteractionMode.RESEARCH, admitted.interactionMode)
                assertTrue(admitted.runtimeGeneration > 0)
                assertFailsWith<IllegalArgumentException> {
                    f.store.command(SessionAuthority(f.project.id, id, admitted.id, admitted.runtimeGeneration, admitted.interactionMode),
                        "bad-control-$it", OrganismCommand(OrganismAction.RESTORE, target = f.root.id, reason = "model wants retry"))
                }
            }
            assertEquals(parent, f.store.get(id).sessions.getValue(f.root.id))
            assertEquals(SessionObservedState.PENDING, f.store.get(id).sessions.getValue(immunity.id).observed)
        }
    } }

    @Test fun parentScopeWaitsForActualChildRuntimeAndRecordsInheritedRules() = runTest { withContext(Dispatchers.Default) {
        val f = SessionOrganismTestFixture(); f.initialize()
        val started = CompletableDeferred<CodingSession>(); val finish = CompletableDeferred<Unit>(); val parentReturned = CompletableDeferred<Unit>()
        val tree = connect(f, Runtime { session ->
            started.complete(session); finish.await()
            emit(CodingEvent.FinalText("Verified findings")); emit(CodingEvent.Finished)
        })
        val rootJob = async { tree.withScope(f.root) { f.create("create"); parentReturned.complete(Unit) } }
        try {
            val child = withTimeout(5_000) { started.await() }
            withTimeout(5_000) { parentReturned.await() }
            assertFalse(rootJob.isCompleted)
            assertEquals(f.root.planningRulesSnapshot, child.planningRulesSnapshot)
            assertEquals(SessionObservedState.RUNNING, f.store.get(f.root.organismId!!).sessions.getValue(child.id).observed)
            finish.complete(Unit)
            withTimeout(5_000) { rootJob.await() }
            val saved = f.store.get(f.root.organismId!!)
            assertEquals(SessionObservedState.COMPLETED, saved.sessions.getValue(child.id).observed)
            assertEquals(SessionObservedState.COMPLETED, saved.sessions.getValue(f.root.id).observed)
            assertTrue(f.projects.messages(f.project.id, child.id).any { it.text == "Verified findings" })
        } finally { finish.complete(Unit); rootJob.cancelAndJoin() }
    } }

    @Test fun childPersistsNativeConversationBeforeContinuingWork() = runTest { withContext(Dispatchers.Default) {
        val f = SessionOrganismTestFixture(); f.initialize()
        val sessionStarted = CompletableDeferred<Unit>(); val finish = CompletableDeferred<Unit>()
        val tree = connect(f, Runtime { _ ->
            emit(CodingEvent.SessionStarted("native-child-context"))
            sessionStarted.complete(Unit)
            finish.await()
            emit(CodingEvent.Finished)
        })
        val rootJob = launch { tree.withScope(f.root) { f.create("create") } }
        try {
            withTimeout(5_000) { sessionStarted.await() }
            val saved = f.projects.sessions(f.project.id).single { it.id == "session-create" }
            assertEquals("native-child-context", saved.piSessionId)
            assertFalse(saved.needsHistorySeed)
        } finally {
            finish.complete(Unit)
            withTimeout(5_000) { rootJob.join() }
        }
    } }

    /** Admits a user turn the way DefaultCodingService does, before the launch reaches the tree. */
    private suspend fun admit(f: SessionOrganismTestFixture, turn: Int): Pair<CodingMachine.RunRef, CodingSession> {
        val current = f.projects.sessions(f.project.id).single { it.id == f.root.id }
        val request = CodingRunCheckpoint("input-$turn", "Prompt $turn", responseId = "output-$turn",
            responseTimelineId = "timeline-$turn", runId = "request-$turn")
        val admitted = f.projects.dispatch(f.project.id, CodingMachine.Intent.BeginRun(CodingMachine.ref(current), request, 1_000))
        return admitted.effects.filterIsInstance<CodingMachine.Effect.RunRequest>().single().ref to admitted.state.sessions.getValue(f.root.id)
    }

    @Test fun laterTurnOfAResumedSessionKeepsItsRunLiveAndBindsItsNativeConversation() = runTest { withContext(Dispatchers.Default) {
        for (resumed in listOf(false, true)) {
            val f = SessionOrganismTestFixture(); f.initialize()
            connect(f, Runtime { session ->
                emit(CodingEvent.SessionStarted("native-${session.pendingRun!!.runId}"))
                emit(CodingEvent.FinalText("Done")); emit(CodingEvent.Finished)
            })
            val id = f.root.organismId!!
            if (resumed) {
                // Found interrupted after a crash and resumed by the user's next message: a restart of the root's generation.
                f.store.observe(id, f.root.id, f.store.get(id).sessions.getValue(f.root.id).generation, SessionObservedState.UNKNOWN)
                f.service.prepareUserTurn(f.root, "resume")
            }
            for (turn in 1..2) {
                val (ref, admitted) = admit(f, turn)
                // The second turn advances the generation by running, not by a restart: its own run must stay live.
                val events = try { f.ports.nativeRuntime.run(f.project, admitted, admitted.pendingRun!!.prompt, null, emptyList()).toList() }
                    catch (refused: CodingCommandRejected) { fail("resumed=$resumed turn $turn: ${refused.message}") }
                assertEquals(CodingEvent.Finished, events.last(), "resumed=$resumed turn $turn")
                val state = f.projects.states.value.getValue(f.project.id)
                assertEquals(CodingMachine.Phase.RUNNING, state.runs.getValue(f.root.id).phase, "resumed=$resumed turn $turn")
                assertEquals("native-request-$turn", state.sessions.getValue(f.root.id).piSessionId, "resumed=$resumed turn $turn")
                f.projects.dispatch(f.project.id, CodingMachine.Fact.RunFinished(ref, CodingMessage(ref.responseId, CodingRole.AGENT, "Done", createdAt = 1)))
            }
        }
    } }

    @Test fun stopRequestedBeforeTheLaunchReachesTheTreeEndsItAsAStopWithoutStartingTheEngine() = runTest { withContext(Dispatchers.Default) {
        for (earlierTurn in listOf(false, true)) {
            val f = SessionOrganismTestFixture(); f.initialize()
            var engineStarts = 0
            connect(f, Runtime { engineStarts++; emit(CodingEvent.SessionStarted("native")); emit(CodingEvent.Finished) })
            if (earlierTurn) {
                // A finished turn leaves the root settled, which the tree's stop passes over.
                val (ref, admitted) = admit(f, 0)
                f.ports.nativeRuntime.run(f.project, admitted, admitted.pendingRun!!.prompt, null, emptyList()).toList()
                f.projects.dispatch(f.project.id, CodingMachine.Fact.RunFinished(ref, CodingMessage(ref.responseId, CodingRole.AGENT, "Done", createdAt = 1)))
                engineStarts = 0
            }
            val (_, admitted) = admit(f, 1)
            // The user's stop, sent as OrchestrationService.stopManaged sends it, lands before the admitted launch enters the tree.
            val id = f.root.organismId!!
            val stopped = f.store.requestUserStop(id, f.root.id, "stop", archive = false)
            f.service.project(stopped)
            f.service.stopSubtree(stopped.subtree(f.root.id))
            f.service.project(f.store.finishStop(id, stopped.subtree(f.root.id)))

            assertFailsWith<CancellationException>("after an earlier turn: $earlierTurn") {
                f.ports.nativeRuntime.run(f.project, admitted, admitted.pendingRun!!.prompt, null, emptyList()).toList()
            }
            assertEquals(0, engineStarts, "after an earlier turn: $earlierTurn")
            // Nothing was dispatched to the engine, so the stop is a known one.
            assertEquals(CodingMachine.Phase.INTERRUPTED, f.projects.states.value.getValue(f.project.id).runs.getValue(f.root.id).phase,
                "after an earlier turn: $earlierTurn")
        }
    } }

    @Test fun cancellingParentWaitsForChildCleanupAndCancelsLocalQuestions() = runTest { withContext(Dispatchers.Default) {
        val f = SessionOrganismTestFixture(); f.initialize()
        val started = CompletableDeferred<Unit>(); val cleaning = CompletableDeferred<Unit>(); val cleaned = CompletableDeferred<Unit>()
        val rootWaiting = CompletableDeferred<Unit>(); val cancelledQuestions = mutableSetOf<String>(); val questionsLock = Mutex()
        val tree = connect(f, Runtime { _ ->
            started.complete(Unit)
            try { awaitCancellation() } finally { withContext(NonCancellable) { cleaning.complete(Unit); cleaned.await() } }
        })
        f.ports.cancelQuestions = { questionsLock.withLock { cancelledQuestions += it }; Unit }
        val rootJob = launch { tree.withScope(f.root) { f.create("create"); rootWaiting.complete(Unit); awaitCancellation() } }
        try {
            withTimeout(5_000) { started.await(); rootWaiting.await() }
            rootJob.cancel()
            withTimeout(5_000) { cleaning.await() }
            assertFalse(rootJob.isCompleted)
            assertFalse(f.store.get(f.root.organismId!!).sessions.getValue("session-create").settled)
            cleaned.complete(Unit)
            withTimeout(5_000) { rootJob.join() }
            assertEquals(SessionObservedState.STOPPED, f.store.get(f.root.organismId!!).sessions.getValue("session-create").observed)
            assertEquals(SessionObservedState.STOPPED, f.store.get(f.root.organismId!!).sessions.getValue("root").observed)
            assertTrue("session-create" in cancelledQuestions)
            assertTrue("root" in cancelledQuestions)
        } finally { cleaned.complete(Unit); rootJob.cancelAndJoin() }
    } }

    @Test fun failedNativeEventProducesFailedChildWithoutCancellingIndependentSibling() = runTest { withContext(Dispatchers.Default) {
        val f = SessionOrganismTestFixture(); f.initialize()
        val tree = connect(f, Runtime { session ->
            if (session.id == "session-failed") emit(CodingEvent.Failed("Backend failed"))
            else emit(CodingEvent.FinalText("Independent result"))
            emit(CodingEvent.Finished)
        })
        withTimeout(5_000) { tree.withScope(f.root) { f.create("failed"); f.create("success") } }
        val saved = f.store.get(f.root.organismId!!)
        assertEquals(SessionObservedState.FAILED, saved.sessions.getValue("session-failed").observed)
        assertEquals(SessionObservedState.COMPLETED, saved.sessions.getValue("session-success").observed)
    } }

    @Test fun duplicateCreationAfterCompletionNeverRepeatsRuntimeOrOverwritesResult() = runTest { withContext(Dispatchers.Default) {
        val f = SessionOrganismTestFixture(); f.initialize()
        val done = CompletableDeferred<Unit>()
        var starts = 0
        val tree = connect(f, Runtime { _ ->
            starts++
            emit(CodingEvent.FinalText("Original result")); emit(CodingEvent.Finished)
            done.complete(Unit)
        })
        withTimeout(5_000) { tree.withScope(f.root) {
            f.create("create")
            done.await()
            f.ports.await(setOf("session-create"))
            val original = f.projects.messages(f.project.id, "session-create")
            f.create("create")
            f.ports.await(setOf("session-create"))
            assertEquals(original, f.projects.messages(f.project.id, "session-create"))
        } }
        assertEquals(1, starts)
    } }

    @Test fun explicitFailurePolicyActuallyCancelsRunningSibling() = runTest { withContext(Dispatchers.Default) {
        val f = SessionOrganismTestFixture(); f.initialize()
        val initial = f.store.get(f.root.organismId!!)
        f.reopenLegacy(initial.copy(sessions = initial.sessions +
            ("root" to initial.sessions.getValue("root").copy(failurePolicy = SessionFailurePolicy.CANCEL_SIBLINGS))))
        val firstStarted = CompletableDeferred<Unit>(); val siblingStarted = CompletableDeferred<Unit>(); val siblingCancelled = CompletableDeferred<Unit>()
        val tree = connect(f, Runtime { session ->
            if (session.id == "session-failed") {
                firstStarted.complete(Unit)
                siblingStarted.await(); emit(CodingEvent.Failed("Failure")); emit(CodingEvent.Finished)
            } else {
                siblingStarted.complete(Unit)
                try { awaitCancellation() } finally { siblingCancelled.complete(Unit) }
            }
        })
        val rootJob = launch { tree.withScope(f.root) { f.create("failed"); firstStarted.await(); f.create("waiting") } }
        try {
            withTimeout(5_000) { siblingCancelled.await(); rootJob.join() }
            val saved = f.store.get(initial.id)
            assertEquals(SessionObservedState.FAILED, saved.sessions.getValue("session-failed").observed)
            assertEquals(SessionObservedState.STOPPED, saved.sessions.getValue("session-waiting").observed)
        } finally { rootJob.cancelAndJoin() }
    } }

    @Test fun dependentChildStartsOnlyAfterItsPredecessorSettles() = runTest { withContext(Dispatchers.Default) {
        val f = SessionOrganismTestFixture(); f.initialize()
        val firstStarted = CompletableDeferred<Unit>(); val releaseFirst = CompletableDeferred<Unit>(); val secondStarted = CompletableDeferred<Unit>()
        var firstSettledWhenSecondStarted = false
        val tree = connect(f, Runtime { session ->
            if (session.id == "session-first") { firstStarted.complete(Unit); releaseFirst.await() }
            else {
                firstSettledWhenSecondStarted = f.store.get(f.root.organismId!!).sessions.getValue("session-first").settled
                secondStarted.complete(Unit)
            }
            emit(CodingEvent.FinalText("Done")); emit(CodingEvent.Finished)
        })
        val rootJob = launch { tree.withScope(f.root) {
            f.create("first")
            firstStarted.await()
            f.service.execute(f.context(), "second", "session.create", Json.encodeToJsonElement(SessionCreateArgs("Dependent", "Use predecessor result",
                "Result verified", 1_000, dependencies = setOf("session-first"))).jsonObject)
            releaseFirst.complete(Unit)
        } }
        try {
            withTimeout(5_000) { secondStarted.await(); rootJob.join() }
            assertTrue(firstSettledWhenSecondStarted)
        } finally { releaseFirst.complete(Unit); rootJob.cancelAndJoin() }
    } }

    @Test fun setupFailureBeforeNativeStartLeavesConfirmedFailedChild() = runTest { withContext(Dispatchers.Default) {
        val f = SessionOrganismTestFixture(); f.initialize(CodingInteractionMode.PLANNING)
        val tree = connect(f, Runtime { _ -> error("No configured model should prevent native start") })
        withTimeout(5_000) { tree.withScope(f.root) { f.create("missing-profile") } }
        assertEquals(SessionObservedState.FAILED, f.store.get(f.root.organismId!!).sessions.getValue("session-missing-profile").observed)
    } }

    @Test fun failedSessionLookupBeforeBodyDoesNotLeakParentHandleOrJob() = runTest { withContext(Dispatchers.Default) {
        val backing = SessionOrganismTestFixture()
        var failLookup = false
        val faultProjects = object : CodingProjectOwner by backing.projects {
            override suspend fun sessions(projectId: String): List<CodingSession> {
                if (failLookup) { failLookup = false; error("session lookup failed") }
                return backing.projects.sessions(projectId)
            }
        }
        val f = SessionOrganismTestFixture(faultProjects, backing.storage); f.initialize()
        val tree = connect(f, Runtime { emit(CodingEvent.Finished) })
        failLookup = true
        withTimeout(5_000) {
            assertFailsWith<IllegalStateException> { tree.withScope(f.root) { error("must not reach body") } }
            assertEquals("recovered", tree.withScope(f.root) { "recovered" })
        }
    } }

    @Test fun stopRemainsUnconfirmedUntilNativeReconciliationCompletes() = runTest { withContext(Dispatchers.Default) {
        val f = SessionOrganismTestFixture(); f.initialize()
        val started = CompletableDeferred<Unit>(); val reconciling = CompletableDeferred<Unit>(); val reconciled = CompletableDeferred<Unit>()
        val native = Runtime { started.complete(Unit); awaitCancellation() }
        native.reconcileHandler = { id -> if (id == "session-create") { reconciling.complete(Unit); reconciled.await() } }
        val tree = connect(f, native)
        val rootJob = launch { tree.withScope(f.root) { f.create("create"); awaitCancellation() } }
        try {
            withTimeout(5_000) { started.await() }
            rootJob.cancel()
            withTimeout(5_000) { reconciling.await() }
            assertFalse(rootJob.isCompleted)
            assertFalse(f.store.get(f.root.organismId!!).sessions.getValue("session-create").settled)
            reconciled.complete(Unit)
            withTimeout(5_000) { rootJob.join() }
            assertEquals(SessionObservedState.STOPPED, f.store.get(f.root.organismId!!).sessions.getValue("session-create").observed)
        } finally { reconciled.complete(Unit); rootJob.cancelAndJoin() }
    } }

    /**
     * Stopping proves cleanup, not the outcome of what the interrupted turn executed. A stop someone asked for is settled by
     * a proven exit, so the user's stop can finish; the unknown outcome stays with the native recovery and the coding run.
     * An exit nobody proved, or a cancellation nobody asked for, still leaves the session unknown. Regression: a Pi run
     * stopped while a questionnaire was open stayed unknown, and «Остановка ещё не подтверждена» refused the user's stop.
     */
    @Test fun requestedStopWithAProvenExitSettlesThoughTheTurnOutcomeIsUnknown() = runTest { withContext(Dispatchers.Default) {
        for ((termination, requested) in listOf(NativeRunTermination.STOPPED to true, NativeRunTermination.UNKNOWN to true,
            NativeRunTermination.STOPPED to false)) {
            val f = SessionOrganismTestFixture(); f.initialize()
            val attempt = NativeRunRecoveryRef(CodingEngine.PI, f.root.id, "request", 0)
            val native = Runtime { emit(CodingEvent.Finished) }
            native.reconcileHandler = { throw NativeRunRecoveryRequired(NativeRunRecoverySnapshot(
                listOf(NativeRunRecoveryItem(attempt, NativeRunOutcome.UNKNOWN, termination, null)), false)) }
            val tree = connect(f, native)
            val id = f.root.organismId!!
            val started = CompletableDeferred<Unit>()
            val rootJob = launch { tree.withScope(f.root) { started.complete(Unit); awaitCancellation() } }
            withTimeout(5_000) { started.await() }
            // The order OrchestrationService.stopManaged uses: record the stop, stop the subtree, then finish the stop.
            if (requested) { f.store.requestUserStop(id, f.root.id, "stop-root", archive = false); f.service.stopSubtree(setOf(f.root.id)) }
            else rootJob.cancelAndJoin()
            assertTrue(rootJob.isCompleted, "the stop joins the run it stopped")
            val observed = f.store.get(id).sessions.getValue(f.root.id).observed
            val case = "termination=$termination requested=$requested"
            if (termination == NativeRunTermination.STOPPED && requested) {
                assertEquals(SessionObservedState.STOPPED, observed, case)
                f.store.finishStop(id, setOf(f.root.id))
                assertTrue(f.service.failures.value.isEmpty(), "a stop the user asked for is not a failure to confirm: $case")
            } else {
                assertEquals(SessionObservedState.UNKNOWN, observed, case)
                if (requested) assertFailsWith<ToolArgumentRejection>(case) { f.store.finishStop(id, setOf(f.root.id)) }
                assertTrue(withTimeout(5_000) { f.service.failures.first { it.isNotEmpty() } }.containsKey(id), case)
            }
        }
    } }

    /**
     * Relaunching a session after an interrupted run needs its exit proven and its unknown outcome decided by the user, the
     * rule the native journal admits the next run by. A decided outcome relaunches and the run settles; an undecided one refuses.
     */
    @Test fun relaunchAfterAnInterruptedRunAcceptsAnOutcomeTheUserDecided() = runTest { withContext(Dispatchers.Default) {
        for (decided in listOf(true, false)) {
            val f = SessionOrganismTestFixture(); f.initialize()
            val native = Runtime { emit(CodingEvent.Finished) }
            val tree = connect(f, native)
            val id = f.root.organismId!!
            f.ports.cancelQuestions = { throw CancellationException("interrupted") }
            assertFailsWith<CancellationException> { tree.withScope(f.root) {} }
            assertEquals(SessionObservedState.UNKNOWN, f.store.get(id).sessions.getValue(f.root.id).observed)
            f.ports.cancelQuestions = {}
            val attempt = NativeRunRecoveryRef(CodingEngine.PI, f.root.id, "request", 0)
            val decision = NativeRunRecoveryAcknowledgement("ack", attempt, "decision").takeIf { decided }
            native.reconcileHandler = { throw NativeRunRecoveryRequired(NativeRunRecoverySnapshot(
                listOf(NativeRunRecoveryItem(attempt, NativeRunOutcome.UNKNOWN, NativeRunTermination.STOPPED, decision)), false)) }
            var relaunched = false
            val result = runCatching { tree.withScope(f.root) { relaunched = true } }
            val case = "decided=$decided"
            assertEquals(decided, relaunched, case)
            if (decided) assertEquals(SessionObservedState.COMPLETED, f.store.get(id).sessions.getValue(f.root.id).observed, case)
            else assertIs<NativeRunRecoveryRequired>(result.exceptionOrNull(), case)
        }
    } }

    /** An auxiliary run stopped with its owner follows the same rule: a proven exit settles it, an unproven one does not. */
    @Test fun auxiliaryRunStoppedWithItsOwnerSettlesOnAProvenExit() = runTest { withContext(Dispatchers.Default) {
        for (termination in listOf(NativeRunTermination.STOPPED, NativeRunTermination.UNKNOWN)) {
            val f = SessionOrganismTestFixture(); f.initialize(CodingInteractionMode.PLANNING)
            val alias = CodingSession("alias", f.project.id, "Planner", 1, planId = "plan", parentSessionId = f.root.id, planningMode = true)
            val native = Runtime { emit(CodingEvent.Finished) }
            native.reconcileHandler = { throw NativeRunRecoveryRequired(NativeRunRecoverySnapshot(listOf(NativeRunRecoveryItem(
                NativeRunRecoveryRef(CodingEngine.PI, alias.id, "request", 0), NativeRunOutcome.UNKNOWN, termination, null)), false)) }
            val tree = connect(f, native)
            val id = f.root.organismId!!
            val started = CompletableDeferred<Unit>()
            val job = launch {
                tree.withAuxiliaryScope(alias, ToolExecutionContext(f.project.id, f.root.id, alias.id, "request", ToolRole.PLANNER,
                    CodingInteractionMode.PLANNING, planId = "plan", runId = "run", organismId = id,
                    runtimeGeneration = f.root.runtimeGeneration)) { started.complete(Unit); awaitCancellation() }
            }
            withTimeout(5_000) { started.await() }
            f.store.requestUserStop(id, f.root.id, "stop-root", archive = false)
            job.cancelAndJoin()
            val expected = if (termination == NativeRunTermination.STOPPED) SessionObservedState.STOPPED else SessionObservedState.UNKNOWN
            assertEquals(expected, f.store.get(id).auxiliaryRuns.values.single().observed, "termination=$termination")
        }
    } }

    @Test fun questionCleanupFailureCannotLeaveSiblingRuntimeAlive() = runTest { withContext(Dispatchers.Default) { supervisorScope {
        val f = SessionOrganismTestFixture(); f.initialize()
        val firstStarted = CompletableDeferred<Unit>(); val secondStarted = CompletableDeferred<Unit>()
        val firstStopped = CompletableDeferred<Unit>(); val secondStopped = CompletableDeferred<Unit>()
        val tree = connect(f, Runtime { session ->
            val first = session.id == "session-first"
            (if (first) firstStarted else secondStarted).complete(Unit)
            try { awaitCancellation() } finally { (if (first) firstStopped else secondStopped).complete(Unit) }
        })
        f.ports.cancelQuestions = { id -> if (id == "session-first") error("question store failed") }
        val rootJob = async { tree.withScope(f.root) { f.create("first"); f.create("second"); awaitCancellation() } }
        try {
            withTimeout(5_000) { firstStarted.await(); secondStarted.await() }
            rootJob.cancel()
            withTimeout(5_000) { firstStopped.await(); secondStopped.await(); rootJob.join() }
        } finally { rootJob.cancelAndJoin() }
    } } }

    @Test fun lateOldRuntimeResultNeverAcquiresTheReplacementGeneration() = runTest { withContext(Dispatchers.Default) {
        val f = SessionOrganismTestFixture(sourceSnapshot = { "unchanged-source" }); f.initialize()
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val tree = connect(f, Runtime { _ ->
            started.complete(Unit); release.await()
            emit(CodingEvent.FinalText("Late result from old runtime")); emit(CodingEvent.Finished)
        })
        val rootJob = launch { tree.withScope(f.root) { f.create("create") } }
        try {
            withTimeout(5_000) { started.await() }
            // Inject a crash/recreation race while an old backend response is in flight.
            val before = f.store.get(f.root.organismId!!)
            val old = before.sessions.getValue("session-create")
            f.store.requestUserStop(before.id, old.id, "replace-stop", true)
            f.store.observe(before.id, old.id, old.generation, SessionObservedState.STOPPED)
            f.store.finishStop(before.id, setOf(old.id))
            val replaced = f.store.restoreByUser(before.id, old.id, "replace-run", f.settings.load().planningRules.snapshot(), old.task?.sourceVersion)
            val replacement = replaced.sessions.getValue(old.id)
            f.service.project(f.store.get(before.id))
            release.complete(Unit)
            withTimeout(5_000) { rootJob.join() }
            val saved = f.store.get(before.id)
            assertTrue(saved.results.none { it.sessionId == old.id && it.generation == replacement.generation })
            assertTrue(saved.outbox.none { "Late result" in it.packet.text })
        } finally { release.complete(Unit); rootJob.cancelAndJoin() }
    } }

    @Test fun queuedOldChildCannotRefreshItsAuthorityFromANewerSessionProjection() = runTest { withContext(Dispatchers.Default) {
        val f = SessionOrganismTestFixture(sourceSnapshot = { "unchanged-source" }); f.initialize()
        val admitted = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var nativeStarted = false
        val tree = connect(f, Runtime { nativeStarted = true; emit(CodingEvent.Finished) })
        f.ports.prepareSession = { session ->
            if (session.id == "session-create") { admitted.complete(Unit); release.await() }
            session
        }
        val rootJob = launch { tree.withScope(f.root) { f.create("create") } }
        try {
            withTimeout(5_000) { admitted.await() }
            val before = f.store.get(f.root.organismId!!)
            val old = before.sessions.getValue("session-create")
            f.store.requestUserStop(before.id, old.id, "replace-stop", true)
            f.store.observe(before.id, old.id, old.generation, SessionObservedState.STOPPED)
            f.store.finishStop(before.id, setOf(old.id))
            val replaced = f.store.restoreByUser(before.id, old.id, "replace-run", f.settings.load().planningRules.snapshot(), old.task?.sourceVersion)
            val replacement = replaced.sessions.getValue(old.id)
            f.service.project(f.store.get(before.id))
            release.complete(Unit)
            withTimeout(5_000) { rootJob.join() }
            assertFalse(nativeStarted)
            assertEquals(replacement.generation, f.store.get(before.id).sessions.getValue(old.id).generation)
            assertTrue(f.store.get(before.id).results.none { it.sessionId == old.id })
        } finally { release.complete(Unit); rootJob.cancelAndJoin() }
    } }

    @Test fun nativeFailureEventCancelsWaitingChildInsteadOfJoiningForever() = runTest { withContext(Dispatchers.Default) {
        val f = SessionOrganismTestFixture(); f.initialize()
        val childStarted = CompletableDeferred<Unit>(); val childStopped = CompletableDeferred<Unit>()
        val tree = connect(f, Runtime { session ->
            if (session.id == f.root.id) {
                f.create("waiting")
                childStarted.await()
                emit(CodingEvent.Failed("root failed")); emit(CodingEvent.Finished)
            } else {
                childStarted.complete(Unit)
                try { awaitCancellation() } finally { childStopped.complete(Unit) }
            }
        })
        withTimeout(5_000) {
            tree.runtime!!.run(f.project, f.root, "Work", null).collect()
            childStopped.await()
        }
        val saved = f.store.get(f.root.organismId!!)
        assertEquals(SessionObservedState.FAILED, saved.sessions.getValue(f.root.id).observed)
        assertEquals(SessionObservedState.STOPPED, saved.sessions.getValue("session-waiting").observed)
    } }

    @Test fun failedPlanningRootCancelsASeparatelyOwnedLegacyStageBeforeSettling() = runTest { withContext(Dispatchers.Default) {
        val f = SessionOrganismTestFixture(); f.initialize(CodingInteractionMode.PLANNING)
        val tree = connect(f, Runtime { emit(CodingEvent.Finished) })
        val rootEntered = CompletableDeferred<Unit>(); val failRoot = CompletableDeferred<Unit>()
        val childStarted = CompletableDeferred<Unit>(); val childStopped = CompletableDeferred<Unit>()
        val initial = f.store.get(f.root.organismId!!)
        val rootJob = async {
            assertFailsWith<IllegalStateException> { tree.withScope(f.root) {
                rootEntered.complete(Unit); failRoot.await(); error("planning root failed")
            } }
        }
        var childJob: Job? = null
        try {
            withTimeout(5_000) { rootEntered.await() }
            val child = CodingSession("legacy-worker", f.project.id, "Legacy worker", 1,
                planId = "plan", stageId = "work", parentSessionId = f.root.id, engine = CodingEngine.PI)
            val admitted = f.store.admitPlanWorker(initial.id, child, SessionTask("Work", f.root.id, "Verify"),
                SessionLegacyAttempt("plan", "run", "work", "attempt", 0, 0), initial.sessions.getValue(f.root.id).rules, setOf("work"))
            f.service.project(admitted)
            childJob = launch { tree.withScope(child.copy(organismId = initial.id, runtimeGeneration = admitted.sessions.getValue(child.id).generation)) {
                childStarted.complete(Unit)
                try { awaitCancellation() } finally { childStopped.complete(Unit) }
            } }
            withTimeout(5_000) { childStarted.await() }
            failRoot.complete(Unit)
            withTimeout(5_000) { rootJob.await(); childStopped.await(); childJob.join() }
            val saved = f.store.get(initial.id)
            assertEquals(SessionObservedState.FAILED, saved.sessions.getValue(f.root.id).observed)
            assertEquals(SessionObservedState.STOPPED, saved.sessions.getValue(child.id).observed)
            assertEquals(SessionDesiredState.STOP, saved.sessions.getValue(child.id).desired)
            assertEquals(initial.sessions.getValue(initial.immunityId!!), saved.sessions.getValue(initial.immunityId!!))
            assertTrue(saved.operations.values.none { it.state == SessionOperationState.ACCEPTED })
        } finally { failRoot.complete(Unit); rootJob.cancelAndJoin(); childJob?.cancelAndJoin() }
    } }

    @Test fun unconfirmedLegacyControllerCleanupKeepsFailedRootUnknown() = runTest { withContext(Dispatchers.Default) {
        val f = SessionOrganismTestFixture(); f.initialize(CodingInteractionMode.PLANNING)
        val tree = connect(f, Runtime { emit(CodingEvent.Finished) })
        f.ports.externalPlanStop = { _, _ -> false }
        assertFailsWith<IllegalStateException> { tree.withScope(f.root) { error("planning root failed") } }
        val saved = f.store.get(f.root.organismId!!)
        assertEquals(SessionObservedState.UNKNOWN, saved.sessions.getValue(f.root.id).observed)
        assertTrue(saved.operations.values.any { it.state == SessionOperationState.ACCEPTED })
    } }

    @Test fun closingParentRejectsNewChildrenButOpenChildCanStillCreateItsOwnChild() = runTest {
        withContext(Dispatchers.Default.limitedParallelism(1)) {
        val f = SessionOrganismTestFixture(); f.initialize()
        val childStarted = CompletableDeferred<Unit>(); val releaseChild = CompletableDeferred<Unit>(); val parentReturned = CompletableDeferred<Unit>()
        val started = MutableStateFlow(emptySet<String>())
        val tree = connect(f, Runtime { session ->
            started.update { it + session.id }
            if (session.id == "session-open") { childStarted.complete(Unit); releaseChild.await() }
            emit(CodingEvent.FinalText("Complete")); emit(CodingEvent.Finished)
        })
        val rootJob = async { tree.withScope(f.root) {
            f.service.execute(f.context(), "open", "session.create", Json.encodeToJsonElement(SessionCreateArgs("Open child", "Inspect project",
                "Verified result", 5_000)).jsonObject)
            childStarted.await()
            parentReturned.complete(Unit)
        } }
        try {
            withTimeout(5_000) { parentReturned.await() }
            assertFalse(rootJob.isCompleted)
            assertFailsWith<IllegalArgumentException> { f.create("too-late") }
            val rejected = f.store.get(f.root.organismId!!).sessions.getValue("session-too-late")
            assertEquals(SessionObservedState.STOPPED, rejected.observed)
            assertEquals(0L, rejected.remainingTokens)
            assertFalse("session-too-late" in started.value)
            f.create("nested", "session-open")
            withTimeout(5_000) { f.ports.await(setOf("session-nested")) }
            assertTrue("session-nested" in started.value)
            releaseChild.complete(Unit)
            withTimeout(5_000) { rootJob.await() }
            assertEquals(SessionObservedState.COMPLETED, f.store.get(f.root.organismId!!).sessions.getValue("root").observed)
        } finally { releaseChild.complete(Unit); rootJob.cancelAndJoin() }
        }
    }

    @Test fun nextRootTurnHandlesUnprocessedChildResultFromPreviousGeneration() = runTest { withContext(Dispatchers.Default) {
        val f = SessionOrganismTestFixture(); f.initialize()
        val tree = connect(f, Runtime { _ -> emit(CodingEvent.FinalText("Completed child result")); emit(CodingEvent.Finished) })
        withTimeout(5_000) { tree.withScope(f.root) { f.create("create") } }
        val previous = f.store.get(f.root.organismId!!)
        assertTrue(previous.outbox.any { it.recipient == "root" && it.state == SessionDeliveryState.DELIVERED })
        withTimeout(5_000) { tree.withScope(f.root) { current ->
            assertTrue(current.runtimeGeneration > previous.sessions.getValue("root").generation)
            assertTrue(tree.incoming(current).all { it.recipientGeneration == current.runtimeGeneration })
        } }
        assertTrue(f.projects.messages(f.project.id, "root").any { it.origin == MessageOrigin.SESSION && it.text == "Completed child result" })
    } }
}
