package io.aequicor.magicpaper.domain

import io.aequicor.magicpaper.domain.tools.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class SessionAuxiliaryRuntimeTest {
    private class Native(val body: suspend FlowCollector<CodingEvent>.(CodingSession) -> Unit) : CodingRuntime {
        var reconcileBody: suspend (String) -> Unit = {}
        override val supported = true
        override val rootPath = "/fixture"
        override suspend fun status() = RuntimeStatus(RuntimePhase.READY)
        override fun ensureReady() = flowOf(RuntimeStatus(RuntimePhase.READY))
        override fun run(project: CodingProject, session: CodingSession, prompt: String, profile: LlmProfile?, attachments: List<Attachment>) = flow { body(session) }
        override fun runPlanning(project: CodingProject, session: CodingSession, prompt: String, profile: LlmProfile) = flow { body(session) }
        override fun abort(sessionId: String) = Unit
        override fun abortAll() = Unit
        override suspend fun reconcile(sessionId: String) = reconcileBody(sessionId)
        override suspend fun uninstall() = Unit
    }
    private fun context(f: SessionOrganismTestFixture, id: String = "final") = ToolExecutionContext(f.project.id, f.root.id, id, "request-$id",
        ToolRole.CHAT, CodingInteractionMode.CODE, planId = "plan", runId = "run", organismId = f.root.organismId,
        runtimeGeneration = f.root.runtimeGeneration, auxiliaryExecution = true)
    private fun session(f: SessionOrganismTestFixture, id: String = "final") = CodingSession(id, f.project.id, "Verification", 1, planId = "plan", parentSessionId = f.root.id)
    private fun connect(f: SessionOrganismTestFixture, native: Native): Pair<SessionTreeRuntime, ToolHost> {
        val tree = SessionTreeRuntime(f.service, f.projects, f.profiles, f.settings, clock = { 1_000 })
        val host = ToolHost(MemoryToolReceiptStore())
        host.prepareWorker = { context(f, it.id) }
        tree.runtime = ToolEnabledCodingRuntime(native, host, tree)
        return tree to host
    }

    @Test fun planAliasUsesOwnersGenerationAndDeduplicatesActualUsage() = runTest { withContext(Dispatchers.Default) {
        val f = SessionOrganismTestFixture(); f.initialize(CodingInteractionMode.PLANNING)
        val before = f.store.get(f.root.organismId!!).sessions.getValue(f.root.id)
        val (tree, _) = connect(f, Native { current ->
            assertEquals(f.root.organismId, current.organismId); assertEquals(before.generation, current.runtimeGeneration)
            emit(CodingEvent.UsageObserved(TokenUsage(total = 10), "usage"))
            emit(CodingEvent.UsageObserved(TokenUsage(total = 10), "usage"))
            emit(CodingEvent.UsageObserved(TokenUsage(total = 15), "usage"))
            emit(CodingEvent.Finished)
        })
        tree.runtime!!.run(f.project, session(f), "Verify", null).collect()
        val saved = f.store.get(f.root.organismId!!)
        assertEquals(setOf(f.root.id, saved.immunityId), saved.sessions.keys)
        assertEquals(before.remainingTokens - 15, saved.sessions.getValue(f.root.id).remainingTokens)
        assertEquals(15L, saved.sessions.getValue(f.root.id).spentTokens)
        assertEquals(SessionObservedState.COMPLETED, saved.auxiliaryRuns.values.single().observed)
        assertEquals(before.generation, saved.sessions.getValue(f.root.id).generation)
    } }

    @Test fun inheritedPlanningToolScopeHasDurableNativeAliasLifetime() = runTest { withContext(Dispatchers.Default) {
        val f = SessionOrganismTestFixture(); f.initialize(CodingInteractionMode.PLANNING)
        val (tree, host) = connect(f, Native { emit(CodingEvent.UsageObserved(TokenUsage(total = 7), "planning")); emit(CodingEvent.Finished) })
        val tools = host.session(context(f, "planning-alias").copy(role = ToolRole.PLANNER, mode = CodingInteractionMode.PLANNING, auxiliaryExecution = false))
        val profile = LlmProfile("profile", "Profile", modelId = "model")
        tree.runtime!!.runPlanning(f.project, session(f, "planning-alias").copy(planningMode = true), "Inspect", profile).withTools(tools).collect()
        val saved = f.store.get(f.root.organismId!!)
        assertEquals(7L, saved.sessions.getValue(f.root.id).spentTokens)
        assertEquals(SessionObservedState.COMPLETED, saved.auxiliaryRuns.values.single().observed)
    } }

    @Test fun stoppingOwnerCancelsAndJoinsSeparatelyOwnedAlias() = runTest { withContext(Dispatchers.Default) {
        val f = SessionOrganismTestFixture(); f.initialize(CodingInteractionMode.PLANNING)
        val started = CompletableDeferred<Unit>(); val cleanup = CompletableDeferred<Unit>()
        val (tree, _) = connect(f, Native { started.complete(Unit); try { awaitCancellation() } finally { cleanup.complete(Unit) } })
        val job = launch { tree.runtime!!.run(f.project, session(f), "Verify", null).collect() }
        try {
            withTimeout(5_000) { started.await() }
            f.store.requestUserStop(f.root.organismId!!, f.root.id, "stop", false)
            withTimeout(5_000) { f.service.stopSubtree(setOf(f.root.id)); job.join(); cleanup.await() }
            val saved = f.store.get(f.root.organismId!!)
            assertEquals(SessionObservedState.STOPPED, saved.auxiliaryRuns.values.single().observed)
            assertEquals(SessionObservedState.STOPPED, saved.sessions.getValue(f.root.id).observed)
        } finally { job.cancelAndJoin() }
    } }

    @Test fun authenticatedPlanningAliasCreatesChildInItsOwnScopeAndJoinsIt() = runTest { withContext(Dispatchers.Default) {
        val f = SessionOrganismTestFixture(); f.initialize(CodingInteractionMode.PLANNING)
        val profile = LlmProfile("profile", "Profile", modelId = "model")
        f.profiles.save(profile); f.settings.save(f.settings.load().copy(activeLlmProfileId = profile.id))
        val childStarted = CompletableDeferred<Unit>(); val finishChild = CompletableDeferred<Unit>(); val modelReturned = CompletableDeferred<Unit>()
        val native = Native { current ->
            if (current.id == "planning-alias") {
                val context = currentCoroutineContext()[ToolSession]!!.context
                f.service.execute(context, "create", "session.create", buildJsonObject {
                    put("name", "Child"); put("task", "Inspect"); put("acceptance", "Verified findings"); put("tokens", 1_000)
                })
                childStarted.await(); modelReturned.complete(Unit); emit(CodingEvent.Finished)
            } else {
                childStarted.complete(Unit); finishChild.await(); emit(CodingEvent.FinalText("Verified child findings")); emit(CodingEvent.Finished)
            }
        }
        val (tree, host) = connect(f, native)
        host.prepareWorker = { ToolExecutionContext.worker(it) }
        val tools = host.session(context(f, "planning-alias").copy(role = ToolRole.PLANNER, mode = CodingInteractionMode.PLANNING, auxiliaryExecution = false))
        val job = async { tree.runtime!!.runPlanning(f.project, session(f, "planning-alias").copy(planningMode = true), "Inspect", profile).withTools(tools).collect() }
        try {
            withTimeout(5_000) { modelReturned.await() }; assertFalse(job.isCompleted)
            assertEquals(SessionObservedState.RUNNING, f.store.get(f.root.organismId!!).sessions.getValue("session-create").observed)
            finishChild.complete(Unit); withTimeout(5_000) { job.await() }
            val saved = f.store.get(f.root.organismId!!)
            assertEquals(SessionObservedState.COMPLETED, saved.sessions.getValue("session-create").observed)
            assertEquals(f.root.id, saved.sessions.getValue("session-create").lifecycleParentId)
            assertEquals(SessionObservedState.COMPLETED, saved.auxiliaryRuns.values.single().observed)
        } finally { finishChild.complete(Unit); job.cancelAndJoin() }
    } }

    @Test fun unknownAliasBlocksNewOwnerGenerationUntilExplicitReconciliation() = runTest { withContext(Dispatchers.Default) {
        val f = SessionOrganismTestFixture(); f.initialize(CodingInteractionMode.PLANNING)
        val native = Native { emit(CodingEvent.Finished) }
        native.reconcileBody = { error("process unconfirmed") }
        val (tree, _) = connect(f, native)
        tree.runtime!!.run(f.project, session(f), "Verify", null).collect()
        assertEquals(SessionObservedState.UNKNOWN, f.store.get(f.root.organismId!!).auxiliaryRuns.values.single().observed)
        assertFailsWith<IllegalArgumentException> { f.store.beginRun(f.root.organismId!!, f.root.id) }
        native.reconcileBody = {}
        f.service.stopSubtree(setOf(f.root.id))
        assertEquals(SessionObservedState.STOPPED, f.store.get(f.root.organismId!!).auxiliaryRuns.values.single().observed)
    } }

    @Test fun auxiliaryReservationsShareActualRuntimeCapacityAndCannotDebitLaterGeneration() = runTest {
        val f = SessionOrganismTestFixture(); f.initialize(CodingInteractionMode.PLANNING)
        val runs = (0..7).map { f.store.beginAuxiliary(context(f, "alias-$it")) }
        assertFailsWith<IllegalArgumentException> { f.store.beginAuxiliary(context(f, "over-limit")) }
        runs.forEach { f.store.finishAuxiliary(f.root.organismId!!, it.id, SessionObservedState.COMPLETED) }
        f.store.beginRun(f.root.organismId!!, f.root.id)
        assertFailsWith<IllegalArgumentException> { f.store.finishAuxiliary(f.root.organismId!!, runs.first().id, SessionObservedState.COMPLETED) }
        assertFailsWith<IllegalArgumentException> { f.store.chargeAuxiliary(f.root.organismId!!, runs.first().id, "late", 100) }
        assertEquals(0L, f.store.get(f.root.organismId!!).sessions.getValue(f.root.id).spentTokens)
    }
}
