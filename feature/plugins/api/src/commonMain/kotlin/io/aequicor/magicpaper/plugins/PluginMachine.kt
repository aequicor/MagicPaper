package io.aequicor.magicpaper.plugins

import io.aequicor.magicpaper.domain.PluginState
import io.aequicor.magicpaper.machine.Machine
import io.aequicor.magicpaper.machine.MachineId
import io.aequicor.magicpaper.machine.Step
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Preferences belong to this owner; renderers and importers submit intentions only. */
object PluginMachine : Machine<PluginMachine.State, PluginMachine.Input, PluginMachine.Effect> {
    override val id = MachineId("plugin")
    override val space get() = PluginSpace
    /** Bridge to the owner's own reducer: [Transition] and [reduce] keep every call site. */
    override fun step(state: State, input: Input) = reduce(state, input).let { Step(it.state, it.effects) }

    @ConsistentCopyVisibility
    data class State internal constructor(
        val initialized: Boolean = false,
        val preferences: Map<String, PluginState> = emptyMap(),
        val persistenceUnknown: Boolean = false,
        val missingPlugin: String? = null,
    )

    @Serializable sealed interface Input
    @Serializable sealed interface Intent : Input {
        @Serializable @SerialName("Toggle") data class Toggle(val id: String, val enabled: Boolean) : Intent
        @Serializable @SerialName("Import") data class Import(val preferences: List<PluginState>) : Intent
        @Serializable @SerialName("Clear") data object Clear : Intent
    }
    @Serializable sealed interface Fact : Input {
        @Serializable @SerialName("Initialized") data class Initialized(val preferences: List<PluginState>) : Fact
        @Serializable @SerialName("NeighbourMissing") data class NeighbourMissing(val id: String) : Fact
        @Serializable @SerialName("PersistenceUnknown") data object PersistenceUnknown : Fact
    }
    sealed interface Effect { data class Reject(val reason: String) : Effect }
    data class Transition(val state: State, val effects: List<Effect> = emptyList())

    fun initial() = State()

    fun reduce(state: State, input: Input): Transition {
        fun reject(reason: String) = Transition(state, listOf(Effect.Reject(reason)))
        if (input == Fact.PersistenceUnknown) return Transition(state.copy(persistenceUnknown = true))
        if (state.persistenceUnknown) return reject("Требуется повторно загрузить настройки плагинов")
        if (!state.initialized && input !is Fact.Initialized) return reject("Плагины ещё не загружены")
        fun preferences(values: List<PluginState>): Transition {
            if (values.any { it.id.isBlank() } || values.map { it.id }.distinct().size != values.size)
                return reject("Некорректные идентификаторы плагинов")
            return Transition(state.copy(initialized = true, preferences = values.associate { it.id to it.copy(config = it.config.toMap()) }, missingPlugin = null))
        }
        return when (input) {
            is Fact.Initialized -> if (state.initialized) reject("Плагины уже загружены") else preferences(input.preferences)
            is Intent.Import -> preferences(input.preferences)
            Intent.Clear -> preferences(emptyList())
            is Intent.Toggle -> {
                if (input.id.isBlank()) return reject("Не указан плагин")
                val previous = state.preferences[input.id] ?: PluginState(input.id)
                Transition(state.copy(preferences = state.preferences + (input.id to previous.copy(enabled = input.enabled)), missingPlugin = null))
            }
            is Fact.NeighbourMissing -> Transition(state.copy(missingPlugin = input.id))
            Fact.PersistenceUnknown -> error("Handled above")
        }
    }
}

/** Explicit import/export/reset boundary; it exposes neither journal inputs nor a mutable snapshot. */
interface PluginPreferences {
    suspend fun exportPreferences(): List<PluginState>
    suspend fun importPreferences(preferences: List<PluginState>)
    suspend fun clearPreferences()
}
