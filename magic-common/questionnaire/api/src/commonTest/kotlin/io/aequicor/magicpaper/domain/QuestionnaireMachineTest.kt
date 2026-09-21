package io.aequicor.magicpaper.domain

import io.aequicor.magicpaper.domain.QuestionnaireMachine.State
import io.aequicor.magicpaper.domain.QuestionnaireMachine.Input
import io.aequicor.magicpaper.domain.QuestionnaireMachine.Intent
import io.aequicor.magicpaper.domain.QuestionnaireMachine.Fact
import io.aequicor.magicpaper.domain.QuestionnaireMachine.Transition
import io.aequicor.magicpaper.domain.QuestionnaireMachine.Effect
import kotlin.test.*

class QuestionnaireMachineTest {
    private fun accepted(transition: Transition): Transition { assertTrue(transition.effects.none { it is Effect.Reject }); return transition }
    private fun rejected(transition: Transition, message: String = "") { assertTrue(transition.effects.any { it is Effect.Reject }, message) }
    private val request = UserInteractionRequest("q", "project", "session", InteractionKind.RUNTIME,
        listOf(PlanningQuestion("field", "Value", QuestionKind.TEXT)))
    private val answers = listOf(PlanningAnswer("field", text = "answer"))
    private fun reduce(state: State, input: Input) = accepted(QuestionnaireMachine.reduce(state, input)).state
    private fun initialized(status: RuntimeQuestionnaireStatus): State = reduce(QuestionnaireMachine.initial(),
        Fact.Initialized(listOf(RuntimeQuestionnaireRecord(request, status, answers,
            deliveryAttemptId = if (status in delivered) "attempt" else null))))
    private val delivered = setOf(RuntimeQuestionnaireStatus.DELIVERY_PENDING, RuntimeQuestionnaireStatus.DELIVERY_UNKNOWN, RuntimeQuestionnaireStatus.DELIVERED)

    @Test fun entireStatusAndDeliveryCommandProductHasExplicitDecisions() {
        for (status in RuntimeQuestionnaireStatus.entries) {
            val state = initialized(status)
            val begin = QuestionnaireMachine.reduce(state, Intent.BeginDelivery("q", "new"))
            assertEquals(status == RuntimeQuestionnaireStatus.ANSWERED, begin.effects.none { it is Effect.Reject }, "$status begin")
            for (confirmed in listOf(false, true)) {
                val observed = QuestionnaireMachine.reduce(state, Fact.DeliveryObserved("q", "attempt", confirmed))
                val allowed = status in delivered && !(status == RuntimeQuestionnaireStatus.DELIVERED && !confirmed)
                assertEquals(allowed, observed.effects.none { it is Effect.Reject }, "$status observed=$confirmed")
            }
            val attach = QuestionnaireMachine.reduce(state, Intent.Attach(request))
            assertEquals(status !in delivered && status != RuntimeQuestionnaireStatus.CANCELLED, attach.effects.none { it is Effect.Reject }, "$status attach")
            val cancelled = reduce(state, Intent.Revoke("session", null))
            assertEquals(if (status in delivered) status else RuntimeQuestionnaireStatus.CANCELLED, cancelled.records.getValue("q").status)
        }
    }

    @Test fun restoredStateHasNoEffectsAndCannotResumeAnUnfinishedDelivery() {
        for (status in RuntimeQuestionnaireStatus.entries) {
            val transition = accepted(QuestionnaireMachine.reduce(initialized(status), Fact.Restored))
            assertTrue(transition.effects.isEmpty())
            assertTrue(transition.state.attached.isEmpty())
            assertEquals(when (status) {
                RuntimeQuestionnaireStatus.OPEN -> RuntimeQuestionnaireStatus.INTERRUPTED
                RuntimeQuestionnaireStatus.DELIVERY_PENDING -> RuntimeQuestionnaireStatus.DELIVERY_UNKNOWN
                else -> status
            }, transition.state.records.getValue("q").status)
        }
    }

    @Test fun validationRequiresTheExactLiveIntentToken() {
        var state = reduce(QuestionnaireMachine.initial(), Fact.Initialized(emptyList()))
        state = reduce(state, Intent.Attach(request))
        val unsolicited = Fact.AnswersValidated("q", "token", answers, false)
        rejected(QuestionnaireMachine.reduce(state, unsolicited))
        state = reduce(state, Intent.Submit("q", "token"))
        rejected(QuestionnaireMachine.reduce(state, unsolicited.copy(token = "another")))
        val answer = accepted(QuestionnaireMachine.reduce(state, unsolicited))
        assertEquals(listOf(Effect.Complete("q", "token")), answer.effects)
        rejected(QuestionnaireMachine.reduce(answer.state, unsolicited))
        assertEquals(RuntimeQuestionnaireStatus.ANSWERED, answer.state.records.getValue("q").status)
    }

    @Test fun persistenceUnknownRejectsEveryDomainMutation() {
        val state = reduce(initialized(RuntimeQuestionnaireStatus.OPEN), Fact.PersistenceUnknown)
        val all = listOf(Intent.Attach(request), Intent.Submit("q", "token"), Intent.Detach("q"), Intent.Revoke("session", null),
            Intent.BeginDelivery("q", "attempt"), Intent.Reset, Fact.Restored, Fact.Initialized(emptyList()),
            Fact.AnswersValidated("q", "token", answers, false), Fact.AnswersInvalid("q", "token"), Fact.DeliveryObserved("q", "attempt", true))
        all.forEach { rejected(QuestionnaireMachine.reduce(state, it), it.toString()) }
    }
}
