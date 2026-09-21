package io.aequicor.magicpaper.domain

import io.aequicor.magicpaper.machine.Branch
import io.aequicor.magicpaper.machine.EffectId
import io.aequicor.magicpaper.machine.InputId
import io.aequicor.magicpaper.machine.InputSpec
import io.aequicor.magicpaper.machine.PhaseId
import io.aequicor.magicpaper.machine.StateSpace
import io.aequicor.magicpaper.machine.acceptance

/**
 * The state space of [QuestionnaireMachine], declared so it can be read without running anything.
 *
 * The state holds a map of records, so a position here describes the store, not one questionnaire.
 * [label] ranks in two steps. Live things come first — a validation in flight, then an attached
 * waiter — because they decide what `Submit`, `Attach` and `Reset` do. When nothing is live it
 * takes the record that matters most, in the order of [PRECEDENCE]: an unconfirmed delivery outranks
 * everything, since it is the one outcome that must not be lost and must not be repeated.
 *
 * What the declaration cannot express, and leaves to `QuestionnaireMachineTest`:
 *  - acceptance that depends on *which* record an input names. A store with two records is one
 *    position, and the matrix speaks about the single-record representatives;
 *  - a token or attempt id that does not match, a request that names another call, and the two
 *    quantitative bounds (`maxPending`, at most 64 questions);
 *  - `unknown(state)` can be true beside a live position: an unconfirmed delivery next to an
 *    attached waiter is labelled `waiting` and still reports unknown.
 *
 * An `OPEN` record with no waiter attached exists only between `Initialized` and `Restored`. It
 * accepts and refuses exactly what an interrupted record does, so it shares that position.
 */
object QuestionnaireSpace : StateSpace<QuestionnaireMachine.State, QuestionnaireMachine.Input, QuestionnaireMachine.Effect> {
    val NEW = PhaseId("new")
    val EMPTY = PhaseId("empty")
    val WAITING = PhaseId("waiting")
    val VALIDATING = PhaseId("validating")
    val ANSWERED = PhaseId("answered")
    val DELIVERING = PhaseId("delivering")
    val DELIVERY_UNKNOWN = PhaseId("delivery-unknown")
    val DELIVERED = PhaseId("delivered")
    val INTERRUPTED = PhaseId("interrupted")
    val CANCELLED = PhaseId("cancelled")
    val PERSISTENCE_UNKNOWN = PhaseId("persistence-unknown")

    val ATTACH = InputId("Attach")
    val SUBMIT = InputId("Submit")
    val DETACH = InputId("Detach")
    val REVOKE = InputId("Revoke")
    val BEGIN_DELIVERY = InputId("BeginDelivery")
    val RESET = InputId("Reset")
    val INITIALIZED = InputId("Initialized")
    val ANSWERS_VALIDATED = InputId("AnswersValidated")
    val ANSWERS_INVALID = InputId("AnswersInvalid")
    val DELIVERY_CONFIRMED = InputId("DeliveryConfirmed")
    val DELIVERY_UNCONFIRMED = InputId("DeliveryUnconfirmed")
    val RESTORED = InputId("Restored")
    val PERSISTENCE_UNKNOWN_FACT = InputId("PersistenceUnknown")

    override val phases = listOf(
        NEW, EMPTY, WAITING, VALIDATING, ANSWERED, DELIVERING, DELIVERY_UNKNOWN, DELIVERED, INTERRUPTED,
        CANCELLED, PERSISTENCE_UNKNOWN,
    )

    override val inputs = listOf(
        InputSpec(ATTACH, Branch.INTENT),
        InputSpec(SUBMIT, Branch.INTENT),
        InputSpec(DETACH, Branch.INTENT),
        InputSpec(REVOKE, Branch.INTENT),
        InputSpec(BEGIN_DELIVERY, Branch.INTENT),
        InputSpec(RESET, Branch.INTENT),
        InputSpec(INITIALIZED, Branch.FACT),
        InputSpec(ANSWERS_VALIDATED, Branch.FACT),
        InputSpec(ANSWERS_INVALID, Branch.FACT),
        InputSpec(DELIVERY_CONFIRMED, Branch.FACT),
        InputSpec(DELIVERY_UNCONFIRMED, Branch.FACT),
        InputSpec(RESTORED, Branch.FACT),
        InputSpec(PERSISTENCE_UNKNOWN_FACT, Branch.FACT),
    )

    override val effects = listOf(EffectId("Validate"), EffectId("Complete"), EffectId("Cancel"), EffectId("Reject"))

