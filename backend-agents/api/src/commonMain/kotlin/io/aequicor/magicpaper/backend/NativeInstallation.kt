package io.aequicor.magicpaper.backend

enum class NativeInstallationPhase { CHECKING, INSTALLING, READY, ERROR }
/** [signedIn] is the engine's own account; null when the engine has none or could not tell. */
data class NativeInstallationStatus(val phase: NativeInstallationPhase, val detail: String, val version: String = "", val signedIn: Boolean? = null)

/** Reads packaged bytes; the backend does not know the host's resource container or application bridges. */
fun interface NativeResources { fun read(path: String): ByteArray? }
