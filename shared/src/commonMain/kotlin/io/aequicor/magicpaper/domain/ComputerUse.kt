package io.aequicor.magicpaper.domain

import kotlinx.coroutines.flow.StateFlow

enum class ComputerAccess { OFF, SCREEN, CONTROL }

/** Ephemeral permission: never stored in a profile, exported, or inherited by plan workers. */
data class ComputerUseState(
    val sessionId: String? = null,
    val access: ComputerAccess = ComputerAccess.OFF,
    val busy: Boolean = false,
    val detail: String = "",
    val preview: Attachment? = null,
)

interface ComputerUse {
    val supported: Boolean
    val state: StateFlow<ComputerUseState>
    suspend fun enable(sessionId: String, access: ComputerAccess)
    /** Revocation must be immediate, even while a capture or input operation is in progress. */
    fun disable(sessionId: String? = null)
    suspend fun preview(sessionId: String)
    fun openSystemSettings()
}
