package io.aequicor.magicpaper.domain.tools

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** The common tool owner has one workflow machine; receipts prove individual external outcomes. */
object ProviderToolMachine {
    @Serializable enum class Phase { NEW, MODEL_READY, MODEL_PENDING, TOOLS_READY, TOOL_PENDING, OUTPUT_PENDING, OUTPUT_WRITING, SUCCEEDED, FAILED, CANCELLED, INTERRUPTED, UNKNOWN }
    @Serializable data class Call(val id: String, val name: String, val fingerprint: String)
    @Serializable data class OutputRef(val runId: String, val attempt: String, val identity: String, val digest: String)
    @ConsistentCopyVisibility
    data class State internal constructor(
        val phase: Phase = Phase.NEW,
        val runId: String = "",
        val identity: String = "",
        val available: Set<String> = emptySet(),
        val maxTurns: Int = 16,
        val maxCalls: Int = 64,
        val turns: Int = 0,
        val calls: Int = 0,
        val attempt: String? = null,
        val pending: List<Call> = emptyList(),
        val seen: Map<String, Call> = emptyMap(),
        val reason: String = "",
        val output: OutputRef? = null,
    )
    @Serializable sealed interface Input
    @Serializable sealed interface Intent : Input {
        @Serializable @SerialName("Start") data class Start(val runId: String, val identity: String,
            val available: Set<String>, val maxTurns: Int = 16, val maxCalls: Int = 64) : Intent
        @Serializable @SerialName("RequestModel") data class RequestModel(val attempt: String) : Intent
        @Serializable @SerialName("ExecuteTool") data class ExecuteTool(val id: String) : Intent
        @Serializable @SerialName("Cancel") data object Cancel : Intent
        @Serializable @SerialName("StoreOutput") data object StoreOutput : Intent
    }
    @Serializable sealed interface Fact : Input {
        @Serializable @SerialName("ModelReturned") data class ModelReturned(val attempt: String, val calls: List<Call>, val hasText: Boolean,
            val output: OutputRef? = null) : Fact
        @Serializable @SerialName("OutputStored") data class OutputStored(val output: OutputRef) : Fact
        @Serializable @SerialName("ToolReturned") data class ToolReturned(val id: String, val phase: ToolPhase) : Fact
        @Serializable @SerialName("Failed") data class Failed(val operation: String, val unknown: Boolean) : Fact
        @Serializable @SerialName("Restored") data object Restored : Fact
        @Serializable @SerialName("PersistenceUnknown") data object PersistenceUnknown : Fact
    }
    sealed interface Effect {
        data class InvokeModel(val attempt: String) : Effect
        data class InvokeTool(val id: String) : Effect
        data class PersistOutput(val output: OutputRef) : Effect
        data class Reject(val reason: String) : Effect
    }
    data class Transition(val state: State, val effects: List<Effect> = emptyList())
    fun initial() = State()

