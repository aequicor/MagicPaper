package io.aequicor.magicpaper.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** One conversation owns its ordered summaries. Model calls are never replayed from a journal. */
object RequestPinMachine {
    @ConsistentCopyVisibility
    data class State internal constructor(
        val initialized: Boolean = false,
        val records: List<RequestPinRecord> = emptyList(),
        val messages: List<PinMessage> = emptyList(),
        val profileToken: String? = null,
        val available: Boolean = false,
        val attempted: Set<String> = emptySet(),
        val unknown: Set<PinMessage> = emptySet(),
        val failed: Set<PinMessage> = emptySet(),
        val active: Attempt? = null,
        val removed: Boolean = false,
        val persistenceUnknown: Boolean = false,
    ) {
        val failure: Failure? get() = when {
            persistenceUnknown -> Failure.PERSISTENCE
            records.any { !it.analysed && it.source in unknown } -> Failure.UNKNOWN_OUTCOME
            records.any { !it.analysed && it.source in failed } -> Failure.ANALYSIS
            else -> null
        }
    }

    data class Attempt internal constructor(val id: String, val prefix: List<PinMessage>, val profileToken: String?)
    enum class Failure { ANALYSIS, UNKNOWN_OUTCOME, PERSISTENCE }

    @Serializable sealed interface Input
    @Serializable sealed interface Intent : Input {
        @Serializable @SerialName("Sync") data class Sync(val messages: List<PinMessage>, val profileToken: String?,
            val available: Boolean, val reopened: Boolean = false) : Intent
        @Serializable @SerialName("Analyse") data class Analyse(val attemptId: String) : Intent
        @Serializable @SerialName("Remove") data object Remove : Intent
    }
    @Serializable sealed interface Fact : Input {
        @Serializable @SerialName("Initialized") data class Initialized(val records: List<RequestPinRecord>) : Fact
        @Serializable @SerialName("Completed") data class Completed(val attemptId: String, val summary: String,
            val newRequest: Boolean) : Fact
        @Serializable @SerialName("Failed") data class Failed(val attemptId: String, val unknown: Boolean) : Fact
        @Serializable @SerialName("Restored") data object Restored : Fact
        @Serializable @SerialName("PersistenceUnknown") data object PersistenceUnknown : Fact
    }
    sealed interface Effect {
        data class Analyse(val attemptId: String, val source: PinMessage, val preceding: List<RequestPinRecord>,
            val context: List<PinMessage>, val profileToken: String?) : Effect
        data class Checkpoint(val records: List<RequestPinRecord>) : Effect
        data object DeleteCheckpoint : Effect
        data class Reject(val reason: String) : Effect
    }
    data class Transition(val state: State, val effects: List<Effect> = emptyList())

    fun initial(): State = State()
    fun pending(state: State): Boolean = state.initialized && !state.removed && !state.persistenceUnknown &&
        state.active == null && state.available && state.records.any { eligible(state, it) }

