package io.aequicor.magicpaper.domain

/** Runtime preference commands never replace a settings snapshot captured by another owner. */
interface SettingsCommands {
    suspend fun selectDefaultCodingEngine(engine: CodingEngine): AppSettings
    /** A restored projection is not evidence that its runtime policy completed. */
    suspend fun runtimePolicy(): SettingsRuntimePolicy
}

sealed interface SettingsRuntimePolicy {
    data class Confirmed(val settings: AppSettings) : SettingsRuntimePolicy
    data object Unconfirmed : SettingsRuntimePolicy
}

/** The platform participates in a settings change without acquiring persistence authority. */
interface SettingsRuntimeParticipant {
    /** Revoke old live authority before the configuration commit. Safe to repeat explicitly. */
    suspend fun prepare(previous: AppSettings, next: AppSettings)
    /** Apply only the committed configuration. Safe to repeat explicitly after an unknown outcome. */
    suspend fun apply(settings: AppSettings)
}
