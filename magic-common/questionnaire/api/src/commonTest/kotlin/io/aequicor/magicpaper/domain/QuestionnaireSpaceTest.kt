package io.aequicor.magicpaper.domain

import io.aequicor.magicpaper.domain.QuestionnaireMachine.Fact
import io.aequicor.magicpaper.domain.QuestionnaireMachine.Intent
import io.aequicor.magicpaper.machine.verifyStateSpace
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The representatives of [QuestionnaireSpace], kept here rather than in the api so a shipped binary —
 * the browser bundle included — carries no fixtures.
 *
 * Each one is built by running the machine from `initial`, never by constructing a state, which is
 * what the `internal constructor` on `State` is there to enforce. Every one holds the single record
 * `q`, because the matrix speaks about one questionnaire at a time.
 */
class QuestionnaireSpaceTest {
    private val request = UserInteractionRequest("q", "project", "session", InteractionKind.RUNTIME,
        listOf(PlanningQuestion("field", "Value", QuestionKind.TEXT)))
    private val answers = listOf(PlanningAnswer("field", text = "answer"))
    private fun step(state: QuestionnaireMachine.State, input: QuestionnaireMachine.Input) = QuestionnaireMachine.reduce(state, input).state

    private val new = QuestionnaireMachine.initial()
    private val empty = step(new, Fact.Initialized(emptyList()))
    private val waiting = step(empty, Intent.Attach(request))
    private val validating = step(waiting, Intent.Submit("q", "token"))
    private val answered = step(validating, Fact.AnswersValidated("q", "token", answers, redacted = false))
    private val delivering = step(answered, Intent.BeginDelivery("q", "attempt"))

    /**
     * The harness drives single-record representatives, so it cannot see how [QuestionnaireSpace.label]
     * ranks a store that holds several. Reversing that order would leave it green, and the order is
     * the part that says an unconfirmed delivery outranks everything else.
     */
    @Test fun theRecordThatMattersMostDecidesThePositionOfAStoreHoldingSeveral() {
        fun store(vararg statuses: RuntimeQuestionnaireStatus) = step(new, Fact.Initialized(statuses.mapIndexed { index, status ->
            RuntimeQuestionnaireRecord(request.copy(id = "q$index"), status, answers)
        }))
        val ranked = listOf(
            RuntimeQuestionnaireStatus.DELIVERY_UNKNOWN to QuestionnaireSpace.DELIVERY_UNKNOWN,
            RuntimeQuestionnaireStatus.DELIVERY_PENDING to QuestionnaireSpace.DELIVERING,
            RuntimeQuestionnaireStatus.ANSWERED to QuestionnaireSpace.ANSWERED,
            RuntimeQuestionnaireStatus.INTERRUPTED to QuestionnaireSpace.INTERRUPTED,
            RuntimeQuestionnaireStatus.DELIVERED to QuestionnaireSpace.DELIVERED,
            RuntimeQuestionnaireStatus.CANCELLED to QuestionnaireSpace.CANCELLED,
        )
        for ((higher, lower) in ranked.flatMapIndexed { index, high -> ranked.drop(index + 1).map { high to it } }) {
            // Whatever order the store holds them in, the more pressing record names the position.
            assertEquals(higher.second, QuestionnaireSpace.label(store(higher.first, lower.first)), "${higher.first} over ${lower.first}")
            assertEquals(higher.second, QuestionnaireSpace.label(store(lower.first, higher.first)), "${lower.first} under ${higher.first}")
        }
        // A record that is open with no waiter attached only exists between Initialized and Restored.
        assertEquals(QuestionnaireSpace.INTERRUPTED, QuestionnaireSpace.label(store(RuntimeQuestionnaireStatus.OPEN)))
        // A store beside a live waiter is named by the waiter, yet still reports the unknown outcome.
        val beside = step(store(RuntimeQuestionnaireStatus.DELIVERY_UNKNOWN), Intent.Attach(request.copy(id = "live")))
        assertEquals(QuestionnaireSpace.WAITING, QuestionnaireSpace.label(beside))
        assertTrue(QuestionnaireSpace.unknown(beside))
    }

    @Test fun declaredSpaceIsClosedAndMatchesEveryTransition() = verifyStateSpace(
        QuestionnaireMachine,
        states = mapOf(
            QuestionnaireSpace.NEW to new,
            QuestionnaireSpace.EMPTY to empty,
            QuestionnaireSpace.WAITING to waiting,
            QuestionnaireSpace.VALIDATING to validating,
            QuestionnaireSpace.ANSWERED to answered,
            QuestionnaireSpace.DELIVERING to delivering,
            // The delivery was started and its outcome never seen: it must not be repeated.
            QuestionnaireSpace.DELIVERY_UNKNOWN to step(delivering, Fact.DeliveryObserved("q", "attempt", confirmed = false)),
            QuestionnaireSpace.DELIVERED to step(delivering, Fact.DeliveryObserved("q", "attempt", confirmed = true)),
            // A restart while the waiter was attached: the answer can no longer arrive.
            QuestionnaireSpace.INTERRUPTED to step(waiting, Fact.Restored),
            QuestionnaireSpace.CANCELLED to step(waiting, Intent.Detach("q")),
            QuestionnaireSpace.PERSISTENCE_UNKNOWN to step(waiting, Fact.PersistenceUnknown),
        ),
        inputs = mapOf(
            QuestionnaireSpace.ATTACH to Intent.Attach(request),
            QuestionnaireSpace.SUBMIT to Intent.Submit("q", "token"),
            QuestionnaireSpace.DETACH to Intent.Detach("q"),
            QuestionnaireSpace.REVOKE to Intent.Revoke("session", null),
            QuestionnaireSpace.BEGIN_DELIVERY to Intent.BeginDelivery("q", "attempt"),
            QuestionnaireSpace.RESET to Intent.Reset,
            QuestionnaireSpace.INITIALIZED to Fact.Initialized(emptyList()),
            QuestionnaireSpace.ANSWERS_VALIDATED to Fact.AnswersValidated("q", "token", answers, redacted = false),
            QuestionnaireSpace.ANSWERS_INVALID to Fact.AnswersInvalid("q", "token"),
            QuestionnaireSpace.DELIVERY_CONFIRMED to Fact.DeliveryObserved("q", "attempt", confirmed = true),
            QuestionnaireSpace.DELIVERY_UNCONFIRMED to Fact.DeliveryObserved("q", "attempt", confirmed = false),
            QuestionnaireSpace.RESTORED to Fact.Restored,
            QuestionnaireSpace.PERSISTENCE_UNKNOWN_FACT to Fact.PersistenceUnknown,
        ),
    )
}
