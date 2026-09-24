package io.aequicor.magicpaper.domain

/** Фазы состояния кодинг-рантайма (движка пи-агента). */
enum class RuntimePhase { UNKNOWN, CHECKING, INSTALLING, READY, ERROR, UNSUPPORTED }

/** Снимок состояния рантайма для UI. */
data class RuntimeStatus(
    val phase: RuntimePhase,
    val detail: String = "",
    val version: String = "",
    /** The app installs these dependencies itself, so it can also remove them; null when the producer did not report. */
    val dependenciesRemovable: Boolean? = null,
    /** Preparation only checks a runtime installed outside the app; it installs nothing; null when the producer did not report. */
    val verifiesExternalInstall: Boolean? = null,
    /** The engine's own account ([CodingRecovery.SignIn]); null when the engine has none or could not tell. */
    val signedIn: Boolean? = null,
) {
    val ready: Boolean get() = phase == RuntimePhase.READY
}

