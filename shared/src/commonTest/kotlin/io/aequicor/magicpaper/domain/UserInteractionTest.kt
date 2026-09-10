package io.aequicor.magicpaper.domain

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.Json
import io.aequicor.magicpaper.data.coding.JsonRuntimeQuestionnaireStore
import io.aequicor.magicpaper.data.storage.InMemoryKeyValueStore
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class UserInteractionTest {
    private val q = PlanningQuestion("q", "Как продолжить?", QuestionKind.SINGLE, listOf(QuestionOption("a", "Первый"), QuestionOption("b", "Второй")))
    private fun request(id: String) = UserInteractionRequest(id, "p", "s", InteractionKind.RUNTIME, listOf(q))

    @Test fun queuePreservesArrivalAndChangesNeitherActiveRequestNorItsPosition() {
        val queue = UserInteractionQueue()
        assertEquals(listOf("a"), queue.reconcile(listOf(request("a"))).map { it.id })
        assertEquals(listOf("a", "b"), queue.reconcile(listOf(request("b"), request("a").copy(context = "Updated"))).map { it.id })
        assertEquals(listOf("b", "c"), queue.reconcile(listOf(request("c"), request("b"), request("a")), setOf("a")).map { it.id })
        assertEquals(listOf("c"), queue.reconcile(listOf(request("c"))).map { it.id })
    }

    @Test fun choicesAndTextAreAdditiveButSkipIsAnExplicitDifferentAnswer() {
        validateInteractionAnswers(listOf(q), listOf(PlanningAnswer("q", listOf("a"), "Комментарий")))
        validateInteractionAnswers(listOf(q), listOf(PlanningAnswer("q", skipped = true)))
        assertContains(interactionAnswerText(listOf(q), listOf(PlanningAnswer("q", skipped = true))), "Пропущено пользователем")
        for (answer in listOf(PlanningAnswer("q"), PlanningAnswer("unknown", listOf("a")), PlanningAnswer("q", listOf("z")),
            PlanningAnswer("q", listOf("a", "b")), PlanningAnswer("q", listOf("a"), skipped = true))) {
            assertFailsWith<IllegalArgumentException> { validateInteractionAnswers(listOf(q), listOf(answer)) }
        }
    }

    @Test fun approvalsCannotBeSkippedOrAnsweredWithTextOrDisabledConsent() {
        val permission = q.copy(allowCustomInput = false, canSkip = false, options = q.options.map { it.copy(enabled = it.id != "a") })
        for (a in listOf(PlanningAnswer("q", skipped = true), PlanningAnswer("q", text = "Да"), PlanningAnswer("q", listOf("a"))))
            assertFailsWith<IllegalArgumentException> { validateInteractionAnswers(listOf(permission), listOf(a)) }
        validateInteractionAnswers(listOf(permission), listOf(PlanningAnswer("q", listOf("b"))))
    }

    @Test fun oldAnswersRemainReadableAndSecretsAreRedactedInHistory() {
        val old = Json.decodeFromString(PlanningAnswer.serializer(), """{"questionId":"q","text":"secret"}""")
        assertFalse(old.skipped)
        assertFalse(interactionAnswerText(listOf(q.copy(secret = true)), listOf(old), true).contains("secret"))
    }

    @Test fun liveQuestionWaitsBeyondNormalTimeoutAndAcceptsExactlyOneReply() = runTest {
        val registry = RuntimeQuestionnaires()
        val result = async { registry.ask(request("live")) }
        runCurrent(); advanceTimeBy(180_000); runCurrent()
        assertFalse(result.isCompleted)
        assertEquals("live", registry.requests.value.single().id)
        assertFailsWith<IllegalArgumentException> { registry.respond("live", listOf(PlanningAnswer("q"))) }
        val answers = listOf(PlanningAnswer("q", listOf("a"), "Детали"))
        registry.respond("live", answers)
        assertFailsWith<IllegalStateException> { registry.respond("live", answers) }
        assertEquals(answers, result.await())
        assertTrue(registry.requests.value.isEmpty())
    }

    @Test fun cancellingOwnerRemovesItsQuestionWithoutAnsweringOtherSessions() = runTest {
        val registry = RuntimeQuestionnaires()
        val first = launch { registry.ask(request("a")) }
        val second = launch { registry.ask(request("b").copy(sessionId = "other")) }
        runCurrent(); first.cancelAndJoin(); runCurrent()
        assertEquals(listOf("b"), registry.requests.value.map { it.id })
        second.cancelAndJoin()
        assertTrue(registry.requests.value.isEmpty())
    }

    @Test fun savedAnswerReplaysOnlyToTheExactCallAfterRestart() = runTest {
        val persistence = JsonRuntimeQuestionnaireStore(InMemoryKeyValueStore())
        val original = RuntimeQuestionnaires(persistence)
        val req = request("durable").copy(runtimeGeneration = 7, runId = "run")
        val waiter = async { original.ask(req) }
        runCurrent()
        val answers = listOf(PlanningAnswer("q", listOf("b")))
        original.respond(req.id, answers)
        assertEquals(answers, waiter.await())
        val restored = RuntimeQuestionnaires(persistence)
        assertEquals(answers, restored.ask(req))
        assertFailsWith<IllegalArgumentException> { restored.ask(req.copy(runtimeGeneration = 8)) }
        assertFailsWith<IllegalArgumentException> { restored.ask(req.copy(sessionId = "sibling")) }
        assertFailsWith<IllegalArgumentException> { restored.ask(req.copy(questions = listOf(q.copy(title = "Different")))) }
        restored.acknowledgeDelivery(req.id)
        assertEquals(RuntimeQuestionnaireStatus.DELIVERED, RuntimeQuestionnaires(persistence).history.value.single().status)
    }

    @Test fun crashBeforeReplyKeepsTheQuestionButRequiresItsOriginalCallToReattach() = runTest {
        val persistence = JsonRuntimeQuestionnaireStore(InMemoryKeyValueStore())
        val req = request("recover").copy(runtimeGeneration = 4, runId = "run")
        persistence.save(listOf(RuntimeQuestionnaireRecord(req)))
        val restored = RuntimeQuestionnaires(persistence)
        assertEquals(RuntimeQuestionnaireStatus.INTERRUPTED, restored.history.value.single().status)
        assertTrue(restored.requests.value.isEmpty())
        assertFailsWith<IllegalStateException> { restored.respond(req.id, listOf(PlanningAnswer("q", listOf("a")))) }
        val waiter = async { restored.ask(req) }; runCurrent()
        assertEquals(req.sessionId, restored.requests.value.single().ownerSessionId)
        val answers = listOf(PlanningAnswer("q", listOf("a")))
        restored.respond(req.id, answers)
        assertEquals(answers, waiter.await())
    }

    @Test fun failedAnswerPersistenceDoesNotCompleteTheCallOrLoseTheQuestion() = runTest {
        val durable = JsonRuntimeQuestionnaireStore(InMemoryKeyValueStore())
        var fail = false
        val persistence = object : RuntimeQuestionnaireStore by durable {
            override fun save(records: List<RuntimeQuestionnaireRecord>) {
                if (fail) error("Injected disk failure")
                durable.save(records)
            }
        }
        val registry = RuntimeQuestionnaires(persistence)
        val waiter = async { registry.ask(request("disk")) }; runCurrent()
        fail = true
        val answers = listOf(PlanningAnswer("q", listOf("a")))
        assertFailsWith<IllegalStateException> { registry.respond("disk", answers) }
        assertFalse(waiter.isCompleted)
        assertEquals(RuntimeQuestionnaireStatus.OPEN, durable.load().single().status)
        assertEquals("disk", registry.requests.value.single().id)
        fail = false
        registry.respond("disk", answers)
        assertEquals(answers, waiter.await())
    }

    @Test fun revocationPersistsCancellationAndDoesNotAffectANewGeneration() = runTest {
        val persistence = JsonRuntimeQuestionnaireStore(InMemoryKeyValueStore())
        val registry = RuntimeQuestionnaires(persistence)
        val old = async { registry.ask(request("old").copy(runtimeGeneration = 1)) }
        val fresh = async { registry.ask(request("fresh").copy(runtimeGeneration = 2)) }
        runCurrent(); registry.revoke("s", 1); runCurrent()
        assertTrue(old.isCancelled)
        assertFalse(fresh.isCompleted)
        assertFailsWith<IllegalStateException> { registry.respond("old", listOf(PlanningAnswer("q", listOf("a")))) }
        assertEquals(RuntimeQuestionnaireStatus.CANCELLED, persistence.load().first { it.request.id == "old" }.status)
        fresh.cancelAndJoin()
    }

    @Test fun failureAfterCommitUsesReadbackAndDoesNotOverwriteTheConfirmedAnswer() = runTest {
        val durable = JsonRuntimeQuestionnaireStore(InMemoryKeyValueStore())
        val persistence = object : RuntimeQuestionnaireStore by durable {
            override fun save(records: List<RuntimeQuestionnaireRecord>) {
                durable.save(records)
                if (records.any { it.status == RuntimeQuestionnaireStatus.ANSWERED }) error("Injected post-rename failure")
            }
        }
        val registry = RuntimeQuestionnaires(persistence)
        val req = request("post-commit")
        val waiter = async { registry.ask(req) }; runCurrent()
        val answers = listOf(PlanningAnswer("q", listOf("b")))
        registry.respond(req.id, answers)
        assertEquals(answers, waiter.await())
        assertEquals(answers, RuntimeQuestionnaires(durable).ask(req))
    }

    @Test fun secretsAreNeverSavedAndCannotBeReplayedAfterRestart() = runTest {
        val kv = InMemoryKeyValueStore()
        val persistence = JsonRuntimeQuestionnaireStore(kv)
        val registry = RuntimeQuestionnaires(persistence)
        val req = request("secret").copy(questions = listOf(q.copy(secret = true)))
        val waiter = async { registry.ask(req) }; runCurrent()
        val answers = listOf(PlanningAnswer("q", text = "sensitive-token"))
        registry.respond(req.id, answers)
        assertEquals(answers, waiter.await())
        assertFalse(kv.read("runtime-questionnaires")!!.contains("sensitive-token"))
        val restored = RuntimeQuestionnaires(persistence)
        val reattached = async { restored.ask(req) }; runCurrent()
        assertFalse(reattached.isCompleted)
        reattached.cancelAndJoin()
    }

    @Test fun pendingQuestionBudgetIsReservedBeforePublishing() = runTest {
        val registry = RuntimeQuestionnaires(maxPending = 1)
        val first = launch { registry.ask(request("first")) }; runCurrent()
        assertFailsWith<IllegalArgumentException> { registry.ask(request("overflow")) }
        assertEquals(listOf("first"), registry.requests.value.map { it.id })
        first.cancelAndJoin()
    }
}
