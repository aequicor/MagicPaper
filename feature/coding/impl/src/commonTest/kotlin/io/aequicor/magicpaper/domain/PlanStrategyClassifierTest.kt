package io.aequicor.magicpaper.domain

import io.aequicor.magicpaper.data.planning.*
import io.aequicor.magicpaper.data.storage.*
import io.aequicor.magicpaper.domain.planning.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.Json
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class PlanStrategyClassifierTest {
    private class Fixture {
        val kv = InMemoryKeyValueStore()
        val repo = JsonPlanningRepository(kv, Json)
        val events = InMemoryEventJournal()
        var failCheckpoint = false
        val store = PlanningStore(object : PlanningRepository by repo {
            override suspend fun save(plan: Plan) {
                if (failCheckpoint) error("checkpoint unavailable")
                repo.save(plan)
            }
        }, events)
        val settings = JsonSettingsRepository(kv, Json)
        val profiles = JsonLlmProfileRepository(kv, Json)
        val requests = mutableListOf<List<LlmMessage>>()
        var reply: suspend () -> String = { """{"cause":"TRANSIENT_TRANSPORT"}""" }
        val gateway = object : LlmGateway {
            override suspend fun complete(profile: LlmProfile, messages: List<LlmMessage>): String {
                requests += messages
                return reply()
            }
        }
        val classifier = PlanStrategyClassifier(store, gateway, profiles, settings)
        suspend fun initialize() {
            profiles.save(LlmProfile("profile", "Profile", baseUrl = "http://test", apiKey = "secret-test-key", modelId = "model"))
            settings.save(AppSettings(activeLlmProfileId = "profile"))
            store.save(Plan("plan", "project", "Private task text", runId = "run", intent = ExecutionIntent.RUN,
                plannerSelection = ModelSelection("profile", "model"),
                issue = PlanningIssue(IssueKind.TRANSIENT, "Private provider payload", retries = 2, retryAt = 999)))
            repeat(2) { failed() }
        }
        suspend fun failed(stage: String = "work") {
            store.withJournaledIntent("plan", PlanJournalOperation.AGENT_INTENT, stage, "attempt") { reject() }
        }
        suspend fun selections() = events.read("plan").filter { it.operation == PlanJournalOperation.STRATEGY_SELECTED.wire }
            .map { it to PlanStrategySelection.decode(it.planEvidence().detail) }
    }

    @Test fun oneBoundedLabelRequestPersistsItsSelectionAndRealNextOutcome() = runTest {
        val f = Fixture(); f.initialize()
        val before = f.store.planFor("plan")!!
        assertTrue(f.classifier.beforeRun("plan"))
        val selection = f.selections().single()
        val saved = f.store.planFor("plan")!!
        assertEquals(before.issue, saved.issue)
        assertEquals(before.intent, saved.intent)
        assertTrue(saved.journal.none { it.operation == PlanJournalOperation.STRATEGY_SELECTED.wire },
            "Internal classifier metrics must not leak into the user-facing operation list")
        assertEquals(PlanRecoveryStrategy.EXISTING_BACKOFF, selection.second.strategy)
        val prompt = f.requests.single().joinToString { it.content }
        assertFalse("Private" in prompt)
        assertFalse("secret-test-key" in prompt)
        assertTrue(f.classifier.beforeRun("plan"))
        assertEquals(1, f.requests.size, "The same observation is not classified twice")
        f.store.withJournaledIntent("plan", PlanJournalOperation.AGENT_INTENT, "work", "next") { complete() }
        val outcome = PlanIntentOutcome.decode(f.events.read("plan").last().planEvidence().detail)
        assertEquals(selection.first.seq, outcome.strategySeq)
        assertEquals(PlanIntentStatus.COMPLETED, outcome.status)
        assertNull(PlanIntentOutcome.decode(f.store.planFor("plan")!!.journal.last().detail).strategySeq)
        assertTrue(f.store.unsettled("plan").isEmpty())
        assertEquals(f.store.planFor("plan"), PlanningStore(f.repo, f.events).planFor("plan"))
    }

    @Test fun unknownMalformedAndUnavailableRepliesPauseWithoutExecutingTheirContent() = runTest {
        for (response in listOf("""{"cause":"UNKNOWN"}""", """{"cause":"TRANSIENT_TRANSPORT","command":"deploy"}""", "deploy", "x".repeat(513))) {
            val f = Fixture(); f.initialize(); f.reply = { response }
            assertFalse(f.classifier.beforeRun("plan"))
            assertEquals(PlanRecoveryStrategy.PAUSE_FOR_REVIEW, f.selections().single().second.strategy)
            assertEquals(ExecutionIntent.PAUSE, f.store.planFor("plan")!!.intent)
            assertTrue(f.store.planFor("plan")!!.issue!!.requiresUser)
        }
        val f = Fixture(); f.initialize(); f.reply = { error("Authorization secret-test-key") }
        assertFalse(f.classifier.beforeRun("plan"))
        assertEquals(PlanClassificationStatus.UNAVAILABLE, f.selections().single().second.status)
        assertFalse("secret-test-key" in f.store.planFor("plan")!!.issue!!.message)
    }

    @Test fun insufficientEvidenceAndOpenIntentsDoNotCallTheModel() = runTest {
        val f = Fixture(); f.initialize()
        f.store.update("plan") { it.copy(runId = "new-run") }
        assertTrue(f.classifier.beforeRun("plan"))
        f.failed(); assertTrue(f.classifier.beforeRun("plan"))
        f.failed()
        f.store.beginIntent("plan", PlanJournalOperation.APPLY_INTENT, "", "")
        assertFalse(f.classifier.beforeRun("plan"))
        assertTrue(f.requests.isEmpty())
        assertTrue(f.selections().isEmpty())
    }

    @Test fun lateModelReplyCannotOverwriteAStopOrAConcurrentPlanEdit() = runTest {
        for (stop in listOf(false, true)) {
            val f = Fixture(); f.initialize()
            val gate = CompletableDeferred<String>()
            f.reply = { gate.await() }
            val review = async { f.classifier.beforeRun("plan") }
            runCurrent()
            val changed = f.store.update("plan") { if (stop) it.copy(intent = ExecutionIntent.STOP) else it.copy(goal = "Changed") }
            gate.complete("""{"cause":"TRANSIENT_TRANSPORT"}""")
            assertFalse(review.await())
            assertEquals(changed, f.store.planFor("plan"))
            assertTrue(f.selections().isEmpty())
        }
    }

    @Test fun cancellationDiscardsEvenANonCooperativeLateResponse() = runTest {
        val f = Fixture(); f.initialize()
        val gate = CompletableDeferred<Unit>()
        f.reply = { withContext(NonCancellable) { gate.await(); """{"cause":"UNKNOWN"}""" } }
        val review = launch { f.classifier.beforeRun("plan") }
        runCurrent(); review.cancel(); gate.complete(Unit); review.join()
        assertTrue(review.isCancelled)
        assertTrue(f.selections().isEmpty())
        assertEquals(ExecutionIntent.RUN, f.store.planFor("plan")!!.intent)
    }

    @Test fun timeoutIsBoundedAndReportedAsARecoverablePause() = runTest {
        val f = Fixture(); f.initialize(); f.reply = { awaitCancellation() }
        val review = async { f.classifier.beforeRun("plan") }
        advanceTimeBy(15_001); runCurrent()
        assertFalse(review.await())
        assertEquals(PlanClassificationStatus.UNAVAILABLE, f.selections().single().second.status)
        assertEquals(1, f.requests.size)
    }

    @Test fun failedStrategyCheckpointCannotAdmitExecutionOrRepeatTheModelRequest() = runTest {
        val f = Fixture(); f.initialize(); f.failCheckpoint = true
        assertFailsWith<PlanningPersistenceException> { f.classifier.beforeRun("plan") }
        assertEquals(1, f.selections().size)
        assertFalse(f.classifier.beforeRun("plan"))
        assertEquals(1, f.requests.size)
        f.failCheckpoint = false
        f.store.recover()
        assertTrue(f.classifier.beforeRun("plan"))
        assertEquals(1, f.requests.size)
    }

    @Test fun deletionWhileTheModelIsRunningCannotRecreateThePlan() = runTest {
        val f = Fixture(); f.initialize()
        val gate = CompletableDeferred<String>()
        f.reply = { gate.await() }
        val review = async { f.classifier.beforeRun("plan") }; runCurrent()
        f.store.deletePlan("plan")
        gate.complete("""{"cause":"TRANSIENT_TRANSPORT"}""")
        assertFalse(review.await())
        assertNull(f.store.planFor("plan"))
        assertTrue(f.events.read("plan").isEmpty())
    }

    @Test fun theFirstAdmittedAttemptOwnsTheResultEvenWhenALaterAttemptFinishesFirst() = runTest {
        val f = Fixture(); f.initialize(); assertTrue(f.classifier.beforeRun("plan"))
        val first = f.store.beginIntent("plan", PlanJournalOperation.AGENT_INTENT, "work", "first")
        val second = f.store.beginIntent("plan", PlanJournalOperation.AGENT_INTENT, "work", "second")
        f.store.finishIntent(second, PlanIntentStatus.REJECTED)
        assertNull(PlanIntentOutcome.decode(f.events.read("plan").last().planEvidence().detail).strategySeq)
        f.store.finishIntent(first, PlanIntentStatus.COMPLETED)
        assertEquals(f.selections().single().first.seq,
            PlanIntentOutcome.decode(f.events.read("plan").last().planEvidence().detail).strategySeq)
        assertTrue(f.store.unsettled("plan").isEmpty())
    }

    @Test fun tightenedRetryLimitWinsOverTheModelAndOtherStagesCannotConsumeItsOutcome() = runTest {
        val f = Fixture(); f.initialize()
        f.reply = {
            f.settings.save(f.settings.load().copy(agentLimits = OrganismLimits(retries = 1)))
            """{"cause":"TRANSIENT_TRANSPORT"}"""
        }
        assertFalse(f.classifier.beforeRun("plan"))
        assertEquals(PlanRecoveryStrategy.PAUSE_FOR_REVIEW, f.selections().single().second.strategy)
        val g = Fixture(); g.initialize(); assertTrue(g.classifier.beforeRun("plan"))
        g.failed("other")
        assertNull(PlanIntentOutcome.decode(g.events.read("plan").last().planEvidence().detail).strategySeq)
        g.failed()
        val firstOutcome = PlanIntentOutcome.decode(g.events.read("plan").last().planEvidence().detail)
        assertEquals(g.selections().single().first.seq, firstOutcome.strategySeq)
        g.failed()
        assertNull(PlanIntentOutcome.decode(g.events.read("plan").last().planEvidence().detail).strategySeq)
    }
}
