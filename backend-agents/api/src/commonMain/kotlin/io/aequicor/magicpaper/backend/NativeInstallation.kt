package io.aequicor.magicpaper.backend

enum class NativeInstallationPhase { CHECKING, INSTALLING, READY, ERROR }
data class NativeInstallationStatus(val phase: NativeInstallationPhase, val detail: String, val version: String = "")

/** Reads packaged bytes; the backend does not know the host's resource container or application bridges. */
fun interface NativeResources { fun read(path: String): ByteArray? }
