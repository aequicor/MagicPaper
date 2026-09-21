package io.aequicor.magicpaper.data.llm

import io.aequicor.magicpaper.backend.NativeQuestionnaires
import io.aequicor.magicpaper.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import kotlin.test.*

/**
 * The broker is checked against the [NativeQuestionnaires] contract alone. What the application does with a
 * request afterwards (history, restoring a journal, refusing a second answer) belongs to its own owner.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CodexQuestionnaireBrokerTest {
    private class Questionnaires : NativeQuestionnaires {
        class Open(val request: UserInteractionRequest, val answers: CompletableDeferred<List<PlanningAnswer>>)
        class Finished(val attemptId: String, val outcome: QuestionnaireDeliveryOutcome)
        val open = mutableListOf<Open>()
        val begun = mutableListOf<String>()
        val finished = mutableListOf<Finished>()
        var beginFailure: Throwable? = null

        override suspend fun ask(request: UserInteractionRequest): List<PlanningAnswer> {
            val entry = Open(request, CompletableDeferred()); open += entry
            try { return entry.answers.await() } finally { open -= entry }
        }
        override suspend fun beginDelivery(requestId: String): String {
            beginFailure?.let { throw it }
            return "attempt-${begun.size + 1}".also { begun += it }
        }
        override suspend fun finishDelivery(requestId: String, attemptId: String, outcome: QuestionnaireDeliveryOutcome) {
            finished += Finished(attemptId, outcome)
        }
        fun answer(entry: Open, answers: List<PlanningAnswer>) { entry.answers.complete(answers) }
        fun skipAll(entry: Open) = answer(entry, entry.request.questions.map { PlanningAnswer(it.id, skipped = true) })
    }

    private val params = Json.parseToJsonElement("""{"threadId":"thread","turnId":"turn","itemId":"item","isBlocking":true,"questions":[
      {"id":"q","header":"Format","question":"Формат?","options":[{"label":"PDF","description":"Документ"}]},
      {"id":"secret","header":"Secret","question":"Пароль?","isSecret":true}]}""").jsonObject
    private val session = CodingSession("session", "p", "Сессия", 0)

    @Test fun nativeQuestionIsDeduplicatedAndPreservesIdsChoicesTextAndExplicitSkip() = runTest {
        val registry = Questionnaires(); val sent = mutableListOf<JsonObject>(); val notices = mutableListOf<String>()
        val broker = CodexQuestionnaireBroker(backgroundScope, registry, { _, text -> notices += text }, { _, e -> throw e })
        assertTrue(broker.receive(JsonPrimitive(7), "item/tool/requestUserInput", params, session) { sent += it })
        assertTrue(broker.receive(JsonPrimitive(7), "item/tool/requestUserInput", params, session) { sent += it })
        runCurrent(); advanceTimeBy(120_000); runCurrent()
        assertEquals(1, registry.open.size); assertTrue(sent.isEmpty())
        registry.answer(registry.open.single(), listOf(PlanningAnswer("q", skipped = true), PlanningAnswer("secret", text = "private-value")))
        runCurrent()
        assertEquals(1, sent.size)
        assertEquals(JsonPrimitive(7), sent.single()["id"])
        val answers = sent.single()["result"]!!.jsonObject["answers"]!!.jsonObject
        assertEquals(buildJsonArray {}, answers["q"]!!.jsonObject["answers"])
        assertEquals(JsonPrimitive("private-value"), answers["secret"]!!.jsonObject["answers"]!!.jsonArray.single())
        assertFalse(notices.joinToString().contains("private-value"))
        assertTrue(registry.open.isEmpty())
        broker.receive(JsonPrimitive(7), "item/tool/requestUserInput", params, session) { sent += it }
        runCurrent()
        assertTrue(registry.open.isEmpty()); assertEquals(1, sent.size)
    }

    @Test fun resolutionAndInterruptionCancelOnlyTheirOwnRequestsWithoutSendingAnAnswer() = runTest {
        val registry = Questionnaires(); var sent = 0
        val broker = CodexQuestionnaireBroker(backgroundScope, registry, { _, _ -> }, { _, _ -> })
        broker.receive(JsonPrimitive("a"), "item/tool/requestUserInput", params, session) { sent++ }
        broker.receive(JsonPrimitive("b"), "item/tool/requestUserInput", JsonObject(params + ("turnId" to JsonPrimitive("other"))), session) { sent++ }
        runCurrent(); broker.clearTurn("thread", "turn"); runCurrent()
        assertEquals(1, registry.open.size)
        broker.resolved("thread", JsonPrimitive("b")); runCurrent()
        assertTrue(registry.open.isEmpty()); assertEquals(0, sent)
    }

    @Test fun failedDeliveryDoesNotReplayTheAnswer() = runTest {
        val registry = Questionnaires(); var failures = 0; var sent = 0
        val broker = CodexQuestionnaireBroker(backgroundScope, registry, { _, _ -> }, { _, _ -> failures++ })
        broker.receive(JsonPrimitive(1), "item/tool/requestUserInput", params, session) { sent++; error("Disconnected") }
        runCurrent()
        registry.skipAll(registry.open.single()); runCurrent()
        assertEquals(1, sent); assertEquals(1, failures); assertTrue(registry.open.isEmpty())
        assertEquals(listOf(QuestionnaireDeliveryOutcome.UNKNOWN), registry.finished.map { it.outcome })
        broker.receive(JsonPrimitive(1), "item/tool/requestUserInput", params, session) { sent++ }
        runCurrent(); assertTrue(registry.open.isEmpty()); assertEquals(1, sent)
    }

    @Test fun reusedWireIdWithChangedQuestionsFailsWithoutReplacingTheOriginalCall() = runTest {
        val registry = Questionnaires(); val failures = mutableListOf<Throwable>()
        val broker = CodexQuestionnaireBroker(backgroundScope, registry, { _, _ -> }, { _, e -> failures += e })
        broker.receive(JsonPrimitive(1), "item/tool/requestUserInput", params, session) { }
        runCurrent()
        broker.receive(JsonPrimitive(1), "item/tool/requestUserInput", params,
            session.copy(runtimeGeneration = session.runtimeGeneration + 1)) { }
        assertEquals(1, failures.size)
        assertEquals(session.runtimeGeneration, registry.open.single().request.runtimeGeneration)
        broker.clear(); runCurrent()
    }

    @Test fun successfulPipeWriteAndResolvedNotificationDoNotProveRemoteAcceptance() = runTest {
        val registry = Questionnaires()
        val broker = CodexQuestionnaireBroker(backgroundScope, registry, { _, _ -> }, { _, e -> throw e })
        broker.receive(JsonPrimitive(5), "item/tool/requestUserInput", params, session) { }
        runCurrent()
        registry.skipAll(registry.open.single())
        runCurrent()
        broker.resolved("thread", JsonPrimitive(5))
        val finished = registry.finished.single()
        assertEquals(QuestionnaireDeliveryOutcome.UNKNOWN, finished.outcome)
        assertEquals(registry.begun.single(), finished.attemptId)
    }

    @Test fun deliveryIntentMustPersistBeforeAnyTransportWrite() = runTest {
        val registry = Questionnaires().apply { beginFailure = IllegalStateException("store unavailable") }
        var writes = 0
        var failures = 0
        val broker = CodexQuestionnaireBroker(backgroundScope, registry, { _, _ -> }, { _, _ -> failures++ })
        broker.receive(JsonPrimitive(2), "item/tool/requestUserInput", params, session) { writes++ }
        runCurrent()
        registry.skipAll(registry.open.single())
        runCurrent()
        assertEquals(0, writes); assertEquals(1, failures)
        assertTrue(registry.finished.isEmpty(), "An attempt that never began has no outcome to report")
    }

    @Test fun cancellationDuringTransportWriteKeepsAttemptUnknown() = runTest {
        val registry = Questionnaires()
        val writing = CompletableDeferred<Unit>()
        val broker = CodexQuestionnaireBroker(backgroundScope, registry, { _, _ -> }, { _, e -> throw e })
        broker.receive(JsonPrimitive(3), "item/tool/requestUserInput", params, session) {
            writing.complete(Unit)
            awaitCancellation()
        }
        runCurrent()
        registry.skipAll(registry.open.single())
        runCurrent(); assertTrue(writing.isCompleted)
        broker.clearTurn("thread", "turn"); runCurrent()
        val finished = registry.finished.single()
        assertEquals(QuestionnaireDeliveryOutcome.UNKNOWN, finished.outcome)
        assertEquals(registry.begun.single(), finished.attemptId)
    }
}
