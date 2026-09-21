package io.aequicor.magicpaper.backend

import kotlinx.coroutines.flow.Flow

enum class NativeInstallationPhase { CHECKING, INSTALLING, READY, ERROR }
data class NativeInstallationStatus(val phase: NativeInstallationPhase, val detail: String, val version: String = "")

/** Reads packaged bytes; the backend does not know the host's resource container or application bridges. */
fun interface NativeResources { fun read(path: String): ByteArray? }

interface PiInstallation {
    val cliPath: String
    fun aiDirectory(): String?
    suspend fun status(): NativeInstallationStatus
    fun ensureReady(): Flow<NativeInstallationStatus>
    suspend fun uninstall()
    suspend fun node(): String
    fun prepareBundledTools()
    fun toolsNotice(): String
    fun ensureFuzzySafety()
    fun bashPath(): String?
    fun homeDefaults(directory: String)
    fun environment(nodePath: String, home: String): Map<String, String>
}
