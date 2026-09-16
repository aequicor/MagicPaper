package io.aequicor.magicpaper.domain

import kotlinx.coroutines.flow.StateFlow

@kotlinx.serialization.Serializable
enum class ComputerAccess { OFF, SCREEN, CONTROL }

/** Ephemeral permission: never stored in a profile, exported, or inherited by plan workers. */
data class ComputerUseState(
    val sessionId: String? = null,
    val access: ComputerAccess = ComputerAccess.OFF,
    val busy: Boolean = false,
    val detail: String = "",
    val preview: Attachment? = null,
    val applicationAccess: ComputerAccess = ComputerAccess.OFF,
    val error: Boolean = false,
)
