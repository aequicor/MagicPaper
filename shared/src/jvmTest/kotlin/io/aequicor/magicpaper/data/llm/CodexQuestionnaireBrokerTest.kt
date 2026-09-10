package io.aequicor.magicpaper.data.llm

import io.aequicor.magicpaper.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class CodexQuestionnaireBrokerTest {
    private val params = Json.parseToJsonElement("""{"threadId":"thread","turnId":"turn","itemId":"item","isBlocking":true,"questions":[
      {"id":"q","header":"Format","question":"Формат?","options":[{"label":"PDF","description":"Документ"}]},
      {"id":"secret","header":"Secret","question":"Пароль?","isSecret":true}]}""").jsonObject
    private val session = CodingSession("session", "p", "Сессия", 0)

    @Test fun nativeQuestionIsDeduplicatedAndPreservesIdsChoicesTextAndExplicitSkip() = runTest {
        val registry = RuntimeQuestionnaires(); val sent = mutableListOf<JsonObject>(); val notices = mutableListOf<String>()
        val broker = CodexQuestionnaireBroker(backgroundScope, registry, { _, text -> notices += text }, { _, e -> throw e })
        assertTrue(broker.receive(JsonPrimitive(7), "item/tool/requestUserInput", params, session) { sent += it })
        assertTrue(broker.receive(JsonPrimitive(7), "item/tool/requestUserInput", params, session) { sent += it })
        runCurrent(); advanceTimeBy(120_000); runCurrent()
        assertEquals(1, registry.requests.value.size); assertTrue(sent.isEmpty())
        val request = registry.requests.value.single()
        registry.respond(request.id, listOf(PlanningAnswer("q", skipped = true), PlanningAnswer("secret", text = "private-value")))
        runCurrent()
        assertEquals(1, sent.size)
        assertEquals(JsonPrimitive(7), sent.single()["id"])
        val answers = sent.single()["result"]!!.jsonObject["answers"]!!.jsonObject
        assertEquals(buildJsonArray {}, answers["q"]!!.jsonObject["answers"])
        assertEquals(JsonPrimitive("private-value"), answers["secret"]!!.jsonObject["answers"]!!.jsonArray.single())
        assertFalse(notices.joinToString().contains("private-value"))
        assertTrue(registry.requests.value.isEmpty())
        broker.receive(JsonPrimitive(7), "item/tool/requestUserInput", params, session) { sent += it }
        runCurrent()
        assertTrue(registry.requests.value.isEmpty()); assertEquals(1, sent.size)
    }

    @Test fun resolutionAndInterruptionCancelOnlyTheirOwnRequestsWithoutSendingAnAnswer() = runTest {
        val registry = RuntimeQuestionnaires(); var sent = 0
        val broker = CodexQuestionnaireBroker(backgroundScope, registry, { _, _ -> }, { _, _ -> })
        broker.receive(JsonPrimitive("a"), "item/tool/requestUserInput", params, session) { sent++ }
        broker.receive(JsonPrimitive("b"), "item/tool/requestUserInput", JsonObject(params + ("turnId" to JsonPrimitive("other"))), session) { sent++ }
        runCurrent(); broker.clearTurn("thread", "turn"); runCurrent()
        assertEquals(1, registry.requests.value.size)
        broker.resolved("thread", JsonPrimitive("b")); runCurrent()
        assertTrue(registry.requests.value.isEmpty()); assertEquals(0, sent)
    }

    @Test fun failedDeliveryDoesNotReplayTheAnswer() = runTest {
        val registry = RuntimeQuestionnaires(); var failures = 0; var sent = 0
        val broker = CodexQuestionnaireBroker(backgroundScope, registry, { _, _ -> }, { _, _ -> failures++ })
        broker.receive(JsonPrimitive(1), "item/tool/requestUserInput", params, session) { sent++; error("Disconnected") }
        runCurrent()
        val request = registry.requests.value.single()
        registry.respond(request.id, request.questions.map { PlanningAnswer(it.id, skipped = true) }); runCurrent()
        assertEquals(1, sent); assertEquals(1, failures); assertTrue(registry.requests.value.isEmpty())
        assertFailsWith<IllegalStateException> { registry.respond(request.id, emptyList()) }
        broker.receive(JsonPrimitive(1), "item/tool/requestUserInput", params, session) { sent++ }
        runCurrent(); assertTrue(registry.requests.value.isEmpty()); assertEquals(1, sent)
    }

    @Test fun reusedWireIdWithChangedQuestionsFailsWithoutReplacingTheOriginalCall() = runTest {
        val registry = RuntimeQuestionnaires(); val failures = mutableListOf<Throwable>()
        val broker = CodexQuestionnaireBroker(backgroundScope, registry, { _, _ -> }, { _, e -> failures += e })
        broker.receive(JsonPrimitive(1), "item/tool/requestUserInput", params, session) { }
        runCurrent()
        broker.receive(JsonPrimitive(1), "item/tool/requestUserInput", params,
            session.copy(runtimeGeneration = session.runtimeGeneration + 1)) { }
        assertEquals(1, failures.size)
        assertEquals(session.runtimeGeneration, registry.requests.value.single().runtimeGeneration)
        broker.clear(); runCurrent()
    }
}
