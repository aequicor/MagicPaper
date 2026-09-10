package io.aequicor.magicpaper.domain

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
        val tree = SessionTreeRuntime(f.service, f.projects, f.profiles, f.settings, clock = { 1_000 })
        tree.runtime = ToolEnabledCodingRuntime(native, ToolHost(MemoryToolReceiptStore()), tree)
        return tree
    }

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

    @Test fun cancellingParentWaitsForChildCleanupAndCancelsLocalQuestions() = runTest { withContext(Dispatchers.Default) {
        val f = SessionOrganismTestFixture(); f.initialize()
        val started = CompletableDeferred<Unit>(); val cleaning = CompletableDeferred<Unit>(); val cleaned = CompletableDeferred<Unit>()
        val rootWaiting = CompletableDeferred<Unit>(); val cancelledQuestions = mutableSetOf<String>(); val questionsLock = Mutex()
        val tree = connect(f, Runtime { _ ->
            started.complete(Unit)
            try { awaitCancellation() } finally { withContext(NonCancellable) { cleaning.complete(Unit); cleaned.await() } }
        })
        tree.cancelQuestions = { questionsLock.withLock { cancelledQuestions += it }; Unit }
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
            f.service.waitChildren(setOf("session-create"))
            val original = f.projects.messages(f.project.id, "session-create")
            f.create("create")
            f.service.waitChildren(setOf("session-create"))
            assertEquals(original, f.projects.messages(f.project.id, "session-create"))
        } }
        assertEquals(1, starts)
    } }

    @Test fun explicitFailurePolicyActuallyCancelsRunningSibling() = runTest { withContext(Dispatchers.Default) {
        val f = SessionOrganismTestFixture(); f.initialize()
        val initial = f.store.get(f.root.organismId!!)
        f.storage.write("session-organism-${initial.id}", Json.encodeToString(initial.copy(sessions = initial.sessions +
            ("root" to initial.sessions.getValue("root").copy(failurePolicy = SessionFailurePolicy.CANCEL_SIBLINGS)))))
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
        val faultProjects = object : CodingProjectRepository by backing.projects {
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

    @Test fun questionCleanupFailureCannotLeaveSiblingRuntimeAlive() = runTest { withContext(Dispatchers.Default) { supervisorScope {
        val f = SessionOrganismTestFixture(); f.initialize()
        val firstStarted = CompletableDeferred<Unit>(); val secondStarted = CompletableDeferred<Unit>()
        val firstStopped = CompletableDeferred<Unit>(); val secondStopped = CompletableDeferred<Unit>()
        val tree = connect(f, Runtime { session ->
            val first = session.id == "session-first"
            (if (first) firstStarted else secondStarted).complete(Unit)
            try { awaitCancellation() } finally { (if (first) firstStopped else secondStopped).complete(Unit) }
        })
        tree.cancelQuestions = { id -> if (id == "session-first") error("question store failed") }
        val rootJob = async { tree.withScope(f.root) { f.create("first"); f.create("second"); awaitCancellation() } }
        try {
            withTimeout(5_000) { firstStarted.await(); secondStarted.await() }
            rootJob.cancel()
            withTimeout(5_000) { firstStopped.await(); secondStopped.await(); rootJob.join() }
        } finally { rootJob.cancelAndJoin() }
    } } }

    @Test fun lateOldRuntimeResultNeverAcquiresTheReplacementGeneration() = runTest { withContext(Dispatchers.Default) {
        val f = SessionOrganismTestFixture(); f.initialize()
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
            val replacement = old.copy(generation = old.generation + 1, previousGeneration = old.generation,
                observed = SessionObservedState.PENDING, version = old.version + 1)
            f.storage.write("session-organism-${before.id}", Json.encodeToString(before.copy(sessions = before.sessions + (old.id to replacement))))
            f.service.project(f.store.get(before.id))
            release.complete(Unit)
            withTimeout(5_000) { rootJob.join() }
            val saved = f.store.get(before.id)
            assertTrue(saved.results.none { it.sessionId == old.id && it.generation == replacement.generation })
            assertTrue(saved.outbox.none { "Late result" in it.packet.text })
        } finally { release.complete(Unit); rootJob.cancelAndJoin() }
    } }

    @Test fun queuedOldChildCannotRefreshItsAuthorityFromANewerSessionProjection() = runTest { withContext(Dispatchers.Default) {
        val f = SessionOrganismTestFixture(); f.initialize()
        val admitted = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var nativeStarted = false
        val tree = connect(f, Runtime { nativeStarted = true; emit(CodingEvent.Finished) })
        tree.prepareSession = { session ->
            if (session.id == "session-create") { admitted.complete(Unit); release.await() }
            session
        }
        val rootJob = launch { tree.withScope(f.root) { f.create("create") } }
        try {
            withTimeout(5_000) { admitted.await() }
            val before = f.store.get(f.root.organismId!!)
            val old = before.sessions.getValue("session-create")
            val replacement = old.copy(generation = old.generation + 1, previousGeneration = old.generation,
                observed = SessionObservedState.PENDING, version = old.version + 1)
            f.storage.write("session-organism-${before.id}", Json.encodeToString(before.copy(sessions = before.sessions + (old.id to replacement))))
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
            assertEquals(initial.sessions.getValue(initial.immunityId), saved.sessions.getValue(initial.immunityId))
            assertTrue(saved.operations.values.none { it.state == SessionOperationState.ACCEPTED })
        } finally { failRoot.complete(Unit); rootJob.cancelAndJoin(); childJob?.cancelAndJoin() }
    } }

    @Test fun unconfirmedLegacyControllerCleanupKeepsFailedRootUnknown() = runTest { withContext(Dispatchers.Default) {
        val f = SessionOrganismTestFixture(); f.initialize(CodingInteractionMode.PLANNING)
        val tree = connect(f, Runtime { emit(CodingEvent.Finished) })
        tree.externalPlanStop = { _, _ -> false }
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
            withTimeout(5_000) { f.service.waitChildren(setOf("session-nested")) }
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
