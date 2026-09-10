package io.aequicor.magicpaper.domain

import io.aequicor.magicpaper.domain.tools.ToolExecutionContext
import io.aequicor.magicpaper.domain.tools.ToolRole
import io.aequicor.magicpaper.domain.tools.SessionCreateArgs
import io.aequicor.magicpaper.domain.tools.toolSchema
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class SessionRuntimeLimitsTest {
    private fun TestScope.runtime(f: SessionOrganismTestFixture, startedAt: Long = testScheduler.currentTime) =
        SessionTreeRuntime(f.service, f.projects, f.profiles, f.settings, clock = { 1_000 + testScheduler.currentTime - startedAt })

    @Test fun creatingAChildDoesNotRequireInventingATokenGrant() = runTest {
        val f = SessionOrganismTestFixture(limits = OrganismLimits()); f.initialize()
        val args = Json.decodeFromString<SessionCreateArgs>("""{"name":"Child","task":"Inspect","acceptance":"Verified findings"}""")
        assertEquals(0L, args.tokens)
        assertFalse(toolSchema(SessionCreateArgs.serializer().descriptor).getValue("required").jsonArray.any { it.jsonPrimitive.content == "tokens" })
        val organism = f.store.get(f.root.organismId!!)
        f.store.command(SessionAuthority(f.project.id, organism.id, f.root.id, f.root.runtimeGeneration, f.root.interactionMode),
            "child", OrganismCommand(OrganismAction.CREATE, name = args.name, tokens = args.tokens,
                task = SessionTask(args.task, f.root.id, args.acceptance)))
        assertEquals(SessionDesiredState.RUN, f.store.get(organism.id).sessions.getValue("session-child").desired)
    }

    @Test fun unboundedWorkSurvivesFormerTokenAndTimeCeilings() = runTest {
        val f = SessionOrganismTestFixture(limits = OrganismLimits()); f.initialize()
        val tree = runtime(f)
        val started = CompletableDeferred<Unit>(); val finish = CompletableDeferred<Unit>()
        val job = launch {
            tree.withScope(f.root) { current ->
                tree.observeUsage(current, CodingEvent.UsageObserved(TokenUsage(total = 2_000_000), "actual"))
                started.complete(Unit)
                finish.await()
            }
        }
        runCurrent(); assertTrue(started.isCompleted)
        advanceTimeBy(7_200_000); runCurrent()
        assertTrue(job.isActive)
        val active = f.store.get(f.root.organismId!!)
        assertEquals(2_000_000L, active.sessions.getValue(f.root.id).spentTokens)
        assertNull(active.limits.tokens); assertNull(active.limits.durationMillis)
        finish.complete(Unit); job.join()
        assertEquals(SessionObservedState.COMPLETED, f.store.get(active.id).sessions.getValue(f.root.id).observed)
    }

    @Test fun removingDeadlineCancelsOldTimerForOrdinaryAndAuxiliaryWork() = runTest {
        for (auxiliary in listOf(false, true)) {
            val f = SessionOrganismTestFixture(limits = OrganismLimits(durationMillis = 2_000))
            f.initialize(CodingInteractionMode.PLANNING)
            val tree = runtime(f)
            val started = CompletableDeferred<Unit>(); val finish = CompletableDeferred<Unit>()
            val body: suspend (CodingSession) -> Unit = { started.complete(Unit); finish.await() }
            val job = launch {
                if (!auxiliary) tree.withScope(f.root, body)
                else tree.withAuxiliaryScope(
                    CodingSession("alias", f.project.id, "Planner", 1, planId = "plan", parentSessionId = f.root.id, planningMode = true),
                    ToolExecutionContext(f.project.id, f.root.id, "alias", "request", ToolRole.PLANNER,
                        CodingInteractionMode.PLANNING, planId = "plan", runId = "run", organismId = f.root.organismId,
                        runtimeGeneration = f.root.runtimeGeneration), body)
            }
            runCurrent(); assertTrue(started.isCompleted)
            advanceTimeBy(500); runCurrent()
            f.settings.save(f.settings.load().copy(agentLimits = OrganismLimits()))
            f.service.synchronizeAllLimits(); runCurrent()
            advanceTimeBy(10_000); runCurrent()
            assertTrue(job.isActive, "An obsolete deadline must not stop the run")
            finish.complete(Unit); job.join()
            val saved = f.store.get(f.root.organismId!!)
            assertNull(saved.limits.durationMillis)
            if (auxiliary) assertEquals(SessionObservedState.COMPLETED, saved.auxiliaryRuns.values.single().observed)
            else assertEquals(SessionObservedState.PENDING, saved.sessions.getValue(f.root.id).observed)
        }
    }

    @Test fun savingAnExplicitTokenLimitStopsLiveWorkWithoutLosingItsUsage() = runTest {
        val f = SessionOrganismTestFixture(limits = OrganismLimits()); f.initialize()
        val tree = runtime(f)
        val started = CompletableDeferred<Unit>()
        var cleaned = false
        val job = launch {
            tree.withScope(f.root) { current ->
                tree.observeUsage(current, CodingEvent.UsageObserved(TokenUsage(total = 2_000_000), "actual"))
                started.complete(Unit)
                try { awaitCancellation() } finally { cleaned = true }
            }
        }
        runCurrent(); assertTrue(started.isCompleted)
        f.settings.save(f.settings.load().copy(agentLimits = OrganismLimits(tokens = 1_000_000)))
        f.service.synchronizeAllLimits(); runCurrent(); job.join()
        assertTrue(job.isCancelled); assertTrue(cleaned)
        val saved = f.store.get(f.root.organismId!!)
        assertEquals(2_000_000L, saved.sessions.getValue(f.root.id).spentTokens)
        assertEquals(SessionObservedState.STOPPED, saved.sessions.getValue(f.root.id).observed)
    }

    @Test fun aReceivedUsageObservationSurvivesCancellationWhileWaitingForANewPolicy() = runTest {
        for (auxiliary in listOf(false, true)) {
            val f = SessionOrganismTestFixture(limits = OrganismLimits()); f.initialize(CodingInteractionMode.PLANNING)
            val saving = CompletableDeferred<Unit>(); val releaseSave = CompletableDeferred<Unit>()
            val gated = object : SettingsRepository by f.settings {
                override suspend fun save(settings: AppSettings) {
                    f.settings.save(settings)
                    saving.complete(Unit)
                    releaseSave.await()
                }
            }
            val service = SessionOrganismService(f.store, f.projects, gated)
            val tree = SessionTreeRuntime(service, f.projects, f.profiles, gated, clock = { 1_000 })
            val started = CompletableDeferred<Unit>(); val nextUsage = CompletableDeferred<Unit>()
            val received = CompletableDeferred<Unit>()
            val body: suspend (CodingSession) -> Unit = { current ->
                tree.observeUsage(current, CodingEvent.UsageObserved(TokenUsage(total = 900), "usage"))
                started.complete(Unit)
                nextUsage.await()
                received.complete(Unit)
                tree.observeUsage(current, CodingEvent.UsageObserved(TokenUsage(total = 950), "usage"))
                awaitCancellation()
            }
            val job = launch {
                if (!auxiliary) tree.withScope(f.root, body)
                else tree.withAuxiliaryScope(
                    CodingSession("alias", f.project.id, "Planner", 1, planId = "plan", parentSessionId = f.root.id, planningMode = true),
                    ToolExecutionContext(f.project.id, f.root.id, "alias", "request", ToolRole.PLANNER,
                        CodingInteractionMode.PLANNING, planId = "plan", runId = "run", organismId = f.root.organismId,
                        runtimeGeneration = f.root.runtimeGeneration), body)
            }
            runCurrent(); assertTrue(started.isCompleted)
            val settings = f.settings.load().copy(agentLimits = OrganismLimits(tokens = 900))
            val save = async { service.saveSettingsAndApplyLimits(settings) }
            saving.await()
            nextUsage.complete(Unit)
            runCurrent(); assertTrue(received.isCompleted)
            assertEquals(900L, f.store.get(f.root.organismId!!).sessions.getValue(f.root.id).spentTokens)
            // Applying the saved limit cancels the live owner while its received usage
            // event waits on the policy lock. That event still must reach durable accounting.
            releaseSave.complete(Unit)
            assertTrue(save.await().isSuccess)
            job.join()
            val saved = f.store.get(f.root.organismId!!)
            assertTrue(job.isCancelled)
            assertEquals(950L, saved.sessions.getValue(f.root.id).spentTokens)
            if (auxiliary) {
                assertEquals(950L, saved.auxiliaryRuns.values.single().usage.getValue("usage"))
                assertEquals(SessionObservedState.STOPPED, saved.auxiliaryRuns.values.single().observed)
            } else assertEquals(SessionObservedState.STOPPED, saved.sessions.getValue(f.root.id).observed)
        }
    }

    @Test fun anExplicitDeadlineStopsWorkWithItsActualReason() = runTest {
        val f = SessionOrganismTestFixture(limits = OrganismLimits(durationMillis = 1_000)); f.initialize()
        val tree = runtime(f)
        val started = CompletableDeferred<Unit>()
        var reason: String? = null
        val job = launch {
            try { tree.withScope(f.root) { started.complete(Unit); awaitCancellation() } }
            catch (cancelled: CancellationException) { reason = cancelled.message; throw cancelled }
        }
        runCurrent(); assertTrue(started.isCompleted)
        advanceTimeBy(1_001); runCurrent(); job.join()
        assertTrue(job.isCancelled)
        assertEquals("Время задачи исчерпано", reason)
        assertEquals(SessionObservedState.STOPPED, f.store.get(f.root.organismId!!).sessions.getValue(f.root.id).observed)
    }
}
