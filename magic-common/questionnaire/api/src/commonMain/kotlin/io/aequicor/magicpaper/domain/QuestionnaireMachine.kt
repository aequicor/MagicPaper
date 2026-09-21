package io.aequicor.magicpaper.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

/** One pure owner for durable lifecycle and live attachments. The interpreter alone performs I/O. */
object QuestionnaireMachine {
    @ConsistentCopyVisibility
    data class State internal constructor(
        val records: Map<String, RuntimeQuestionnaireRecord> = emptyMap(),
        val maxPending: Int = 64,
        val attached: Set<String> = emptySet(),
        val validation: Map<String, String> = emptyMap(),
        val initialized: Boolean = false,
        val persistenceUnknown: Boolean = false,
    )

    @Serializable
    sealed interface Input
    @Serializable
    sealed interface Intent : Input {
        @Serializable @SerialName("Attach") data class Attach(val request: UserInteractionRequest) : Intent
        /** Answers live only in the interpreter's short-lived vault until validation finishes. */
        @Serializable @SerialName("Submit") data class Submit(val id: String, val token: String) : Intent
        @Serializable @SerialName("Detach") data class Detach(val id: String) : Intent
        @Serializable @SerialName("Revoke") data class Revoke(val sessionId: String, val generation: Long?) : Intent
        @Serializable @SerialName("BeginDelivery") data class BeginDelivery(val id: String, val attemptId: String) : Intent
        @Serializable @SerialName("Reset") data object Reset : Intent
    }
    @Serializable
    sealed interface Fact : Input {
        @Serializable @SerialName("Initialized") data class Initialized(val records: List<RuntimeQuestionnaireRecord>) : Fact
        @Serializable @SerialName("AnswersValidated") data class AnswersValidated(val id: String, val token: String,
            val answers: List<PlanningAnswer>, val redacted: Boolean) : Fact
        @Serializable @SerialName("AnswersInvalid") data class AnswersInvalid(val id: String, val token: String) : Fact
        @Serializable @SerialName("DeliveryObserved") data class DeliveryObserved(val id: String, val attemptId: String, val confirmed: Boolean) : Fact
        @Serializable @SerialName("Restored") data object Restored : Fact
        /** In-memory fact: an unavailable journal cannot record evidence about its own failure. */
        @Serializable @SerialName("PersistenceUnknown") data object PersistenceUnknown : Fact
    }
    sealed interface Effect {
        data class Validate(val id: String, val token: String) : Effect
        data class Complete(val id: String, val token: String? = null) : Effect
        data class Cancel(val id: String) : Effect
        data class Reject(val reason: String) : Effect
    }
    data class Transition(val state: State, val effects: List<Effect> = emptyList())

    fun initial(maxPending: Int = 64): State { require(maxPending > 0); return State(maxPending = maxPending) }

