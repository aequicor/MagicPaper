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
    val applicationSupported: Boolean get() = false
    val state: StateFlow<ComputerUseState>
    val permissions: ComputerPermissions? get() = null
    /** Changing policy revokes current leases, but never starts work or grants access by itself. */
    fun configure(computer: ComputerAccess, application: ComputerAccess)
    /** A read-only fence, not a grant. Capture before checking the settings owner's readiness. */
    fun capturePolicy(): ComputerPolicyRef?
    /** Immediately fences new admission and revokes grants and pending permission requests. */
    fun invalidatePolicy()
    /** True only while the permission result still owns a current grant. */
    suspend fun enable(sessionId: String, access: ComputerAccess, expectedPolicy: ComputerPolicyRef): Boolean
    /** Revocation must be immediate, even while a capture or input operation is in progress. */
    fun disable(sessionId: String? = null)
    suspend fun preview(sessionId: String)
    fun openSystemSettings()
}

@Serializable
data class ComputerPolicyRef(val incarnation: String, val revision: Long)