    // Rows follow `phases`, columns follow `inputs`. `Attach` from `answered` is accepted without a
    // state change: an answer that was never redacted is completed for the new waiter. Everything but
    // `Initialized` and `PersistenceUnknown` is refused before initialization, `Restored` included.
    override val accepts = acceptance(phases, inputs, listOf(
        //                       At Su De Rv Bd Rs In Av Ai Dc Du Rt Pu
        /* new                */ "0000001000001",
        /* empty              */ "1011010000011",
        /* waiting            */ "0111000000011",
        /* validating         */ "0011000110011",
        /* answered           */ "1011110000011",
        /* delivering         */ "0011010001111",
        /* delivery-unknown   */ "0011010001111",
        /* delivered          */ "0011010001011",
        /* interrupted        */ "1011010000011",
        /* cancelled          */ "0011010000011",
        /* persistence        */ "0000000000001",
    ))

    // Most pressing first. Exhaustiveness over the status enum is enforced by [position]: a new
    // status does not compile until it is named there, and only then ranked here.
    private val PRECEDENCE = listOf(DELIVERY_UNKNOWN, DELIVERING, ANSWERED, INTERRUPTED, DELIVERED, CANCELLED)

    private fun position(status: RuntimeQuestionnaireStatus): PhaseId = when (status) {
        RuntimeQuestionnaireStatus.DELIVERY_UNKNOWN -> DELIVERY_UNKNOWN
        RuntimeQuestionnaireStatus.DELIVERY_PENDING -> DELIVERING
        RuntimeQuestionnaireStatus.ANSWERED -> ANSWERED
        RuntimeQuestionnaireStatus.INTERRUPTED, RuntimeQuestionnaireStatus.OPEN -> INTERRUPTED
        RuntimeQuestionnaireStatus.DELIVERED -> DELIVERED
        RuntimeQuestionnaireStatus.CANCELLED -> CANCELLED
    }

    override fun label(state: QuestionnaireMachine.State): PhaseId = when {
        state.persistenceUnknown -> PERSISTENCE_UNKNOWN
        !state.initialized -> NEW
        state.validation.isNotEmpty() -> VALIDATING
        state.attached.isNotEmpty() -> WAITING
        state.records.isEmpty() -> EMPTY
        else -> state.records.values.map { position(it.status) }.toSet().let { held -> PRECEDENCE.first { it in held } }
    }

    override fun name(input: QuestionnaireMachine.Input): InputId = when (input) {
        is QuestionnaireMachine.Intent.Attach -> ATTACH
        is QuestionnaireMachine.Intent.Submit -> SUBMIT
        is QuestionnaireMachine.Intent.Detach -> DETACH
        is QuestionnaireMachine.Intent.Revoke -> REVOKE
        is QuestionnaireMachine.Intent.BeginDelivery -> BEGIN_DELIVERY
        QuestionnaireMachine.Intent.Reset -> RESET
        is QuestionnaireMachine.Fact.Initialized -> INITIALIZED
        is QuestionnaireMachine.Fact.AnswersValidated -> ANSWERS_VALIDATED
        is QuestionnaireMachine.Fact.AnswersInvalid -> ANSWERS_INVALID
        // One fact, two inputs: a confirmed delivery may repeat, an unconfirmed one may not follow a
        // confirmation, and only the unconfirmed one can open an unknown outcome.
        is QuestionnaireMachine.Fact.DeliveryObserved -> if (input.confirmed) DELIVERY_CONFIRMED else DELIVERY_UNCONFIRMED
        QuestionnaireMachine.Fact.Restored -> RESTORED
        QuestionnaireMachine.Fact.PersistenceUnknown -> PERSISTENCE_UNKNOWN_FACT
    }

    override fun name(effect: QuestionnaireMachine.Effect): EffectId = when (effect) {
        is QuestionnaireMachine.Effect.Validate -> EffectId("Validate")
        is QuestionnaireMachine.Effect.Complete -> EffectId("Complete")
        is QuestionnaireMachine.Effect.Cancel -> EffectId("Cancel")
        is QuestionnaireMachine.Effect.Reject -> EffectId("Reject")
    }

    override fun unknown(state: QuestionnaireMachine.State) =
        state.persistenceUnknown || state.records.values.any { it.status == RuntimeQuestionnaireStatus.DELIVERY_UNKNOWN }

    override fun rejected(effect: QuestionnaireMachine.Effect) = effect is QuestionnaireMachine.Effect.Reject
}
