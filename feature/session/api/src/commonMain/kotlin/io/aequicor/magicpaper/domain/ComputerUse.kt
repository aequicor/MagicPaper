package io.aequicor.magicpaper.domain

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import io.aequicor.magicpaper.util.Id

interface ComputerUse {
    val supported: Boolean
    val state: StateFlow<ComputerUseState>
    suspend fun enable(sessionId: String, access: ComputerAccess)
    /** Revocation must be immediate, even while a capture or input operation is in progress. */
    fun disable(sessionId: String? = null)
    suspend fun preview(sessionId: String)
    fun openSystemSettings()
}