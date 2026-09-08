package io.aequicor.magicpaper.domain

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.Json
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
}
