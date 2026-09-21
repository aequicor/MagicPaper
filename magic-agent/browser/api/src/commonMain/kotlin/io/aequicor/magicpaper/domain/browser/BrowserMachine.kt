package io.aequicor.magicpaper.domain.browser

import io.aequicor.magicpaper.machine.Machine
import io.aequicor.magicpaper.machine.MachineId
import io.aequicor.magicpaper.machine.Step
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** One explicit native run owns the handles. The journal contains identities, never page/input bytes. */
object BrowserMachine : Machine<BrowserMachine.State, BrowserMachine.Input, BrowserMachine.Effect> {
    override val id = MachineId("browser")
    override val space get() = BrowserSpace
    /** Bridge to the owner's own reducer: [Transition] and [reduce] keep every call site. */
    override fun step(state: State, input: Input) = reduce(state, input).let { Step(it.state, it.effects) }

    enum class Stage { NEW, READY, EXECUTING, UNKNOWN, CLOSING, CLOSED }
    @Serializable enum class Action(val observesOnly: Boolean) {
        OPEN(false), SEARCH(false), SNAPSHOT(true), CLICK(false), FILL(false), PRESS(false), EVALUATE(false),
        SCREENSHOT(true), VALIDATE_DOCUMENT(true), VALIDATE_TAB(true),
    }
    @Serializable data class Owner(val sessionId: String, val requestId: String)
    @Serializable data class Operation(val id: String, val action: Action, val fingerprint: String, val tabId: String? = null)
    @ConsistentCopyVisibility data class State internal constructor(
        val owner: Owner? = null,
        val lifecycle: Stage = Stage.NEW,
        val pending: Operation? = null,
        val completed: Set<String> = emptySet(),
        val unknown: Set<String> = emptySet(),
        val tabs: Set<String> = emptySet(),
        val persistenceUnknown: Boolean = false,
        val cleanupUnknown: Boolean = false,
    ) {
        /** Unknown external/persistence outcomes take precedence even after local resources close. */
        val stage: Stage get() = if (persistenceUnknown || cleanupUnknown || unknown.isNotEmpty()) Stage.UNKNOWN else lifecycle
    }
    @Serializable sealed interface Input
    @Serializable sealed interface Intent : Input {
        @Serializable @SerialName("Start") data class Start(val owner: Owner) : Intent
        @Serializable @SerialName("Perform") data class Perform(val operation: Operation) : Intent
        @Serializable @SerialName("Close") data object Close : Intent
    }
    @Serializable sealed interface Fact : Input {
        @Serializable @SerialName("Completed") data class Completed(val operationId: String, val tabs: Set<String>) : Fact
        /** Safe only when the interpreter proves no external action was dispatched. */
        @Serializable @SerialName("Failed") data class Failed(val operationId: String, val beforeEffect: Boolean) : Fact
        @Serializable @SerialName("NeighbourMissing") data class NeighbourMissing(val operationId: String) : Fact
        @Serializable @SerialName("Closed") data object Closed : Fact
        @Serializable @SerialName("Restored") data object Restored : Fact
        @Serializable @SerialName("PersistenceUnknown") data object PersistenceUnknown : Fact
    }
    sealed interface Effect {
        data class Execute(val operation: Operation) : Effect
        data object Release : Effect
        data class Reject(val reason: String) : Effect
    }
    data class Transition(val state: State, val effects: List<Effect> = emptyList())
    fun initial() = State()
    fun reduce(state: State, input: Input): Transition {
        fun reject(reason: String) = Transition(state, listOf(Effect.Reject(reason)))
        if (input == Fact.PersistenceUnknown) return Transition(state.copy(persistenceUnknown = true))
        if (input == Fact.Restored) return Transition(state.copy(lifecycle = Stage.CLOSED, tabs = emptySet(), pending = null,
            cleanupUnknown = state.cleanupUnknown || state.lifecycle == Stage.CLOSING,
            unknown = state.unknown + listOfNotNull(state.pending?.takeUnless { it.action.observesOnly }?.id)))
        if (input == Intent.Close) return Transition(state.copy(lifecycle = Stage.CLOSING), listOf(Effect.Release))
        if (input == Fact.Closed) return if (state.lifecycle != Stage.CLOSING) reject("Закрытие браузера не запрошено")
            else Transition(state.copy(lifecycle = Stage.CLOSED, tabs = emptySet(), pending = null,
                unknown = state.unknown + listOfNotNull(state.pending?.takeUnless { it.action.observesOnly }?.id)))
        if (state.persistenceUnknown) return reject("Сохранение браузерного действия не подтверждено. Запуск остановлен.")
        return when (input) {
            is Intent.Start -> if (state.lifecycle != Stage.NEW || input.owner.sessionId.isBlank() || input.owner.requestId.isBlank())
                reject("Браузер принадлежит одному запуску") else Transition(State(owner = input.owner, lifecycle = Stage.READY))
            is Intent.Perform -> {
                val operation = input.operation
                when {
                    (state.cleanupUnknown || state.unknown.isNotEmpty()) && !operation.action.observesOnly -> reject("Исход действия неизвестен. Доступна только проверка страницы; повторное действие отключено.")
                    state.lifecycle != Stage.READY || state.pending != null -> reject("Браузер занят или запуск завершён")
                    operation.id.isBlank() || !operation.fingerprint.matches(Regex("[0-9a-f]{64}")) -> reject("Некорректный идентификатор действия")
                    operation.id in state.completed || operation.id in state.unknown -> reject("Повторное исполнение браузерного действия запрещено")
                    else -> Transition(state.copy(lifecycle = Stage.EXECUTING, pending = operation), listOf(Effect.Execute(operation)))
                }
            }
            is Fact.Completed -> when {
                state.pending?.id != input.operationId || state.lifecycle != Stage.EXECUTING -> reject("Результат не принадлежит выполняемому действию")
                input.tabs.any { !it.matches(Regex("tab-[1-9][0-9]*")) } -> reject("Некорректный идентификатор вкладки")
                else -> Transition(state.copy(lifecycle = Stage.READY, pending = null, tabs = input.tabs.toSet(),
                    completed = state.completed + input.operationId))
            }
            is Fact.Failed, is Fact.NeighbourMissing -> {
                val id = if (input is Fact.Failed) input.operationId else (input as Fact.NeighbourMissing).operationId
                val operation = state.pending
                if (operation?.id != id || state.lifecycle != Stage.EXECUTING) reject("Отказ не принадлежит выполняемому действию")
                else {
                    val uncertain = input is Fact.Failed && !input.beforeEffect && !operation.action.observesOnly
                    Transition(state.copy(lifecycle = Stage.READY, pending = null, completed = state.completed + id,
                        unknown = if (uncertain) state.unknown + id else state.unknown))
                }
            }
            Intent.Close, Fact.Closed, Fact.Restored, Fact.PersistenceUnknown -> error("Handled above")
        }
    }
}