    fun reduce(state: State, input: Input): Transition {
        fun reject(reason: String) = Transition(state, listOf(Effect.Reject(reason)))
        if (input == Fact.PersistenceUnknown) return Transition(state.copy(phase = Phase.UNKNOWN, reason = "persistence"))
        if (state.phase == Phase.UNKNOWN) return if (input == Fact.Restored) Transition(state)
            else reject("Исход предыдущего действия не подтверждён. Повторное выполнение заблокировано.")
        if (input == Fact.Restored) return Transition(when (state.phase) {
            Phase.MODEL_PENDING, Phase.TOOL_PENDING, Phase.OUTPUT_PENDING, Phase.OUTPUT_WRITING -> state.copy(phase = Phase.UNKNOWN, reason = "interrupted_external_attempt")
            Phase.MODEL_READY, Phase.TOOLS_READY -> state.copy(phase = Phase.INTERRUPTED, reason = "interrupted_conversation")
            else -> state
        })
        return when (input) {
            is Intent.Start -> when {
                state.phase != Phase.NEW -> reject("Этот запрос уже выполнялся. Отправьте новое сообщение после проверки результата.")
                input.runId.isBlank() || input.identity.isBlank() || input.maxTurns < 1 || input.maxCalls < 1 -> reject("Некорректные параметры запроса")
                else -> Transition(State(Phase.MODEL_READY, input.runId, input.identity, input.available, input.maxTurns, input.maxCalls))
            }
            is Intent.RequestModel -> when {
                state.phase != Phase.MODEL_READY -> reject("Запрос к модели сейчас недоступен")
                state.turns >= state.maxTurns -> reject("Достигнут лимит обращений к модели")
                input.attempt.isBlank() -> reject("Отсутствует идентификатор обращения")
                else -> Transition(state.copy(phase = Phase.MODEL_PENDING, turns = state.turns + 1, attempt = input.attempt), listOf(Effect.InvokeModel(input.attempt)))
            }
            is Fact.ModelReturned -> when {
                state.phase != Phase.MODEL_PENDING || input.attempt != state.attempt -> reject("Ответ принадлежит другому обращению")
                input.calls.isEmpty() && !input.hasText -> reject("Модель вернула пустой ответ")
                input.calls.isEmpty() && (input.output == null || input.output.runId != state.runId ||
                    input.output.attempt != state.attempt || input.output.identity != state.identity || input.output.digest.isBlank()) -> reject("Ответ не содержит проверяемой ссылки на результат")
                input.calls.isNotEmpty() && input.output != null -> reject("Вызовы инструментов ещё не являются итоговым ответом")
                input.calls.distinctBy { it.id }.size != input.calls.size || input.calls.any { it.id.isBlank() || it.name !in state.available } -> reject("Модель вернула недоступные или повторяющиеся вызовы")
                input.calls.any { call -> state.seen[call.id]?.let { it != call } == true } -> reject("Идентификатор вызова уже использован с другими аргументами")
                state.calls + input.calls.size > state.maxCalls -> reject("Достигнут лимит вызовов инструментов")
                else -> Transition(state.copy(phase = if (input.calls.isEmpty()) Phase.OUTPUT_PENDING else Phase.TOOLS_READY,
                    pending = input.calls, attempt = null, output = input.output, seen = state.seen + input.calls.associateBy { it.id }))
            }
            is Intent.ExecuteTool -> if (state.phase != Phase.TOOLS_READY || state.pending.firstOrNull()?.id != input.id)
                reject("Вызов инструмента сейчас недоступен") else Transition(state.copy(phase = Phase.TOOL_PENDING, calls = state.calls + 1), listOf(Effect.InvokeTool(input.id)))
            is Fact.ToolReturned -> when {
                state.phase != Phase.TOOL_PENDING || state.pending.firstOrNull()?.id != input.id -> reject("Результат принадлежит другому вызову")
                input.phase == ToolPhase.UNKNOWN -> Transition(state.copy(phase = Phase.UNKNOWN, reason = "tool_outcome"))
                input.phase !in setOf(ToolPhase.SUCCEEDED, ToolPhase.FAILED, ToolPhase.CANCELLED) -> reject("Инструмент ещё не завершён")
                else -> Transition(state.copy(phase = if (state.pending.size == 1) Phase.MODEL_READY else Phase.TOOLS_READY, pending = state.pending.drop(1)))
            }
            Intent.StoreOutput -> if (state.phase != Phase.OUTPUT_PENDING || state.output == null) reject("Итоговый ответ ещё не получен")
                else Transition(state.copy(phase = Phase.OUTPUT_WRITING), listOf(Effect.PersistOutput(state.output)))
            is Fact.OutputStored -> if (state.phase != Phase.OUTPUT_WRITING || input.output != state.output) reject("Сохранённый ответ принадлежит другому запросу")
                else Transition(state.copy(phase = Phase.SUCCEEDED))
            is Fact.Failed -> if (state.phase in terminal) reject("Запрос уже завершён")
                else Transition(state.copy(phase = if (input.unknown) Phase.UNKNOWN else Phase.FAILED, reason = input.operation))
            Intent.Cancel -> if (state.phase in terminal) reject("Запрос уже завершён") else Transition(state.copy(
                phase = if (state.phase in setOf(Phase.MODEL_PENDING, Phase.TOOL_PENDING, Phase.OUTPUT_PENDING, Phase.OUTPUT_WRITING)) Phase.UNKNOWN else Phase.CANCELLED,
                reason = "cancelled"))
            Fact.Restored, Fact.PersistenceUnknown -> error("Handled above")
        }
    }
    private val terminal = setOf(Phase.SUCCEEDED, Phase.FAILED, Phase.CANCELLED, Phase.INTERRUPTED)
}
