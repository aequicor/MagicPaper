package io.aequicor.magicpaper.domain

/** Runtime-owned admission and lease recovery, called by the parent coordinator before Git effects. */
interface TaskWorktreeRuntimeAccess {
    suspend fun requireQuiescent(sessionId: String)
    suspend fun releaseUnownedLeases(): Boolean
}