    fun reduce(state: State, input: Input): Transition {
        fun reject(reason: String) = Transition(state, listOf(Effect.Reject(reason)))
        fun accept(next: State, vararg effects: Effect) = Transition(next, effects.toList())
        fun updated(id: String, record: RuntimeQuestionnaireRecord) = state.copy(records = state.records + (id to record))
        if (input == Fact.PersistenceUnknown) return accept(state.copy(persistenceUnknown = true))
        if (state.persistenceUnknown) return reject("Состояние хранилища опросников не подтверждено; требуется восстановление")
        if (!state.initialized && input !is Fact.Initialized) return reject("Опросники ещё не восстановлены")
        return when (input) {
            is Fact.Initialized -> {
                if (state.initialized) reject("Опросники уже восстановлены")
                else if (input.records.distinctBy { it.request.id }.size != input.records.size) reject("Повторяющийся идентификатор опросника")
                else accept(state.copy(initialized = true, records = input.records.associateBy { it.request.id }))
            }
            Fact.Restored -> accept(state.copy(attached = emptySet(), validation = emptyMap(), records = state.records.mapValues { (_, record) ->
                when (record.status) {
                    RuntimeQuestionnaireStatus.OPEN -> record.copy(status = RuntimeQuestionnaireStatus.INTERRUPTED)
                    RuntimeQuestionnaireStatus.DELIVERY_PENDING -> record.copy(status = RuntimeQuestionnaireStatus.DELIVERY_UNKNOWN)
                    else -> record
                }
            }))
            is Intent.Attach -> {
                val request = input.request
                val record = state.records[request.id]
                when {
                    request.id in state.attached -> reject("Запрос уже существует")
                    request.questions.isEmpty() || request.questions.size > 64 || request.questions.distinctBy { it.id }.size != request.questions.size -> reject("Некорректное число вопросов")
                    state.attached.size >= state.maxPending -> reject("Достигнут лимит ожидающих опросников")
                    record != null && !sameCall(record.request, request) -> reject("Идентификатор вопроса принадлежит другому вызову")
                    record?.status == RuntimeQuestionnaireStatus.CANCELLED -> reject("Обращение отменено")
                    record?.status in deliveryStarted -> reject("Исход доставки ответа не допускает повторную отправку")
                    record?.status == RuntimeQuestionnaireStatus.ANSWERED && !record.answersRedacted -> accept(state, Effect.Complete(request.id))
                    else -> accept(state.copy(records = state.records + (request.id to RuntimeQuestionnaireRecord(request)), attached = state.attached + request.id))
                }
            }
            is Intent.Submit -> when {
                input.id !in state.attached || state.records[input.id]?.status != RuntimeQuestionnaireStatus.OPEN -> reject("Обращение уже закрыто")
                input.id in state.validation -> reject("Ответ уже проверяется")
                else -> accept(state.copy(validation = state.validation + (input.id to input.token)), Effect.Validate(input.id, input.token))
            }
            is Fact.AnswersValidated -> {
                val record = state.records[input.id]
                if (state.validation[input.id] != input.token || input.id !in state.attached || record?.status != RuntimeQuestionnaireStatus.OPEN)
                    reject("Проверка ответа устарела")
                else accept(updated(input.id, record.copy(status = RuntimeQuestionnaireStatus.ANSWERED, answers = input.answers,
                    answersRedacted = input.redacted)).copy(attached = state.attached - input.id, validation = state.validation - input.id),
                    Effect.Complete(input.id, input.token))
            }
            is Fact.AnswersInvalid -> if (state.validation[input.id] != input.token) reject("Проверка ответа устарела")
                else accept(state.copy(validation = state.validation - input.id))
            is Intent.Detach -> {
                val record = state.records[input.id]
                val next = if (record?.status == RuntimeQuestionnaireStatus.OPEN) updated(input.id, record.copy(status = RuntimeQuestionnaireStatus.CANCELLED)) else state
                accept(next.copy(attached = state.attached - input.id, validation = state.validation - input.id))
            }
            is Intent.Revoke -> {
                val ids = state.records.filterValues { it.request.sessionId == input.sessionId &&
                    (input.generation == null || it.request.runtimeGeneration == input.generation) }.keys
                // Cancellation closes live waiters; it cannot erase evidence of a delivery attempt.
                val next = state.copy(records = state.records.mapValues { (id, record) ->
                    if (id in ids && record.status !in deliveryStarted) record.copy(status = RuntimeQuestionnaireStatus.CANCELLED) else record
                }, attached = state.attached - ids, validation = state.validation - ids)
                Transition(next, ids.map(Effect::Cancel))
            }
            is Intent.BeginDelivery -> {
                val record = state.records[input.id]
                if (record?.status != RuntimeQuestionnaireStatus.ANSWERED || input.attemptId.isBlank()) reject("Ответ недоступен для отправки")
                else accept(updated(input.id, record.copy(status = RuntimeQuestionnaireStatus.DELIVERY_PENDING, deliveryAttemptId = input.attemptId)))
            }
            is Fact.DeliveryObserved -> {
                val record = state.records[input.id]
                when {
                    record?.deliveryAttemptId != input.attemptId || record.status !in deliveryStarted -> reject("Попытка доставки не найдена")
                    record.status == RuntimeQuestionnaireStatus.DELIVERED && !input.confirmed -> reject("Подтверждение доставки уже записано")
                    else -> accept(updated(input.id, record.copy(status = if (input.confirmed) RuntimeQuestionnaireStatus.DELIVERED else RuntimeQuestionnaireStatus.DELIVERY_UNKNOWN)))
                }
            }
            Intent.Reset -> if (state.attached.isNotEmpty()) reject("Дождитесь закрытия опросников") else accept(State(initialized = true, maxPending = state.maxPending))
            Fact.PersistenceUnknown -> error("Handled above")
        }
    }

    private val deliveryStarted = setOf(RuntimeQuestionnaireStatus.DELIVERY_PENDING, RuntimeQuestionnaireStatus.DELIVERY_UNKNOWN, RuntimeQuestionnaireStatus.DELIVERED)
    private fun sameCall(a: UserInteractionRequest, b: UserInteractionRequest) =
        a.projectId == b.projectId && a.sessionId == b.sessionId && a.ownerSessionId == b.ownerSessionId &&
            a.sourceId == b.sourceId && a.kind == b.kind && a.questions == b.questions &&
            a.runtimeGeneration == b.runtimeGeneration && a.runId == b.runId
}