    fun reduce(state: State, input: Input): Transition {
        fun reject(reason: String) = Transition(state, listOf(Effect.Reject(reason)))
        if (input == Fact.PersistenceUnknown) return Transition(state.copy(persistenceUnknown = true))
        if (state.persistenceUnknown) return reject("Требуется восстановить закрепления")
        if (!state.initialized && input !is Fact.Initialized) return reject("Закрепления ещё не восстановлены")
        if (state.removed && input != Fact.Restored) return reject("Диалог удалён")
        return when (input) {
            is Fact.Initialized -> when {
                state.initialized -> reject("Закрепления уже восстановлены")
                input.records.distinctBy { it.source.id }.size != input.records.size -> reject("Повторяющиеся сообщения")
                else -> Transition(state.copy(initialized = true, records = input.records))
            }
            is Intent.Sync -> {
                val inputs = input.messages.filter { it.input && it.pinnable && (it.text.isNotBlank() || it.attachments.isNotEmpty()) }.distinctBy { it.id }
                val prefix = inputs.zip(state.records).takeWhile { (source, record) -> source == record.source }.size
                val replaced = state.records.drop(prefix).map { it.source.id }.toSet()
                val records = state.records.take(prefix) + inputs.drop(prefix).map {
                    RequestPinRecord(it, it.pinExcerpt(), newRequest = !it.clarification)
                }
                val retryKnown = input.reopened || input.profileToken != state.profileToken
                val next = state.copy(records = records, messages = input.messages, profileToken = input.profileToken,
                    available = input.available, attempted = if (retryKnown) emptySet() else state.attempted - replaced,
                    failed = if (retryKnown) emptySet() else state.failed)
                Transition(next, if (records == state.records) emptyList() else listOf(Effect.Checkpoint(records)))
            }
            is Intent.Analyse -> {
                val index = state.records.indexOfFirst { eligible(state, it) }
                when {
                    !pending(state) || index < 0 -> reject("Нет доступных сообщений для пересказа")
                    input.attemptId.isBlank() -> reject("Не указан идентификатор попытки")
                    else -> {
                        val source = state.records[index].source
                        val prefix = state.records.take(index + 1).map { it.source }
                        val context = state.messages.take(state.messages.indexOfFirst { it.id == source.id }.coerceAtLeast(0))
                            .takeLast(6).map { it.copy(text = it.text.takeLast(3000)) }
                        Transition(state.copy(active = Attempt(input.attemptId, prefix, state.profileToken),
                            attempted = state.attempted + source.id), listOf(Effect.Analyse(input.attemptId, source,
                            state.records.take(index), context, state.profileToken)))
                    }
                }
            }
            is Fact.Completed -> {
                val active = state.active
                when {
                    active?.id != input.attemptId -> reject("Попытка пересказа устарела")
                    input.summary.isBlank() -> reject("Пустой пересказ")
                    !matches(state, active) -> Transition(state.copy(active = null))
                    else -> {
                        val index = active.prefix.lastIndex
                        val records = state.records.mapIndexed { i, item -> if (i != index) item else item.copy(
                            summary = compactPinText(input.summary), newRequest = i == 0 || !item.source.clarification && input.newRequest,
                            analysed = true) }
                        Transition(state.copy(active = null, records = records), listOf(Effect.Checkpoint(records)))
                    }
                }
            }
            is Fact.Failed -> {
                val active = state.active
                if (active?.id != input.attemptId) reject("Попытка пересказа устарела")
                else Transition(state.copy(active = null,
                    unknown = if (input.unknown) state.unknown + active.prefix.last() else state.unknown,
                    failed = if (!input.unknown) state.failed + active.prefix.last() else state.failed))
            }
            Fact.Restored -> Transition(state.copy(active = null, available = false,
                unknown = state.active?.let { state.unknown + it.prefix.last() } ?: state.unknown))
            Intent.Remove -> Transition(state.copy(removed = true, active = null, records = emptyList(), messages = emptyList()),
                listOf(Effect.DeleteCheckpoint))
            Fact.PersistenceUnknown -> error("Handled above")
        }
    }

    private fun eligible(state: State, record: RequestPinRecord) = !record.analysed && record.source.id !in state.attempted && record.source !in state.unknown
    private fun matches(state: State, attempt: Attempt) = state.records.take(attempt.prefix.size).map { it.source } == attempt.prefix &&
        state.profileToken == attempt.profileToken
}

fun PinMessage.pinExcerpt(): String = compactPinText(text.ifBlank { "Вложения: ${attachments.joinToString()}" })
fun compactPinText(text: String): String = text.replace(Regex("\\s+"), " ").trim().take(180)

fun List<RequestPinRecord>.pinGroups(): List<RequestPinGroup> = buildList {
    var request: RequestPin? = null
    val clarifications = mutableListOf<RequestPin>()
    for (record in this@pinGroups) {
        val pin = RequestPin(record.source.id, record.summary, record.source.author)
        if (record.newRequest || request == null) {
            request?.let { add(RequestPinGroup(it, clarifications.toList())) }
            request = pin
            clarifications.clear()
        } else clarifications += pin
    }
    request?.let { add(RequestPinGroup(it, clarifications.toList())) }
}
